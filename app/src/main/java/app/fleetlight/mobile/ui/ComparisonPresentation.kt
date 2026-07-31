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

internal enum class FleetComparisonOrdering(val label: String) {
    SPEED("Speed"),
    CHANGE("Change"),
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

internal enum class FleetComparisonEvidenceStrength {
    NONE,
    LIMITED,
    FAIR,
    STRONG,
}

internal data class FleetComparisonRank(
    val host: FleetHost,
    val valueMilliseconds: Double?,
    val isObserver: Boolean,
    val sampleCount: Int? = null,
    val currentCoverageSeconds: Double? = null,
    val previousValueMilliseconds: Double? = null,
    val previousSampleCount: Int? = null,
    val previousCoverageSeconds: Double? = null,
    val comparisonWindowSeconds: Double? = null,
    val direction: FleetComparisonDirection = FleetComparisonDirection.NO_BASELINE,
    val evidenceSource: FleetComparisonEvidenceSource = FleetComparisonEvidenceSource.LIVE,
) {
    val deltaMilliseconds: Double?
        get() = valueMilliseconds.verifiedTiming()?.let { current ->
            previousValueMilliseconds.verifiedTiming()?.let { previous ->
                (current - previous).takeIf(Double::isFinite)
            }
        }

    val deltaPercent: Double?
        get() = previousValueMilliseconds
            ?.takeIf { it > 0.0 }
            ?.let { previous ->
                deltaMilliseconds
                    ?.div(previous)
                    ?.times(100.0)
                    ?.takeIf(Double::isFinite)
            }

    val isPairedPeriodComparison: Boolean
        get() = !isObserver && evidenceStrength != FleetComparisonEvidenceStrength.NONE

    val evidenceStrength: FleetComparisonEvidenceStrength
        get() = if (isObserver) {
            FleetComparisonEvidenceStrength.NONE
        } else {
            comparisonEvidenceStrength(
                currentMilliseconds = valueMilliseconds,
                currentSampleCount = sampleCount ?: 0,
                currentCoverageSeconds = currentCoverageSeconds,
                previousMilliseconds = previousValueMilliseconds,
                previousSampleCount = previousSampleCount ?: 0,
                previousCoverageSeconds = previousCoverageSeconds,
                windowSeconds = comparisonWindowSeconds,
            )
        }
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
    val strongEvidenceCount: Int,
    val fairEvidenceCount: Int,
    val limitedEvidenceCount: Int,
    val unpairedCount: Int,
    val currentSampleCount: Int,
    val previousSampleCount: Int,
    val biggestImprovement: FleetComparisonMover?,
    val biggestSlowdown: FleetComparisonMover?,
)

internal data class FleetComparisonMover(
    val hostId: String,
    val hostName: String,
    val deltaMilliseconds: Double,
    val deltaPercent: Double?,
    val isNewDelay: Boolean,
    val hasLimitedEvidence: Boolean,
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
    val coverageSeconds: Double?,
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
    ordering: FleetComparisonOrdering = FleetComparisonOrdering.SPEED,
): List<FleetComparisonRank> {
    val hours = requireNotNull(window.hours) { "Historical comparison requires a time window" }
    val windowSeconds = Duration.ofHours(hours.toLong()).seconds.toDouble()
    val matchingControllerPairs = timingComparisons.asSequence()
        .filter { it.windowHours == hours && it.metric == metric.wireValue }
        .mapNotNull { comparison ->
            comparison.toPeriodPairOrNull(windowSeconds)?.let { comparison.hostId to it }
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
            currentCoverageSeconds = if (isObserver) null else pair.current.coverageSeconds,
            previousValueMilliseconds = previousValue,
            previousSampleCount = if (isObserver) 0 else pair.previous.sampleCount,
            previousCoverageSeconds = if (isObserver) null else pair.previous.coverageSeconds,
            comparisonWindowSeconds = if (isObserver) null else windowSeconds,
            direction = if (isObserver) {
                FleetComparisonDirection.NO_BASELINE
            } else {
                comparisonDirection(currentValue, previousValue)
            },
            evidenceSource = source,
        )
    }.sortedForComparison(ordering)
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

