package app.fleetlight.mobile.ui

import app.fleetlight.mobile.data.FleetHost
import app.fleetlight.mobile.data.HostMetric
import app.fleetlight.mobile.data.HostState
import app.fleetlight.mobile.data.TimingComparison
import java.time.Instant
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

    @Test
    fun historicalRanksUseVerifiedWindowSamplesAndKeepOfflineHistory() {
        val endAt = Instant.parse("2026-01-15T12:00:00Z")
        val hosts = listOf(
            host("observer", "Observer", ping = 1.0),
            host("offline", "Offline now", state = HostState.OFFLINE),
            host("steady", "Steady"),
            host("empty", "Empty"),
        )
        val metrics = listOf(
            metric("offline", "2026-01-15T11:10:00Z", "online", ping = 10.0),
            metric("offline", "2026-01-15T11:20:00Z", "online", ping = 10.0),
            metric("steady", "2026-01-15T11:15:00Z", "unreachable", ping = 1.0),
            metric("steady", "2026-01-15T11:15:00Z", "online", ping = 999.0),
            metric("steady", "2026-01-15T11:15:00Z", "online", ping = 20.0),
            metric("steady", "2026-01-15T11:45:00Z", "unreachable", ping = 1.0),
            metric("steady", "2026-01-15T11:35:00Z", "attention", ping = 2.0),
            metric("steady", "2026-01-15T11:40:00Z", "online", ping = -5.0),
            metric("steady", "2026-01-15T11:50:00Z", "online", ping = Double.NaN),
            metric("steady", "2026-01-15T10:00:00Z", "online", ping = 2.0),
            metric("steady", "2026-01-15T12:01:00Z", "online", ping = 3.0),
            metric("observer", "2026-01-15T11:30:00Z", "online", ping = 1.0),
            metric("other", "2026-01-15T11:30:00Z", "online", ping = 4.0),
        )

        val ranks = historicalFleetComparisonRanks(
            hosts,
            "observer",
            metrics,
            endAt,
            FleetComparisonWindow.ONE_HOUR,
            FleetComparisonMetric.PING,
        )

        assertEquals(listOf("offline", "steady", "empty", "observer"), ranks.map { it.host.id })
        assertEquals(listOf(10.0, 20.0, null, null), ranks.map { it.valueMilliseconds })
        assertEquals(listOf(2, 1, 0, 0), ranks.map { it.sampleCount })
        assertEquals(HostState.OFFLINE, ranks[0].host.state)
    }

    @Test
    fun equalMeasurementsUseStableHostIdTieBreak() {
        val ranks = fleetComparisonRanks(
            hosts = listOf(
                host("z-id", "Same", ping = 20.0),
                host("a-id", "Same", ping = 20.0),
            ),
            observerId = "observer",
            metric = FleetComparisonMetric.PING,
        )

        assertEquals(listOf("a-id", "z-id"), ranks.map { it.host.id })
    }

    @Test
    fun historicalChecksIgnoreMissingAndInvalidPairs() {
        val endAt = Instant.parse("2026-01-15T12:00:00Z")
        val ranks = historicalFleetComparisonRanks(
            hosts = listOf(host("machine", "Machine")),
            observerId = "observer",
            metrics = listOf(
                metric("machine", "2026-01-15T11:10:00Z", "online", ready = 100.0, probe = 250.0),
                metric("machine", "2026-01-15T11:20:00Z", "online", ready = 300.0, probe = 250.0),
                metric("machine", "2026-01-15T11:30:00Z", "online", ready = 100.0),
            ),
            endAt = endAt,
            window = FleetComparisonWindow.ONE_HOUR,
            metric = FleetComparisonMetric.CHECKS,
        )

        assertEquals(150.0, ranks.single().valueMilliseconds ?: -1.0, 0.0)
        assertEquals(1, ranks.single().sampleCount)
    }

    @Test
    fun comparesDisjointCurrentAndPreviousWindowsWithTheBoundaryInCurrent() {
        val endAt = Instant.parse("2026-01-15T12:00:00Z")
        val ranks = historicalFleetComparisonRanks(
            hosts = listOf(host("alpha", "Alpha")),
            observerId = "observer",
            metrics = listOf(
                metric("alpha", "2026-01-15T09:59:59Z", "online", ping = 1.0),
                metric("alpha", "2026-01-15T10:00:00Z", "online", ping = 60.0),
                metric("alpha", "2026-01-15T10:30:00Z", "online", ping = 40.0),
                metric("alpha", "2026-01-15T11:00:00Z", "online", ping = 30.0),
                metric("alpha", "2026-01-15T11:30:00Z", "online", ping = 20.0),
                metric("alpha", "2026-01-15T12:00:00Z", "online", ping = 10.0),
                metric("alpha", "2026-01-15T12:00:01Z", "online", ping = 1.0),
            ),
            endAt = endAt,
            window = FleetComparisonWindow.ONE_HOUR,
            metric = FleetComparisonMetric.PING,
        )

        val rank = ranks.single()
        assertEquals(20.0, rank.valueMilliseconds ?: -1.0, 0.0)
        assertEquals(3, rank.sampleCount)
        assertEquals(50.0, rank.previousValueMilliseconds ?: -1.0, 0.0)
        assertEquals(2, rank.previousSampleCount)
        assertEquals(-30.0, rank.deltaMilliseconds ?: 1.0, 0.0)
        assertEquals(-60.0, rank.deltaPercent ?: 1.0, 0.0)
        assertEquals(FleetComparisonDirection.FASTER, rank.direction)
    }

    @Test
    fun duplicateCorrectionsWinBeforeStateAndValueValidation() {
        val ranks = historicalFleetComparisonRanks(
            hosts = listOf(host("alpha", "Alpha")),
            observerId = "observer",
            metrics = listOf(
                metric("alpha", "2026-01-15T11:15:00Z", "online", ping = 20.0),
                metric("alpha", "2026-01-15T11:15:00Z", "unreachable", ping = 20.0),
                metric("alpha", "2026-01-15T11:30:00Z", "unreachable", ping = 1.0),
                metric("alpha", "2026-01-15T11:30:00Z", "online", ping = 30.0),
            ),
            endAt = Instant.parse("2026-01-15T12:00:00Z"),
            window = FleetComparisonWindow.ONE_HOUR,
            metric = FleetComparisonMetric.PING,
        )

        assertEquals(30.0, ranks.single().valueMilliseconds ?: -1.0, 0.0)
        assertEquals(1, ranks.single().sampleCount)
        assertNull(ranks.single().previousValueMilliseconds)
    }

    @Test
    fun controllerAggregateWinsOverSampledHistoryAndKeepsEvidence() {
        val ranks = historicalFleetComparisonRanks(
            hosts = listOf(host("alpha", "Alpha", state = HostState.OFFLINE)),
            observerId = "observer",
            metrics = listOf(
                metric("alpha", "2026-01-15T10:30:00Z", "online", ping = 999.0),
                metric("alpha", "2026-01-15T11:30:00Z", "online", ping = 999.0),
            ),
            endAt = Instant.parse("2026-01-15T12:00:00Z"),
            window = FleetComparisonWindow.ONE_HOUR,
            metric = FleetComparisonMetric.PING,
            timingComparisons = listOf(
                TimingComparison(
                    hostId = "alpha",
                    metric = "ping",
                    windowHours = 1,
                    currentAverageMs = 40.0,
                    currentSampleCount = 4,
                    previousAverageMs = 50.0,
                    previousSampleCount = 3,
                ),
            ),
        )

        val rank = ranks.single()
        assertEquals(40.0, rank.valueMilliseconds ?: -1.0, 0.0)
        assertEquals(4, rank.sampleCount)
        assertEquals(50.0, rank.previousValueMilliseconds ?: -1.0, 0.0)
        assertEquals(3, rank.previousSampleCount)
        assertEquals(FleetComparisonDirection.FASTER, rank.direction)
        assertEquals(FleetComparisonEvidenceSource.CONTROLLER, rank.evidenceSource)
        assertEquals(HostState.OFFLINE, rank.host.state)
    }

    @Test
    fun malformedControllerAggregateFallsBackToUsableRawHistory() {
        val ranks = historicalFleetComparisonRanks(
            hosts = listOf(host("alpha", "Alpha")),
            observerId = "observer",
            metrics = listOf(
                metric("alpha", "2026-01-15T10:30:00Z", "online", ping = 50.0),
                metric("alpha", "2026-01-15T11:30:00Z", "online", ping = 30.0),
            ),
            endAt = Instant.parse("2026-01-15T12:00:00Z"),
            window = FleetComparisonWindow.ONE_HOUR,
            metric = FleetComparisonMetric.PING,
            timingComparisons = listOf(
                TimingComparison(
                    hostId = "alpha",
                    metric = "ping",
                    windowHours = 1,
                    currentAverageMs = 999.0,
                    currentSampleCount = 0,
                    previousAverageMs = 50.0,
                    previousSampleCount = 1,
                ),
            ),
        )

        val rank = ranks.single()
        assertEquals(30.0, rank.valueMilliseconds ?: -1.0, 0.0)
        assertEquals(50.0, rank.previousValueMilliseconds ?: -1.0, 0.0)
        assertEquals(FleetComparisonDirection.FASTER, rank.direction)
        assertEquals(FleetComparisonEvidenceSource.RAW_HISTORY, rank.evidenceSource)
    }

    @Test
    fun keepsPreviousOnlyEvidenceWhenTheCurrentWindowIsEmpty() {
        val ranks = historicalFleetComparisonRanks(
            hosts = listOf(host("alpha", "Alpha")),
            observerId = "observer",
            metrics = listOf(
                metric("alpha", "2026-01-15T10:30:00Z", "online", ping = 50.0),
            ),
            endAt = Instant.parse("2026-01-15T12:00:00Z"),
            window = FleetComparisonWindow.ONE_HOUR,
            metric = FleetComparisonMetric.PING,
        )

        val rank = ranks.single()
        assertNull(rank.valueMilliseconds)
        assertEquals(0, rank.sampleCount)
        assertEquals(50.0, rank.previousValueMilliseconds ?: -1.0, 0.0)
        assertEquals(1, rank.previousSampleCount)
        assertEquals(FleetComparisonDirection.NO_BASELINE, rank.direction)
    }

    @Test
    fun classifiesMaterialChangeAndAvoidsInfinitePercentages() {
        assertEquals(FleetComparisonDirection.STABLE, comparisonDirection(104.0, 100.0))
        assertEquals(FleetComparisonDirection.STABLE, comparisonDirection(105.0, 100.0))
        assertEquals(FleetComparisonDirection.SLOWER, comparisonDirection(106.0, 100.0))
        assertEquals(FleetComparisonDirection.FASTER, comparisonDirection(94.0, 100.0))
        assertEquals(FleetComparisonDirection.STABLE, comparisonDirection(0.0, 0.0))
        assertEquals(FleetComparisonDirection.STABLE, comparisonDirection(1.5, 0.0))
        assertEquals(FleetComparisonDirection.STABLE, comparisonDirection(2.0, 0.0))
        assertEquals(FleetComparisonDirection.SLOWER, comparisonDirection(3.0, 0.0))
        assertEquals(FleetComparisonDirection.SLOWER, comparisonDirection(5.0, 0.0))
        assertEquals(FleetComparisonDirection.NO_BASELINE, comparisonDirection(5.0, null))

        val rank = FleetComparisonRank(
            host = host("zero", "Zero"),
            valueMilliseconds = 5.0,
            isObserver = false,
            previousValueMilliseconds = 0.0,
            direction = FleetComparisonDirection.SLOWER,
        )
        assertEquals(5.0, rank.deltaMilliseconds ?: -1.0, 0.0)
        assertNull(rank.deltaPercent)
    }

    @Test
    fun changeOrderingUsesSignedPercentThenSignedDeltaAndName() {
        val ranks = historicalFleetComparisonRanks(
            hosts = listOf(
                host("slow", "Slow"),
                host("stable-positive", "Stable positive"),
                host("tie-z", "Zulu improvement"),
                host("stable-negative", "Stable negative"),
                host("tie-a", "Alpha improvement"),
                host("largest-absolute", "Largest absolute"),
            ),
            observerId = "observer",
            metrics = emptyList(),
            endAt = Instant.parse("2026-01-15T12:00:00Z"),
            window = FleetComparisonWindow.ONE_HOUR,
            metric = FleetComparisonMetric.PING,
            timingComparisons = listOf(
                comparison("slow", current = 120.0, previous = 100.0),
                comparison("stable-positive", current = 105.0, previous = 100.0),
                comparison("tie-z", current = 50.0, previous = 100.0),
                comparison("stable-negative", current = 95.0, previous = 100.0),
                comparison("tie-a", current = 50.0, previous = 100.0),
                comparison("largest-absolute", current = 100.0, previous = 200.0),
            ),
            ordering = FleetComparisonOrdering.CHANGE,
        )

        assertEquals(
            listOf(
                "largest-absolute",
                "tie-a",
                "tie-z",
                "stable-negative",
                "stable-positive",
                "slow",
            ),
            ranks.map { it.host.id },
        )
        assertEquals(FleetComparisonDirection.FASTER, ranks.first().direction)
        assertEquals(FleetComparisonDirection.STABLE, ranks[3].direction)
        assertEquals(FleetComparisonDirection.SLOWER, ranks.last().direction)
    }

    @Test
    fun changeOrderingKeepsNoPercentEvidenceAndObserverInTruthfulBuckets() {
        val ranks = historicalFleetComparisonRanks(
            hosts = listOf(
                host("observer", "This phone"),
                host("none", "No data"),
                host("previous", "Previous only"),
                host("zero", "Zero baseline"),
                host("current", "Current only"),
                host("paired", "Paired"),
            ),
            observerId = "observer",
            metrics = emptyList(),
            endAt = Instant.parse("2026-01-15T12:00:00Z"),
            window = FleetComparisonWindow.ONE_HOUR,
            metric = FleetComparisonMetric.PING,
            timingComparisons = listOf(
                comparison("observer", current = 1.0, previous = 2.0),
                comparison("previous", current = null, previous = 20.0),
                comparison("zero", current = 5.0, previous = 0.0),
                comparison("current", current = 4.0, previous = null),
                comparison("paired", current = 9.0, previous = 10.0),
            ),
            ordering = FleetComparisonOrdering.CHANGE,
        )

        assertEquals(
            listOf("paired", "zero", "current", "previous", "none", "observer"),
            ranks.map { it.host.id },
        )
        assertNull(ranks.first { it.host.id == "zero" }.deltaPercent)
        assertEquals(FleetComparisonDirection.SLOWER, ranks.first { it.host.id == "zero" }.direction)
        assertTrue(ranks.last().isObserver)
    }

    @Test
    fun summarySelectsOnlyMaterialPercentMovers() {
        fun moverRank(
            id: String,
            current: Double,
            previous: Double,
            observer: Boolean = false,
        ) = FleetComparisonRank(
            host = host(id, id.replace('-', ' ')),
            valueMilliseconds = current,
            isObserver = observer,
            sampleCount = if (observer) 0 else 3,
            previousValueMilliseconds = previous,
            previousSampleCount = if (observer) 0 else 3,
            direction = comparisonDirection(current, previous),
            evidenceSource = FleetComparisonEvidenceSource.CONTROLLER,
        )
        val summary = fleetComparisonSummary(
            listOf(
                moverRank("smaller-improvement", current = 80.0, previous = 100.0),
                moverRank("biggest-improvement", current = 100.0, previous = 200.0),
                moverRank("about-same", current = 104.0, previous = 100.0),
                moverRank("smaller-slowdown", current = 120.0, previous = 100.0),
                moverRank("biggest-slowdown", current = 180.0, previous = 100.0),
                moverRank("zero-baseline", current = 20.0, previous = 0.0),
                moverRank("observer", current = 1.0, previous = 100.0, observer = true),
            ),
        )

        assertEquals("biggest-improvement", summary.biggestImprovement?.hostId)
        assertEquals(-50.0, summary.biggestImprovement?.deltaPercent ?: 0.0, 0.0)
        assertEquals(-100.0, summary.biggestImprovement?.deltaMilliseconds ?: 0.0, 0.0)
        assertEquals("biggest-slowdown", summary.biggestSlowdown?.hostId)
        assertEquals(80.0, summary.biggestSlowdown?.deltaPercent ?: 0.0, 0.0)
        assertEquals(80.0, summary.biggestSlowdown?.deltaMilliseconds ?: 0.0, 0.0)
        assertEquals(6, summary.comparableCount)
        assertEquals(1, summary.stableCount)
        assertEquals(3, summary.slowerCount)
    }

    @Test
    fun summaryUsesNewDelayAsSlowdownFallbackWithoutInventingAPercentage() {
        val summary = fleetComparisonSummary(
            listOf(
                FleetComparisonRank(
                    host = host("stable", "Stable"),
                    valueMilliseconds = 102.0,
                    isObserver = false,
                    sampleCount = 2,
                    previousValueMilliseconds = 100.0,
                    previousSampleCount = 2,
                    direction = FleetComparisonDirection.STABLE,
                ),
                FleetComparisonRank(
                    host = host("zero", "Zero baseline"),
                    valueMilliseconds = 10.0,
                    isObserver = false,
                    sampleCount = 2,
                    previousValueMilliseconds = 0.0,
                    previousSampleCount = 2,
                    direction = FleetComparisonDirection.SLOWER,
                ),
            ),
        )

        assertNull(summary.biggestImprovement)
        assertEquals("zero", summary.biggestSlowdown?.hostId)
        assertTrue(summary.biggestSlowdown?.isNewDelay == true)
        assertNull(summary.biggestSlowdown?.deltaPercent)
        assertEquals(10.0, summary.biggestSlowdown?.deltaMilliseconds ?: 0.0, 0.0)
        assertEquals(2, summary.comparableCount)
        assertEquals(1, summary.stableCount)
        assertEquals(1, summary.slowerCount)
    }

    @Test
    fun summaryTruthfullyReportsNoMaterialMoversWhenEverythingIsAboutSame() {
        val summary = fleetComparisonSummary(
            listOf(
                FleetComparisonRank(
                    host = host("stable", "Stable"),
                    valueMilliseconds = 102.0,
                    isObserver = false,
                    sampleCount = 2,
                    previousValueMilliseconds = 100.0,
                    previousSampleCount = 2,
                    direction = FleetComparisonDirection.STABLE,
                ),
                FleetComparisonRank(
                    host = host("zero", "Zero baseline"),
                    valueMilliseconds = 2.0,
                    isObserver = false,
                    sampleCount = 2,
                    previousValueMilliseconds = 0.0,
                    previousSampleCount = 2,
                    direction = FleetComparisonDirection.STABLE,
                ),
            ),
        )

        assertNull(summary.biggestImprovement)
        assertNull(summary.biggestSlowdown)
        assertEquals(2, summary.stableCount)
    }

    @Test
    fun summaryCountsOnlyPairedRemoteComparisonsAndReportsSourceCoverage() {
        fun rank(
            id: String,
            direction: FleetComparisonDirection,
            source: FleetComparisonEvidenceSource = FleetComparisonEvidenceSource.CONTROLLER,
            observer: Boolean = false,
        ) = FleetComparisonRank(
            host = host(id, id),
            valueMilliseconds = if (direction == FleetComparisonDirection.NO_BASELINE) 40.0 else 20.0,
            isObserver = observer,
            sampleCount = if (observer) 0 else 2,
            previousValueMilliseconds = if (direction == FleetComparisonDirection.NO_BASELINE) null else 30.0,
            previousSampleCount = if (observer || direction == FleetComparisonDirection.NO_BASELINE) 0 else 3,
            direction = direction,
            evidenceSource = source,
        )
        val ranks = listOf(
            rank("fast", FleetComparisonDirection.FASTER),
            rank("stable", FleetComparisonDirection.STABLE),
            rank("slow", FleetComparisonDirection.SLOWER, FleetComparisonEvidenceSource.RAW_HISTORY),
            rank("missing", FleetComparisonDirection.NO_BASELINE),
            rank("observer", FleetComparisonDirection.FASTER, observer = true),
        )

        val summary = fleetComparisonSummary(ranks)
        assertEquals(3, summary.comparableCount)
        assertEquals(1, summary.fasterCount)
        assertEquals(1, summary.stableCount)
        assertEquals(1, summary.slowerCount)
        assertEquals(8, summary.currentSampleCount)
        assertEquals(9, summary.previousSampleCount)

        val partial = fleetComparisonSourceSummary(ranks, FleetComparisonWindow.TWENTY_FOUR_HOURS, 24)
        assertEquals(3, partial.controllerHostCount)
        assertEquals(1, partial.rawHistoryHostCount)
        assertEquals(48, partial.requiredHistoryHours)
        assertTrue(partial.rawHistoryCoverageInsufficient)
        assertFalse(
            fleetComparisonSourceSummary(ranks, FleetComparisonWindow.TWENTY_FOUR_HOURS, 48)
                .rawHistoryCoverageInsufficient,
        )
        val unknown = fleetComparisonSourceSummary(
            ranks,
            FleetComparisonWindow.TWENTY_FOUR_HOURS,
            null,
        )
        assertTrue(unknown.rawHistoryCoverageUnknown)
        assertFalse(unknown.rawHistoryCoverageInsufficient)
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

    private fun metric(
        hostId: String,
        capturedAt: String,
        state: String,
        ping: Double? = null,
        ready: Double? = null,
        probe: Double? = null,
    ) = HostMetric(
        hostId = hostId,
        capturedAt = Instant.parse(capturedAt),
        state = state,
        pingMs = ping,
        sshReadyMs = ready,
        fullProbeMs = probe,
    )

    private fun comparison(
        hostId: String,
        current: Double?,
        previous: Double?,
    ) = TimingComparison(
        hostId = hostId,
        metric = "ping",
        windowHours = 1,
        currentAverageMs = current,
        currentSampleCount = if (current == null) 0 else 2,
        previousAverageMs = previous,
        previousSampleCount = if (previous == null) 0 else 2,
    )
}
