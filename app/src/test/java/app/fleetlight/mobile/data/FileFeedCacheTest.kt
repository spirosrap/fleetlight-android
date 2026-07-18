package app.fleetlight.mobile.data

import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileFeedCacheTest {
    @Test
    fun completeEnvelopeIgnoresStaleLegacyMetadataAcrossCrashWindow() {
        val current = CachedFeed(
            raw = feedJson("Current"),
            endpoint = "https://current.example/feed.json",
            savedAt = Instant.parse("2026-07-18T08:00:00Z"),
        )

        val decoded = FeedCacheRecordCodec.read(
            contents = FeedCacheRecordCodec.encode(current),
            fileModifiedAtMillis = Instant.parse("2026-07-18T08:00:01Z").toEpochMilli(),
            legacyEndpoint = "https://stale.example/feed.json",
            legacySavedAt = "2026-07-17T08:00:00Z",
        )

        assertEquals(current, decoded?.value)
        assertEquals(null, decoded?.migrationContents)
    }

    @Test
    fun mismatchedLegacyMetadataFallsBackToFileFactsBeforeAtomicMigration() {
        val fileTime = Instant.parse("2026-07-18T08:00:00Z")
        val decoded = requireNotNull(FeedCacheRecordCodec.read(
            contents = feedJson("Legacy"),
            fileModifiedAtMillis = fileTime.toEpochMilli(),
            legacyEndpoint = "https://wrong-generation.example/feed.json",
            legacySavedAt = "2026-07-17T08:00:00Z",
        ))

        assertEquals(null, decoded.value.endpoint)
        assertEquals(fileTime, decoded.value.savedAt)
        val migrated = FeedCacheRecordCodec.read(
            contents = requireNotNull(decoded.migrationContents),
            fileModifiedAtMillis = fileTime.plusSeconds(2).toEpochMilli(),
            legacyEndpoint = "https://still-stale.example/feed.json",
            legacySavedAt = "2026-07-16T08:00:00Z",
        )
        assertEquals(decoded.value, migrated?.value)
        assertEquals(null, migrated?.migrationContents)
    }

    @Test
    fun legacyRecordMigratesInsideTheFileLockAndKeepsConsistentMetadata() = runTest {
        val directory = Files.createTempDirectory("fleetlight-feed-cache-migration").toFile()
        try {
            val destination = File(directory, "last-good-v1.json")
            val fileTime = Instant.parse("2026-07-18T08:00:00Z")
            destination.writeText(feedJson("Legacy"))
            assertTrue(destination.setLastModified(fileTime.toEpochMilli()))
            val store = AtomicFeedFileStore(directory, destination)

            val migrated = store.readAndMigrate { contents, modifiedAtMillis ->
                FeedCacheRecordCodec.read(
                    contents = contents,
                    fileModifiedAtMillis = modifiedAtMillis,
                    legacyEndpoint = "https://legacy.example/feed.json",
                    legacySavedAt = fileTime.minusSeconds(1).toString(),
                )
            }

            assertEquals("https://legacy.example/feed.json", migrated?.endpoint)
            assertEquals(fileTime.minusSeconds(1), migrated?.savedAt)
            val reread = FeedCacheRecordCodec.read(
                contents = destination.readText(),
                fileModifiedAtMillis = destination.lastModified(),
                legacyEndpoint = "https://wrong.example/feed.json",
                legacySavedAt = "2020-01-01T00:00:00Z",
            )
            assertEquals(migrated, reread?.value)
            assertEquals(null, reread?.migrationContents)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun malformedOrExtendedEnvelopeIsRejectedInsteadOfTreatedAsLegacyFeed() {
        val malformed = """{"cacheSchemaVersion":1,"raw":"{}","endpoint":null,"savedAt":"not-a-time"}"""
        val extended = """{"cacheSchemaVersion":1,"raw":"{}","endpoint":null,"savedAt":"2026-07-18T08:00:00Z","unexpected":true}"""

        assertEquals(null, FeedCacheRecordCodec.read(malformed, 0L, null, null))
        assertEquals(null, FeedCacheRecordCodec.read(extended, 0L, null, null))
    }

    @Test
    fun concurrentStoresSerializeWritesAndUseUniqueSameDirectoryTemps() = runTest {
        val directory = Files.createTempDirectory("fleetlight-feed-cache").toFile()
        try {
            val destination = File(directory, "last-good-v1.json")
            val activeWriters = AtomicInteger(0)
            val maximumWriters = AtomicInteger(0)
            val temporaryNames = Collections.synchronizedSet(mutableSetOf<String>())
            val beforeReplace: (File) -> Unit = { temporary ->
                temporaryNames += temporary.name
                val active = activeWriters.incrementAndGet()
                maximumWriters.accumulateAndGet(active, ::maxOf)
                Thread.sleep(5)
                activeWriters.decrementAndGet()
            }
            val first = AtomicFeedFileStore(directory, destination, beforeReplace = beforeReplace)
            val second = AtomicFeedFileStore(directory, destination, beforeReplace = beforeReplace)
            val payloads = (0 until 24).map { index -> "payload-$index-${"x".repeat(2_048)}" }

            coroutineScope {
                payloads.mapIndexed { index, payload ->
                    async(Dispatchers.Default) {
                        assertTrue((if (index % 2 == 0) first else second).replace(payload))
                    }
                }.awaitAll()
            }

            assertEquals(1, maximumWriters.get())
            assertEquals(payloads.size, temporaryNames.size)
            assertTrue(destination.readText() in payloads)
            assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun missingTemporaryDuringReplacementKeepsLastGoodFileWithoutCrashing() = runTest {
        val directory = Files.createTempDirectory("fleetlight-feed-cache-missing-temp").toFile()
        try {
            val destination = File(directory, "last-good-v1.json").apply { writeText("last-good") }
            val store = AtomicFeedFileStore(
                directory = directory,
                destination = destination,
                beforeReplace = { temporary -> assertTrue(temporary.delete()) },
            )

            assertFalse(store.replace("new-value"))
            assertEquals("last-good", destination.readText())
            assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun lockedWritePrunesOldOrphanTempsWithoutTouchingRecentFiles() = runTest {
        val directory = Files.createTempDirectory("fleetlight-feed-cache-pruning").toFile()
        try {
            val destination = File(directory, "last-good-v1.json")
            val oldOrphan = File(directory, "last-good-v1.json.old.tmp").apply {
                writeText("orphan")
                setLastModified(System.currentTimeMillis() - 10 * 60 * 1_000L)
            }
            val recent = File(directory, "last-good-v1.json.recent.tmp").apply { writeText("recent") }

            assertTrue(AtomicFeedFileStore(directory, destination).replace("current"))

            assertFalse(oldOrphan.exists())
            assertTrue(recent.exists())
            assertEquals("current", destination.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun feedJson(observer: String): String =
        """{"schemaVersion":1,"generatedAt":"2026-07-18T08:00:00Z","observer":{"name":"$observer"},"summary":{},"hosts":[],"linuxUpdates":[],"incidents":[],"metrics":[]}"""
}