    val currentByHost = mutableMapOf<String, MutableList<PeriodSample>>()
    val previousByHost = mutableMapOf<String, MutableList<PeriodSample>>()
    canonicalSamples.forEach { sample ->
        if (HostState.from(sample.state) != HostState.ONLINE) return@forEach
        val value = metric.value(sample) ?: return@forEach
        val periodSample = PeriodSample(sample.capturedAt, value)
        if (sample.capturedAt.isBefore(currentStart)) {
            previousByHost.getOrPut(sample.hostId, ::mutableListOf).add(periodSample)
        } else {
            currentByHost.getOrPut(sample.hostId, ::mutableListOf).add(periodSample)
        }
    }

    val windowSeconds = duration.seconds.toDouble()

    return (currentByHost.keys + previousByHost.keys).associateWith { hostId ->
        PeriodPair(
            current = currentByHost[hostId].toMeasurement(windowSeconds),
            previous = previousByHost[hostId].toMeasurement(windowSeconds),
        )
    }
}

private data class PeriodSample(
    val capturedAt: Instant,
    val milliseconds: Double,
)

private fun TimingComparison.toPeriodPairOrNull(windowSeconds: Double): PeriodPair? {
    val current = aggregateMeasurementOrNull(
        currentAverageMs,
        currentSampleCount,
        currentCoverageSeconds,
        windowSeconds,
    ) ?: return null
    val previous = aggregateMeasurementOrNull(
        previousAverageMs,
        previousSampleCount,
        previousCoverageSeconds,
        windowSeconds,
    ) ?: return null
    return PeriodPair(current = current, previous = previous)
}

private fun aggregateMeasurementOrNull(
    average: Double?,
    count: Int,
    coverageSeconds: Double?,
    windowSeconds: Double,
): PeriodMeasurement? {
    val verifiedCoverage = coverageSeconds?.takeIf { it.isFinite() && it >= 0.0 }
    if (coverageSeconds != null && verifiedCoverage == null) return null
    val clampedCoverage = verifiedCoverage
        ?.let { if (count < 2) 0.0 else it }
        ?.coerceAtMost(windowSeconds)
    return when {
        count == 0 && average == null -> PeriodMeasurement(
            averageMilliseconds = null,
            sampleCount = 0,
            coverageSeconds = clampedCoverage,
        )
        count > 0 -> average.verifiedTiming()?.let {
            PeriodMeasurement(
                averageMilliseconds = it,
                sampleCount = count,
                coverageSeconds = clampedCoverage,
            )
        }
        else -> null
    }
}

private fun List<PeriodSample>?.toMeasurement(windowSeconds: Double): PeriodMeasurement {
    val values = orEmpty()
    val coverageSeconds = if (values.size < 2) {
        0.0
    } else {
        val earliest = checkNotNull(values.minOfOrNull(PeriodSample::capturedAt))
        val latest = checkNotNull(values.maxOfOrNull(PeriodSample::capturedAt))
        Duration.between(earliest, latest).toMillis().toDouble()
            .div(1_000.0)
            .coerceIn(0.0, windowSeconds)
    }
    return PeriodMeasurement(
        averageMilliseconds = values.incrementalAverage(),
        sampleCount = values.size,
        coverageSeconds = coverageSeconds,
    )
}

private fun List<PeriodSample>.incrementalAverage(): Double? {
    if (isEmpty()) return null
    return foldIndexed(0.0) { index, average, sample ->
        average + (sample.milliseconds - average) / (index + 1).toDouble()
    }.takeIf(Double::isFinite)
}

private fun emptyPeriodPair() = PeriodPair(
    current = PeriodMeasurement(null, 0, 0.0),
    previous = PeriodMeasurement(null, 0, 0.0),
)

