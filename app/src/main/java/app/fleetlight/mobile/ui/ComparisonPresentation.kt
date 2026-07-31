package app.fleetlight.mobile.ui

import app.fleetlight.mobile.data.FleetHost
import app.fleetlight.mobile.data.HostMetric
import app.fleetlight.mobile.data.HostState
import app.fleetlight.mobile.data.TimingComparison
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.max

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

internal enum class FleetComparisonMetric(
    val label: String,
    val wireValue: String,
) {
    PING("Ping", "ping"),
    SSH_READY("SSH ready", "sshReady"),
    CHECKS("Checks", "checks"),
    FULL_PROBE("Full probe", "fullProbe");

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

internal enum class FleetComparisonDirection {
    FASTER,
    STABLE,
    SLOWER,
    NO_BASELINE,
}

internal enum class FleetComparisonEvidenceSource {
    LIVE,
    CONTROLLER,
    RAW_HISTORY,
}

internal data class FleetComparisonRank(
    val host: FleetHost,
    val valueMilliseconds: Double?,
    val isObserver: Boolean,
    val sampleCount: Int? = null,
    val previousValueMilliseconds: Double? = null,
    val previousSampleCount: Int? = null,
    val direction: FleetComparisonDirection = FleetComparisonDirection.NO_BASELINE,
    val evidenceSource: FleetComparisonEvidenceSource = FleetComparisonEvidenceSource.LIVE,
) {
    val deltaMilliseconds: Double?
        get() = valueMilliseconds?.let { current ->
            previousValueMilliseconds?.let { previous -> current - previous }
        }

    val deltaPercent: Double?
        get() = previousValueMilliseconds
            ?.takeIf { it > 0.0 }
            ?.let { previous -> deltaMilliseconds?.times(100.0)?.div(previous) }
}

internal data class FleetComparisonSummary(
    val measuredCount: Int,
    val remoteCount: Int,
    val bestMilliseconds: Double?,
    val medianMilliseconds: Double?,
    val spreadMilliseconds: Double?,
    val comparableCount: Int,
    val fasterCount: Int,
    val stableCount: Int,
    val slowerCount: Int,
    val currentSampleCount: Int,
    val previousSampleCount: Int,
)

internal data class FleetComparisonSourceSummary(
    val controllerHostCount: Int,
    val rawHistoryHostCount: Int,
    val requiredHistoryHours: Int?,
    val reportedHistoryHours: Int?,
) {
    val rawHistoryCoverageInsufficient: Boolean
        get() = rawHistoryHostCount > 0 &&
            requiredHistoryHours != null &&
            reportedHistoryHours != null &&
            reportedHistoryHours < requiredHistoryHours

    val rawHistoryCoverageUnknown: Boolean
        get() = rawHistoryHostCount > 0 &&
            requiredHistoryHours != null &&
            reportedHistoryHours == null
}

private data class PeriodMeasurement(
    val averageMilliseconds: Double?,
    val sampleCount: Int,
)

private data class PeriodPair(
    val current: PeriodMeasurement,
    val previous: PeriodMeasurement,
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
    timingComparisons: List<TimingComparison> = emptyList(),
): List<FleetComparisonRank> {
    val hours = requireNotNull(window.hours) { "Historical comparison requires a time window" }
    val matchingControllerPairs = timingComparisons.asSequence()
        .filter { it.windowHours == hours && it.metric == metric.wireValue }
        .mapNotNull { comparison ->
            comparison.toPeriodPairOrNull()?.let { comparison.hostId to it }
        }
        .toMap()
    val rawPairs = rawPeriodPairs(metrics, endAt, hours, metric)

    return hosts.map { host ->
        val isObserver = observerId.isNotBlank() && host.id == observerId
        val controllerPair = matchingControllerPairs[host.id]
        val pair = controllerPair ?: rawPairs[host.id] ?: emptyPeriodPair()
        val source = if (controllerPair != null) {
            FleetComparisonEvidenceSource.CONTROLLER
        } else {
            FleetComparisonEvidenceSource.RAW_HISTORY
        }
        val currentValue = pair.current.averageMilliseconds.takeIf { !isObserver }
        val previousValue = pair.previous.averageMilliseconds.takeIf { !isObserver }
        FleetComparisonRank(
            host = host,
            valueMilliseconds = currentValue,
            isObserver = isObserver,
            sampleCount = if (isObserver) 0 else pair.current.sampleCount,
            previousValueMilliseconds = previousValue,
            previousSampleCount = if (isObserver) 0 else pair.previous.sampleCount,
            direction = if (isObserver) {
                FleetComparisonDirection.NO_BASELINE
            } else {
                comparisonDirection(currentValue, previousValue)
            },
            evidenceSource = source,
        )
    }.sortedForComparison()
}

private fun rawPeriodPairs(
    metrics: List<HostMetric>,
    endAt: Instant,
    hours: Int,
    metric: FleetComparisonMetric,
): Map<String, PeriodPair> {
    val duration = Duration.ofHours(hours.toLong())
    val currentStart = endAt.minus(duration)
    val previousStart = currentStart.minus(duration)

    // A later occurrence in the feed is a correction for the same host and timestamp.
    // Canonicalize before checking state or values so a corrected failure cannot resurrect
    // an older successful measurement.
    val canonicalSamples = metrics.asSequence()
        .filter { !it.capturedAt.isBefore(previousStart) && !it.capturedAt.isAfter(endAt) }
        .associateBy { it.hostId to it.capturedAt }
        .values

    val currentByHost = mutableMapOf<String, MutableList<Double>>()
    val previousByHost = mutableMapOf<String, MutableList<Double>>()
    canonicalSamples.forEach { sample ->
        if (HostState.from(sample.state) != HostState.ONLINE) return@forEach
        val value = metric.value(sample) ?: return@forEach
        if (sample.capturedAt.isBefore(currentStart)) {
            previousByHost.getOrPut(sample.hostId, ::mutableListOf).add(value)
        } else {
            currentByHost.getOrPut(sample.hostId, ::mutableListOf).add(value)
        }
    }

    return (currentByHost.keys + previousByHost.keys).associateWith { hostId ->
        PeriodPair(
            current = currentByHost[hostId].toMeasurement(),
            previous = previousByHost[hostId].toMeasurement(),
        )
    }
}

private fun TimingComparison.toPeriodPairOrNull(): PeriodPair? {
    val current = aggregateMeasurementOrNull(currentAverageMs, currentSampleCount) ?: return null
    val previous = aggregateMeasurementOrNull(previousAverageMs, previousSampleCount) ?: return null
    return PeriodPair(current = current, previous = previous)
}

private fun aggregateMeasurementOrNull(average: Double?, count: Int): PeriodMeasurement? = when {
    count == 0 && average == null -> PeriodMeasurement(averageMilliseconds = null, sampleCount = 0)
    count > 0 -> average.verifiedTiming()?.let {
        PeriodMeasurement(averageMilliseconds = it, sampleCount = count)
    }
    else -> null
}

private fun List<Double>?.toMeasurement(): PeriodMeasurement {
    val values = orEmpty()
    return PeriodMeasurement(
        averageMilliseconds = values.takeIf(List<Double>::isNotEmpty)?.average(),
        sampleCount = values.size,
    )
}

private fun emptyPeriodPair() = PeriodPair(
    current = PeriodMeasurement(null, 0),
    previous = PeriodMeasurement(null, 0),
)

internal fun comparisonDirection(
    currentMilliseconds: Double?,
    previousMilliseconds: Double?,
): FleetComparisonDirection {
    if (currentMilliseconds == null || previousMilliseconds == null) {
        return FleetComparisonDirection.NO_BASELINE
    }
    val tolerance = max(2.0, abs(previousMilliseconds) * 0.05)
    val delta = currentMilliseconds - previousMilliseconds
    return when {
        delta < -tolerance -> FleetComparisonDirection.FASTER
        delta > tolerance -> FleetComparisonDirection.SLOWER
        else -> FleetComparisonDirection.STABLE
    }
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
        comparableCount = ranks.count {
            !it.isObserver && it.direction != FleetComparisonDirection.NO_BASELINE
        },
        fasterCount = ranks.count { !it.isObserver && it.direction == FleetComparisonDirection.FASTER },
        stableCount = ranks.count { !it.isObserver && it.direction == FleetComparisonDirection.STABLE },
        slowerCount = ranks.count { !it.isObserver && it.direction == FleetComparisonDirection.SLOWER },
        currentSampleCount = ranks.filterNot(FleetComparisonRank::isObserver).sumOf { it.sampleCount ?: 0 },
        previousSampleCount = ranks.filterNot(FleetComparisonRank::isObserver).sumOf { it.previousSampleCount ?: 0 },
    )
}

internal fun fleetComparisonSourceSummary(
    ranks: List<FleetComparisonRank>,
    window: FleetComparisonWindow,
    reportedHistoryHours: Int?,
): FleetComparisonSourceSummary = FleetComparisonSourceSummary(
    controllerHostCount = ranks.count {
        !it.isObserver && it.evidenceSource == FleetComparisonEvidenceSource.CONTROLLER
    },
    rawHistoryHostCount = ranks.count {
        !it.isObserver && it.evidenceSource == FleetComparisonEvidenceSource.RAW_HISTORY
    },
    requiredHistoryHours = window.hours?.times(2),
    reportedHistoryHours = reportedHistoryHours,
)
