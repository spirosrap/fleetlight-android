package app.fleetlight.mobile.ui

import app.fleetlight.mobile.data.LinuxUpdate
import app.fleetlight.mobile.data.ControlCheck
import app.fleetlight.mobile.data.ControlCheckProgress
import app.fleetlight.mobile.data.ControlCheckProgressState
import app.fleetlight.mobile.data.ControlCheckState
import app.fleetlight.mobile.data.ControlAction
import app.fleetlight.mobile.data.ControlCapability
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
}
