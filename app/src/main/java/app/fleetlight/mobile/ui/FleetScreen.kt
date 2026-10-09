package app.fleetlight.mobile.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fleetlight.mobile.data.CodexDesktopAppState
import app.fleetlight.mobile.data.ControlAction
import app.fleetlight.mobile.data.FleetHost
import app.fleetlight.mobile.data.FleetSummary
import app.fleetlight.mobile.data.HostState
import app.fleetlight.mobile.data.ObserverDisagreement
import app.fleetlight.mobile.data.eligibleFor
import java.time.Instant
import kotlin.math.roundToInt

internal enum class FleetFilter(val label: String) {
    ALL("All"),
    ISSUES("Issues"),
    PINNED("Pinned"),
    MACOS("macOS"),
    LINUX("Linux"),
}

internal enum class FleetSort(val label: String) {
    PRIORITY("Priority"),
    NAME("Name"),
    LATENCY("Latency"),
    HEALTH("Health"),
}

internal val FleetHost.hasIssue: Boolean
    get() = state != HostState.ONLINE || issueTypes.isNotEmpty() || restartRequired || warnings.isNotEmpty()

internal val FleetHost.isMacOS: Boolean
    get() = platform.contains("mac", ignoreCase = true) || platform.contains("darwin", ignoreCase = true)

internal val FleetHost.isLinux: Boolean
    get() = platform.contains("linux", ignoreCase = true)

internal fun FleetFilter.matches(host: FleetHost): Boolean = when (this) {
    FleetFilter.ALL -> true
    FleetFilter.ISSUES -> host.hasIssue
    FleetFilter.PINNED -> host.isPinned
    FleetFilter.MACOS -> host.isMacOS
    FleetFilter.LINUX -> host.isLinux
}

internal fun hostMatchesQuery(host: FleetHost, query: String): Boolean {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return true
    return host.name.lowercase().contains(needle) ||
        host.platform.lowercase().contains(needle) ||
        host.operatingSystem?.lowercase()?.contains(needle) == true ||
        host.status.lowercase().contains(needle) ||
        host.issueTypes.any { it.lowercase().contains(needle) }
}

