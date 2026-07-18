package app.fleetlight.mobile.data

import android.content.Context
import androidx.core.content.edit
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

class FileFeedCache(context: Context, namespace: String = "feed-cache") : FeedCache {
    private val safeNamespace = namespace.takeIf { CACHE_NAMESPACE.matches(it) }
        ?: throw IllegalArgumentException("Invalid feed cache namespace")
    private val directory = File(context.filesDir, safeNamespace)
    private val feedFile = File(directory, "last-good-v1.json")
    private val fileStore = AtomicFeedFileStore(directory, feedFile)
    private val metadata = context.getSharedPreferences("$safeNamespace-metadata", Context.MODE_PRIVATE)

    override suspend fun read(): CachedFeed? = fileStore.readAndMigrate { contents, modifiedAtMillis ->
        FeedCacheRecordCodec.read(
            contents = contents,
            fileModifiedAtMillis = modifiedAtMillis,
            legacyEndpoint = metadata.getString("endpoint", null),
            legacySavedAt = metadata.getString("savedAt", null),
        )
    }

    override suspend fun write(value: CachedFeed) {
        fileStore.replace(FeedCacheRecordCodec.encode(value))
    }

    private companion object {
        val CACHE_NAMESPACE = Regex("^[a-z0-9-]{1,48}$")
    }
}

internal data class AtomicFileReadDecision<T : Any>(
    val value: T,
    val migrationContents: String? = null,
)

internal object FeedCacheRecordCodec {
    private const val CACHE_SCHEMA_VERSION = 1
    private const val MAX_RAW_CHARACTERS = 2 * 1024 * 1024
    private const val LEGACY_METADATA_TOLERANCE_MILLIS = 60_000L
    private const val SCHEMA_KEY = "cacheSchemaVersion"
    private val envelopeKeys = setOf(SCHEMA_KEY, "raw", "endpoint", "savedAt")
    private val json = Json

    fun encode(value: CachedFeed): String = buildJsonObject {
        put(SCHEMA_KEY, JsonPrimitive(CACHE_SCHEMA_VERSION))
        put("raw", JsonPrimitive(value.raw))
        put("endpoint", value.endpoint?.let(::JsonPrimitive) ?: JsonNull)
        put("savedAt", JsonPrimitive(value.savedAt.toString()))
    }.toString()

    fun read(
        contents: String,
        fileModifiedAtMillis: Long,
        legacyEndpoint: String?,
        legacySavedAt: String?,
    ): AtomicFileReadDecision<CachedFeed>? {
        val root = runCatching { json.parseToJsonElement(contents) as? JsonObject }.getOrNull()
            ?: return null
        if (SCHEMA_KEY in root) {
            return decodeEnvelope(root)?.let(::AtomicFileReadDecision)
        }

        val fileSavedAt = Instant.ofEpochMilli(fileModifiedAtMillis.coerceAtLeast(0L))
        val parsedLegacySavedAt = legacySavedAt?.let { raw ->
            runCatching { Instant.parse(raw) }.getOrNull()
        }
        val metadataMatchesFile = parsedLegacySavedAt?.let { savedAt ->
            val difference = kotlin.math.abs(savedAt.toEpochMilli() - fileSavedAt.toEpochMilli())
            difference <= LEGACY_METADATA_TOLERANCE_MILLIS
        } == true
        val normalizedLegacyEndpoint = legacyEndpoint?.let(EndpointPolicy::normalize)
            ?.takeIf { it == legacyEndpoint }
        val migrated = CachedFeed(
            raw = contents,
            endpoint = normalizedLegacyEndpoint.takeIf { metadataMatchesFile },
            savedAt = parsedLegacySavedAt.takeIf { metadataMatchesFile } ?: fileSavedAt,
        )
        return AtomicFileReadDecision(migrated, encode(migrated))
    }

    private fun decodeEnvelope(root: JsonObject): CachedFeed? {
        if (root.keys != envelopeKeys) return null
        val schema = root[SCHEMA_KEY] as? JsonPrimitive ?: return null
        if (schema.isString || schema.intOrNull != CACHE_SCHEMA_VERSION) return null
        val rawValue = root["raw"] as? JsonPrimitive ?: return null
        if (!rawValue.isString) return null
        val raw = rawValue.contentOrNull?.takeIf { it.isNotBlank() && it.length <= MAX_RAW_CHARACTERS }
            ?: return null
        if (runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() == null) return null

        val endpointElement = root["endpoint"] ?: return null
        val endpoint = when (endpointElement) {
            JsonNull -> null
            is JsonPrimitive -> endpointElement.contentOrNull
                ?.takeIf { endpointElement.isString }
                ?.let(EndpointPolicy::normalize)
                ?.takeIf { it == endpointElement.content }
                ?: return null
            else -> return null
        }
        val savedAtValue = root["savedAt"] as? JsonPrimitive ?: return null
        if (!savedAtValue.isString) return null
        val savedAt = savedAtValue.contentOrNull?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: return null
        return CachedFeed(raw = raw, endpoint = endpoint, savedAt = savedAt)
    }
}

