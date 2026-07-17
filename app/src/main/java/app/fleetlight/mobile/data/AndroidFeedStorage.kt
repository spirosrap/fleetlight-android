package app.fleetlight.mobile.data

import android.content.Context
import androidx.core.content.edit
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FileFeedCache(context: Context) : FeedCache {
    private val directory = File(context.filesDir, "feed-cache")
    private val feedFile = File(directory, "last-good-v1.json")
    private val metadata = context.getSharedPreferences("feed-cache-metadata", Context.MODE_PRIVATE)

    override suspend fun read(): CachedFeed? = withContext(Dispatchers.IO) {
        if (!feedFile.isFile) return@withContext null
        val raw = runCatching { feedFile.readText() }.getOrNull() ?: return@withContext null
        val savedAt = metadata.getString("savedAt", null)?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: Instant.ofEpochMilli(feedFile.lastModified())
        CachedFeed(
            raw = raw,
            endpoint = metadata.getString("endpoint", null),
            savedAt = savedAt,
        )
    }

    override suspend fun write(value: CachedFeed) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val temporary = File(directory, "last-good-v1.json.tmp")
        temporary.writeText(value.raw)
        if (!temporary.renameTo(feedFile)) {
            temporary.copyTo(feedFile, overwrite = true)
            temporary.delete()
        }
        metadata.edit {
            putString("endpoint", value.endpoint)
            putString("savedAt", value.savedAt.toString())
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
