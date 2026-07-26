package app.fleetlight.mobile.ui

import app.fleetlight.mobile.data.HostMetric
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class TrendPresentationTest {
    private val endAt = Instant.parse("2026-01-15T12:00:00Z")

    @Test
    fun windowsUseActualTimeAndExcludeOtherHostsAndFutureSamples() {
        val metrics = listOf(
            metric("alpha", "2026-01-14T11:00:00Z", 10.0),
            metric("alpha", "2026-01-14T13:00:00Z", 20.0),
            metric("alpha", "2026-01-15T07:00:00Z", 30.0),
            metric("alpha", "2026-01-15T11:30:00Z", 40.0),
            metric("alpha", "2026-01-15T11:30:00Z", 999.0),
            metric("alpha", "2026-01-15T12:01:00Z", 50.0),
            metric("bravo", "2026-01-15T11:50:00Z", 60.0),
        )

        assertEquals(listOf(40.0), trendMetrics(metrics, "alpha", endAt, TrendWindow.ONE_HOUR).map { it.pingMs })
        assertEquals(listOf(30.0, 40.0), trendMetrics(metrics, "alpha", endAt, TrendWindow.SIX_HOURS).map { it.pingMs })
        assertEquals(listOf(20.0, 30.0, 40.0), trendMetrics(metrics, "alpha", endAt, TrendWindow.TWENTY_FOUR_HOURS).map { it.pingMs })
    }

    @Test
    fun averagesIgnoreMissingValues() {
        val samples = listOf(
            metric("alpha", "2026-01-15T11:00:00Z", 20.0),
            HostMetric(hostId = "alpha", capturedAt = Instant.parse("2026-01-15T11:30:00Z")),
            metric("alpha", "2026-01-15T12:00:00Z", 40.0),
        )

        assertEquals(30.0, averageTrendValue(samples, HostMetric::pingMs))
        assertEquals(null, averageTrendValue(samples, HostMetric::jitterMs))
    }

    @Test
    fun coverageUsesPublishedCadenceAndCapsManualExtraSamples() {
        val threeSamples = listOf(
            metric("alpha", "2026-01-15T11:00:00Z", 20.0),
            metric("alpha", "2026-01-15T11:30:00Z", 30.0),
            metric("alpha", "2026-01-15T12:00:00Z", 40.0),
        )

        assertEquals(100, trendCoveragePercent(threeSamples, TrendWindow.ONE_HOUR, 1800))
        assertEquals(60, trendCoveragePercent(threeSamples.take(3), TrendWindow.ONE_HOUR, 900))
        assertEquals(null, trendCoveragePercent(threeSamples, TrendWindow.ONE_HOUR, null))
        assertEquals(100, trendCoveragePercent(threeSamples + threeSamples, TrendWindow.ONE_HOUR, 3600))
    }

    @Test
    fun gapThresholdTracksCadenceAndLegacyFeedsRemainBounded() {
        assertEquals(5_400L, trendGapThresholdSeconds(TrendWindow.SIX_HOURS, 1800))
        assertEquals(90L, trendGapThresholdSeconds(TrendWindow.ONE_HOUR, 10))
        assertEquals(3_600L, trendGapThresholdSeconds(TrendWindow.SIX_HOURS, null))
        assertEquals("45 sec", formatTrendDuration(45))
        assertEquals("30 min", formatTrendDuration(1800))
        assertEquals("2h 05m", formatTrendDuration(7500))
    }

    @Test
    fun nearestInspectionClampsAndPrefersEarlierOnTies() {
        val samples = listOf(
            metric("alpha", "2026-01-15T10:00:00Z", 10.0),
            metric("alpha", "2026-01-15T11:00:00Z", 20.0),
            metric("alpha", "2026-01-15T12:00:00Z", 30.0),
        )

        assertEquals(null, nearestTrendMetric(emptyList(), endAt))
        assertEquals(10.0, nearestTrendMetric(samples, Instant.parse("2026-01-15T09:00:00Z"))?.pingMs)
        assertEquals(30.0, nearestTrendMetric(samples, Instant.parse("2026-01-15T13:00:00Z"))?.pingMs)
        assertEquals(20.0, nearestTrendMetric(samples, Instant.parse("2026-01-15T11:00:00Z"))?.pingMs)
        assertEquals(10.0, nearestTrendMetric(samples, Instant.parse("2026-01-15T10:29:59Z"))?.pingMs)
        assertEquals(20.0, nearestTrendMetric(samples, Instant.parse("2026-01-15T10:30:01Z"))?.pingMs)
        assertEquals(10.0, nearestTrendMetric(samples, Instant.parse("2026-01-15T10:30:00Z"))?.pingMs)
    }

    private fun metric(hostId: String, capturedAt: String, pingMs: Double) = HostMetric(
        hostId = hostId,
        capturedAt = Instant.parse(capturedAt),
        pingMs = pingMs,
    )
}
