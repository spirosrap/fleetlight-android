package app.fleetlight.mobile.ui

import app.fleetlight.mobile.data.FleetHost
import app.fleetlight.mobile.data.HostState

internal enum class FleetComparisonMetric(val label: String) {
    PING("Ping"),
    SSH_READY("SSH ready"),
    CHECKS("Checks"),
    FULL_PROBE("Full probe");

    fun value(host: FleetHost): Double? = when (this) {
        PING -> host.pingMs
        SSH_READY -> host.sshReadyMs
        CHECKS -> host.sshReadyMs?.let { ready ->
            host.fullProbeMs?.takeIf { total -> total >= ready }?.minus(ready)
        }
        FULL_PROBE -> host.fullProbeMs
    }
}

internal data class FleetComparisonRank(
    val host: FleetHost,
    val valueMilliseconds: Double?,
    val isObserver: Boolean,
)

internal data class FleetComparisonSummary(
    val measuredCount: Int,
    val remoteCount: Int,
    val bestMilliseconds: Double?,
    val medianMilliseconds: Double?,
    val spreadMilliseconds: Double?,
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
    .sortedWith(
        compareBy<FleetComparisonRank>(
            { if (it.host.state.isLiveForComparison()) 0 else 1 },
            { if (it.valueMilliseconds != null) 0 else 1 },
            { it.valueMilliseconds ?: Double.MAX_VALUE },
            { it.host.name.lowercase() },
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
