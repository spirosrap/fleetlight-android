package app.fleetlight.mobile.ui

import app.fleetlight.mobile.data.ControlCheckOrchestrator
import app.fleetlight.mobile.data.ControlCheckState
import app.fleetlight.mobile.data.ControlCheck
import app.fleetlight.mobile.data.ControlAction
import app.fleetlight.mobile.data.ControlHttpException
import app.fleetlight.mobile.data.ControlProtocolException
import app.fleetlight.mobile.data.ControlCredentialStore
import app.fleetlight.mobile.data.ControlHttpRequest
import app.fleetlight.mobile.data.ControlRepository
import app.fleetlight.mobile.data.ControlSession
import app.fleetlight.mobile.data.ControlTransport
import app.fleetlight.mobile.data.StoredControlCheck
import app.fleetlight.mobile.data.StoredControlJob
import app.fleetlight.mobile.data.isRetryableControlFailure
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class FleetlightViewModelCheckOrchestrationTest {
    private val endpoint = "https://observer.example/fleetlight/mobile-feed.json"
    private val requestId = "00000000-0000-0000-0000-000000000001"
    private val checkId = "10000000-0000-0000-0000-000000000001"

    @Test
    fun pairingWaitsForActiveOrStoredControlWorkIncludingTerminalPostSync() {
        val running = ControlCheck(checkId, requestId, ControlCheckState.RUNNING, "linux", "Checking")
        val terminal = running.copy(state = ControlCheckState.SUCCEEDED, phase = "complete")

        assertTrue(pairingBlockedByControlWork(FleetUiState(activeCheck = running), false, false))
        assertTrue(pairingBlockedByControlWork(FleetUiState(activeCheck = terminal), false, true))
        assertTrue(pairingBlockedByControlWork(FleetUiState(checkSyncPending = true), false, false))
        assertTrue(pairingBlockedByControlWork(FleetUiState(), true, false))
        assertFalse(pairingBlockedByControlWork(FleetUiState(activeCheck = terminal), false, false))
        assertTrue(matchesPairedController("  $endpoint  ", endpoint))
        assertFalse(matchesPairedController(endpoint, "https://other.example/feed.json"))
        assertFalse(matchesPairedController(endpoint, null))
    }

    @Test
    fun liveCheckRunsPostPollThenRefreshesFeedAndControllerStatus() = runTest {
        val transport = QueueTransport(
            mutableListOf(
                checkJson("queued", "queued"),
                checkJson("running", "linux"),
                checkJson("succeeded", "complete"),
                """{"controllerId":"controller-a","commandAuthorityEnabled":true,"capabilities":[{"hostId":"host-a","actions":[]}]}""",
            ),
        )
        val repository = ControlRepository(transport, Credentials())
        val orchestrator = ControlCheckOrchestrator(
            repository = repository,
            pause = {},
            pollIntervalMillis = 0,
            maximumPolls = 5,
        )
        val events = mutableListOf<String>()

        val completed = orchestrator.execute(
            stored = StoredControlCheck(
                endpoint = endpoint,
                controlBase = "https://observer.example/fleetlight/control/v1",
                checkId = null,
                requestId = requestId,
            ),
            onAccepted = { events += "accepted:${it.state}" },
            onProgress = { events += "progress:${it.state}" },
            refreshFeed = { events += "refresh:feed" },
            refreshStatus = {
                repository.status(endpoint)
                events += "refresh:status"
            },
        )

        assertEquals(ControlCheckState.SUCCEEDED, completed.state)
        assertEquals(
            listOf(
                "accepted:QUEUED",
                "progress:QUEUED",
                "progress:RUNNING",
                "progress:SUCCEEDED",
                "refresh:feed",
                "refresh:status",
            ),
            events,
        )
        assertEquals(listOf("POST", "GET", "GET", "GET"), transport.requests.map { it.method })
        assertTrue(transport.requests.first().url.endsWith("/control/v1/checks"))
        assertTrue(transport.requests[1].url.endsWith("/control/v1/checks/$checkId"))
        assertTrue(transport.requests.last().url.endsWith("/control/v1/status"))
    }

    @Test
    fun processRecreationResumesPersistedCheckWithoutCreatingAnotherRequest() = runTest {
        val transport = QueueTransport(
            mutableListOf(
                checkJson("running", "linux"),
                checkJson("succeeded", "complete"),
            ),
        )
        val orchestrator = ControlCheckOrchestrator(
            repository = ControlRepository(transport, Credentials()),
            pause = {},
            pollIntervalMillis = 0,
            maximumPolls = 2,
        )

        val completed = orchestrator.execute(
            stored = StoredControlCheck(
                endpoint = endpoint,
                controlBase = "https://observer.example/fleetlight/control/v1",
                checkId = checkId,
                requestId = requestId,
            ),
            refreshFeed = {},
            refreshStatus = {},
        )

        assertEquals(ControlCheckState.SUCCEEDED, completed.state)
        assertEquals(listOf("GET", "GET"), transport.requests.map { it.method })
        assertTrue(transport.requests.all { it.url.endsWith("/control/v1/checks/$checkId") })
    }

    @Test
    fun cancellationEscapesPollingSoPersistedRecoveryIsNotTreatedAsFailure() = runTest {
        var requests = 0
        var refreshed = false
        val repository = ControlRepository(
            ControlTransport {
                if (requests++ == 0) checkJson("queued", "queued") else throw CancellationException("closed")
            },
            Credentials(),
        )
        val orchestrator = ControlCheckOrchestrator(
            repository = repository,
            pause = {},
            pollIntervalMillis = 0,
            maximumPolls = 2,
        )

        var cancellation: CancellationException? = null
        try {
            orchestrator.execute(
                stored = StoredControlCheck(
                    endpoint,
                    "https://observer.example/fleetlight/control/v1",
                    null,
                    requestId,
                ),
                refreshFeed = { refreshed = true },
                refreshStatus = { refreshed = true },
            )
        } catch (error: CancellationException) {
            cancellation = error
        }

        assertEquals("closed", cancellation?.message)
        assertTrue(!refreshed)
    }

    @Test
    fun terminalRefreshFailureRetainsRecoveryAndStillAttemptsStatusRefresh() = runTest {
        val transport = QueueTransport(mutableListOf(checkJson("succeeded", "complete")))
        val orchestrator = ControlCheckOrchestrator(
            repository = ControlRepository(transport, Credentials()),
            pause = {},
            pollIntervalMillis = 0,
            maximumPolls = 1,
        )
        var active: app.fleetlight.mobile.data.ControlCheck? = null
        var statusAttempted = false
        var failure: Throwable? = null

        try {
            orchestrator.execute(
                stored = StoredControlCheck(
                    endpoint,
                    "https://observer.example/fleetlight/control/v1",
                    null,
                    requestId,
                ),
                onProgress = { active = it },
                refreshFeed = { throw java.io.IOException("controller feed unavailable") },
                refreshStatus = { statusAttempted = true },
            )
        } catch (error: Throwable) {
            failure = error
        }

        assertTrue(failure is java.io.IOException)
        assertTrue(statusAttempted)
        assertFalse(shouldClearStoredCheckAfterFailure(active, requireNotNull(failure)))
        assertFalse(shouldClearStoredCheckAfterFailure(active, ControlHttpException(503, "unavailable")))
        assertFalse(shouldClearStoredCheckAfterFailure(active, ControlHttpException(408, "timeout")))
        assertFalse(shouldClearStoredCheckAfterFailure(active, ControlHttpException(429, "slow down")))
        assertTrue(shouldClearStoredCheckAfterFailure(active, ControlHttpException(401, "expired token")))
        assertTrue(shouldClearStoredCheckAfterFailure(active, ControlHttpException(404, "check expired")))
        assertTrue(shouldClearStoredCheckAfterFailure(active, ControlProtocolException("mismatched response")))
    }

    @Test
    fun liveCheckRetriesRateLimitWithTheSameIdentity() = runTest {
        var call = 0
        val transport = ControlTransport { request ->
            when (call++) {
                0 -> checkJson("queued", "queued")
                1 -> throw ControlHttpException(429, "slow down")
                else -> checkJson("succeeded", "complete")
            }
        }
        val orchestrator = ControlCheckOrchestrator(
            repository = ControlRepository(transport, Credentials()),
            pause = {},
            pollIntervalMillis = 0,
            maximumPolls = 3,
        )

        val completed = orchestrator.execute(
            stored = StoredControlCheck(
                endpoint,
                "https://observer.example/fleetlight/control/v1",
                null,
                requestId,
            ),
            refreshFeed = {},
            refreshStatus = {},
        )

        assertEquals(ControlCheckState.SUCCEEDED, completed.state)
        assertEquals(3, call)
        assertTrue(ControlHttpException(408, "timeout").isRetryableControlFailure())
        assertTrue(ControlHttpException(429, "slow down").isRetryableControlFailure())
        assertTrue(ControlHttpException(503, "offline").isRetryableControlFailure())
        assertFalse(ControlHttpException(409, "busy").isRetryableControlFailure())
        assertTrue(ControlHttpException(409, "busy", "controller-busy").isControllerBusyFailure())
        assertFalse(ControlHttpException(409, "conflict", "request-id-conflict").isControllerBusyFailure())
    }

    @Test
    fun storedJobLookupRetryPreservesTheExactAcceptedOperationIdentity() {
        val stored = StoredControlJob(
            endpoint = endpoint,
            controlBase = "https://observer.example/fleetlight/control/v1",
            jobId = "20000000-0000-0000-0000-000000000001",
            requestId = requestId,
            action = ControlAction.LINUX_OS,
            targetHostIds = listOf("host-a", "host-b"),
        )

        listOf(
            java.io.IOException("offline"),
            ControlHttpException(408, "timeout"),
            ControlHttpException(429, "slow down"),
            ControlHttpException(503, "unavailable"),
            ControlHttpException(409, "busy", "controller-busy"),
        ).forEach { error ->
            val retry = storedJobRetryAfterLookupFailure(stored, error)
            assertSame(stored, retry)
            assertEquals(stored.jobId, retry?.jobId)
            assertEquals(stored.requestId, retry?.requestId)
            assertEquals(stored.action, retry?.action)
            assertEquals(stored.targetHostIds, retry?.targetHostIds)
        }
        assertNull(storedJobRetryAfterLookupFailure(stored, ControlHttpException(401, "expired")))
        assertNull(storedJobRetryAfterLookupFailure(stored, ControlHttpException(404, "missing")))
    }

    private class Credentials : ControlCredentialStore {
        private val session = ControlSession(
            authority = "observer.example",
            controlBase = "https://observer.example/fleetlight/control/v1",
            token = "token-a",
            observerId = "controller-a",
        )
        override fun read(authority: String): ControlSession? = session.takeIf { authority == it.authority }
        override fun write(session: ControlSession) = Unit
        override fun remove(authority: String) = Unit
    }

    private class QueueTransport(private val responses: MutableList<String>) : ControlTransport {
        val requests = mutableListOf<ControlHttpRequest>()
        override suspend fun request(request: ControlHttpRequest): String {
            requests += request
            return responses.removeAt(0)
        }
    }

    private fun checkJson(state: String, phase: String): String =
        """{"id":"$checkId","requestId":"$requestId","state":"$state","phase":"$phase","detail":"Checking $phase"}"""
}
