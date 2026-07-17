package app.fleetlight.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlParserTest {
    private val parser = ControlParser()

    @Test
    fun parsesPairStatusAndProgressContract() {
        val pair = parser.pair("""{"schemaVersion":1,"token":"scoped-token","controllerId":"controller-a"}""")
        assertEquals("controller-a", pair.observerId)

        val status = parser.status(
            """{
              "schemaVersion":1,
              "controllerId":"controller-a",
              "controllerName":"Primary controller",
              "commandAuthorityEnabled":true,
              "jobJournalAvailable":true,
              "busy":true,
              "activeJobId":"job-a",
              "capabilities":[{
                "hostId":"host-a","hostName":"Workstation","state":"online",
                "actions":["codex-cli","codex-mac-app","restart-linux"],
                "codexCliUpdateAvailable":true,
                "codexMacAppUpdateAvailable":false,
                "linuxUpdateAvailable":false,
                "restartRequired":true
              }],
              "recentJobs":[]
            }""",
        )
        assertTrue(status.commandAuthorityEnabled)
        assertTrue(status.jobJournalAvailable)
        assertTrue(status.busy)
        assertEquals(setOf(ControlAction.CODEX_CLI, ControlAction.CODEX_MAC_APP, ControlAction.RESTART_LINUX), status.actions)
        assertTrue(status.capabilities.single().codexCliUpdateAvailable)
        assertTrue(status.capabilities.single().restartRequired)

        val job = parser.job(
            """{
              "id":"job-a","requestId":"00000000-0000-0000-0000-000000000001",
              "action":"codex-cli","targetHostIds":["host-a"],"state":"partial",
              "completed":1,"total":1,
              "progress":[{"hostId":"host-a","phase":"failed","detail":"Verification failed"}]
            }""",
            ControlAction.CODEX_CLI,
        )
        assertEquals(ControlJobState.PARTIAL, job.state)
        assertEquals(ControlTargetState.FAILED, job.targets.single().state)
        assertTrue(job.state.isTerminal)
    }

    @Test
    fun statusFailsClosedWithoutControllerOrCapabilities() {
        assertThrows(ControlProtocolException::class.java) {
            parser.status("""{"commandAuthorityEnabled":true,"capabilities":[]}""")
        }
        assertThrows(ControlProtocolException::class.java) {
            parser.status("""{"controllerId":"controller-a","commandAuthorityEnabled":true,"capabilities":[]}""")
        }
        val disabled = parser.status("""{"controllerId":"controller-a","commandAuthorityEnabled":false,"capabilities":[]}""")
        assertFalse(disabled.commandAuthorityEnabled)
        assertTrue(disabled.actions.isEmpty())
    }

    @Test
    fun parsesNestedErrorMessage() {
        assertEquals("Controller busy", parser.errorMessage("""{"error":{"code":"busy","message":"Controller busy"}}"""))
    }

    @Test
    fun mapsRestartWaitPhases() {
        listOf(
            "issuing" to ControlTargetState.ISSUING,
            "waitingForOffline" to ControlTargetState.WAITING_FOR_OFFLINE,
            "waitingForOnline" to ControlTargetState.WAITING_FOR_ONLINE,
            "verifying" to ControlTargetState.VERIFYING,
        ).forEach { (phase, expected) ->
            val job = parser.job(
                """{"id":"job-a","action":"restart-linux","targetHostIds":["host-a"],"state":"running","progress":[{"hostId":"host-a","phase":"$phase"}]}""",
                ControlAction.RESTART_LINUX,
            )
            assertEquals(expected, job.targets.single().state)
        }
    }

    @Test
    fun rejectsAvailableOperationsWithoutMatchingSupportAction() {
        listOf(
            "\"codexCliUpdateAvailable\":true" to "Codex CLI",
            "\"codexMacAppUpdateAvailable\":true" to "Codex Mac app",
            "\"linuxUpdateAvailable\":true" to "Linux OS",
            "\"restartRequired\":true" to "Linux restart",
        ).forEach { (field, label) ->
            val error = assertThrows(ControlProtocolException::class.java) {
                parser.status(
                    """{"controllerId":"controller-a","commandAuthorityEnabled":true,"capabilities":[{"hostId":"host-a","actions":[],${field}}]}""",
                )
            }
            assertTrue(error.message.orEmpty().contains(label))
        }
    }

    @Test
    fun enrichesRecentProgressWithFriendlyCapabilityName() {
        val status = parser.status(
            """{
              "controllerId":"controller-a","commandAuthorityEnabled":true,
              "capabilities":[{"hostId":"host-a","hostName":"Studio Linux","actions":["restart-linux"],"restartRequired":true}],
              "recentJobs":[{
                "id":"job-a","requestId":"00000000-0000-0000-0000-000000000001",
                "action":"restart-linux","targetHostIds":["host-a"],"state":"running",
                "progress":[{"hostId":"host-a","phase":"waitingForOnline"}]
              }]
            }""",
        )

        assertEquals("Studio Linux", status.recentJobs.single().targets.single().hostName)
    }
}
