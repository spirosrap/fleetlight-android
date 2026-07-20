package app.fleetlight.mobile.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlRepositoryTest {
    private val endpoint = "https://observer.example/fleetlight/mobile-feed.json"
    private val requestId = "00000000-0000-0000-0000-000000000001"
    private val checkId = "10000000-0000-0000-0000-000000000001"

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
    fun acceptsEquivalentUuidCaseForCreatedAndRecoveredJobs() = runTest {
        val lowerRequest = "abcdefab-cdef-4abc-8def-abcdefabcdef"
        val lowerJob = "12345678-9abc-4def-8123-456789abcdef"
        val response = jobJson(
            requestId = lowerRequest.uppercase(),
            action = "codex-mac-app",
            targets = listOf("host-a"),
            id = lowerJob.uppercase(),
        )
        val transport = QueueTransport(mutableListOf(response, response, response))
        val repository = ControlRepository(transport, pairedCredentials())

        repository.createJob(endpoint, ControlAction.CODEX_MAC_APP, listOf("host-a"), lowerRequest)
        repository.job(
            endpoint,
            lowerJob,
            ControlAction.CODEX_MAC_APP,
            lowerRequest,
            listOf("host-a"),
        )
        repository.jobByRequest(
            endpoint,
            lowerRequest,
            ControlAction.CODEX_MAC_APP,
            listOf("host-a"),
        )

        assertEquals("https://observer.example/fleetlight/control/v1/jobs/$lowerJob", transport.requests[1].url)
        assertEquals(
            "https://observer.example/fleetlight/control/v1/jobs/by-request/$lowerRequest",
            transport.requests[2].url,
        )
    }

    @Test
    fun refusesTokenOnDifferentControlBase() = runTest {
        val credentials = pairedCredentials()
        val repository = ControlRepository(QueueTransport(mutableListOf()), credentials)

        assertThrows(ControlProtocolException::class.java) {
            kotlinx.coroutines.runBlocking { repository.status("https://observer.example/other/mobile-feed.json") }
        }
    }

    @Test
    fun restartUsesDedicatedActionAndExactlyOneTarget() = runTest {
        val transport = QueueTransport(
            mutableListOf(jobJson(requestId, "restart-linux", listOf("host-a"))),
        )
        val repository = ControlRepository(transport, pairedCredentials())

        repository.createJob(endpoint, ControlAction.RESTART_LINUX, listOf("host-a"), requestId)

        val request = transport.requests.single()
        assertTrue(request.body.orEmpty().contains("\"action\":\"restart-linux\""))
        assertTrue(request.body.orEmpty().contains("\"targetHostIds\":[\"host-a\"]"))
        assertEquals(requestId, request.idempotencyKey)
    }

    @Test
    fun refusesRestartWithMoreThanOneTargetBeforeNetwork() = runTest {
        val transport = QueueTransport(mutableListOf())
        val repository = ControlRepository(transport, pairedCredentials())

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                repository.createJob(
                    endpoint,
                    ControlAction.RESTART_LINUX,
                    listOf("host-a", "host-b"),
                    requestId,
                )
            }
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun startsAndPollsAuthenticatedIdempotentUpdateCheck() = runTest {
        val transport = QueueTransport(
            mutableListOf(
                checkJson(requestId, "queued"),
                checkJson(requestId, "succeeded"),
            ),
        )
        val repository = ControlRepository(transport, pairedCredentials())

        val accepted = repository.createCheck(endpoint, requestId)
        val completed = repository.check(endpoint, accepted.id, requestId)

        assertEquals(ControlCheckState.QUEUED, accepted.state)
        assertEquals(ControlCheckState.SUCCEEDED, completed.state)
        assertEquals("POST", transport.requests[0].method)
        assertEquals("https://observer.example/fleetlight/control/v1/checks", transport.requests[0].url)
        assertEquals(requestId, transport.requests[0].idempotencyKey)
        assertTrue(transport.requests[0].body.orEmpty().contains("\"requestId\":\"$requestId\""))
        assertEquals("token-a", transport.requests[0].token)
        assertEquals("GET", transport.requests[1].method)
        assertEquals("https://observer.example/fleetlight/control/v1/checks/$checkId", transport.requests[1].url)
    }

    @Test
    fun rejectsMismatchedCheckIdAndRequestIdentity() = runTest {
        val wrongRequest = "00000000-0000-0000-0000-000000000002"
        val repository = ControlRepository(
            QueueTransport(mutableListOf(checkJson(wrongRequest, "queued"))),
            pairedCredentials(),
        )
        assertThrows(ControlProtocolException::class.java) {
            kotlinx.coroutines.runBlocking { repository.createCheck(endpoint, requestId) }
        }

        val wrongIdRepository = ControlRepository(
            QueueTransport(mutableListOf(checkJson(requestId, "running", id = "10000000-0000-0000-0000-000000000002"))),
            pairedCredentials(),
        )
        assertThrows(ControlProtocolException::class.java) {
            kotlinx.coroutines.runBlocking { wrongIdRepository.check(endpoint, checkId, requestId) }
        }
    }

    @Test
    fun acceptsEquivalentUuidCaseFromSwiftController() = runTest {
        val lowerRequest = "abcdefab-cdef-4abc-8def-abcdefabcdef"
        val upperCheck = "ABCDEFAB-CDEF-4ABC-8DEF-ABCDEFABCDE0"
        val transport = QueueTransport(
            mutableListOf(checkJson(lowerRequest.uppercase(), "queued", id = upperCheck)),
        )
        val repository = ControlRepository(transport, pairedCredentials())

        val accepted = repository.createCheck(endpoint, lowerRequest)

        assertEquals(upperCheck, accepted.id)
        assertEquals(lowerRequest.uppercase(), accepted.requestId)
    }

    @Test
    fun rejectsNonCanonicalUuidBeforeNetwork() = runTest {
        val transport = QueueTransport(mutableListOf())
        val repository = ControlRepository(transport, pairedCredentials())

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repository.createCheck(endpoint, "1-1-1-1-1") }
        }
        assertTrue(transport.requests.isEmpty())
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

    private fun jobJson(
        requestId: String,
        action: String,
        targets: List<String>,
        id: String = "job-a",
    ): String =
        """{"id":"$id","requestId":"$requestId","action":"$action","targetHostIds":[${targets.joinToString { "\"$it\"" }}],"state":"queued","completed":0,"total":${targets.size},"progress":[]}"""

    private fun checkJson(requestId: String, state: String, id: String = checkId): String =
        """{"id":"$id","requestId":"$requestId","state":"$state","phase":"versions","detail":"Checking versions"}"""
}
