package app.fleetlight.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

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

    @Test
    fun parsesOptionalLiveCheckMetadataWithoutBreakingLegacyStatus() {
        val status = parser.status(
            """{
              "controllerId":"controller-a","commandAuthorityEnabled":true,
              "checkingUpdates":true,"activeCheckId":"check-a",
              "latestCodexCliVersion":"0.144.5","codexCliCheckedAt":"2026-07-17T10:00:00Z",
              "codexCliCheckFailed":false,
              "latestCodexMacAppVersion":"26.715.21425","latestCodexMacAppBuild":"5488",
              "codexMacAppCheckedAt":"2026-07-17T10:01:00Z","codexMacAppCheckFailed":true,
              "capabilities":[{
                "hostId":"host-a","actions":["linux-os"],
                "linuxCheckedAt":"2026-07-17T10:02:00Z"
              }]
            }""",
        )

        assertTrue(status.checkingUpdates)
        assertEquals("check-a", status.activeCheckId)
        assertEquals("0.144.5", status.latestCodexCliVersion)
        assertEquals(Instant.parse("2026-07-17T10:00:00Z"), status.codexCliCheckedAt)
        assertEquals("26.715.21425", status.latestCodexMacAppVersion)
        assertEquals("5488", status.latestCodexMacAppBuild)
        assertTrue(status.codexMacAppCheckFailed)
        assertEquals(Instant.parse("2026-07-17T10:02:00Z"), status.capabilities.single().linuxCheckedAt)

        val legacy = parser.status(
            """{"controllerId":"controller-a","commandAuthorityEnabled":true,"capabilities":[{"hostId":"host-a","actions":[]}]}""",
        )
        assertFalse(legacy.checkingUpdates)
        assertEquals(null, legacy.latestCodexCliVersion)
        assertEquals(null, legacy.capabilities.single().linuxCheckedAt)
    }

    @Test
    fun parsesCheckEnvelopeAndRejectsMalformedTerminalData() {
        val check = parser.check(
            """{"check":{"id":"check-a","requestId":"00000000-0000-0000-0000-000000000001","state":"partial","phase":"linux","detail":"One host offline","startedAt":"2026-07-17T10:00:00Z","finishedAt":"2026-07-17T10:01:00Z","completed":2,"total":3,"progress":[{"id":"fleet","name":"Installed versions","category":"fleet","state":"succeeded","detail":"Versions checked"},{"id":"linux","name":"Linux packages","category":"linux","state":"partial","detail":"One host offline"},{"id":"publishing","name":"Publish results","category":"publishing","state":"queued","detail":"Waiting"}]}}""",
        )
        assertEquals(ControlCheckState.PARTIAL, check.state)
        assertEquals("linux", check.phase)
        assertEquals(2, check.completed)
        assertEquals(3, check.total)
        assertEquals(listOf("fleet", "linux", "publishing"), check.progress.map { it.id })
        assertEquals(ControlCheckProgressState.PARTIAL, check.progress[1].state)
        assertTrue(check.state.isTerminal)

        assertThrows(ControlProtocolException::class.java) {
            parser.check("""{"id":"check-a","requestId":"00000000-0000-0000-0000-000000000001","state":"mystery","phase":"complete","detail":"Done"}""")
        }
        assertThrows(ControlProtocolException::class.java) {
            parser.check("""{"id":"check-a","requestId":"00000000-0000-0000-0000-000000000001","state":"succeeded","phase":"complete","detail":"Done","finishedAt":"not-a-time"}""")
        }
        assertThrows(ControlProtocolException::class.java) {
            parser.check("""{"id":"check-a","requestId":"00000000-0000-0000-0000-000000000001","state":"running","phase":"linux"}""")
        }
    }

    @Test
    fun checkProgressIsOptionalBoundedClampedAndDropsMalformedItems() {
        val legacy = parser.check(
            """{"id":"check-a","requestId":"00000000-0000-0000-0000-000000000001","state":"running","phase":"fleet","detail":"Checking"}""",
        )
        assertEquals(null, legacy.completed)
        assertEquals(null, legacy.total)
        assertTrue(legacy.progress.isEmpty())

        val entries = (0 until 40).joinToString(",") { index ->
            """{"id":"stage-$index","name":"Stage $index","category":"fleet","state":"running","detail":"Checking $index"}"""
        }
        val bounded = parser.check(
            """{"id":"check-a","requestId":"00000000-0000-0000-0000-000000000001","state":"running","phase":"fleet","detail":"Checking","completed":999,"total":999,"progress":[$entries,{"id":"broken","name":"Broken","category":"fleet","state":"mystery","detail":"No"}]}""",
        )

        assertEquals(64, bounded.total)
        assertEquals(64, bounded.completed)
        assertEquals(32, bounded.progress.size)
        assertEquals("stage-0", bounded.progress.first().id)
        assertEquals("stage-31", bounded.progress.last().id)
    }
}
