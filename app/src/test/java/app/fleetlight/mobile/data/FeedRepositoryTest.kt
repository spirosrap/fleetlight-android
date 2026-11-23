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
        assertEquals(listOf("Newer", "Older"), result.observerViews.map { it.observerName })
        assertEquals("https://one.example/feed", result.observerViews.last().endpoint)
    }

    @Test
    fun staysWithTheObserverOnScreenWhileItKeepsUp() = runTest {
        var mac = feedJson("2026-01-01T00:01:00Z", "Mac")
        var linux = feedJson("2026-01-01T00:00:40Z", "Linux")
        var macAnswers = true
        val repository = FeedRepository(
            source = FeedSource { endpoint ->
                if (endpoint.contains("mac")) { if (macAnswers) mac else error("HTTP 502") } else linux
            },
            cache = MemoryCache(),
            now = { Instant.parse("2026-01-01T00:10:00Z") },
        )
        val endpoints = listOf("https://mac.example/feed", "https://linux.example/feed")
        suspend fun shown() = (repository.refresh(endpoints) as FeedRefreshResult.Success).feed.observer.name

        assertEquals("Mac", shown())

        // The other observer checked more recently; the one on screen is still current.
        linux = feedJson("2026-01-01T00:01:40Z", "Linux")
        assertEquals("Mac", shown())
        mac = feedJson("2026-01-01T00:02:00Z", "Mac")
        assertEquals("Mac", shown())

        // It stopped checking: the live observer takes over once the gap is more than a missed check.
        linux = feedJson("2026-01-01T00:04:30Z", "Linux")
        assertEquals("Mac", shown())
        linux = feedJson("2026-01-01T00:04:31Z", "Linux")
        assertEquals("Linux", shown())

        // And the phone stays there, even when the first one is back and newer.
        mac = feedJson("2026-01-01T00:05:00Z", "Mac")
        assertEquals("Linux", shown())

        // The other observer going away changes nothing.
        linux = feedJson("2026-01-01T00:06:00Z", "Linux")
        mac = feedJson("2026-01-01T00:06:10Z", "Mac")
        assertEquals("Linux", shown())
        macAnswers = false
        assertEquals("Linux", shown())
    }

    @Test
    fun leavesAnObserverThatStopsAnswering() = runTest {
        var macAnswers = true
        val repository = FeedRepository(
            source = FeedSource { endpoint ->
                when {
                    endpoint.contains("linux") -> feedJson("2026-01-01T00:00:30Z", "Linux")
                    macAnswers -> feedJson("2026-01-01T00:01:00Z", "Mac")
                    else -> error("HTTP 502")
                }
            },
            cache = MemoryCache(),
            now = { Instant.parse("2026-01-01T00:10:00Z") },
        )
        val endpoints = listOf("https://mac.example/feed", "https://linux.example/feed")

        assertEquals("Mac", (repository.refresh(endpoints) as FeedRefreshResult.Success).feed.observer.name)
        macAnswers = false
        val result = repository.refresh(endpoints) as FeedRefreshResult.Success
        assertEquals("Linux", result.feed.observer.name)
        assertFalse(result.fromCache)
    }

    @Test
    fun remembersTheObserverOnScreenAcrossAnAppRestart() = runTest {
        val cache = MemoryCache(
            CachedFeed(feedJson("2026-01-01T00:00:00Z", "Linux"), "https://linux.example/feed", Instant.parse("2026-01-01T00:00:05Z")),
        )
        val repository = FeedRepository(
            source = FeedSource { endpoint ->
                if (endpoint.contains("mac")) feedJson("2026-01-01T00:01:00Z", "Mac") else feedJson("2026-01-01T00:00:40Z", "Linux")
            },
            cache = cache,
            now = { Instant.parse("2026-01-01T00:10:00Z") },
        )

        val result = repository.refresh(listOf("https://mac.example/feed", "https://linux.example/feed"))
            as FeedRefreshResult.Success

        assertEquals("Linux", result.feed.observer.name)
        assertEquals(listOf("Mac", "Linux"), result.observerViews.map { it.observerName })
    }

    @Test
    fun oneFailedEndpointDoesNotTurnAValidFailoverFeedIntoAnError() = runTest {
        val live = feedJson("2026-01-01T00:01:00Z", "Live observer")
        val repository = FeedRepository(
            source = FeedSource { endpoint ->
                if (endpoint.contains("unavailable")) error("HTTP 502") else live
            },
            cache = MemoryCache(),
            now = { Instant.parse("2026-01-01T00:01:05Z") },
        )

        val result = repository.refresh(
            listOf("https://unavailable.example/feed", "https://live.example/feed"),
        ) as FeedRefreshResult.Success

        assertFalse(result.fromCache)
        assertEquals("Live observer", result.feed.observer.name)
        assertEquals("https://live.example/feed", result.endpoint)
        assertEquals(1, result.endpointFailures.size)
        assertTrue(result.endpointFailures.single().contains("HTTP 502"))
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
