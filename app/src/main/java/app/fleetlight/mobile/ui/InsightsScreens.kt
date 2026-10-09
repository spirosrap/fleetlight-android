package app.fleetlight.mobile.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fleetlight.mobile.data.*
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

internal enum class InsightMode(val label: String) {
    COMPARE("Compare"),
    TRENDS("Trends"),
}

@Composable
internal fun InsightsScreen(feed: MobileFeed?, trendsRequest: TrendsRequest? = null) {
    var selectedMode by rememberSaveable { mutableStateOf(InsightMode.COMPARE) }
    LaunchedEffect(trendsRequest) {
        if (trendsRequest != null) selectedMode = InsightMode.TRENDS
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            InsightMode.entries.forEach { mode ->
                FilterChip(
                    selected = selectedMode == mode,
                    onClick = { selectedMode = mode },
                    label = { Text(mode.label) },
                    leadingIcon = if (mode == InsightMode.COMPARE) {
                        { Icon(Icons.AutoMirrored.Outlined.CompareArrows, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else {
                        { Icon(Icons.AutoMirrored.Outlined.ShowChart, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    },
                )
            }
        }
        HorizontalDivider()
        Box(modifier = Modifier.weight(1f)) {
            when (selectedMode) {
                InsightMode.COMPARE -> ComparisonScreen(feed)
                InsightMode.TRENDS -> TrendsScreen(feed, trendsRequest)
            }
        }
    }
}

@Composable
internal fun ComparisonScreen(feed: MobileFeed?) {
    if (feed == null) {
        EmptyState(Icons.AutoMirrored.Outlined.CompareArrows, "No comparison data", "Connect a feed to compare live machine timing.")
        return
    }

    var selectedMetric by rememberSaveable { mutableStateOf(FleetComparisonMetric.PING) }
    var selectedWindow by rememberSaveable { mutableStateOf(FleetComparisonWindow.NOW) }
    var selectedOrdering by rememberSaveable { mutableStateOf(FleetComparisonOrdering.SPEED) }
    LaunchedEffect(selectedWindow) {
        if (selectedWindow == FleetComparisonWindow.NOW) {
            selectedOrdering = FleetComparisonOrdering.SPEED
        }
    }
    val effectiveOrdering = if (selectedWindow == FleetComparisonWindow.NOW) {
        FleetComparisonOrdering.SPEED
    } else {
        selectedOrdering
    }
    val ranks = remember(
        feed.hosts,
        feed.metrics,
        feed.timingComparisons,
        feed.generatedAt,
        feed.observer.id,
        selectedMetric,
        selectedWindow,
        effectiveOrdering,
    ) {
        if (selectedWindow == FleetComparisonWindow.NOW) {
            fleetComparisonRanks(feed.hosts, feed.observer.id, selectedMetric)
        } else {
            historicalFleetComparisonRanks(
                hosts = feed.hosts,
                observerId = feed.observer.id,
                metrics = feed.metrics,
                endAt = feed.generatedAt,
                window = selectedWindow,
                metric = selectedMetric,
                timingComparisons = feed.timingComparisons,
                ordering = effectiveOrdering,
            )
        }
    }
    val measuredRanks = remember(ranks) { ranks.filter { it.valueMilliseconds != null } }
    val positionedRanks = remember(ranks, effectiveOrdering) {
        if (effectiveOrdering == FleetComparisonOrdering.CHANGE) {
            ranks.filter(FleetComparisonRank::isPairedPeriodComparison)
        } else {
            measuredRanks
        }
    }
    val hasHistoricalEvidence = remember(ranks, selectedWindow) {
        selectedWindow != FleetComparisonWindow.NOW &&
            ranks.any { (it.previousSampleCount ?: 0) > 0 }
    }
    val summary = remember(ranks) { fleetComparisonSummary(ranks) }
    val sourceSummary = remember(ranks, selectedWindow, feed.metricsWindowHours) {
        fleetComparisonSourceSummary(ranks, selectedWindow, feed.metricsWindowHours)
    }
    val maximumValue = measuredRanks.maxOfOrNull { it.valueMilliseconds ?: 0.0 }?.coerceAtLeast(1.0) ?: 1.0
    val fastest = measuredRanks.minWithOrNull(
        compareBy<FleetComparisonRank>(
            { it.valueMilliseconds ?: Double.MAX_VALUE },
            { it.host.id },
        ),
    )

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeading(
                title = "Fleet comparison",
                subtitle = if (selectedWindow == FleetComparisonWindow.NOW) {
                    "Current feed timings ranked fastest to slowest"
                } else if (effectiveOrdering == FleetComparisonOrdering.CHANGE) {
                    "Most improved to most regressed vs the previous ${selectedWindow.label}"
                } else {
                    "Verified ${selectedWindow.label} averages ranked fastest to slowest"
                },
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(FleetComparisonMetric.entries) { metric ->
                    FilterChip(
                        selected = selectedMetric == metric,
                        onClick = { selectedMetric = metric },
                        label = { Text(metric.label) },
                    )
                }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(FleetComparisonWindow.entries) { window ->
                    FilterChip(
                        selected = selectedWindow == window,
                        onClick = { selectedWindow = window },
                        label = { Text(window.label) },
                    )
                }
            }
        }
        if (selectedWindow != FleetComparisonWindow.NOW) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Order",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FleetComparisonOrdering.entries.forEach { ordering ->
                        FilterChip(
                            selected = effectiveOrdering == ordering,
                            onClick = { selectedOrdering = ordering },
                            label = { Text(ordering.label) },
                        )
                    }
                }
            }
        }
        item {
            ComparisonSummaryCard(
                fastestName = fastest?.host?.name,
                summary = summary,
                observerExcluded = ranks.any(FleetComparisonRank::isObserver),
                observerIdentityReported = feed.observer.id.isNotBlank(),
                window = selectedWindow,
                sourceSummary = sourceSummary,
            )
        }
        if (measuredRanks.isEmpty() && !hasHistoricalEvidence) {
            item {
                InlineEmpty(
                    if (selectedWindow == FleetComparisonWindow.NOW) {
                        "No live ${selectedMetric.label.lowercase()} measurements yet. Reload after the controller finishes probing the fleet."
                    } else {
                        "No verified ${selectedMetric.label.lowercase()} history in ${selectedWindow.label} yet."
                    },
                )
            }
        } else {
            items(ranks, key = { it.host.id }) { rank ->
                ComparisonRankCard(
                    rank = rank,
                    metric = selectedMetric,
                    position = positionedRanks.indexOfFirst { it.host.id == rank.host.id }
                        .takeIf { it >= 0 }
                        ?.plus(1),
                    bestMilliseconds = summary.bestMilliseconds,
                    maximumMilliseconds = maximumValue,
                    window = selectedWindow,
                    ordering = effectiveOrdering,
                )
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
internal fun ComparisonSummaryCard(
    fastestName: String?,
    summary: FleetComparisonSummary,
    observerExcluded: Boolean,
    observerIdentityReported: Boolean,
    window: FleetComparisonWindow,
    sourceSummary: FleetComparisonSourceSummary,
) {
    FleetCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ComparisonStat("Fastest", fastestName ?: "—", Modifier.weight(1f))
                ComparisonStat("Typical", summary.medianMilliseconds?.let(::formatComparisonDuration) ?: "—", Modifier.weight(1f))
                ComparisonStat("Spread", summary.spreadMilliseconds?.let(::formatComparisonDuration) ?: "—", Modifier.weight(1f))
            }
            if (window == FleetComparisonWindow.NOW) {
                Text(
                    buildString {
                        append("${summary.measuredCount} of ${summary.remoteCount} ")
                        append(if (observerExcluded) "remote " else "")
                        append("machine${if (summary.remoteCount == 1) "" else "s"} measured")
                        append(observerEvidenceLabel(observerExcluded, observerIdentityReported))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "${summary.measuredCount} of ${summary.remoteCount} measured · ${summary.comparableCount} comparable",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Faster ${summary.fasterCount} · About same ${summary.stableCount} · Slower ${summary.slowerCount}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Evidence · Strong ${summary.strongEvidenceCount} · Fair ${summary.fairEvidenceCount} · " +
                        "Limited ${summary.limitedEvidenceCount} · Unpaired ${summary.unpairedCount}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Coverage is the time span from the first to last valid sample, not sample density.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                summary.biggestImprovement?.let { mover ->
                    ComparisonMoverCallout(
                        label = "Biggest improvement",
                        mover = mover,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                summary.biggestSlowdown?.let { mover ->
                    ComparisonMoverCallout(
                        label = "Biggest slowdown",
                        mover = mover,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    "Current ${summary.currentSampleCount} samples · previous ${summary.previousSampleCount} · " +
                        comparisonSourceLabel(sourceSummary) +
                        observerEvidenceLabel(observerExcluded, observerIdentityReported),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (sourceSummary.rawHistoryCoverageInsufficient) {
                    Text(
                        "${window.label} comparison needs ${sourceSummary.requiredHistoryHours}h of source history; " +
                            "${sourceSummary.reportedHistoryHours}h reported. " +
                            rawHistoryControllerAssurance(sourceSummary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (sourceSummary.rawHistoryCoverageUnknown) {
                    Text(
                        "Sampled-history coverage is not reported, so previous-period results may be incomplete. " +
                            rawHistoryControllerAssurance(sourceSummary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ComparisonMoverCallout(
    label: String,
    mover: FleetComparisonMover,
    color: Color,
) {
    val value = mover.let {
        val movement = if (it.isNewDelay) {
            "${it.hostName} · new delay +${formatComparisonDuration(it.deltaMilliseconds)}"
        } else {
            val direction = if (it.deltaMilliseconds < 0.0) "faster" else "slower"
            val percent = it.deltaPercent?.let { change -> "${formatDecimal(kotlin.math.abs(change))}% $direction · " }.orEmpty()
            "${it.hostName} · $percent${formatComparisonDuration(kotlin.math.abs(it.deltaMilliseconds))}"
        }
        movement + if (it.hasLimitedEvidence) " · Limited evidence" else ""
    }
    Surface(
        color = color.copy(alpha = 0.10f),
        contentColor = color,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        }
    }
}

internal fun observerEvidenceLabel(observerExcluded: Boolean, observerIdentityReported: Boolean): String = when {
    observerExcluded -> " · observer excluded"
    observerIdentityReported -> " · reported observer is not listed"
    else -> " · observer identity not reported"
}

internal fun comparisonSourceLabel(source: FleetComparisonSourceSummary): String = when {
    source.controllerHostCount > 0 && source.rawHistoryHostCount > 0 -> "controller + sampled history"
    source.controllerHostCount > 0 -> "controller aggregates"
    else -> "sampled history"
}

internal fun rawHistoryControllerAssurance(source: FleetComparisonSourceSummary): String =
    if (source.controllerHostCount > 0) {
        "Controller-backed machines remain exact."
    } else {
        "Use a current controller feed for exact comparisons."
    }

@Composable
internal fun ComparisonStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun ComparisonRankCard(
    rank: FleetComparisonRank,
    metric: FleetComparisonMetric,
    position: Int?,
    bestMilliseconds: Double?,
    maximumMilliseconds: Double,
    window: FleetComparisonWindow,
    ordering: FleetComparisonOrdering,
) {
    val color = when (metric) {
        FleetComparisonMetric.PING -> MaterialTheme.colorScheme.secondary
        FleetComparisonMetric.SSH_READY -> MaterialTheme.colorScheme.primary
        FleetComparisonMetric.CHECKS -> MaterialTheme.colorScheme.tertiary
        FleetComparisonMetric.FULL_PROBE -> MaterialTheme.colorScheme.error
    }
    FleetCard {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    position?.toString() ?: "—",
                    modifier = Modifier.width(24.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (position == 1) color else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(rank.host.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                val leaderLabel = when {
                    position != 1 -> null
                    ordering == FleetComparisonOrdering.SPEED -> "FASTEST"
                    rank.direction == FleetComparisonDirection.FASTER -> "MOST IMPROVED"
                    else -> null
                }
                if (leaderLabel != null) {
                    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(999.dp)) {
                        Text(
                            leaderLabel,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = color,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Text(comparisonValueLabel(rank, window), fontWeight = FontWeight.Bold, color = comparisonRankColor(rank))
                comparisonDelta(rank.valueMilliseconds, bestMilliseconds)
                    ?.takeIf { ordering == FleetComparisonOrdering.SPEED }
                    ?.let { delta ->
                        Spacer(Modifier.width(6.dp))
                        Text(delta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
            }
            rank.valueMilliseconds?.takeIf { ordering == FleetComparisonOrdering.SPEED }?.let { value ->
                LinearProgressIndicator(
                    progress = { (value / maximumMilliseconds).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = color,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
            if (window != FleetComparisonWindow.NOW) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (rank.direction != FleetComparisonDirection.NO_BASELINE) {
                        ComparisonDirectionBadge(rank)
                    }
                    ComparisonEvidenceBadge(rank.evidenceStrength)
                }
            }
            Text(
                comparisonDetail(rank, metric, window),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (window == FleetComparisonWindow.NOW) 2 else 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun ComparisonEvidenceBadge(strength: FleetComparisonEvidenceStrength) {
    val label = when (strength) {
        FleetComparisonEvidenceStrength.STRONG -> "Strong evidence"
        FleetComparisonEvidenceStrength.FAIR -> "Fair evidence"
        FleetComparisonEvidenceStrength.LIMITED -> "Limited evidence"
        FleetComparisonEvidenceStrength.NONE -> "Unpaired"
    }
    val color = when (strength) {
        FleetComparisonEvidenceStrength.STRONG -> MaterialTheme.colorScheme.primary
        FleetComparisonEvidenceStrength.FAIR -> MaterialTheme.colorScheme.tertiary
        FleetComparisonEvidenceStrength.LIMITED -> MaterialTheme.colorScheme.error
        FleetComparisonEvidenceStrength.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun comparisonRankColor(rank: FleetComparisonRank): Color = when {
    rank.isObserver -> MaterialTheme.colorScheme.onSurfaceVariant
    rank.sampleCount != null && rank.valueMilliseconds != null -> MaterialTheme.colorScheme.onSurface
    rank.host.state == HostState.OFFLINE -> MaterialTheme.colorScheme.error
    rank.valueMilliseconds == null -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.onSurface
}

internal fun comparisonValueLabel(rank: FleetComparisonRank, window: FleetComparisonWindow): String = when {
    rank.isObserver -> "Observer"
    rank.valueMilliseconds != null -> formatComparisonDuration(rank.valueMilliseconds)
    window != FleetComparisonWindow.NOW -> "No history"
    rank.host.state == HostState.OFFLINE -> "Offline"
    else -> "No data"
}

@Composable
internal fun ComparisonDirectionBadge(rank: FleetComparisonRank) {
    val color = when (rank.direction) {
        FleetComparisonDirection.FASTER -> MaterialTheme.colorScheme.primary
        FleetComparisonDirection.STABLE -> MaterialTheme.colorScheme.onSurfaceVariant
        FleetComparisonDirection.SLOWER -> MaterialTheme.colorScheme.error
        FleetComparisonDirection.NO_BASELINE -> return
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            comparisonDirectionLabel(rank),
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

internal fun comparisonDirectionLabel(rank: FleetComparisonRank): String {
    val direction = when (rank.direction) {
        FleetComparisonDirection.FASTER -> "Faster"
        FleetComparisonDirection.STABLE -> "About same"
        FleetComparisonDirection.SLOWER -> "Slower"
        FleetComparisonDirection.NO_BASELINE -> return "No baseline"
    }
    val delta = rank.deltaMilliseconds?.let(::formatSignedComparisonDuration) ?: return direction
    val percent = rank.deltaPercent?.let { " (${formatSignedDecimal(it)}%)" }.orEmpty()
    return "$direction · $delta$percent"
}

internal fun comparisonDelta(value: Double?, best: Double?): String? {
    if (value == null || best == null || value <= best) return null
    return "+${formatComparisonDuration(value - best)}"
}

internal fun comparisonDetail(
    rank: FleetComparisonRank,
    metric: FleetComparisonMetric,
    window: FleetComparisonWindow,
): String {
    if (rank.isObserver) return "Local process timing is not comparable with remote SSH machines"
    rank.sampleCount?.let { count ->
        val previousCount = rank.previousSampleCount ?: 0
        val current = rank.valueMilliseconds?.let(::formatComparisonDuration) ?: "no data"
        val previous = rank.previousValueMilliseconds?.let(::formatComparisonDuration) ?: "no data"
        val currentCoverage = comparisonCoverageLabel(rank.currentCoverageSeconds, rank.comparisonWindowSeconds)
        val previousCoverage = comparisonCoverageLabel(rank.previousCoverageSeconds, rank.comparisonWindowSeconds)
        val currentState = if (rank.host.state.isLiveForComparison()) {
            null
        } else {
            "currently ${rank.host.status.lowercase()}"
        }
        val currentLine = "Current $current · ${comparisonSampleLabel(count)} · $currentCoverage"
        val previousLine = listOfNotNull(
            "Previous $previous",
            comparisonSampleLabel(previousCount),
            previousCoverage,
            currentState,
        ).joinToString(" · ")
        return "$currentLine\n$previousLine"
    }
    if (!rank.host.state.isLiveForComparison()) return rank.host.detail ?: rank.host.status.replaceFirstChar(Char::uppercase)
    return when (metric) {
        FleetComparisonMetric.PING -> listOfNotNull(
            rank.host.jitterMs?.let { "jitter ${formatComparisonDuration(it)}" },
            rank.host.packetLossPercent?.let { "loss ${formatDecimal(it)}%" },
        ).joinToString(" · ").ifBlank { "Round-trip network time" }
        FleetComparisonMetric.SSH_READY -> rank.host.pingMs?.let { "ping ${formatComparisonDuration(it)}" }
            ?: "Time until the SSH connection is ready"
        FleetComparisonMetric.CHECKS -> "Remote metrics and service work after SSH is ready"
        FleetComparisonMetric.FULL_PROBE -> listOfNotNull(
            rank.host.sshReadyMs?.let { "SSH ${formatComparisonDuration(it)}" },
            FleetComparisonMetric.CHECKS.value(rank.host)?.let { "checks ${formatComparisonDuration(it)}" },
        ).joinToString(" + ").ifBlank { "Complete remote probe time" }
    }
}

internal fun comparisonSampleLabel(count: Int): String = "$count sample${if (count == 1) "" else "s"}"

internal fun comparisonCoverageLabel(coverageSeconds: Double?, windowSeconds: Double?): String {
    val window = windowSeconds?.takeIf { it.isFinite() && it > 0.0 } ?: return "coverage unknown"
    val coverage = coverageSeconds?.takeIf { it.isFinite() && it >= 0.0 } ?: return "coverage unknown"
    // Floor the displayed value so 24.99% never looks like it meets the 25% tier boundary.
    val percent = (coverage.coerceAtMost(window) / window * 100.0).toInt()
    return "$percent% coverage"
}

internal fun formatComparisonDuration(milliseconds: Double): String = when {
    milliseconds >= 1_000 -> "%.2f s".format(milliseconds / 1_000.0)
    else -> "${formatDecimal(milliseconds)} ms"
}

internal fun formatSignedComparisonDuration(milliseconds: Double): String {
    val sign = if (milliseconds >= 0.0) "+" else "−"
    return sign + formatComparisonDuration(kotlin.math.abs(milliseconds))
}

internal fun formatSignedDecimal(value: Double): String {
    val sign = if (value >= 0.0) "+" else "−"
    return sign + formatDecimal(kotlin.math.abs(value))
}

internal enum class TrendWindow(val hours: Int, val label: String) {
    ONE_HOUR(1, "1h"),
    SIX_HOURS(6, "6h"),
    TWENTY_FOUR_HOURS(24, "24h"),
}

internal fun trendMetrics(
    metrics: List<HostMetric>,
    hostId: String,
    endAt: Instant,
    window: TrendWindow,
): List<HostMetric> {
    val startAt = endAt.minus(Duration.ofHours(window.hours.toLong()))
    return metrics.asSequence()
        .filter { it.hostId == hostId }
        .filter { !it.capturedAt.isBefore(startAt) && !it.capturedAt.isAfter(endAt) }
        .sortedBy(HostMetric::capturedAt)
        .distinctBy(HostMetric::capturedAt)
        .toList()
}

internal fun averageTrendValue(
    metrics: List<HostMetric>,
    value: (HostMetric) -> Double?,
): Double? {
    val values = metrics.mapNotNull(value)
    return values.takeIf { it.isNotEmpty() }?.average()
}

internal fun trendCoveragePercent(
    samples: List<HostMetric>,
    window: TrendWindow,
    sampleIntervalSeconds: Int?,
): Int? {
    val interval = sampleIntervalSeconds?.takeIf { it > 0 } ?: return null
    val expected = (Duration.ofHours(window.hours.toLong()).seconds / interval).toInt() + 1
    if (expected <= 0) return null
    return ((samples.size * 100.0) / expected).roundToInt().coerceIn(0, 100)
}

internal fun trendGapThresholdSeconds(
    window: TrendWindow,
    sampleIntervalSeconds: Int?,
): Long = sampleIntervalSeconds
    ?.takeIf { it > 0 }
    ?.toLong()
    ?.times(3)
    ?.coerceAtLeast(90L)
    ?: maxOf(45 * 60L, Duration.ofHours(window.hours.toLong()).seconds / 6)

internal fun formatTrendDuration(seconds: Long): String = when {
    seconds < 60 -> "${seconds.coerceAtLeast(0)} sec"
    seconds < 3_600 -> "${seconds / 60} min"
    else -> "${seconds / 3_600}h ${((seconds % 3_600) / 60).toString().padStart(2, '0')}m"
}

internal fun nearestTrendMetric(samples: List<HostMetric>, target: Instant): HostMetric? {
    if (samples.isEmpty()) return null
    if (!target.isAfter(samples.first().capturedAt)) return samples.first()
    if (!target.isBefore(samples.last().capturedAt)) return samples.last()

    var lower = 0
    var upper = samples.lastIndex
    while (lower <= upper) {
        val middle = (lower + upper) ushr 1
        val timestamp = samples[middle].capturedAt
        when {
            timestamp == target -> return samples[middle]
            timestamp.isBefore(target) -> lower = middle + 1
            else -> upper = middle - 1
        }
    }
    val earlier = samples[upper]
    val later = samples[lower]
    val earlierDistance = Duration.between(earlier.capturedAt, target).toMillis()
    val laterDistance = Duration.between(target, later.capturedAt).toMillis()
    return if (earlierDistance <= laterDistance) earlier else later
}

internal data class TrendDefinition(
    val label: String,
    val color: Color,
    val value: (HostMetric) -> Double?,
)

@Composable
internal fun TrendsScreen(feed: MobileFeed?, trendsRequest: TrendsRequest? = null) {
    if (feed == null) {
        EmptyState(Icons.AutoMirrored.Outlined.ShowChart, "No trend data", "Connect a feed to view machine history.")
        return
    }

    val metricHostIDs = remember(feed.metrics) { feed.metrics.map(HostMetric::hostId).toSet() }
    val hostsWithMetrics = remember(feed.hosts, metricHostIDs) {
        prioritizedFleetHosts(feed.hosts.filter { it.id in metricHostIDs })
    }
    if (hostsWithMetrics.isEmpty()) {
        EmptyState(
            Icons.AutoMirrored.Outlined.ShowChart,
            "History is still collecting",
            "Fleetlight will show trends after the observer publishes metric samples.",
        )
        return
    }

    var selectedHostID by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedWindow by rememberSaveable { mutableStateOf(TrendWindow.SIX_HOURS) }
    var selectedTimestampMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(trendsRequest) {
        if (trendsRequest != null && hostsWithMetrics.any { it.id == trendsRequest.hostId }) {
            selectedHostID = trendsRequest.hostId
            selectedTimestampMillis = null
        }
    }
    val selectedHost = hostsWithMetrics.firstOrNull { it.id == selectedHostID } ?: hostsWithMetrics.first()
    val samples = remember(feed.metrics, selectedHost.id, feed.generatedAt, selectedWindow) {
        trendMetrics(feed.metrics, selectedHost.id, feed.generatedAt, selectedWindow)
    }
    val selectedSample = remember(samples, selectedTimestampMillis) {
        selectedTimestampMillis
            ?.let(Instant::ofEpochMilli)
            ?.let { nearestTrendMetric(samples, it) }
            ?: samples.lastOrNull()
    }
    val coverage = feed.metricsWindowHours?.let { "Source history: ${it}h" } ?: "Source history window not reported"
    val cadence = feed.metricsSampleIntervalSeconds?.let { "every ${formatTrendDuration(it.toLong())}" } ?: "cadence not reported"

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeading(
                title = "Trends",
                subtitle = "$coverage · $cadence · ${feed.metrics.size} fleet samples",
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(hostsWithMetrics, key = FleetHost::id) { host ->
                    FilterChip(
                        selected = host.id == selectedHost.id,
                        onClick = {
                            selectedHostID = host.id
                            selectedTimestampMillis = null
                        },
                        label = { Text(host.name, maxLines = 1) },
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TrendWindow.entries.forEach { window ->
                    FilterChip(
                        selected = selectedWindow == window,
                        onClick = {
                            selectedWindow = window
                            selectedTimestampMillis = null
                        },
                        label = { Text(window.label) },
                    )
                }
            }
        }
        item {
            TrendSummary(
                hostName = selectedHost.name,
                samples = samples,
                window = selectedWindow,
                endAt = feed.generatedAt,
                sampleIntervalSeconds = feed.metricsSampleIntervalSeconds,
            )
        }
        selectedSample?.let { sample ->
            item { TrendSelectionReadout(sample) }
        }
        item {
            TrendChartCard(
                title = "Network quality",
                subtitle = "Ping RTT and jitter",
                samples = samples,
                endAt = feed.generatedAt,
                window = selectedWindow,
                sampleIntervalSeconds = feed.metricsSampleIntervalSeconds,
                selectedSample = selectedSample,
                onSelectTimestamp = { selectedTimestampMillis = it.toEpochMilli() },
                definitions = listOf(
                    TrendDefinition("Ping", MaterialTheme.colorScheme.secondary) { it.pingMs },
                    TrendDefinition("Jitter", MaterialTheme.colorScheme.primary) { it.jitterMs },
                ),
                unit = "ms",
            )
        }
        item {
            TrendChartCard(
                title = "Connection timing",
                subtitle = "SSH ready versus the complete probe",
                samples = samples,
                endAt = feed.generatedAt,
                window = selectedWindow,
                sampleIntervalSeconds = feed.metricsSampleIntervalSeconds,
                selectedSample = selectedSample,
                onSelectTimestamp = { selectedTimestampMillis = it.toEpochMilli() },
                definitions = listOf(
                    TrendDefinition("SSH ready", MaterialTheme.colorScheme.primary) { it.sshReadyMs },
                    TrendDefinition("Full probe", MaterialTheme.colorScheme.tertiary) { it.fullProbeMs },
                ),
                unit = "ms",
            )
        }
        item {
            TrendChartCard(
                title = "Resource usage",
                subtitle = "Disk and memory used",
                samples = samples,
                endAt = feed.generatedAt,
                window = selectedWindow,
                sampleIntervalSeconds = feed.metricsSampleIntervalSeconds,
                selectedSample = selectedSample,
                onSelectTimestamp = { selectedTimestampMillis = it.toEpochMilli() },
                definitions = listOf(
                    TrendDefinition("Disk", MaterialTheme.colorScheme.tertiary) { it.diskPercent },
                    TrendDefinition("Memory", MaterialTheme.colorScheme.primary) { it.memoryPercent },
                ),
                unit = "%",
                fixedMaximum = 100.0,
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
internal fun TrendSummary(
    hostName: String,
    samples: List<HostMetric>,
    window: TrendWindow,
    endAt: Instant,
    sampleIntervalSeconds: Int?,
) {
    val ping = averageTrendValue(samples, HostMetric::pingMs)
    val ready = averageTrendValue(samples, HostMetric::sshReadyMs)
    val loss = averageTrendValue(samples, HostMetric::packetLossPercent)
    val coverage = trendCoveragePercent(samples, window, sampleIntervalSeconds)
    val freshness = samples.lastOrNull()?.let { Duration.between(it.capturedAt, endAt).seconds.coerceAtLeast(0) }
    FleetCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(hostName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                buildString {
                    append("${samples.size} ordered sample${if (samples.size == 1) "" else "s"} in ${window.label}")
                    coverage?.let { append(" · $it% coverage") }
                    freshness?.let { append(" · latest ${formatTrendDuration(it)} ago") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TrendValueChip("Avg ping", ping?.let { "${formatDecimal(it)} ms" } ?: "—")
                TrendValueChip("Avg ready", ready?.let { "${formatDecimal(it)} ms" } ?: "—")
                TrendValueChip("Avg loss", loss?.let { "${formatDecimal(it)}%" } ?: "—")
            }
        }
    }
}

@Composable
internal fun TrendValueChip(label: String, value: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun TrendSelectionReadout(sample: HostMetric) {
    FleetCard(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Selected check", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    DateTimeFormatter.ofPattern("MMM d, HH:mm:ss").withZone(ZoneId.systemDefault()).format(sample.capturedAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                sample.state.replaceFirstChar(Char::uppercase),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                sample.pingMs?.let { TrendValueChip("Ping", "${formatDecimal(it)} ms") }
                sample.jitterMs?.let { TrendValueChip("Jitter", "${formatDecimal(it)} ms") }
                sample.packetLossPercent?.let { TrendValueChip("Loss", "${formatDecimal(it)}%") }
                sample.sshReadyMs?.let { TrendValueChip("SSH ready", "${formatDecimal(it)} ms") }
                sample.fullProbeMs?.let { TrendValueChip("Full probe", "${formatDecimal(it)} ms") }
                sample.diskPercent?.let { TrendValueChip("Disk", "${formatDecimal(it)}%") }
                sample.memoryPercent?.let { TrendValueChip("Memory", "${formatDecimal(it)}%") }
            }
        }
    }
}

@Composable
internal fun TrendChartCard(
    title: String,
    subtitle: String,
    samples: List<HostMetric>,
    endAt: Instant,
    window: TrendWindow,
    sampleIntervalSeconds: Int?,
    selectedSample: HostMetric?,
    onSelectTimestamp: (Instant) -> Unit,
    definitions: List<TrendDefinition>,
    unit: String,
    fixedMaximum: Double? = null,
) {
    val values = definitions.flatMap { definition -> samples.mapNotNull(definition.value) }
    val maximum = fixedMaximum ?: values.maxOrNull()?.let { (it * 1.12).coerceAtLeast(1.0) }
    val startAt = endAt.minus(Duration.ofHours(window.hours.toLong()))
    val totalSeconds = Duration.between(startAt, endAt).seconds.coerceAtLeast(1)
    val gapThresholdSeconds = trendGapThresholdSeconds(window, sampleIntervalSeconds)
    val selectionColor = MaterialTheme.colorScheme.onSurface
    val chartModifier = Modifier
        .fillMaxWidth()
        .height(150.dp)
        .pointerInput(startAt, endAt, onSelectTimestamp) {
            fun selectAt(horizontalPosition: Float) {
                if (size.width <= 0) return
                val fraction = (horizontalPosition / size.width.toFloat()).coerceIn(0f, 1f)
                val selectedSeconds = (totalSeconds * fraction).toLong()
                onSelectTimestamp(startAt.plusSeconds(selectedSeconds))
            }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                selectAt(down.position.x)
                do {
                    val event = awaitPointerEvent()
                    event.changes.firstOrNull()?.let { change ->
                        selectAt(change.position.x)
                        change.consume()
                    }
                } while (event.changes.any { it.pressed })
            }
        }

    FleetCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                maximum?.let {
                    Text("Scale ${formatDecimal(it)} $unit", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (maximum == null) {
                Text(
                    "No values in this window",
                    modifier = Modifier.padding(vertical = 44.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val gridColor = MaterialTheme.colorScheme.outlineVariant
                Canvas(modifier = chartModifier) {
                    val top = 8f
                    val bottom = size.height - 8f
                    repeat(4) { index ->
                        val y = top + (bottom - top) * index / 3f
                        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    }
                    definitions.forEach { definition ->
                        var previousPoint: Offset? = null
                        var previousAt: Instant? = null
                        samples.forEach { sample ->
                            val value = definition.value(sample)
                            if (value == null) {
                                previousPoint = null
                                previousAt = null
                            } else {
                                val elapsed = Duration.between(startAt, sample.capturedAt).seconds
                                val x = (elapsed.toDouble() / totalSeconds.toDouble()).coerceIn(0.0, 1.0).toFloat() * size.width
                                val yFraction = (value / maximum).coerceIn(0.0, 1.0).toFloat()
                                val point = Offset(x, bottom - yFraction * (bottom - top))
                                val previousTimestamp = previousAt
                                if (previousPoint != null && previousTimestamp != null &&
                                    Duration.between(previousTimestamp, sample.capturedAt).seconds <= gapThresholdSeconds
                                ) {
                                    drawLine(
                                        definition.color,
                                        previousPoint!!,
                                        point,
                                        strokeWidth = 4f,
                                        cap = StrokeCap.Round,
                                    )
                                }
                                val isSelected = sample.capturedAt == selectedSample?.capturedAt
                                drawCircle(definition.color, radius = if (isSelected) 6f else 3.5f, center = point)
                                previousPoint = point
                                previousAt = sample.capturedAt
                            }
                        }
                    }
                    selectedSample?.let { selected ->
                        val elapsed = Duration.between(startAt, selected.capturedAt).seconds
                        val x = (elapsed.toDouble() / totalSeconds.toDouble()).coerceIn(0.0, 1.0).toFloat() * size.width
                        drawLine(
                            selectionColor.copy(alpha = 0.55f),
                            Offset(x, top),
                            Offset(x, bottom),
                            strokeWidth = 2f,
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(dateTime(startAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(dateTime(endAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Tap or drag across any chart to inspect the nearest recorded check.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                definitions.forEach { definition ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = definition.color, shape = CircleShape, modifier = Modifier.size(8.dp)) {}
                        Spacer(Modifier.width(5.dp))
                        Text(definition.label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
