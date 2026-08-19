package app.fleetlight.mobile.ui

import app.fleetlight.mobile.data.LinuxUpdate
import app.fleetlight.mobile.data.ControlCheck
import app.fleetlight.mobile.data.ControlCheckProgress
import app.fleetlight.mobile.data.ControlCheckProgressState
import app.fleetlight.mobile.data.ControlCheckState
import app.fleetlight.mobile.data.ControlAction
import app.fleetlight.mobile.data.ControlCapability
import app.fleetlight.mobile.data.ControlStatus
import app.fleetlight.mobile.data.CodexDesktopAppState
import app.fleetlight.mobile.data.FeedObserver
import app.fleetlight.mobile.data.FleetSummary
import app.fleetlight.mobile.data.FleetHost
import app.fleetlight.mobile.data.MobileFeed
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckPresentationTest {
    private val checkedAt = Instant.parse("2026-07-17T10:00:00Z")

    @Test
    fun updateCenterCountsActionableOperationsRatherThanMachines() {
        val allAvailable = ControlCapability(
            hostId = "all-available",
            state = "online",
            actions = setOf(
                ControlAction.CODEX_CLI,
                ControlAction.CODEX_MAC_APP,
                ControlAction.LINUX_OS,
                ControlAction.RESTART_LINUX,
            ),
            codexCliUpdateAvailable = true,
            codexDesktopAppState = CodexDesktopAppState.UPDATE_AVAILABLE,
            codexDesktopAppCheckedAt = checkedAt,
            linuxUpdateAvailable = true,
            restartRequired = true,
            linuxCheckedAt = checkedAt,
        )
        val current = ControlCapability(
            hostId = "current",
            state = "online",
            actions = setOf(ControlAction.CODEX_CLI, ControlAction.CODEX_MAC_APP),
            codexDesktopAppState = CodexDesktopAppState.CURRENT,
            codexDesktopAppCheckedAt = checkedAt,
        )

        val presentation = updateCenterPresentation(
            status(listOf(allAvailable, current), codexCliCheckedAt = checkedAt),
        )

        assertEquals(3, presentation.actionableUpdateCount)
        assertEquals(1, presentation.restartRequiredCount)
        assertEquals(0, presentation.checkRequiredCount)
        assertEquals(0, presentation.offlineCount)
        assertEquals("3 updates available · 1 restart required", presentation.headline)
    }

    @Test
    fun updateCenterKeepsOfflineUnknownUnavailableAndMissingDistinct() {
        val offlineWithCachedUpdate = ControlCapability(
            hostId = "offline",
            state = "offline",
            actions = setOf(ControlAction.CODEX_CLI),
            codexCliUpdateAvailable = true,
        )
        val desktopCheckOffline = ControlCapability(
            hostId = "desktop-offline",
            state = "online",
            actions = setOf(ControlAction.CODEX_MAC_APP),
            codexDesktopAppState = CodexDesktopAppState.OFFLINE,
        )
        val unknownReachability = ControlCapability(
            hostId = "unknown",
            state = "unknown",
            actions = setOf(ControlAction.LINUX_OS),
        )
        val unavailable = ControlCapability(
            hostId = "unavailable",
            state = "online",
            actions = setOf(ControlAction.CODEX_MAC_APP),
            codexDesktopAppState = CodexDesktopAppState.UNAVAILABLE,
        )
        val missing = ControlCapability(
            hostId = "missing",
            state = "online",
            actions = setOf(ControlAction.CODEX_MAC_APP),
            codexDesktopAppState = CodexDesktopAppState.MISSING,
            codexDesktopAppCheckedAt = checkedAt,
        )

        val presentation = updateCenterPresentation(
            status(
                listOf(offlineWithCachedUpdate, desktopCheckOffline, unknownReachability, unavailable, missing),
                codexCliCheckedAt = checkedAt,
            ),
        )

        assertEquals(0, presentation.actionableUpdateCount)
        assertEquals(0, presentation.restartRequiredCount)
        assertEquals(2, presentation.checkRequiredCount)
        assertEquals(2, presentation.offlineCount)
        assertEquals(1, presentation.notInstalledCount)
        assertEquals("2 checks needed · 2 offline · 1 not installed", presentation.headline)
    }

    @Test
    fun updateCenterUsesEligibilityAndDeduplicatesControllerTargets() {
        val unreachable = ControlCapability(
            hostId = "same-host",
            state = "unreachable",
            actions = setOf(ControlAction.CODEX_CLI, ControlAction.RESTART_LINUX),
            codexCliUpdateAvailable = true,
            restartRequired = true,
        )
        val duplicate = unreachable.copy(hostName = "Duplicate")
        val inconsistentWithoutAction = ControlCapability(
            hostId = "no-action",
            state = "online",
            codexCliUpdateAvailable = true,
            restartRequired = true,
        )

        val presentation = updateCenterPresentation(
            status(
                listOf(unreachable, duplicate, inconsistentWithoutAction),
                codexCliCheckedAt = checkedAt,
            ),
        )

        assertEquals(0, presentation.actionableUpdateCount)
        assertEquals(0, presentation.restartRequiredCount)
        assertEquals(1, presentation.offlineCount)
        assertEquals("1 offline", presentation.headline)
    }

    @Test
    fun updateCenterExplainsEmptyUnpairedAndCurrentStates() {
        val unpaired = updateCenterPresentation(null)
        val noTargets = updateCenterPresentation(status(emptyList()))
        val current = updateCenterPresentation(
            status(
                listOf(
                    ControlCapability(
                        hostId = "current",
                        state = "online",
                        actions = setOf(ControlAction.CODEX_CLI, ControlAction.CODEX_MAC_APP),
                        codexDesktopAppState = CodexDesktopAppState.CURRENT,
                        codexDesktopAppCheckedAt = checkedAt,
                    ),
                ),
                codexCliCheckedAt = checkedAt,
            ),
        )

        assertEquals("Pair to load update status", unpaired.headline)
        assertFalse(unpaired.hasManagedTargets)
        assertEquals("No managed update targets", noTargets.headline)
        assertEquals("All checked · no updates", current.headline)
    }

    @Test
    fun updateCenterNeverTreatsMissingOrFailedCliFreshnessAsCurrent() {
        val cli = ControlCapability(
            hostId = "cli",
            state = "online",
            actions = setOf(ControlAction.CODEX_CLI),
            codexCliUpdateAvailable = false,
        )

        val neverChecked = updateCenterPresentation(status(listOf(cli)))
        val failedCachedCheck = updateCenterPresentation(
            status(listOf(cli), codexCliCheckedAt = checkedAt, codexCliCheckFailed = true),
        )
        val verified = updateCenterPresentation(status(listOf(cli), codexCliCheckedAt = checkedAt))

        assertEquals("1 check needed", neverChecked.headline)
        assertEquals("1 check needed", failedCachedCheck.headline)
        assertEquals("All checked · no updates", verified.headline)
    }

    @Test
    fun updateCenterRequiresPerMachineLinuxAndDesktopEvidence() {
        val linuxWithoutTimestamp = ControlCapability(
            hostId = "linux",
            state = "online",
            actions = setOf(ControlAction.LINUX_OS, ControlAction.RESTART_LINUX),
        )
        val desktopWithoutTimestamp = ControlCapability(
            hostId = "desktop",
            state = "online",
            actions = setOf(ControlAction.CODEX_MAC_APP),
            codexDesktopAppState = CodexDesktopAppState.CURRENT,
        )
        val legacyDesktop = ControlCapability(
            hostId = "legacy",
            state = "online",
            actions = setOf(ControlAction.CODEX_MAC_APP),
            codexDesktopAppUpdateAvailable = false,
        )

        val incomplete = updateCenterPresentation(
            status(listOf(linuxWithoutTimestamp, desktopWithoutTimestamp, legacyDesktop)),
        )
        val verified = updateCenterPresentation(
            status(
                listOf(
                    linuxWithoutTimestamp.copy(linuxCheckedAt = checkedAt),
                    desktopWithoutTimestamp.copy(codexDesktopAppCheckedAt = checkedAt),
                ),
            ),
        )

        assertEquals(3, incomplete.checkRequiredCount)
        assertEquals("3 checks needed", incomplete.headline)
        assertEquals("All checked · no updates", verified.headline)
    }

    @Test
    fun updateCenterKeepsOfflineExclusiveEvenWhenFreshnessIsMissing() {
        val offline = ControlCapability(
            hostId = "offline",
            state = "offline",
            actions = setOf(
                ControlAction.CODEX_CLI,
                ControlAction.CODEX_MAC_APP,
                ControlAction.LINUX_OS,
                ControlAction.RESTART_LINUX,
            ),
        )

        val presentation = updateCenterPresentation(status(listOf(offline)))

        assertEquals(0, presentation.checkRequiredCount)
        assertEquals(1, presentation.offlineCount)
        assertEquals("1 offline", presentation.headline)
    }

    @Test
    fun desktopAvailabilityPresentationKeepsUnknownDistinctFromCurrentAndNotInstalled() {
        val action = ControlAction.CODEX_MAC_APP
        val unknown = ControlCapability(
            hostId = "unknown",
            state = "online",
            actions = setOf(action),
            hasCodexDesktopAppMetadata = true,
            codexDesktopAppPlatform = "Linux",
            codexDesktopAppProvider = "linux-apt",
            codexDesktopAppUpdateAvailable = null,
        )
        val available = unknown.copy(
            hostId = "available",
            codexDesktopAppVersion = "1.0",
            codexDesktopAppAvailableVersion = "1.1",
            codexDesktopAppState = CodexDesktopAppState.UPDATE_AVAILABLE,
        )
        val notInstalled = unknown.copy(
            hostId = "not-installed",
            codexDesktopAppState = CodexDesktopAppState.MISSING,
        )
        val offline = unknown.copy(hostId = "offline", codexDesktopAppState = CodexDesktopAppState.OFFLINE)
        val unavailable = unknown.copy(
            hostId = "unavailable",
            codexDesktopAppState = CodexDesktopAppState.UNAVAILABLE,
        )
        val legacyCurrent = unknown.copy(
            hostId = "legacy-current",
            hasCodexDesktopAppMetadata = false,
            codexDesktopAppPlatform = null,
            codexDesktopAppProvider = null,
            codexDesktopAppUpdateAvailable = false,
        )
        val supported = listOf(unknown, available, notInstalled, offline, unavailable, legacyCurrent)

        assertEquals(
            "1 update available · 1 check required · 1 not installed · 1 offline · 1 unavailable",
            updateAvailabilitySummary(action, supported, supported, listOf(available)),
        )
        assertEquals("Update known", updateAllButtonLabel(action, supported, listOf(available)))
        assertEquals("Check required", unknown.updateButtonLabel(action, null))
        assertEquals("Check required", unknown.controllerUpdateReport(action))
        assertEquals("Install", available.updateButtonLabel(action, null))
        assertEquals(
            "Available 1.1",
            available.controllerUpdateReport(action, installedVersion = "Installed 1.0", availableVersion = "1.1"),
        )
        assertEquals("Not installed", notInstalled.updateButtonLabel(action, null))
        assertEquals("Not installed", notInstalled.controllerUpdateReport(action))
        assertEquals("Offline", offline.updateButtonLabel(action, null))
        assertEquals("Desktop app check offline", offline.controllerUpdateReport(action))
        assertEquals("Unavailable", unavailable.updateButtonLabel(action, null))
        assertEquals("Desktop app unavailable", unavailable.controllerUpdateReport(action))
        assertEquals("Current", legacyCurrent.updateButtonLabel(action, "Installed 1.0"))
        assertEquals("Current", legacyCurrent.updateButtonLabel(action, null))
        assertEquals("Controller reports current", legacyCurrent.controllerUpdateReport(action, "Installed 1.0"))
    }

    @Test
    fun desktopPlatformCopyUsesReportedProviderPlatformThenHostFallback() {
        val explicit = FleetHost(
            id = "linux-a",
            name = "Linux A",
            platform = "GNU/Linux",
            codexDesktopAppPlatform = "Linux",
            codexDesktopAppProvider = "linux-apt",
        )
        val macFallback = FleetHost(
            id = "mac-a",
            name = "Mac A",
            platform = "Darwin",
            codexDesktopAppProvider = "macos-appcast",
        )

        assertEquals("Linux", explicit.desktopAppPlatformLabel)
        assertEquals("OpenAI APT repository", explicit.desktopAppProviderLabel)
        assertEquals("macOS", macFallback.desktopAppPlatformLabel)
        assertEquals("Signed macOS appcast", macFallback.desktopAppProviderLabel)
    }

    @Test
    fun desktopFreshnessPrefersCapabilityTimestampAndFallsBackToFeed() {
        val action = ControlAction.CODEX_MAC_APP
        val host = FleetHost(
            id = "linux-a",
            name = "Linux A",
            codexDesktopAppCheckedAt = Instant.parse("2026-07-17T09:00:00Z"),
        )
        val capability = ControlCapability(
            hostId = host.id,
            codexDesktopAppCheckedAt = Instant.parse("2026-07-17T10:00:00Z"),
        )
        val now = Instant.parse("2026-07-17T10:05:00Z")

        assertEquals("Checked 5m ago", desktopAppFreshnessLabel(action, capability, host, now))
        assertEquals(
            "Checked 1h ago",
            desktopAppFreshnessLabel(action, capability.copy(codexDesktopAppCheckedAt = null), host, now),
        )
        assertNull(desktopAppFreshnessLabel(action, capability.copy(codexDesktopAppCheckedAt = null), null, now))
        assertNull(desktopAppFreshnessLabel(ControlAction.CODEX_CLI, capability, host, now))
    }

    @Test
    fun liveAuditShowsDeterminateStageProgressAndCurrentItem() {
        val presentation = checkProgressPresentation(
            ControlCheck(
                id = "check-a",
                requestId = "00000000-0000-0000-0000-000000000001",
                state = ControlCheckState.RUNNING,
                phase = "linux",
                detail = "Checking Linux packages",
                completed = 1,
                total = 3,
                progress = listOf(
                    ControlCheckProgress("fleet", "Installed versions", "fleet", ControlCheckProgressState.SUCCEEDED, "Checked"),
                    ControlCheckProgress("linux", "Linux packages", "linux", ControlCheckProgressState.RUNNING, "Checking six machines"),
                    ControlCheckProgress("publishing", "Publish results", "publishing", ControlCheckProgressState.QUEUED, "Waiting"),
                ),
            ),
        )

        assertEquals(1f / 3f, presentation.fraction)
        assertEquals("1 of 3 stages complete", presentation.countLabel)
        assertEquals("Linux packages · Running", presentation.currentLabel)
        assertEquals("Checking six machines", presentation.currentDetail)
    }

    @Test
    fun legacyLiveAuditFallsBackToIndeterminateProgress() {
        val presentation = checkProgressPresentation(
            ControlCheck(
                id = "check-a",
                requestId = "00000000-0000-0000-0000-000000000001",
                state = ControlCheckState.RUNNING,
                phase = "linux",
                detail = "Checking",
            ),
        )

        assertNull(presentation.fraction)
        assertNull(presentation.countLabel)
        assertNull(presentation.currentLabel)
    }

    @Test
    fun updatesKeepControllerFeedWhenMainObserverBecomesNewer() {
        val controller = feed("Controller", "2026-07-17T10:00:00Z")
        val newerMain = feed("Newer observer", "2026-07-17T10:05:00Z")
        val state = FleetUiState(feed = newerMain, controllerFeed = controller)

        assertEquals("Controller", state.updatesFeed?.observer?.name)
        assertEquals("Newer observer", state.feed?.observer?.name)
    }

    @Test
    fun liveFeedKeepsControllerFailureScopedAndNeutral() {
        val state = FleetUiState(
            feed = feed("Healthy observer", "2026-07-17T10:05:00Z"),
            connection = FeedConnection.LIVE,
            controlEndpoint = "https://controller.example/fleetlight/mobile-feed.json",
            controllerAvailabilityError = "HTTP 502",
        )

        assertNull(state.controlError)
        assertEquals(
            "Fleet status is live. The paired update controller is temporarily unavailable.",
            controllerAvailabilityNotice(state),
        )
        assertEquals(
            "Fleet status is live. The paired update controller is temporarily unavailable. Technical detail: HTTP 502",
            controllerAvailabilityNotice(state, includeDetail = true),
        )
    }

    @Test
    fun controllerNoticeRequiresAPairedUnavailableController() {
        val unpaired = FleetUiState(
            connection = FeedConnection.LIVE,
            controllerAvailabilityError = "HTTP 502",
        )
        val checking = unpaired.copy(
            controlEndpoint = "https://controller.example/fleetlight/mobile-feed.json",
            controlChecking = true,
        )

        assertNull(controllerAvailabilityNotice(unpaired))
        assertNull(controllerAvailabilityNotice(checking))
    }

    @Test
    fun failedReleaseChecksLabelCachedValuesAsLastKnown() {
        assertEquals("Latest 0.144.5", releaseVersionLabel("0.144.5", failed = false))
        assertEquals("Last known 0.144.5", releaseVersionLabel("0.144.5", failed = true))
        assertEquals(
            "Last known 26.715.21425 · build 5488",
            releaseVersionLabel("26.715.21425", "5488", failed = true),
        )
        assertEquals("Last known build 5488", releaseVersionLabel(null, "5488", failed = true))
        assertNull(releaseVersionLabel(null, null, failed = false))
    }

    @Test
    fun linuxSummaryDoesNotHideOfflineFailedOrMissingTimestamps() {
        val recent = Instant.parse("2026-07-17T10:05:00Z")
        val older = Instant.parse("2026-07-17T10:00:00Z")
        val complete = linuxCheckPresentation(
            listOf(
                LinuxUpdate("a", "A", state = "current", checkedAt = recent),
                LinuxUpdate("b", "B", state = "updateAvailable", checkedAt = older),
            ),
        )
        assertEquals("2 of 2 machines checked", complete.countLabel)
        assertFalse(complete.incomplete)
        assertEquals(older, complete.oldestCheckedAt)

        val incomplete = linuxCheckPresentation(
            listOf(
                LinuxUpdate("a", "A", state = "current", checkedAt = recent),
                LinuxUpdate("b", "B", state = "offline", checkedAt = older),
                LinuxUpdate("c", "C", state = "failed", checkedAt = recent),
                LinuxUpdate("d", "D", state = "current", checkedAt = null),
                LinuxUpdate("e", "E", state = "notChecked", checkedAt = null),
            ),
        )
        assertEquals("1 of 5 machines checked", incomplete.countLabel)
        assertTrue(incomplete.incomplete)
        assertNull(incomplete.oldestCheckedAt)
    }

    private fun feed(observer: String, timestamp: String) = MobileFeed(
        schemaVersion = 1,
        generatedAt = Instant.parse(timestamp),
        observer = FeedObserver(name = observer),
        summary = FleetSummary(),
        hosts = emptyList(),
        linuxUpdates = emptyList(),
        incidents = emptyList(),
        metrics = emptyList(),
    )

    private fun status(
        capabilities: List<ControlCapability>,
        codexCliCheckedAt: Instant? = null,
        codexCliCheckFailed: Boolean = false,
    ) = ControlStatus(
        observerId = "controller-a",
        commandAuthorityEnabled = true,
        jobJournalAvailable = true,
        capabilities = capabilities,
        codexCliCheckedAt = codexCliCheckedAt,
        codexCliCheckFailed = codexCliCheckFailed,
    )
}
