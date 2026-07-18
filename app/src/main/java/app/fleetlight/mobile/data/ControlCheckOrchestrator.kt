package app.fleetlight.mobile.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

class ControlCheckOrchestrator(
    private val repository: ControlRepository,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val pollIntervalMillis: Long = 1_500L,
    private val maximumPolls: Int = 1_200,
) {
    suspend fun execute(
        stored: StoredControlCheck,
        onAccepted: suspend (ControlCheck) -> Unit = {},
        onProgress: suspend (ControlCheck) -> Unit = {},
        refreshFeed: suspend () -> Unit,
        refreshStatus: suspend () -> Unit,
    ): ControlCheck {
        var check = if (stored.checkId == null) {
            repository.createCheck(stored.endpoint, stored.requestId)
        } else {
            repository.check(stored.endpoint, stored.checkId, stored.requestId)
        }
        onAccepted(check)
        onProgress(check)

        var polls = 0
        var consecutiveFailures = 0
        var nextDelay = pollIntervalMillis
        while (!check.state.isTerminal) {
            if (polls++ >= maximumPolls) {
                throw ControlProtocolException("Update check polling paused after 30 minutes; reopen Fleetlight to resume")
            }
            pause(nextDelay)
            val result = runCatching {
                repository.check(stored.endpoint, check.id, stored.requestId)
            }
            val updated = result.getOrNull()
            if (updated == null) {
                val error = requireNotNull(result.exceptionOrNull())
                if (error is CancellationException) throw error
                if (!error.isRetryableControlFailure() || ++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    throw error
                }
                nextDelay = (nextDelay * 2).coerceAtMost(MAX_POLL_INTERVAL_MILLIS)
                continue
            }
            check = updated
            consecutiveFailures = 0
            nextDelay = pollIntervalMillis
            onProgress(check)
        }

        val feedResult = runCatching { refreshFeed() }
        val statusResult = runCatching { refreshStatus() }
        feedResult.getOrThrow()
        statusResult.getOrThrow()
        return check
    }

    private companion object {
        const val MAX_CONSECUTIVE_FAILURES = 5
        const val MAX_POLL_INTERVAL_MILLIS = 15_000L
    }
}

internal fun Throwable.isRetryableControlFailure(): Boolean =
    this is IOException ||
        (this is ControlHttpException && (status == 408 || status == 429 || status >= 500))
