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
    fun desktopAppUsesGenericCopyWhileKeepingLegacyWireCompatibility() {
        val action = ControlAction.CODEX_MAC_APP
        val mac = capability(
            actions = setOf(action),
            codexDesktopAppUpdateAvailable = true,
        )
        val linux = mac.copy(hostId = "host-linux", hostName = "Studio Linux")

        assertEquals("codex-mac-app", action.wireValue)
        assertEquals("ChatGPT Desktop App", action.title)
        assertEquals(action, ControlAction.fromWire("codex-mac-app"))
        assertEquals(action, ControlAction.fromWire("codex-desktop-app"))
        assertEquals(action, ControlAction.fromWire("CODEX_DESKTOP_APP"))
        assertTrue(mac.eligibleFor(action))
        assertTrue(linux.eligibleFor(action))
        assertFalse(linux.copy(state = "offline").eligibleFor(action))

        val copy = PendingControlAction(
            action = action,
            targetHostIds = listOf(mac.hostId, linux.hostId),
            targetHostNames = listOf(mac.hostName, linux.hostName),
        ).confirmationCopy()
        assertEquals("Update ChatGPT Desktop App?", copy.title)
        assertTrue(copy.description.contains("may close and reopen on macOS or Linux"))
    }

    @Test
    fun desktopAppUnknownAvailabilityIsNeverEligibleOrTreatedAsCurrent() {
        val unknown = capability(
            actions = setOf(ControlAction.CODEX_MAC_APP),
            hasCodexDesktopAppMetadata = true,
            codexDesktopAppUpdateAvailable = null,
        )
        val legacyCurrent = unknown.copy(
            hasCodexDesktopAppMetadata = false,
            codexDesktopAppUpdateAvailable = false,
        )

        assertFalse(unknown.updateAvailable(ControlAction.CODEX_MAC_APP))
        assertFalse(unknown.eligibleFor(ControlAction.CODEX_MAC_APP))
        assertFalse(legacyCurrent.updateAvailable(ControlAction.CODEX_MAC_APP))
        assertFalse(legacyCurrent.eligibleFor(ControlAction.CODEX_MAC_APP))

        val available = unknown.copy(codexDesktopAppState = CodexDesktopAppState.UPDATE_AVAILABLE)
        val missing = unknown.copy(
            codexDesktopAppState = CodexDesktopAppState.MISSING,
            codexDesktopAppUpdateAvailable = true,
        )
        assertTrue(available.updateAvailable(ControlAction.CODEX_MAC_APP))
        assertTrue(available.eligibleFor(ControlAction.CODEX_MAC_APP))
        assertFalse(missing.updateAvailable(ControlAction.CODEX_MAC_APP))
        assertFalse(missing.eligibleFor(ControlAction.CODEX_MAC_APP))
    }

    @Test
    fun readOnlyRecheckSupportsOfflineMachinesAndNeverUsesUpdateConfirmation() {
        val offline = capability(actions = setOf(ControlAction.REFRESH_HOSTS)).copy(state = "offline")
        val unsupported = offline.copy(actions = emptySet())

        assertTrue(offline.eligibleFor(ControlAction.REFRESH_HOSTS))
        assertFalse(unsupported.eligibleFor(ControlAction.REFRESH_HOSTS))
        assertTrue(ControlAction.REFRESH_HOSTS.isReadOnly)
        assertFalse(ControlAction.REFRESH_HOSTS.isUpdate)
        assertFalse(ControlAction.REFRESH_HOSTS.requiresExactlyOneTarget)
        assertThrows(IllegalStateException::class.java) {
            PendingControlAction(
                action = ControlAction.REFRESH_HOSTS,
                targetHostIds = listOf("host-a"),
                targetHostNames = listOf("Studio Linux"),
            ).confirmationCopy()
        }
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
        hasCodexDesktopAppMetadata: Boolean = false,
        codexDesktopAppUpdateAvailable: Boolean? = false,
        restartRequired: Boolean = false,
    ) = ControlCapability(
        hostId = "host-a",
        hostName = "Studio Linux",
        state = "online",
        actions = actions,
        codexCliUpdateAvailable = codexCliUpdateAvailable,
        hasCodexDesktopAppMetadata = hasCodexDesktopAppMetadata,
        codexDesktopAppUpdateAvailable = codexDesktopAppUpdateAvailable,
        restartRequired = restartRequired,
    )
}