/** Search, filter and sort the fleet. Priority keeps the pinned-first, issues-first order. */
internal fun filterFleetHosts(
    hosts: List<FleetHost>,
    query: String = "",
    filter: FleetFilter = FleetFilter.ALL,
    sort: FleetSort = FleetSort.PRIORITY,
): List<FleetHost> {
    val filtered = hosts.filter { filter.matches(it) && hostMatchesQuery(it, query) }
    return when (sort) {
        FleetSort.PRIORITY -> prioritizedFleetHosts(filtered)
        FleetSort.NAME -> filtered.sortedBy { it.name.lowercase() }
        FleetSort.LATENCY -> filtered.sortedWith(
            compareBy<FleetHost>({ it.pingMs == null }, { it.pingMs ?: 0.0 }, { it.name.lowercase() }),
        )
        FleetSort.HEALTH -> filtered.sortedWith(
            compareBy<FleetHost>({ it.health == null }, { it.health ?: 0 }, { it.name.lowercase() }),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FleetScreen(
    state: FleetUiState,
    onHostClick: (FleetHost) -> Unit,
    onRecheckHosts: (List<String>) -> Unit,
    onRefresh: () -> Unit,
) {
    val feed = state.feed
    if (feed == null) {
        EmptyState(
            icon = Icons.Outlined.Shield,
            title = "Ready for your fleet",
            message = "Add one or more HTTPS mobile-feed endpoints in Settings. Pair a controller separately when you want to initiate updates.",
        )
        return
    }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(FleetFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(FleetSort.PRIORITY) }
    val visibleHosts = remember(feed.hosts, query, filter, sort) { filterFleetHosts(feed.hosts, query, filter, sort) }
    val filterCounts = remember(feed.hosts) {
        FleetFilter.entries.associateWith { candidate -> feed.hosts.count { candidate.matches(it) } }
    }
    val capabilities = state.controlStatus?.capabilities.orEmpty()
    val recheckTargets = capabilities.filter { ControlAction.REFRESH_HOSTS in it.actions }
    val recheckCapabilities = capabilities.associateBy { it.hostId }
    val recheckRunning = state.activeJob?.let {
        it.action == ControlAction.REFRESH_HOSTS && !it.state.isTerminal
    } == true

    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                FleetOverviewCard(
                    summary = feed.summary,
                    generatedAt = feed.generatedAt,
                    recheckAvailable = recheckTargets.isNotEmpty(),
                    recheckEnabled = state.controlJobReady && recheckTargets.isNotEmpty(),
                    recheckRunning = recheckRunning,
                    onRecheckFleet = { onRecheckHosts(recheckTargets.map { it.hostId }) },
                )
            }
            state.controlError?.let { message ->
                item { ControlMessageCard(message, error = true) }
            }
            if (state.observerDisagreements.isNotEmpty()) {
                item {
                    val names = state.observerDisagreements.values.flatten().map { it.observerName }.distinct().joinToString(" and ")
                    ControlMessageCard(
                        "${state.observerDisagreements.size} machine${if (state.observerDisagreements.size == 1) "" else "s"} " +
                            "reported differently by $names than by ${feed.observer.name}. " +
                            "Usually the two observers' machine lists have drifted apart.",
                    )
                }
            }
            item {
                FleetToolbar(
                    query = query,
                    onQueryChange = { query = it },
                    filter = filter,
                    onFilterChange = { filter = it },
                    filterCounts = filterCounts,
                    sort = sort,
                    onSortChange = { sort = it },
                    visibleCount = visibleHosts.size,
                    totalCount = feed.hosts.size,
                )
            }
            if (visibleHosts.isEmpty()) {
                item {
                    InlineEmpty(
                        if (feed.hosts.isEmpty()) "No machines in this feed" else "No machines match the current search or filter",
                    )
                }
            } else {
                items(visibleHosts, key = FleetHost::id) { host ->
                    val capability = recheckCapabilities[host.id]
                    HostCard(
                        host = host,
                        onClick = onHostClick,
                        supportsRecheck = capability?.actions?.contains(ControlAction.REFRESH_HOSTS) == true,
                        recheckEnabled = state.controlJobReady && capability?.eligibleFor(ControlAction.REFRESH_HOSTS) == true,
                        onRecheck = { onRecheckHosts(listOf(host.id)) },
                        disagreements = state.observerDisagreements[host.id].orEmpty(),
                    )
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
internal fun FleetOverviewCard(
    summary: FleetSummary,
    generatedAt: Instant,
    recheckAvailable: Boolean,
    recheckEnabled: Boolean,
    recheckRunning: Boolean,
    onRecheckFleet: () -> Unit,
) {
    val healthy = summary.issueCount == 0
    val broken = summary.offline > 0 || summary.accessIssues > 0 || summary.alerts > 0
    val accent = when {
        healthy -> MaterialTheme.colorScheme.secondary
        broken -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.tertiary
    }
    val onlineFraction = if (summary.total > 0) summary.online.toFloat() / summary.total.toFloat() else 0f
    FleetCard {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Overline("Fleet status", color = accent)
                    Text(
                        when {
                            healthy -> "Everything is healthy"
                            summary.issueCount == 1 -> "1 signal needs attention"
                            else -> "${summary.issueCount} signals need attention"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${summary.online} of ${summary.total} online · snapshot ${relativeTime(generatedAt)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                HealthRing(fraction = onlineFraction, color = accent, modifier = Modifier.size(76.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "${summary.online}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = accent,
                        )
                        Text(
                            "of ${summary.total}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OverviewStat("Online", summary.online, MaterialTheme.colorScheme.secondary)
                if (summary.offline > 0) OverviewStat("Offline", summary.offline, MaterialTheme.colorScheme.error)
                if (summary.slowConnections > 0) OverviewStat("Slow", summary.slowConnections, MaterialTheme.colorScheme.tertiary)
                if (summary.accessIssues > 0) OverviewStat("Access", summary.accessIssues, MaterialTheme.colorScheme.error)
                if (summary.alerts > 0) OverviewStat("Alerts", summary.alerts, MaterialTheme.colorScheme.error)
                if (summary.updatesAvailable > 0) OverviewStat("Updates", summary.updatesAvailable, MaterialTheme.colorScheme.primary)
                if (summary.restartRequired > 0) OverviewStat("Restart", summary.restartRequired, MaterialTheme.colorScheme.tertiary)
                if (healthy) OverviewStat("Issues", 0, MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (recheckAvailable) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Run fresh read-only probes on every machine",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    FilledTonalButton(onClick = onRecheckFleet, enabled = recheckEnabled) {
                        if (recheckRunning) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(if (recheckRunning) "Rechecking…" else "Recheck fleet")
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewStat(label: String, count: Int, color: Color) {
    Surface(color = color.copy(alpha = 0.11f), shape = MaterialTheme.shapes.small) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text("$count", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FleetToolbar(
    query: String,
    onQueryChange: (String) -> Unit,
    filter: FleetFilter,
    onFilterChange: (FleetFilter) -> Unit,
    filterCounts: Map<FleetFilter, Int>,
    sort: FleetSort,
    onSortChange: (FleetSort) -> Unit,
    visibleCount: Int,
    totalCount: Int,
) {
    var sortMenu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search machines") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Clear search")
                    }
                }
            } else {
                null
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FleetFilter.entries.forEach { candidate ->
                    val count = filterCounts[candidate] ?: 0
                    if (candidate == FleetFilter.ALL || count > 0) {
                        FilterChip(
                            selected = filter == candidate,
                            onClick = { onFilterChange(candidate) },
                            label = { Text(if (candidate == FleetFilter.ALL) candidate.label else "${candidate.label} $count") },
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Box {
                TextButton(onClick = { sortMenu = true }) {
                    Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(sort.label)
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    FleetSort.entries.forEach { candidate ->
                        DropdownMenuItem(
                            text = { Text(candidate.label) },
                            trailingIcon = if (candidate == sort) {
                                { Icon(Icons.Outlined.Check, contentDescription = null) }
                            } else {
                                null
                            },
                            onClick = {
                                sortMenu = false
                                onSortChange(candidate)
                            },
                        )
                    }
                }
            }
        }
        Text(
            when {
                visibleCount == totalCount -> "$totalCount machine${if (totalCount == 1) "" else "s"}"
                else -> "$visibleCount of $totalCount machines"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HostCard(
    host: FleetHost,
    onClick: (FleetHost) -> Unit,
    supportsRecheck: Boolean,
    recheckEnabled: Boolean,
    onRecheck: () -> Unit,
    disagreements: List<ObserverDisagreement> = emptyList(),
) {
    val color = stateColor(host.state)
    FleetCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        onClick = { onClick(host) },
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .background(color),
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp, top = 14.dp, end = 6.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HostAvatar(host, size = 42.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            host.name,
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (host.isPinned) {
                            Spacer(Modifier.width(5.dp))
                            Icon(
                                Icons.Outlined.PushPin,
                                contentDescription = "Pinned machine",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        StatusPill(stateLabel(host), color)
                    }
                    Text(
                        buildList {
                            add(host.platformLabel)
                            host.pingMs?.let { add("${it.roundToInt()} ms") }
                            host.health?.let { add("Health $it") }
                        }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val hasMetrics = host.diskPercent != null || host.memoryPercent != null || host.loadAverage != null || host.restartRequired
                    if (hasMetrics) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            host.diskPercent?.let { MiniMetric("Disk", "${it.roundToInt()}%", usageColor(it)) }
                            host.memoryPercent?.let { MiniMetric("Mem", "${it.roundToInt()}%", usageColor(it)) }
                            host.loadAverage?.let { MiniMetric("Load", formatDecimal(it)) }
                            if (host.restartRequired) MiniMetric("Restart", "required", MaterialTheme.colorScheme.tertiary)
                        }
                    }
                    val issueLine = (host.issueTypes + listOfNotNull(host.detail)).distinct().joinToString(" · ")
                    if (issueLine.isNotBlank()) {
                        Text(
                            issueLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (host.state == HostState.ONLINE) MaterialTheme.colorScheme.onSurfaceVariant else color,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (disagreements.isNotEmpty()) {
                        StatusPill(
                            "Observers disagree · ${disagreements.joinToString { "${it.observerName}: ${it.view.status.replaceFirstChar(Char::uppercase)}" }}",
                            MaterialTheme.colorScheme.tertiary,
                            icon = Icons.Outlined.WarningAmber,
                        )
                    }
                }
                if (supportsRecheck) {
                    IconButton(onClick = onRecheck, enabled = recheckEnabled) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Recheck ${host.name}")
                    }
                }
                Icon(Icons.Outlined.ChevronRight, contentDescription = "Details", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun HostDetail(
    host: FleetHost,
    supportsRecheck: Boolean,
    recheckEnabled: Boolean,
    onRecheck: () -> Unit,
    hasTrends: Boolean,
    onShowTrends: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
    disagreements: List<ObserverDisagreement> = emptyList(),
    chosenObserverName: String? = null,
) {
    val color = stateColor(host.state)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HostAvatar(host, size = 56.dp)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        host.name,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (host.isPinned) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Outlined.PushPin,
                            contentDescription = "Pinned machine",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(stateLabel(host), color, filled = true)
                    Text(
                        listOfNotNull(host.platformLabel, host.distinctOperatingSystem).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        host.detail?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (supportsRecheck) {
                FilledTonalButton(onClick = onRecheck, enabled = recheckEnabled, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Recheck")
                }
            }
            if (hasTrends) {
                OutlinedButton(onClick = onShowTrends, modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Outlined.ShowChart, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Trends")
                }
            }
            OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Share")
            }
        }
        if (supportsRecheck) {
            Text(
                "Recheck is read-only: it refreshes this machine's status without installing updates or restarting it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (disagreements.isNotEmpty()) {
            DetailSection("Observers disagree") {
                KeyValueRow(chosenObserverName ?: "Shown", stateLabel(host), valueColor = color)
                disagreements.forEach { other ->
                    KeyValueRow(
                        other.observerName,
                        listOfNotNull(
                            other.view.status.replaceFirstChar(Char::uppercase),
                            other.view.issueTypes.joinToString(" · ").ifBlank { null },
                            other.view.detail,
                        ).joinToString(" · "),
                        valueColor = stateColor(other.view.state),
                    )
                }
                Text(
                    "Each observer probes the machine on its own. A difference usually means their machine lists are not identical.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (host.issueTypes.isNotEmpty()) {
            DetailSection("Signals") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    host.issueTypes.forEach { StatusPill(it, MaterialTheme.colorScheme.error, icon = Icons.Outlined.WarningAmber) }
                }
            }
        }
        val connectivity = buildList {
            host.pingMs?.let { add(Triple("Ping", "${it.roundToInt()} ms", null)) }
            host.jitterMs?.let { add(Triple("Jitter", "${formatDecimal(it)} ms", null)) }
            host.packetLossPercent?.let { add(Triple("Packet loss", "${formatDecimal(it)}%", null)) }
            host.sshReadyMs?.let { add(Triple("SSH ready", "${it.roundToInt()} ms", null)) }
            host.fullProbeMs?.let { add(Triple("Full probe", "${it.roundToInt()} ms", null)) }
            host.health?.let { add(Triple("Health", "$it", "score")) }
        }
        if (connectivity.isNotEmpty()) {
            DetailSection("Connectivity") { TileGrid(connectivity) }
        }
        val hasResources = host.diskPercent != null || host.memoryPercent != null || host.loadAverage != null || host.bootDescription != null
        if (hasResources) {
            DetailSection("Resources") {
                host.diskPercent?.let { UsageBar("Disk used", it) }
                host.memoryPercent?.let { UsageBar("Memory used", it) }
                val tiles = buildList {
                    host.loadAverage?.let { add(Triple("Load average", formatDecimal(it), null)) }
                    host.bootDescription?.let { add(Triple("Boot", it, null)) }
                }
                if (tiles.isNotEmpty()) TileGrid(tiles)
            }
        }
        val software = buildList {
            host.operatingSystem?.let { add("System" to it) }
            host.codexCliVersion?.let { add("Codex CLI" to it) }
            host.fleetlightVersion?.let { add("Fleetlight" to it) }
            host.codexDesktopAppVersion
                ?.takeUnless { host.effectiveDesktopAppState == CodexDesktopAppState.MISSING }
                ?.let { version ->
                    add(
                        "ChatGPT Desktop App" to listOfNotNull(
                            host.desktopAppPlatformLabel,
                            version,
                            host.codexDesktopAppBuild?.let { "build $it" },
                        ).joinToString(" · "),
                    )
                }
            host.desktopAppProviderLabel?.let { add("Desktop app provider" to it) }
            when (host.effectiveDesktopAppState) {
                CodexDesktopAppState.UPDATE_AVAILABLE -> add(
                    "Desktop app update" to (host.codexDesktopAppAvailableVersion?.let { "$it available" } ?: "Available"),
                )
                CodexDesktopAppState.CURRENT -> add("Desktop app update" to "Current")
                CodexDesktopAppState.MISSING -> add("Desktop app update" to "Not installed")
                CodexDesktopAppState.OFFLINE -> add("Desktop app update" to "Offline")
                CodexDesktopAppState.UNAVAILABLE -> add("Desktop app update" to "Unavailable")
                null -> if (host.hasDesktopAppMetadata) add("Desktop app update" to "Check required")
            }
            host.codexDesktopAppCheckedAt?.let { add("Desktop app checked" to dateTime(it)) }
            if (host.restartRequired) add("Restart" to "Required")
        }
        if (software.isNotEmpty()) {
            DetailSection("Software") {
                software.forEach { (label, value) ->
                    KeyValueRow(
                        label,
                        value,
                        valueColor = if (label == "Restart") MaterialTheme.colorScheme.tertiary else Color.Unspecified,
                    )
                }
            }
        }
        if (host.services.isNotEmpty()) {
            DetailSection("Services") {
                host.services.forEach { service ->
                    val serviceColor = serviceStateColor(service.state)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .background(serviceColor, CircleShape),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(service.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            service.detail?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(
                            service.state.replaceFirstChar(Char::uppercase),
                            style = MaterialTheme.typography.labelMedium,
                            color = serviceColor,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
        if (host.warnings.isNotEmpty()) {
            DetailSection("Warnings") {
                host.warnings.forEach { warning ->
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(warning.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            warning.detail?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        host.checkedAt?.let {
            Text(
                "Checked ${dateTime(it)} · ${relativeTime(it)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun serviceStateColor(state: String): Color = when (state.trim().lowercase()) {
    "running", "active", "ok", "online", "healthy", "up", "enabled" -> MaterialTheme.colorScheme.secondary
    "stopped", "failed", "error", "inactive", "down", "dead" -> MaterialTheme.colorScheme.error
    "degraded", "warning", "restarting", "activating" -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Overline(title)
        content()
    }
}

@Composable
private fun TileGrid(tiles: List<Triple<String, String, String?>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (label, value, hint) ->
                    MetricTile(label = label, value = value, hint = hint, modifier = Modifier.weight(1f))
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