/**
 * Coordinates every writer targeting the same cache file, including separate repository instances.
 * A cache miss or failed best-effort write must never take down the live fleet refresh.
 */
internal class AtomicFeedFileStore(
    private val directory: File,
    private val destination: File,
    private val temporaryFile: (File) -> File = { parent ->
        File.createTempFile("${destination.name}.", ".tmp", parent)
    },
    private val beforeReplace: (File) -> Unit = {},
) {
    private val mutex = mutexFor(destination)

    suspend fun <T : Any> readAndMigrate(
        decode: (contents: String, modifiedAtMillis: Long) -> AtomicFileReadDecision<T>?,
    ): T? = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!destination.isFile) return@withContext null
            val contents = runCatching { destination.readText() }.getOrNull() ?: return@withContext null
            val decision = decode(contents, destination.lastModified()) ?: return@withContext null
            decision.migrationContents?.let(::replaceLocked)
            decision.value
        }
    }

    suspend fun replace(contents: String, onCommitted: (Boolean) -> Unit = {}): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val committed = replaceLocked(contents)
            runCatching { onCommitted(committed) }
            committed
        }
    }

    private fun replaceLocked(contents: String): Boolean {
        var temporary: File? = null
        return try {
            if (!directory.isDirectory && !directory.mkdirs()) return false
            pruneOrphanedTemporaryFiles()
            temporary = temporaryFile(directory)
            FileOutputStream(temporary).use { stream ->
                val writer = OutputStreamWriter(stream, Charsets.UTF_8)
                writer.write(contents)
                writer.flush()
                stream.fd.sync()
            }
            beforeReplace(temporary)
            moveIntoPlace(temporary)
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        } finally {
            temporary?.let { candidate -> runCatching { candidate.delete() } }
        }
    }

    private fun pruneOrphanedTemporaryFiles(nowMillis: Long = System.currentTimeMillis()) {
        val prefix = "${destination.name}."
        directory.listFiles().orEmpty()
            .asSequence()
            .filter { candidate ->
                candidate.isFile && candidate.name.startsWith(prefix) && candidate.name.endsWith(".tmp") &&
                    nowMillis - candidate.lastModified() >= ORPHAN_MINIMUM_AGE_MILLIS
            }
            .sortedBy(File::lastModified)
            .take(MAX_ORPHANS_PRUNED_PER_WRITE)
            .forEach { candidate -> runCatching { candidate.delete() } }
    }

    private fun moveIntoPlace(temporary: File): Boolean {
        try {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            return true
        } catch (_: AtomicMoveNotSupportedException) {
            // A same-directory replacing move is the safest available fallback.
        } catch (_: NoSuchFileException) {
            return false
        } catch (_: IOException) {
            if (!temporary.isFile) return false
        } catch (_: UnsupportedOperationException) {
            // Some Android file providers do not advertise atomic moves.
        }

        if (!temporary.isFile) return false
        return try {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        }
    }

    private companion object {
        private val lockGuard = Any()
        private val locks = mutableMapOf<String, Mutex>()
        private const val MAX_ORPHANS_PRUNED_PER_WRITE = 8
        private const val ORPHAN_MINIMUM_AGE_MILLIS = 5 * 60 * 1_000L

        fun mutexFor(file: File): Mutex {
            val key = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
            return synchronized(lockGuard) { locks.getOrPut(key) { Mutex() } }
        }
    }
}

class EndpointStore(context: Context) {
    private val preferences = context.getSharedPreferences("endpoint-settings", Context.MODE_PRIVATE)
    private val listeners = mutableSetOf<(List<String>) -> Unit>()
    private val current = AtomicReference(load())

    fun endpoints(): List<String> = current.get()

    fun replace(values: List<String>): List<String> {
        val normalized = EndpointPolicy.normalizeAll(values)
        current.set(normalized)
        preferences.edit { putString(KEY_ENDPOINTS, normalized.joinToString("\n")) }
        listeners.toList().forEach { it(normalized) }
        return normalized
    }

    fun add(values: List<String>): List<String> = replace(endpoints() + values)

    fun observe(listener: (List<String>) -> Unit): AutoCloseable {
        listeners += listener
        listener(endpoints())
        return AutoCloseable { listeners -= listener }
    }

    private fun load(): List<String> = EndpointPolicy.normalizeAll(
        preferences.getString(KEY_ENDPOINTS, "")
            .orEmpty()
            .lineSequence()
            .toList(),
    )

    private companion object {
        const val KEY_ENDPOINTS = "httpsEndpoints"
    }
}
