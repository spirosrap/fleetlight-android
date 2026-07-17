package app.fleetlight.mobile.ui

import app.fleetlight.mobile.data.LinuxUpdate
import app.fleetlight.mobile.data.FeedObserver
import app.fleetlight.mobile.data.FleetSummary
import app.fleetlight.mobile.data.MobileFeed
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckPresentationTest {
    @Test
    fun updatesKeepControllerFeedWhenMainObserverBecomesNewer() {
        val controller = feed("Controller", "2026-07-17T10:00:00Z")
        val newerMain = feed("Newer observer", "2026-07-17T10:05:00Z")
        val state = FleetUiState(feed = newerMain, controllerFeed = controller)

        assertEquals("Controller", state.updatesFeed?.observer?.name)
        assertEquals("Newer observer", state.feed?.observer?.name)
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
