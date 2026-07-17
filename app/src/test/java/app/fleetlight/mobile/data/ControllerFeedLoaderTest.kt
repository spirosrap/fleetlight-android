package app.fleetlight.mobile.data

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ControllerFeedLoaderTest {
    private val controller = "https://controller.example/fleetlight/mobile-feed.json"
    private val other = "https://newer.example/fleetlight/mobile-feed.json"
    private val now = { Instant.parse("2026-07-17T10:06:00Z") }

    @Test
    fun exactControllerLoadDoesNotSelectNewerObserver() = runTest {
        val requests = mutableListOf<String>()
        val source = FeedSource { endpoint ->
            requests += endpoint
            if (endpoint == controller) feedJson("2026-07-17T10:00:00Z", "Controller")
            else feedJson("2026-07-17T10:05:00Z", "Newer observer")
        }
        val main = FeedRepository(source, MemoryCache(), now = now).refresh(listOf(controller, other))
            as FeedRefreshResult.Success
        assertEquals("Newer observer", main.feed.observer.name)

        requests.clear()
        val exact = ControllerFeedLoader(FeedRepository(source, MemoryCache(), now = now)).live(controller)

        assertEquals("Controller", exact.feed.observer.name)
        assertEquals(controller, exact.endpoint)
        assertFalse(exact.fromCache)
        assertEquals(listOf(controller), requests)
    }

    @Test
    fun cachedFallbackIsRejectedForTerminalRefresh() = runTest {
        val cached = CachedFeed(
            raw = feedJson("2026-07-17T10:00:00Z", "Cached controller"),
            endpoint = controller,
            savedAt = Instant.parse("2026-07-17T10:00:01Z"),
        )
        val loader = ControllerFeedLoader(
            FeedRepository(FeedSource { throw IOException("offline") }, MemoryCache(cached), now = now),
        )

        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { loader.live(controller) }
        }
    }

    private class MemoryCache(var value: CachedFeed? = null) : FeedCache {
        override suspend fun read(): CachedFeed? = value
        override suspend fun write(value: CachedFeed) { this.value = value }
    }

    private fun feedJson(timestamp: String, observer: String): String =
        """{"schemaVersion":1,"generatedAt":"$timestamp","observer":{"name":"$observer"},"summary":{},"hosts":[],"linuxUpdates":[],"incidents":[],"metrics":[]}"""
}