internal fun comparisonEvidenceStrength(
    currentMilliseconds: Double?,
    currentSampleCount: Int,
    currentCoverageSeconds: Double?,
    previousMilliseconds: Double?,
    previousSampleCount: Int,
    previousCoverageSeconds: Double?,
    windowSeconds: Double?,
): FleetComparisonEvidenceStrength {
    val currentValid = currentMilliseconds?.let { it.isFinite() && it >= 0.0 } == true &&
        currentSampleCount > 0
    val previousValid = previousMilliseconds?.let { it.isFinite() && it >= 0.0 } == true &&
        previousSampleCount > 0
    if (!currentValid || !previousValid) return FleetComparisonEvidenceStrength.NONE
    if (minOf(currentSampleCount, previousSampleCount) < 2) {
        return FleetComparisonEvidenceStrength.LIMITED
    }
    val verifiedWindow = windowSeconds?.takeIf { it.isFinite() && it > 0.0 }
        ?: return FleetComparisonEvidenceStrength.LIMITED
    val currentCoverage = currentCoverageSeconds?.takeIf { it.isFinite() && it >= 0.0 }
        ?: return FleetComparisonEvidenceStrength.LIMITED
    val previousCoverage = previousCoverageSeconds?.takeIf { it.isFinite() && it >= 0.0 }
        ?: return FleetComparisonEvidenceStrength.LIMITED
    val minimumCoverageRatio = minOf(
        currentCoverage.coerceAtMost(verifiedWindow) / verifiedWindow,
        previousCoverage.coerceAtMost(verifiedWindow) / verifiedWindow,
    )
    if (minimumCoverageRatio < 0.25) return FleetComparisonEvidenceStrength.LIMITED
    if (minOf(currentSampleCount, previousSampleCount) < 4 || minimumCoverageRatio < 0.65) {
        return FleetComparisonEvidenceStrength.FAIR
    }
    return FleetComparisonEvidenceStrength.STRONG
}

