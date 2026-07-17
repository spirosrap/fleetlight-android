package app.fleetlight.mobile.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlRepositoryTest {
    private val endpoint = "https://observer.example/fleetlight/mobile-feed.json"
    private val requestId = "00000000-0000-0000-0000-000000000001"

    @Test
    fun pairsAgainstPrefixedSameOriginAndPinsController() = runTest {
        val transport = QueueTransport(
            mutableListOf(
                """{"token":"token-a","controllerId":"controller-a"}""",
                statusJson("controller-a"),
            ),
        )
        val credentials = MemoryCredentials()
        val repository = ControlRepository(transport, credentials)

        repository.pair(endpoint, "12345678", "device-a", "Android")

        assertEquals("https://observer.example/fleetlight/control/v1/pair", transport.requests[0].url)
        assertEquals("https://observer.example/fleetlight/control/v1", credentials.value?.controlBase)
        assertEquals("controller-a", credentials.value?.observerId)
        assertTrue(transport.requests[1].token == "token-a")
    }

    @Test
    fun submitsOneIdempotentJobWithoutFallback() = runTest {
        val transport = QueueTransport(
            mutableListOf(jobJson(requestId, "codex-cli", listOf("host-a", "host-b"))),
        )
        val repository = ControlRepository(transport, pairedCredentials())

        val job = repository.createJob(endpoint, ControlAction.CODEX_CLI, listOf("host-a", "host-b"), requestId)

        assertEquals("job-a", job.id)
        assertEquals(requestId, transport.requests.single().idempotencyKey)
        assertEquals("https://observer.example/fleetlight/control/v1/jobs", transport.requests.single().url)
        assertTrue(transport.requests.single().body.orEmpty().contains("\"targetHostIds\":[\"host-a\",\"host-b\"]"))
    }

    @Test
    fun rejectsMismatchedJobIdentity() = runTest {
        val transport = QueueTransport(mutableListOf(jobJson("00000000-0000-0000-0000-000000000002", "linux-os", listOf("host-a"))))
        val repository = ControlRepository(transport, pairedCredentials())

        assertThrows(ControlProtocolException::class.java) {
            kotlinx.coroutines.runBlocking {
                repository.createJob(endpoint, ControlAction.CODEX_CLI, listOf("host-a"), requestId)
            }
        }
    }

    @Test
    fun refusesTokenOnDifferentControlBase() = runTest {
        val credentials = pairedCredentials()
        val repository = ControlRepository(QueueTransport(mutableListOf()), credentials)

        assertThrows(ControlProtocolException::class.java) {
            kotlinx.coroutines.runBlocking { repository.status("https://observer.example/other/mobile-feed.json") }
        }
    }

    private fun pairedCredentials() = MemoryCredentials(
        ControlSession(
            authority = "observer.example",
            controlBase = "https://observer.example/fleetlight/control/v1",
            token = "token-a",
            observerId = "controller-a",
        ),
    )

    private class MemoryCredentials(var value: ControlSession? = null) : ControlCredentialStore {
        override fun read(authority: String): ControlSession? = value?.takeIf { it.authority == authority }
        override fun write(session: ControlSession) { value = session }
        override fun remove(authority: String) { if (value?.authority == authority) value = null }
    }

    private class QueueTransport(private val responses: MutableList<String>) : ControlTransport {
        val requests = mutableListOf<ControlHttpRequest>()
        override suspend fun request(request: ControlHttpRequest): String {
            requests += request
            return responses.removeAt(0)
        }
    }

    private fun statusJson(controllerId: String): String =
        """{"controllerId":"$controllerId","commandAuthorityEnabled":true,"jobJournalAvailable":true,"busy":false,"capabilities":[{"hostId":"host-a","actions":["codex-cli"],"codexCliUpdateAvailable":true}]}"""

    private fun jobJson(requestId: String, action: String, targets: List<String>): String =
        """{"id":"job-a","requestId":"$requestId","action":"$action","targetHostIds":[${targets.joinToString { "\"$it\"" }}],"state":"queued","completed":0,"total":${targets.size},"progress":[]}"""
}
