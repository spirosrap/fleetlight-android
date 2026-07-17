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
                "actions":["codex-cli","codex-mac-app"],
                "codexCliUpdateAvailable":true,
                "codexMacAppUpdateAvailable":false,
                "linuxUpdateAvailable":false
              }],
              "recentJobs":[]
            }""",
        )
        assertTrue(status.commandAuthorityEnabled)
        assertTrue(status.jobJournalAvailable)
        assertTrue(status.busy)
        assertEquals(setOf(ControlAction.CODEX_CLI, ControlAction.CODEX_MAC_APP), status.actions)
        assertTrue(status.capabilities.single().codexCliUpdateAvailable)

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
}
