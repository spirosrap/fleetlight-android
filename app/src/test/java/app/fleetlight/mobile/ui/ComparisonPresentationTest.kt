package app.fleetlight.mobile.ui

import app.fleetlight.mobile.data.FleetHost
import app.fleetlight.mobile.data.HostState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComparisonPresentationTest {
    @Test
    fun ranksFastestFirstAndExcludesTheObserverTiming() {
        val hosts = listOf(
            host("slow", "Zulu", HostState.ONLINE, ping = 80.0, ready = 100.0, probe = 180.0),
            host("observer", "This Mac", HostState.ONLINE, ping = 1.0, ready = 2.0, probe = 3.0),
            host("fast-b", "Beta", HostState.ONLINE, ping = 20.0),
            host("fast-a", "Alpha", HostState.ONLINE, ping = 20.0, pinned = true),
        )

        val ranks = fleetComparisonRanks(hosts, "observer", FleetComparisonMetric.PING)

        assertEquals(listOf("fast-a", "fast-b", "slow", "observer"), ranks.map { it.host.id })
        assertEquals(listOf(20.0, 20.0, 80.0, null), ranks.map { it.valueMilliseconds })
        assertTrue(ranks.last().isObserver)
    }

    @Test
    fun keepsConnectedWarningsAndRejectsStaleUnavailableMeasurements() {
        val hosts = listOf(
            host("slow", "Slow", HostState.SLOW, ping = 50.0),
            host("attention", "Attention", HostState.ATTENTION, ping = 40.0),
            host("offline", "Offline", HostState.OFFLINE, ping = 1.0),
            host("access", "Access", HostState.ACCESS, ping = 2.0),
            host("unknown", "Unknown", HostState.UNKNOWN, ping = 3.0),
        )

        val ranks = fleetComparisonRanks(hosts, "observer", FleetComparisonMetric.PING)

        assertEquals(listOf("attention", "slow", "access", "offline", "unknown"), ranks.map { it.host.id })
        assertEquals(listOf(40.0, 50.0, null, null, null), ranks.map { it.valueMilliseconds })
    }

    @Test
    fun derivesChecksOnlyFromValidCompleteTimings() {
        assertEquals(
            75.0,
            FleetComparisonMetric.CHECKS.value(host("valid", "Valid", ready = 125.0, probe = 200.0)) ?: -1.0,
            0.0,
        )
        assertNull(FleetComparisonMetric.CHECKS.value(host("missing", "Missing", ready = 125.0)))
        assertNull(FleetComparisonMetric.CHECKS.value(host("invalid", "Invalid", ready = 250.0, probe = 200.0)))
    }

    @Test
    fun summaryReportsMedianSpreadAndCoverage() {
        val ranks = fleetComparisonRanks(
            listOf(
                host("observer", "Observer", ping = 1.0),
                host("a", "A", ping = 10.0),
                host("b", "B", ping = 20.0),
                host("c", "C", ping = 40.0),
                host("d", "D", ping = 80.0),
                host("missing", "Missing"),
            ),
            "observer",
            FleetComparisonMetric.PING,
        )

        val summary = fleetComparisonSummary(ranks)

        assertEquals(4, summary.measuredCount)
        assertEquals(5, summary.remoteCount)
        assertEquals(10.0, summary.bestMilliseconds ?: -1.0, 0.0)
        assertEquals(30.0, summary.medianMilliseconds ?: -1.0, 0.0)
        assertEquals(70.0, summary.spreadMilliseconds ?: -1.0, 0.0)
    }

    @Test
    fun emptyAndLegacyObserverFeedsRemainHonest() {
        val emptySummary = fleetComparisonSummary(emptyList())
        assertEquals(0, emptySummary.measuredCount)
        assertNull(emptySummary.bestMilliseconds)
        assertNull(emptySummary.medianMilliseconds)
        assertNull(emptySummary.spreadMilliseconds)

        val legacyRanks = fleetComparisonRanks(
            listOf(host("local-looking", "This Mac", ping = 5.0)),
            "",
            FleetComparisonMetric.PING,
        )
        assertFalse(legacyRanks.single().isObserver)
        assertEquals(5.0, legacyRanks.single().valueMilliseconds ?: -1.0, 0.0)
    }

    private fun host(
        id: String,
        name: String,
        state: HostState = HostState.ONLINE,
        ping: Double? = null,
        ready: Double? = null,
        probe: Double? = null,
        pinned: Boolean = false,
    ) = FleetHost(
        id = id,
        name = name,
        state = state,
        pingMs = ping,
        sshReadyMs = ready,
        fullProbeMs = probe,
        isPinned = pinned,
    )
}
