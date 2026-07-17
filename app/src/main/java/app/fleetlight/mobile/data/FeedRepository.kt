package app.fleetlight.mobile.data

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

fun interface FeedSource {
    suspend fun load(endpoint: String): String
}

data class CachedFeed(
    val raw: String,
    val endpoint: String?,
    val savedAt: Instant,
)

interface FeedCache {
    suspend fun read(): CachedFeed?
    suspend fun write(value: CachedFeed)
}

sealed interface FeedRefreshResult {
    data class Success(
        val feed: MobileFeed,
        val endpoint: String?,
        val fromCache: Boolean,
        val fetchedAt: Instant,
        val endpointFailures: List<String> = emptyList(),
    ) : FeedRefreshResult

    data object NoEndpoints : FeedRefreshResult

    data class Failure(
        val message: String,
        val endpointFailures: List<String>,
    ) : FeedRefreshResult
}

class FeedRepository(
    private val source: FeedSource,
    private val cache: FeedCache,
    private val parser: FeedParser = FeedParser(),
    private val now: () -> Instant = Instant::now,
) {
    suspend fun refresh(rawEndpoints: List<String>): FeedRefreshResult {
        val endpoints = EndpointPolicy.normalizeAll(rawEndpoints)
        if (endpoints.isEmpty()) {
            return cachedOr(FeedRefreshResult.NoEndpoints, emptyList())
        }

        val referenceTime = now()
        val attempts = coroutineScope {
            endpoints.map { endpoint ->
                async {
                    runCatching {
                        val raw = source.load(endpoint)
                        Triple(endpoint, raw, parseValidated(raw, referenceTime))
                    }.fold(
                        onSuccess = { EndpointAttempt.Success(it.first, it.second, it.third) },
                        onFailure = { EndpointAttempt.Failure(endpoint, safeError(it)) },
                    )
                }
            }.awaitAll()
        }

        val failures = attempts.filterIsInstance<EndpointAttempt.Failure>()
            .map { "${it.endpoint}: ${it.message}" }
        val freshest = attempts.filterIsInstance<EndpointAttempt.Success>()
            .maxByOrNull { it.feed.generatedAt }
        if (freshest != null) {
            val savedAt = now()
            cache.write(CachedFeed(freshest.raw, freshest.endpoint, savedAt))
            return FeedRefreshResult.Success(
                feed = freshest.feed,
                endpoint = freshest.endpoint,
                fromCache = false,
                fetchedAt = savedAt,
                endpointFailures = failures,
            )
        }

        return cachedOr(
            fallback = FeedRefreshResult.Failure(
                message = "No configured endpoint returned a valid schema 1 feed",
                endpointFailures = failures,
            ),
            failures = failures,
        )
    }

    suspend fun cached(): FeedRefreshResult.Success? {
        val cached = cache.read() ?: return null
        val feed = runCatching { parseValidated(cached.raw, now()) }.getOrNull() ?: return null
        return FeedRefreshResult.Success(
            feed = feed,
            endpoint = cached.endpoint,
            fromCache = true,
            fetchedAt = cached.savedAt,
        )
    }

    private suspend fun cachedOr(
        fallback: FeedRefreshResult,
        failures: List<String>,
    ): FeedRefreshResult {
        val cached = cached() ?: return fallback
        return cached.copy(endpointFailures = failures)
    }

    private sealed interface EndpointAttempt {
        data class Success(val endpoint: String, val raw: String, val feed: MobileFeed) : EndpointAttempt
        data class Failure(val endpoint: String, val message: String) : EndpointAttempt
    }

    private fun safeError(error: Throwable): String = when (error) {
        is FeedParseException -> error.message ?: "invalid feed"
        else -> error.message?.take(160) ?: error::class.simpleName ?: "request failed"
    }

    private fun parseValidated(raw: String, referenceTime: Instant): MobileFeed {
        val feed = parser.parse(raw)
        if (feed.generatedAt.isAfter(referenceTime.plus(MAX_FUTURE_SKEW))) {
            throw FeedParseException("Feed generatedAt is more than 5 minutes in the future")
        }
        return feed
    }

    private companion object {
        val MAX_FUTURE_SKEW: Duration = Duration.ofMinutes(5)
    }
}
