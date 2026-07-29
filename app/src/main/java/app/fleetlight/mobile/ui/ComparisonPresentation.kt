package app.fleetlight.mobile.ui

import app.fleetlight.mobile.data.FleetHost
import app.fleetlight.mobile.data.HostMetric
import app.fleetlight.mobile.data.HostState
import java.time.Duration
import java.time.Instant

internal enum class FleetComparisonWindow(val label: String, val hours: Int?) {
    NOW("Now", null),
    ONE_HOUR("1h", 1),
    SIX_HOURS("6h", 6),
    TWENTY_FOUR_HOURS("24h", 24),
}

private fun Double?.verifiedTiming(): Double? = this?.takeIf { it.isFinite() && it >= 0.0 }

private fun verifiedChecks(ready: Double?, total: Double?): Double? =
    ready.verifiedTiming()?.let { verifiedReady ->
        total.verifiedTiming()?.takeIf { it >= verifiedReady }?.minus(verifiedReady)
    }

internal enum class FleetComparisonMetric(val label: String) {
    PING("Ping"),
    SSH_READY("SSH ready"),
    CHECKS("Checks"),
    FULL_PROBE("Full probe");

    fun value(host: FleetHost): Double? = when (this) {
        PING -> host.pingMs.verifiedTiming()
        SSH_READY -> host.sshReadyMs.verifiedTiming()
        CHECKS -> verifiedChecks(host.sshReadyMs, host.fullProbeMs)
        FULL_PROBE -> host.fullProbeMs.verifiedTiming()
    }

    fun value(sample: HostMetric): Double? = when (this) {
        PING -> sample.pingMs.verifiedTiming()
        SSH_READY -> sample.sshReadyMs.verifiedTiming()
        CHECKS -> verifiedChecks(sample.sshReadyMs, sample.fullProbeMs)
        FULL_PROBE -> sample.fullProbeMs.verifiedTiming()
    }
}

internal data class FleetComparisonRank(
    val host: FleetHost,
    val valueMilliseconds: Double?,
    val isObserver: Boolean,
    val sampleCount: Int? = null,
)

internal data class FleetComparisonSummary(
    val measuredCount: Int,
    val remoteCount: Int,
    val bestMilliseconds: Double?,
    val medianMilliseconds: Double?,
    val spreadMilliseconds: Double?,
)

private data class HistoricalComparisonSample(
    val hostId: String,
    val capturedAt: Instant,
    val valueMilliseconds: Double,
)

internal fun HostState.isLiveForComparison(): Boolean =
    this == HostState.ONLINE || this == HostState.SLOW || this == HostState.ATTENTION

internal fun fleetComparisonRanks(
    hosts: List<FleetHost>,
    observerId: String,
    metric: FleetComparisonMetric,
): List<FleetComparisonRank> = hosts
    .map { host ->
        val isObserver = observerId.isNotBlank() && host.id == observerId
        FleetComparisonRank(
            host = host,
            valueMilliseconds = metric.value(host).takeIf { !isObserver && host.state.isLiveForComparison() },
            isObserver = isObserver,
        )
    }
    .sortedForComparison()

internal fun historicalFleetComparisonRanks(
    hosts: List<FleetHost>,
    observerId: String,
    metrics: List<HostMetric>,
    endAt: Instant,
    window: FleetComparisonWindow,
    metric: FleetComparisonMetric,
): List<FleetComparisonRank> {
    val hours = requireNotNull(window.hours) { "Historical comparison requires a time window" }
    val startAt = endAt.minus(Duration.ofHours(hours.toLong()))
    val samplesByHost = metrics.asSequence()
        .filter { !it.capturedAt.isBefore(startAt) && !it.capturedAt.isAfter(endAt) }
        .mapNotNull { sample ->
            if (HostState.from(sample.state) != HostState.ONLINE) return@mapNotNull null
            HistoricalComparisonSample(
                hostId = sample.hostId,
                capturedAt = sample.capturedAt,
                valueMilliseconds = metric.value(sample) ?: return@mapNotNull null,
            )
        }
        .sortedBy(HistoricalComparisonSample::capturedAt)
        .distinctBy { it.hostId to it.capturedAt }
        .groupBy(HistoricalComparisonSample::hostId)

    return hosts.map { host ->
        val isObserver = observerId.isNotBlank() && host.id == observerId
        val values = samplesByHost[host.id].orEmpty().map(HistoricalComparisonSample::valueMilliseconds)
        FleetComparisonRank(
            host = host,
            valueMilliseconds = values.takeIf { !isObserver && it.isNotEmpty() }?.average(),
            isObserver = isObserver,
            sampleCount = if (isObserver) 0 else values.size,
        )
    }.sortedForComparison()
}

private fun List<FleetComparisonRank>.sortedForComparison(): List<FleetComparisonRank> = sortedWith(
    compareBy<FleetComparisonRank>(
        { if (it.valueMilliseconds != null) 0 else if (it.host.state.isLiveForComparison()) 1 else 2 },
        { it.valueMilliseconds ?: Double.MAX_VALUE },
        { it.host.name.lowercase() },
        { it.host.id },
    ),
)

internal fun fleetComparisonSummary(ranks: List<FleetComparisonRank>): FleetComparisonSummary {
    val values = ranks.mapNotNull(FleetComparisonRank::valueMilliseconds).sorted()
    val median = when {
        values.isEmpty() -> null
        values.size % 2 == 1 -> values[values.size / 2]
        else -> (values[values.size / 2 - 1] + values[values.size / 2]) / 2.0
    }
    return FleetComparisonSummary(
        measuredCount = values.size,
        remoteCount = ranks.count { !it.isObserver },
        bestMilliseconds = values.firstOrNull(),
        medianMilliseconds = median,
        spreadMilliseconds = values.firstOrNull()?.let { best -> values.last() - best },
    )
}
