package app.fleetlight.mobile.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fleetlight.mobile.data.ControlAction
import app.fleetlight.mobile.data.FeedObserver
import app.fleetlight.mobile.data.FleetHost
import app.fleetlight.mobile.data.FleetIncident
import app.fleetlight.mobile.data.FleetSummary
import app.fleetlight.mobile.data.HostState
import app.fleetlight.mobile.data.LinuxUpdate
import app.fleetlight.mobile.data.MobileFeed
import app.fleetlight.mobile.data.PendingControlAction
import app.fleetlight.mobile.data.confirmationCopy
import app.fleetlight.mobile.data.eligibleFor
import app.fleetlight.mobile.ui.theme.AppearanceSettings
import app.fleetlight.mobile.ui.theme.FleetlightTheme
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private enum class AppTab(val label: String, val icon: ImageVector) {
    FLEET("Fleet", Icons.Outlined.Computer),
    TRENDS("Insights", Icons.AutoMirrored.Outlined.ShowChart),
    UPDATES("Updates", Icons.Outlined.SystemUpdateAlt),
    EVENTS("Events", Icons.Outlined.Event),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

/** A request to open the Trends view focused on one machine. The nonce makes repeat requests observable. */
internal data class TrendsRequest(val hostId: String, val nonce: Long)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FleetlightApp(
    viewModel: FleetlightViewModel,
    appearance: AppearanceSettings = AppearanceSettings(),
    onAppearanceChange: ((AppearanceSettings) -> AppearanceSettings) -> Unit = {},
    fleetView: FleetViewSettings = FleetViewSettings(),
    onFleetViewChange: ((FleetViewSettings) -> FleetViewSettings) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    FleetlightContent(
        state = state,
        appearance = appearance,
        onAppearanceChange = onAppearanceChange,
        fleetView = fleetView,
        onFleetViewChange = onFleetViewChange,
        onRefresh = viewModel::refreshNow,
        onRecheckHosts = viewModel::recheckHosts,
        onCheckForUpdates = viewModel::checkForUpdates,
        onSaveEndpoints = viewModel::saveEndpoints,
        onConfirmPendingEndpoints = viewModel::confirmPendingEndpoints,
        onDismissPendingEndpoints = viewModel::dismissPendingEndpoints,
        onStagePairing = viewModel::stagePairing,
        onConfirmPairing = viewModel::confirmPendingPairing,
        onDismissPairing = viewModel::dismissPendingPairing,
        onRevokeControl = viewModel::revokeControl,
        onRequestUpdate = viewModel::requestUpdate,
        onConfirmUpdate = viewModel::confirmPendingUpdate,
        onDismissUpdate = viewModel::dismissPendingUpdate,
        onDismissJob = viewModel::dismissFinishedJob,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FleetlightContent(
    state: FleetUiState,
    onRefresh: () -> Unit,
    onRecheckHosts: (List<String>) -> Unit,
    onCheckForUpdates: () -> Unit,
    onSaveEndpoints: (List<String>) -> Unit,
    onConfirmPendingEndpoints: () -> Unit,
    onDismissPendingEndpoints: () -> Unit,
    onStagePairing: (String, String) -> Unit,
    onConfirmPairing: () -> Unit,
    onDismissPairing: () -> Unit,
    onRevokeControl: () -> Unit,
    onRequestUpdate: (ControlAction, List<String>) -> Unit,
    onConfirmUpdate: () -> Unit,
    onDismissUpdate: () -> Unit,
    onDismissJob: () -> Unit,
    appearance: AppearanceSettings = AppearanceSettings(),
    onAppearanceChange: ((AppearanceSettings) -> AppearanceSettings) -> Unit = {},
    fleetView: FleetViewSettings = FleetViewSettings(),
    onFleetViewChange: ((FleetViewSettings) -> FleetViewSettings) -> Unit = {},
) {
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.FLEET) }
    var selectedHostId by rememberSaveable { mutableStateOf<String?>(null) }
    var trendsRequest by remember { mutableStateOf<TrendsRequest?>(null) }
    val context = LocalContext.current
    // Resolve the sheet's machine from the live feed so it keeps updating while open.
    val selectedHost = selectedHostId?.let { id -> state.feed?.hosts?.firstOrNull { it.id == id } }

    Scaffold(
        topBar = {
            FleetlightTopBar(
                state = state,
                onRefresh = onRefresh,
                onShare = { shareText(context, fleetStatusSummary(state), "Fleet status") },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
            }
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            state.banner?.let { ConnectionBanner(state.connection, it) }
            when (selectedTab) {
                AppTab.FLEET -> FleetScreen(
                    state = state,
                    onHostClick = { selectedHostId = it.id },
                    onRecheckHosts = onRecheckHosts,
                    onRefresh = onRefresh,
                    fleetView = fleetView,
                    onFleetViewChange = onFleetViewChange,
                )
                AppTab.TRENDS -> InsightsScreen(state.feed, trendsRequest)
                AppTab.UPDATES -> UpdatesScreen(state, onCheckForUpdates, onRequestUpdate, onDismissJob)
                AppTab.EVENTS -> EventsScreen(state.feed)
                AppTab.SETTINGS -> SettingsScreen(
                    state = state,
                    appearance = appearance,
                    onAppearanceChange = onAppearanceChange,
                    onSaveEndpoints = onSaveEndpoints,
                    onStagePairing = onStagePairing,
                    onForgetControl = onRevokeControl,
                )
            }
        }
    }

    selectedHost?.let { host ->
        ModalBottomSheet(
            onDismissRequest = { selectedHostId = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            val capability = state.controlStatus?.capabilities?.firstOrNull { it.hostId == host.id }
            HostDetail(
                host = host,
                supportsRecheck = capability?.actions?.contains(ControlAction.REFRESH_HOSTS) == true,
                recheckEnabled = state.controlJobReady && capability?.eligibleFor(ControlAction.REFRESH_HOSTS) == true,
                onRecheck = { onRecheckHosts(listOf(host.id)) },
                hasTrends = state.feed?.metrics?.any { it.hostId == host.id } == true,
                onShowTrends = {
                    trendsRequest = TrendsRequest(host.id, System.nanoTime())
                    selectedHostId = null
                    selectedTab = AppTab.TRENDS
                },
                onShare = { shareText(context, hostSummary(host), host.name) },
                disagreements = state.observerDisagreements[host.id].orEmpty(),
                chosenObserverName = state.feed?.observer?.name,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }

    if (state.pendingEndpoints.isNotEmpty()) {
        EndpointConfirmationDialog(
            endpoints = state.pendingEndpoints,
            onConfirm = onConfirmPendingEndpoints,
            onDismiss = onDismissPendingEndpoints,
        )
    }
    state.pendingPairing?.let { pairing ->
        PairingConfirmationDialog(pairing.endpoint, onConfirmPairing, onDismissPairing)
    }
    state.pendingControlAction?.let { pending ->
        ControlConfirmationDialog(pending, onConfirmUpdate, onDismissUpdate)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FleetlightTopBar(state: FleetUiState, onRefresh: () -> Unit, onShare: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Fleetlight", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    ConnectionPill(state.connection)
                }
                Text(
                    text = observerSubtitle(state),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        actions = {
            IconButton(onClick = onRefresh, enabled = !state.refreshing) {
                if (state.refreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Reload status snapshot")
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Share fleet status") },
                        leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                        enabled = state.feed != null,
                        onClick = {
                            menuOpen = false
                            onShare()
                        },
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

@Composable
private fun ConnectionPill(connection: FeedConnection) {
    val (label, color) = when (connection) {
        FeedConnection.LIVE -> "Live" to MaterialTheme.colorScheme.secondary
        FeedConnection.CACHED -> "Cached" to MaterialTheme.colorScheme.tertiary
        FeedConnection.ERROR -> "Offline" to MaterialTheme.colorScheme.error
        FeedConnection.EMPTY -> return
    }
    Surface(color = color.copy(alpha = 0.13f), contentColor = color, shape = RoundedCornerShape(999.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(color, CircleShape),
            )
            Spacer(Modifier.width(5.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun EndpointConfirmationDialog(
    endpoints: List<String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Fleetlight observer${if (endpoints.size == 1) "" else "s"}?") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("This link wants Fleetlight to contact the following HTTPS endpoint${if (endpoints.size == 1) "" else "s"} and display its fleet data:")
                endpoints.forEach { endpoint ->
                    Text(endpoint, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                }
                Text("Only continue if you trust the source.")
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Add & refresh") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PairingConfirmationDialog(endpoint: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair update controls?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Fleetlight will exchange the one-time code with this observer:")
                Text(endpoint, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                Text("The phone receives a scoped control token. SSH keys and administrator credentials remain on the observer Mac.")
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Pair securely") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ControlConfirmationDialog(
    pending: PendingControlAction,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val copy = pending.confirmationCopy()
    val destructive = pending.action == ControlAction.RESTART_LINUX
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                copy.title,
                color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
        },
        text = {
            Text(
                copy.description,
                modifier = Modifier.verticalScroll(rememberScrollState()),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = if (destructive) {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.textButtonColors()
                },
            ) {
                Text(copy.confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ConnectionBanner(connection: FeedConnection, text: String) {
    val colors = when (connection) {
        FeedConnection.CACHED -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        FeedConnection.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.first)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (connection == FeedConnection.ERROR) Icons.Outlined.WarningAmber else Icons.Outlined.CloudOff,
            contentDescription = null,
            tint = colors.second,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(text, color = colors.second, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------------------------------------------------------------------------
// Shared helpers used by several screens
// ---------------------------------------------------------------------------

@Composable
internal fun SummaryChip(label: String, count: Int, color: Color) {
    Surface(color = color, shape = RoundedCornerShape(999.dp)) {
        Text(
            "$count $label",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun EmptyState(icon: ImageVector, title: String, message: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(icon, contentDescription = null, modifier = Modifier.padding(18.dp).size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun InlineEmpty(text: String) {
    FleetCard {
        Text(
            text,
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
internal fun StatusOrb(healthy: Boolean, modifier: Modifier, state: HostState = if (healthy) HostState.ONLINE else HostState.ATTENTION) {
    val color = stateColor(state)
    Surface(modifier = modifier, shape = CircleShape, color = color.copy(alpha = 0.16f)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (healthy) Icons.Outlined.CheckCircle else if (state == HostState.OFFLINE) Icons.Outlined.CloudOff else Icons.Outlined.Speed,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

private fun hostPriority(host: FleetHost): Int = when (host.state) {
    HostState.OFFLINE -> 0
    HostState.ACCESS -> 1
    HostState.ATTENTION -> 2
    HostState.SLOW -> 3
    HostState.UNKNOWN -> 4
    HostState.ONLINE -> if (host.issueTypes.isEmpty()) 6 else 5
}

/** Issues first, then name. Observer pins are not used on the phone; a saved custom order replaces them. */
internal fun prioritizedFleetHosts(hosts: List<FleetHost>): List<FleetHost> = hosts.sortedWith(
    compareBy<FleetHost>(
        ::hostPriority,
        { it.name.lowercase() },
    ),
)

private fun observerSubtitle(state: FleetUiState): String {
    val feed = state.feed ?: return "Secure fleet companion"
    val snapshot = "snapshot ${relativeTime(feed.generatedAt)}"
    val observer = feed.observer.appVersion?.let { "${feed.observer.name} $it" } ?: feed.observer.name
    return "$observer · $snapshot"
}

internal fun relativeTime(instant: Instant, now: Instant = Instant.now()): String {
    val seconds = Duration.between(instant, now).seconds.coerceAtLeast(0)
    return when {
        seconds < 20 -> "now"
        seconds < 60 -> "${seconds}s ago"
        seconds < 3_600 -> "${seconds / 60}m ago"
        seconds < 86_400 -> "${seconds / 3_600}h ago"
        else -> "${seconds / 86_400}d ago"
    }
}

internal fun dateTime(instant: Instant): String = DateTimeFormatter.ofPattern("MMM d, HH:mm")
    .withZone(ZoneId.systemDefault())
    .format(instant)

internal fun formatDecimal(value: Double): String = if (value == value.roundToInt().toDouble()) {
    value.roundToInt().toString()
} else {
    "%.1f".format(value)
}

// ---------------------------------------------------------------------------
// Sharing
// ---------------------------------------------------------------------------

internal fun shareText(context: Context, text: String?, title: String) {
    if (text.isNullOrBlank()) return
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "Fleetlight · $title")
        .putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(intent, title))
}

private fun hostMarker(host: FleetHost): String = when (host.state) {
    HostState.ONLINE -> if (host.issueTypes.isEmpty()) "OK" else "NOTE"
    HostState.SLOW -> "SLOW"
    HostState.OFFLINE -> "OFFLINE"
    HostState.ACCESS -> "ACCESS"
    HostState.ATTENTION -> "ALERT"
    HostState.UNKNOWN -> "UNKNOWN"
}

/** Plain-text fleet summary for the share sheet. Returns null when no feed is loaded. */
internal fun fleetStatusSummary(state: FleetUiState, now: Instant = Instant.now()): String? {
    val feed = state.feed ?: return null
    val summary = feed.summary
    return buildString {
        appendLine("Fleetlight · ${feed.observer.name}")
        appendLine("Snapshot ${dateTime(feed.generatedAt)} (${relativeTime(feed.generatedAt, now)})")
        appendLine(
            if (summary.issueCount == 0) {
                "All ${summary.total} machines healthy"
            } else {
                "${summary.online} of ${summary.total} online · ${summary.issueCount} signal${if (summary.issueCount == 1) "" else "s"} need attention"
            },
        )
        appendLine()
        prioritizedFleetHosts(feed.hosts).forEach { host ->
            val extras = buildList {
                host.pingMs?.let { add("${it.roundToInt()} ms") }
                host.health?.let { add("health $it") }
                addAll(host.issueTypes)
                host.detail?.let(::add)
            }
            append("[${hostMarker(host)}] ${host.name} · ${host.platformLabel}")
            if (extras.isNotEmpty()) append(" · ").append(extras.distinct().joinToString(" · "))
            appendLine()
        }
    }.trimEnd()
}

/** Plain-text detail for one machine, for the share sheet. */
internal fun hostSummary(host: FleetHost): String = buildString {
    appendLine("${host.name} · ${host.platformLabel} · ${stateLabel(host)}")
    host.detail?.let(::appendLine)
    val rows = buildList {
        host.operatingSystem?.let { add("System: $it") }
        host.health?.let { add("Health: $it") }
        host.pingMs?.let { add("Ping: ${it.roundToInt()} ms") }
        host.jitterMs?.let { add("Jitter: ${formatDecimal(it)} ms") }
        host.packetLossPercent?.let { add("Packet loss: ${formatDecimal(it)}%") }
        host.sshReadyMs?.let { add("SSH ready: ${it.roundToInt()} ms") }
        host.fullProbeMs?.let { add("Full probe: ${it.roundToInt()} ms") }
        host.diskPercent?.let { add("Disk used: ${formatDecimal(it)}%") }
        host.memoryPercent?.let { add("Memory used: ${formatDecimal(it)}%") }
        host.loadAverage?.let { add("Load average: ${formatDecimal(it)}") }
        host.bootDescription?.let { add("Boot: $it") }
        host.codexCliVersion?.let { add("Codex CLI: $it") }
        host.codexDesktopAppVersion?.let { add("ChatGPT Desktop App: $it") }
        host.fleetlightVersion?.let { add("Fleetlight: $it") }
        if (host.restartRequired) add("Restart: required")
        if (host.issueTypes.isNotEmpty()) add("Signals: ${host.issueTypes.joinToString(", ")}")
        if (host.services.isNotEmpty()) add("Services: ${host.services.joinToString { "${it.name} ${it.state}" }}")
        if (host.warnings.isNotEmpty()) add("Warnings: ${host.warnings.joinToString { it.title }}")
        host.checkedAt?.let { add("Checked: ${dateTime(it)}") }
    }
    rows.forEach(::appendLine)
}.trimEnd()

@Preview(showBackground = true, widthDp = 412, heightDp = 860)
@Composable
private fun FleetPreview() {
    FleetlightTheme(dynamicColor = false) {
        FleetlightContent(
            state = FleetUiState(feed = DemoFeed.value, connection = FeedConnection.LIVE),
            onRefresh = {},
            onRecheckHosts = { _ -> },
            onCheckForUpdates = {},
            onSaveEndpoints = {},
            onConfirmPendingEndpoints = {},
            onDismissPendingEndpoints = {},
            onStagePairing = { _, _ -> },
            onConfirmPairing = {},
            onDismissPairing = {},
            onRevokeControl = {},
            onRequestUpdate = { _, _ -> },
            onConfirmUpdate = {},
            onDismissUpdate = {},
            onDismissJob = {},
        )
    }
}

private object DemoFeed {
    private val generatedAt = Instant.parse("2026-01-15T12:00:00Z")
    val value = MobileFeed(
        schemaVersion = 1,
        generatedAt = generatedAt,
        observer = FeedObserver(name = "Primary observer", appVersion = "1.0"),
        summary = FleetSummary(total = 3, online = 2, offline = 1, slowConnections = 1, updatesAvailable = 1),
        hosts = listOf(
            FleetHost("workstation", "Design Workstation", "macOS", HostState.ONLINE, "online", pingMs = 8.0, health = 100, diskPercent = 41.0, memoryPercent = 62.0),
            FleetHost("server", "Media Server", "Linux", HostState.SLOW, "slow", issueTypes = listOf("High latency"), pingMs = 74.0, health = 91, diskPercent = 88.0),
            FleetHost("lab", "Lab Computer", "Linux", HostState.OFFLINE, "offline", detail = "Last reachable 18 minutes ago"),
        ),
        linuxUpdates = listOf(LinuxUpdate("server", "Media Server", "updates available", availableCount = 4)),
        incidents = listOf(FleetIncident("sample", "lab", "Lab Computer", "availability", "warning", "Machine went offline", startedAt = generatedAt)),
        metrics = emptyList(),
    )
}
