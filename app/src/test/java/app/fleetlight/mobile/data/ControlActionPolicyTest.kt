package app.fleetlight.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlActionPolicyTest {
    @Test
    fun restartIsEligibleOnlyWhenSupportedAndRequired() {
        val ready = capability(actions = setOf(ControlAction.RESTART_LINUX), restartRequired = true)
        val current = ready.copy(restartRequired = false)
        val unsupported = ready.copy(actions = setOf(ControlAction.LINUX_OS))

        assertTrue(ready.eligibleFor(ControlAction.RESTART_LINUX))
        assertFalse(current.eligibleFor(ControlAction.RESTART_LINUX))
        assertFalse(unsupported.eligibleFor(ControlAction.RESTART_LINUX))
        assertFalse(ready.copy(state = "offline").eligibleFor(ControlAction.RESTART_LINUX))
    }

    @Test
    fun restartConfirmationNamesSingleMachineAndExplainsReturnWait() {
        val pending = PendingControlAction(
            action = ControlAction.RESTART_LINUX,
            targetHostIds = listOf("host-a"),
            targetHostNames = listOf("Studio Linux"),
        )

        val copy = pending.confirmationCopy()

        assertEquals("Restart Studio Linux?", copy.title)
        assertEquals("Restart Studio Linux", copy.confirmLabel)
        assertTrue(copy.description.contains("Active work and services on Studio Linux will be interrupted"))
        assertTrue(copy.description.contains("wait for the machine to go offline and return"))
    }

    @Test
    fun restartConfirmationRejectsMultipleMachines() {
        val pending = PendingControlAction(
            action = ControlAction.RESTART_LINUX,
            targetHostIds = listOf("host-a", "host-b"),
            targetHostNames = listOf("Studio Linux", "Media Linux"),
        )

        assertThrows(IllegalArgumentException::class.java) { pending.confirmationCopy() }
    }

    @Test
    fun currentMachineRemainsSupportedWithoutAnAvailableUpdate() {
        val current = capability(
            actions = setOf(ControlAction.CODEX_CLI),
            codexCliUpdateAvailable = false,
        )

        assertTrue(ControlAction.CODEX_CLI in current.actions)
        assertFalse(current.updateAvailable(ControlAction.CODEX_CLI))
        assertFalse(current.eligibleFor(ControlAction.CODEX_CLI))
        assertFalse(
            current.copy(state = "offline", codexCliUpdateAvailable = true)
                .eligibleFor(ControlAction.CODEX_CLI),
        )
    }

    @Test
    fun progressNamesNeverExposeInternalHostIds() {
        val raw = ControlJob(
            id = "job-a",
            action = ControlAction.RESTART_LINUX,
            state = ControlJobState.RUNNING,
            targetHostIds = listOf("internal-host-a", "internal-host-b"),
            targets = listOf(
                ControlJobTarget("internal-host-a", hostName = "internal-host-a"),
                ControlJobTarget("internal-host-b", hostName = "internal-host-b"),
            ),
        )
        val named = raw.withCapabilityNames(
            listOf(capability(actions = setOf(ControlAction.RESTART_LINUX)).copy(hostId = "internal-host-a")),
        )

        assertEquals("Studio Linux", named.targets[0].hostName)
        assertEquals("Machine", named.targets[1].hostName)
        assertFalse(named.targets.any { it.hostName.startsWith("internal-") })
    }

    private fun capability(
        actions: Set<ControlAction>,
        codexCliUpdateAvailable: Boolean = false,
        restartRequired: Boolean = false,
    ) = ControlCapability(
        hostId = "host-a",
        hostName = "Studio Linux",
        state = "online",
        actions = actions,
        codexCliUpdateAvailable = codexCliUpdateAvailable,
        restartRequired = restartRequired,
    )
}
