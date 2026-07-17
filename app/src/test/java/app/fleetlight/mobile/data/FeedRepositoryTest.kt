package app.fleetlight.mobile.data

import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedRepositoryTest {
    @Test
    fun choosesFreshestValidSchemaOneFeedAndCachesIt() = runTest {
        val older = feedJson("2026-01-01T00:00:00Z", "Older")
        val newer = feedJson("2026-01-01T00:01:00Z", "Newer")
        val cache = MemoryCache()
        val repository = FeedRepository(
            source = FeedSource { endpoint -> if (endpoint.contains("one")) older else newer },
            cache = cache,
            now = { Instant.parse("2026-01-01T00:01:05Z") },
        )

        val result = repository.refresh(listOf("https://one.example/feed", "https://two.example/feed"))
            as FeedRefreshResult.Success

        assertEquals("Newer", result.feed.observer.name)
        assertEquals("https://two.example/feed", result.endpoint)
        assertFalse(result.fromCache)
        assertEquals(newer, cache.value?.raw)
    }

    @Test
    fun fallsBackToLastGoodFeedWhenAllEndpointsFail() = runTest {
        val cachedRaw = feedJson("2026-01-01T00:00:00Z", "Cached")
        val cache = MemoryCache(CachedFeed(cachedRaw, "https://cached.example/feed", Instant.parse("2026-01-01T00:00:05Z")))
        val repository = FeedRepository(
            source = FeedSource { error("offline") },
            cache = cache,
        )

        val result = repository.refresh(listOf("https://one.example/feed")) as FeedRefreshResult.Success

        assertTrue(result.fromCache)
        assertEquals("Cached", result.feed.observer.name)
        assertTrue(result.endpointFailures.single().contains("offline"))
    }

    @Test
    fun reportsFailureWhenNoEndpointOrCacheIsUsable() = runTest {
        val repository = FeedRepository(
            source = FeedSource { "{}" },
            cache = MemoryCache(),
        )

        val result = repository.refresh(listOf("https://one.example/feed"))

        assertTrue(result is FeedRefreshResult.Failure)
    }

    @Test
    fun rejectsFeedsGeneratedMoreThanFiveMinutesInTheFuture() = runTest {
        val currentTime = Instant.parse("2026-01-01T12:00:00Z")
        val atBoundary = feedJson("2026-01-01T12:05:00Z", "Boundary")
        val tooFarAhead = feedJson("2026-01-01T12:05:01Z", "Future")
        val cache = MemoryCache()
        val repository = FeedRepository(
            source = FeedSource { endpoint -> if (endpoint.contains("future")) tooFarAhead else atBoundary },
            cache = cache,
            now = { currentTime },
        )

        val accepted = repository.refresh(listOf("https://boundary.example/feed")) as FeedRefreshResult.Success
        assertEquals("Boundary", accepted.feed.observer.name)

        cache.value = null
        val rejected = repository.refresh(listOf("https://future.example/feed"))
        assertTrue(rejected is FeedRefreshResult.Failure)
        assertTrue((rejected as FeedRefreshResult.Failure).endpointFailures.single().contains("more than 5 minutes"))
    }

    private class MemoryCache(var value: CachedFeed? = null) : FeedCache {
        override suspend fun read(): CachedFeed? = value
        override suspend fun write(value: CachedFeed) { this.value = value }
    }

    private fun feedJson(timestamp: String, observer: String): String =
        """{"schemaVersion":1,"generatedAt":"$timestamp","observer":{"name":"$observer"},"summary":{},"hosts":[],"linuxUpdates":[],"incidents":[],"metrics":[]}"""
}