internal fun comparisonDirection(
    currentMilliseconds: Double?,
    previousMilliseconds: Double?,
): FleetComparisonDirection {
    val current = currentMilliseconds.verifiedTiming()
    val previous = previousMilliseconds.verifiedTiming()
    if (current == null || previous == null) {
        return FleetComparisonDirection.NO_BASELINE
    }
    val tolerance = max(2.0, previous * 0.05)
    val delta = (current - previous).takeIf(Double::isFinite)
        ?: return FleetComparisonDirection.NO_BASELINE
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

private fun List<FleetComparisonRank>.sortedForComparison(
    ordering: FleetComparisonOrdering,
): List<FleetComparisonRank> = when (ordering) {
    FleetComparisonOrdering.SPEED -> sortedForComparison()
    FleetComparisonOrdering.CHANGE -> sortedWith(
        compareBy<FleetComparisonRank>(
            { changeOrderingBucket(it) },
            { it.deltaPercent ?: Double.MAX_VALUE },
            { it.deltaMilliseconds ?: Double.MAX_VALUE },
            { it.valueMilliseconds ?: Double.MAX_VALUE },
            { it.host.name.lowercase() },
            { it.host.id },
        ),
    )
}

private fun changeOrderingBucket(rank: FleetComparisonRank): Int = when {
    rank.isObserver -> 6
    rank.isPairedPeriodComparison && rank.direction == FleetComparisonDirection.FASTER -> 0
    rank.isPairedPeriodComparison && rank.direction == FleetComparisonDirection.STABLE -> 1
    rank.isPairedPeriodComparison && rank.direction == FleetComparisonDirection.SLOWER -> 2
    rank.valueMilliseconds != null -> 3
    rank.previousValueMilliseconds != null -> 4
    else -> 5
}

internal fun fleetComparisonSummary(ranks: List<FleetComparisonRank>): FleetComparisonSummary {
    val values = ranks.filterNot(FleetComparisonRank::isObserver)
        .mapNotNull(FleetComparisonRank::valueMilliseconds)
        .sorted()
    val median = when {
        values.isEmpty() -> null
        values.size % 2 == 1 -> values[values.size / 2]
        else -> values[values.size / 2 - 1].let { lower ->
            lower + (values[values.size / 2] - lower) / 2.0
        }
    }
    val materialMovers = ranks.filter { rank ->
        rank.isPairedPeriodComparison && rank.deltaMilliseconds?.isFinite() == true
    }
    val biggestImprovement = materialMovers
        .filter { it.direction == FleetComparisonDirection.FASTER }
        .minWithOrNull(materialImprovementComparator)
        ?.toMover()
    val slowdownCandidates = materialMovers.filter { it.direction == FleetComparisonDirection.SLOWER }
    val biggestSlowdown = (
        slowdownCandidates
            .filter { it.deltaPercent?.isFinite() == true }
            .minWithOrNull(materialSlowdownComparator)
            ?: slowdownCandidates
                .filter { it.previousValueMilliseconds == 0.0 }
                .minWithOrNull(zeroBaselineSlowdownComparator)
        )?.toMover()
    return FleetComparisonSummary(
        measuredCount = values.size,
        remoteCount = ranks.count { !it.isObserver },
        bestMilliseconds = values.firstOrNull(),
        medianMilliseconds = median,
        spreadMilliseconds = values.firstOrNull()?.let { best -> values.last() - best },
        comparableCount = ranks.count(FleetComparisonRank::isPairedPeriodComparison),
        fasterCount = ranks.count {
            it.isPairedPeriodComparison && it.direction == FleetComparisonDirection.FASTER
        },
        stableCount = ranks.count {
            it.isPairedPeriodComparison && it.direction == FleetComparisonDirection.STABLE
        },
        slowerCount = ranks.count {
            it.isPairedPeriodComparison && it.direction == FleetComparisonDirection.SLOWER
        },
        strongEvidenceCount = ranks.count {
            !it.isObserver && it.evidenceStrength == FleetComparisonEvidenceStrength.STRONG
        },
        fairEvidenceCount = ranks.count {
            !it.isObserver && it.evidenceStrength == FleetComparisonEvidenceStrength.FAIR
        },
        limitedEvidenceCount = ranks.count {
            !it.isObserver && it.evidenceStrength == FleetComparisonEvidenceStrength.LIMITED
        },
        unpairedCount = ranks.count {
            !it.isObserver && it.evidenceStrength == FleetComparisonEvidenceStrength.NONE
        },
        currentSampleCount = ranks.filterNot(FleetComparisonRank::isObserver)
            .saturatingSampleCountSum(FleetComparisonRank::sampleCount),
        previousSampleCount = ranks.filterNot(FleetComparisonRank::isObserver)
            .saturatingSampleCountSum(FleetComparisonRank::previousSampleCount),
        biggestImprovement = biggestImprovement,
        biggestSlowdown = biggestSlowdown,
    )
}

private fun List<FleetComparisonRank>.saturatingSampleCountSum(
    sampleCount: (FleetComparisonRank) -> Int?,
): Int = fold(0) { total, rank ->
    val count = sampleCount(rank)?.coerceAtLeast(0) ?: 0
    if (count > Int.MAX_VALUE - total) Int.MAX_VALUE else total + count
}

private val materialImprovementComparator = compareBy<FleetComparisonRank>(
    { it.deltaPercent ?: Double.MAX_VALUE },
    { it.deltaMilliseconds ?: Double.MAX_VALUE },
    { it.valueMilliseconds ?: Double.MAX_VALUE },
    { it.host.name.lowercase() },
    { it.host.id },
)

private val materialSlowdownComparator = compareByDescending<FleetComparisonRank> {
    it.deltaPercent ?: Double.MIN_VALUE
}.thenByDescending {
    it.deltaMilliseconds ?: Double.MIN_VALUE
}.thenBy {
    it.valueMilliseconds ?: Double.MAX_VALUE
}.thenBy {
    it.host.name.lowercase()
}.thenBy {
    it.host.id
}

private val zeroBaselineSlowdownComparator = compareByDescending<FleetComparisonRank> {
    it.deltaMilliseconds ?: Double.MIN_VALUE
}.thenBy {
    it.valueMilliseconds ?: Double.MAX_VALUE
}.thenBy {
    it.host.name.lowercase()
}.thenBy {
    it.host.id
}

private fun FleetComparisonRank.toMover(): FleetComparisonMover? {
    val delta = deltaMilliseconds ?: return null
    return FleetComparisonMover(
        hostId = host.id,
        hostName = host.name,
        deltaMilliseconds = delta,
        deltaPercent = deltaPercent,
        isNewDelay = previousValueMilliseconds == 0.0 && delta > 0.0,
        hasLimitedEvidence = evidenceStrength == FleetComparisonEvidenceStrength.LIMITED,
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
