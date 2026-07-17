package app.fleetlight.mobile.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fleetlight.mobile.BuildConfig
import app.fleetlight.mobile.data.EndpointPolicy
import app.fleetlight.mobile.data.ControlAction
import app.fleetlight.mobile.data.ControlCapability
import app.fleetlight.mobile.data.ControlCheckState
import app.fleetlight.mobile.data.ControlEndpointPolicy
import app.fleetlight.mobile.data.ControlJob
import app.fleetlight.mobile.data.ControlJobState
import app.fleetlight.mobile.data.FeedObserver
import app.fleetlight.mobile.data.FleetHost
import app.fleetlight.mobile.data.FleetIncident
import app.fleetlight.mobile.data.FleetSummary
import app.fleetlight.mobile.data.HostState
import app.fleetlight.mobile.data.LinuxUpdate
import app.fleetlight.mobile.data.MobileFeed
import app.fleetlight.mobile.data.PendingControlAction
import app.fleetlight.mobile.data.confirmationCopy
import app.fleetlight.mobile.data.commandReachable
import app.fleetlight.mobile.data.eligibleFor
import app.fleetlight.mobile.data.safeHostName
import app.fleetlight.mobile.data.updateAvailable
import app.fleetlight.mobile.ui.theme.FleetlightTheme
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private enum class AppTab(val label: String, val icon: ImageVector) {
    FLEET("Fleet", Icons.Outlined.Computer),
    UPDATES("Updates", Icons.Outlined.SystemUpdateAlt),
    EVENTS("Events", Icons.Outlined.Event),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FleetlightApp(viewModel: FleetlightViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    FleetlightContent(
        state = state,
        onRefresh = viewModel::refreshNow,
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
) {
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.FLEET) }
    var selectedHost by remember { mutableStateOf<FleetHost?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Fleetlight", fontWeight = FontWeight.Bold)
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
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh fleet snapshot")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            NavigationBar {
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
                AppTab.FLEET -> FleetScreen(state.feed, onHostClick = { selectedHost = it })
                AppTab.UPDATES -> UpdatesScreen(state, onCheckForUpdates, onRequestUpdate, onDismissJob)
                AppTab.EVENTS -> EventsScreen(state.feed)
                AppTab.SETTINGS -> SettingsScreen(state, onSaveEndpoints, onStagePairing, onRevokeControl)
            }
        }
    }

    selectedHost?.let { host ->
        ModalBottomSheet(onDismissRequest = { selectedHost = null }) {
            HostDetail(
                host,
                Modifier
                    .padding(horizontal = 24.dp, vertical = 8.dp)
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

@Composable
private fun FleetScreen(feed: MobileFeed?, onHostClick: (FleetHost) -> Unit) {
    if (feed == null) {
        EmptyState(
            icon = Icons.Outlined.Shield,
            title = "Ready for your fleet",
            message = "Add one or more HTTPS mobile-feed endpoints in Settings. Pair a controller separately when you want to initiate updates.",
        )
        return
    }
    val sortedHosts = remember(feed.hosts) {
        feed.hosts.sortedWith(compareBy<FleetHost>({ hostPriority(it) }, { it.name.lowercase() }))
    }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SummarySection(feed.summary, feed.generatedAt) }
        item {
            SectionHeading(
                title = "Machines",
                subtitle = if (feed.summary.issueCount == 0) "All clear" else "Issues first",
            )
        }
        items(sortedHosts, key = FleetHost::id) { host -> HostCard(host, onHostClick) }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun SummarySection(summary: FleetSummary, generatedAt: Instant) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    if (summary.issueCount == 0) "Fleet is healthy" else "${summary.issueCount} signals need attention",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${summary.online} online of ${summary.total} · ${relativeTime(generatedAt)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusOrb(healthy = summary.issueCount == 0, Modifier.size(44.dp))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryChip("Online", summary.online, MaterialTheme.colorScheme.secondaryContainer)
            if (summary.offline > 0) SummaryChip("Offline", summary.offline, MaterialTheme.colorScheme.errorContainer)
            if (summary.slowConnections > 0) SummaryChip("Slow", summary.slowConnections, MaterialTheme.colorScheme.tertiaryContainer)
            if (summary.accessIssues > 0) SummaryChip("Access", summary.accessIssues, MaterialTheme.colorScheme.errorContainer)
            if (summary.alerts > 0) SummaryChip("Alerts", summary.alerts, MaterialTheme.colorScheme.errorContainer)
            if (summary.updatesAvailable > 0) SummaryChip("Updates", summary.updatesAvailable, MaterialTheme.colorScheme.primaryContainer)
            if (summary.restartRequired > 0) SummaryChip("Restart", summary.restartRequired, MaterialTheme.colorScheme.tertiaryContainer)
        }
    }
}

@Composable
private fun SummaryChip(label: String, count: Int, color: Color) {
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
private fun HostCard(host: FleetHost, onClick: (FleetHost) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { onClick(host) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusOrb(healthy = host.state == HostState.ONLINE, Modifier.size(38.dp), state = host.state)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(host.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        host.status.replaceFirstChar(Char::uppercase),
                        style = MaterialTheme.typography.labelMedium,
                        color = stateColor(host.state),
                    )
                }
                Text(
                    buildList {
                        add(host.platform)
                        host.pingMs?.let { add("${it.roundToInt()} ms") }
                        host.health?.let { add("Health $it") }
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val issueLine = (host.issueTypes + listOfNotNull(host.detail)).distinct().joinToString(" · ")
                if (issueLine.isNotBlank()) {
                    Text(
                        issueLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (host.state == HostState.ONLINE) MaterialTheme.colorScheme.onSurfaceVariant else stateColor(host.state),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = "Details", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HostDetail(host: FleetHost, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusOrb(healthy = host.state == HostState.ONLINE, Modifier.size(48.dp), host.state)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(host.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "${host.platform} · ${host.status.replaceFirstChar(Char::uppercase)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        host.detail?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        HorizontalDivider()
        DetailGrid(host)
        if (host.issueTypes.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Signals", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    host.issueTypes.forEach { SummaryChip(it, 1, MaterialTheme.colorScheme.errorContainer) }
                }
            }
        }
        host.checkedAt?.let {
            Text("Checked ${dateTime(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DetailGrid(host: FleetHost) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        host.health?.let { DetailRow("Health", "$it") }
        host.pingMs?.let { DetailRow("Ping", "${it.roundToInt()} ms") }
        host.jitterMs?.let { DetailRow("Jitter", "${formatDecimal(it)} ms") }
        host.packetLossPercent?.let { DetailRow("Packet loss", "${formatDecimal(it)}%") }
        host.sshReadyMs?.let { DetailRow("SSH ready", "${it.roundToInt()} ms") }
        host.fullProbeMs?.let { DetailRow("Full probe", "${it.roundToInt()} ms") }
        host.operatingSystem?.let { DetailRow("System", it) }
        host.codexCliVersion?.let { DetailRow("Codex CLI", it) }
        host.fleetlightVersion?.let { DetailRow("Fleetlight", it) }
        host.diskPercent?.let { DetailRow("Disk used", "${formatDecimal(it)}%") }
        host.memoryPercent?.let { DetailRow("Memory used", "${formatDecimal(it)}%") }
        host.loadAverage?.let { DetailRow("Load average", formatDecimal(it)) }
        host.bootDescription?.let { DetailRow("Boot", it) }
        if (host.restartRequired) DetailRow("Restart", "Required")
        host.codexMacAppVersion?.let { version ->
            DetailRow("Codex Mac app", listOfNotNull(version, host.codexMacAppBuild?.let { "build $it" }).joinToString(" · "))
        }
        if (host.services.isNotEmpty()) {
            DetailRow("Services", host.services.joinToString { "${it.name}: ${it.state}" })
        }
        if (host.warnings.isNotEmpty()) DetailRow("Warnings", host.warnings.joinToString(" · ") { it.title })
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun UpdatesScreen(
    state: FleetUiState,
    onCheckForUpdates: () -> Unit,
    onRequestUpdate: (ControlAction, List<String>) -> Unit,
    onDismissJob: () -> Unit,
) {
    val feed = state.updatesFeed
    if (feed == null) {
        EmptyState(Icons.Outlined.SystemUpdateAlt, "No update data", "Connect a feed to view fleet update status.")
        return
    }
    val ready = state.connection == FeedConnection.LIVE &&
        state.controlStatus?.commandAuthorityEnabled == true &&
        state.controlStatus.jobJournalAvailable &&
        state.controlStatus.busy.not() &&
        state.controlStatus.checkingUpdates.not() &&
        !state.updateCheckSubmitting &&
        !state.checkSyncPending &&
        state.activeCheck?.state?.isTerminal != false &&
        state.activeJob?.state?.isTerminal != false
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { UpdateCheckCard(state, onCheckForUpdates) }
        state.activeJob?.let { job ->
            item { JobProgressCard(job, state.jobError, onDismissJob) }
        }
        if (state.activeJob == null && state.jobError != null) {
            item { ControlMessageCard(state.jobError, error = true) }
        }
        if (state.controlStatus == null) {
            item {
                ControlMessageCard(
                    if (state.controlChecking) "Checking paired update controller…" else "Pair an observer in Settings to initiate updates. Status remains available without pairing.",
                )
            }
        } else if (!state.controlStatus.commandAuthorityEnabled) {
            item { ControlMessageCard("Remote commands are disabled on the paired observer.") }
        } else if (!state.controlStatus.jobJournalAvailable) {
            item { ControlMessageCard("The paired controller cannot durably record jobs, so updates are disabled.", error = true) }
        } else if (state.connection != FeedConnection.LIVE) {
            item { ControlMessageCard("Updates are disabled until a live fleet snapshot is available.") }
        }
        ControlAction.entries.filter { it.isUpdate }.forEach { action ->
            item {
                UpdateActionSection(
                    action = action,
                    feed = feed,
                    capabilities = state.controlStatus?.capabilities.orEmpty(),
                    enabled = ready,
                    onRequestUpdate = onRequestUpdate,
                )
            }
        }
        item {
            RestartActionSection(
                feed = feed,
                capabilities = state.controlStatus?.capabilities.orEmpty(),
                enabled = ready,
                onRequestRestart = onRequestUpdate,
            )
        }
        item {
            SectionHeading(
                title = "Linux package status",
                subtitle = "Latest snapshot from ${feed.observer.name}",
            )
        }
        val updates = feed.linuxUpdates.sortedWith(compareByDescending<LinuxUpdate> { it.availableCount }.thenBy { it.hostName.lowercase() })
        if (updates.isEmpty()) {
            item { InlineEmpty("No Linux machines in this feed") }
        } else {
            items(updates, key = LinuxUpdate::hostId) { update -> UpdateCard(update) }
        }
    }
}

@Composable
private fun UpdateCheckCard(state: FleetUiState, onCheckForUpdates: () -> Unit) {
    val status = state.controlStatus
    val localCheck = state.activeCheck
    val running = state.updateCheckSubmitting || localCheck?.state?.isTerminal == false || status?.checkingUpdates == true
    val canCheck = state.connection == FeedConnection.LIVE &&
        status?.commandAuthorityEnabled == true &&
        !running &&
        !status.busy &&
        state.activeJob?.state?.isTerminal != false
    val resultTone = when (localCheck?.state) {
        ControlCheckState.FAILED, ControlCheckState.PARTIAL, ControlCheckState.CANCELLED ->
            MaterialTheme.colorScheme.errorContainer
        ControlCheckState.SUCCEEDED -> MaterialTheme.colorScheme.secondaryContainer
        ControlCheckState.QUEUED, ControlCheckState.RUNNING -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    Card(colors = CardDefaults.cardColors(containerColor = resultTone)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Available versions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            state.updateCheckSubmitting -> "Starting a live check…"
                            localCheck?.state == ControlCheckState.QUEUED -> "Live check queued${localCheck.phase.asPhaseSuffix()}"
                            localCheck?.state == ControlCheckState.RUNNING -> "Checking${localCheck.phase.asPhaseSuffix()}"
                            status?.checkingUpdates == true -> "Controller is checking…"
                            localCheck?.state == ControlCheckState.SUCCEEDED -> "Live check complete"
                            localCheck?.state == ControlCheckState.PARTIAL -> "Check complete with some failures"
                            localCheck?.state == ControlCheckState.FAILED -> "Live check failed"
                            localCheck?.state == ControlCheckState.CANCELLED -> "Live check cancelled"
                            else -> "Run a live controller check; the top refresh only reloads the fleet snapshot"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(onClick = onCheckForUpdates, enabled = canCheck) {
                    if (running) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (running) "Checking…" else "Check for updates")
                }
            }
            VersionCheckRow(
                title = "Codex CLI",
                latest = releaseVersionLabel(
                    version = status?.latestCodexCliVersion,
                    failed = status?.codexCliCheckFailed == true,
                ),
                checkedAt = status?.codexCliCheckedAt,
                failed = status?.codexCliCheckFailed == true,
            )
            VersionCheckRow(
                title = "Codex Mac app",
                latest = releaseVersionLabel(
                    version = status?.latestCodexMacAppVersion,
                    build = status?.latestCodexMacAppBuild,
                    failed = status?.codexMacAppCheckFailed == true,
                ),
                checkedAt = status?.codexMacAppCheckedAt,
                failed = status?.codexMacAppCheckFailed == true,
            )
            val linuxSummary = linuxCheckPresentation(state.updatesFeed?.linuxUpdates.orEmpty())
            VersionCheckRow(
                title = "Linux packages",
                latest = linuxSummary.countLabel,
                checkedAt = linuxSummary.oldestCheckedAt,
                failed = linuxSummary.incomplete,
                failureLabel = "Check incomplete",
            )
            localCheck?.detail?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            state.updateCheckError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun VersionCheckRow(
    title: String,
    latest: String?,
    checkedAt: Instant?,
    failed: Boolean,
    failureLabel: String = "Check failed",
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                latest ?: if (failed) "Latest version unavailable" else "Latest version not checked",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            when {
                checkedAt == null -> if (failed) failureLabel else "Not checked"
                failed -> "Last attempt ${relativeTime(checkedAt)}"
                else -> "Checked ${relativeTime(checkedAt)}"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun String?.asPhaseSuffix(): String = this?.trim()?.takeIf(String::isNotEmpty)?.let { " · ${it.replaceFirstChar(Char::uppercase)}" }.orEmpty()

internal data class LinuxCheckPresentation(
    val checkedCount: Int,
    val totalCount: Int,
    val incomplete: Boolean,
    val oldestCheckedAt: Instant?,
) {
    val countLabel: String?
        get() = if (totalCount == 0) null else
            "$checkedCount of $totalCount machine${if (totalCount == 1) "" else "s"} checked"
}

internal fun linuxCheckPresentation(updates: List<LinuxUpdate>): LinuxCheckPresentation {
    val verified = updates.filter { update ->
        update.checkedAt != null && update.state.normalizedLinuxState() in LINUX_VERIFIED_STATES
    }
    val complete = updates.isNotEmpty() && verified.size == updates.size
    return LinuxCheckPresentation(
        checkedCount = verified.size,
        totalCount = updates.size,
        incomplete = updates.isNotEmpty() && !complete,
        oldestCheckedAt = verified.mapNotNull(LinuxUpdate::checkedAt).minOrNull().takeIf { complete },
    )
}

private fun String.normalizedLinuxState(): String = lowercase().replace("-", "").replace("_", "")

private val LINUX_VERIFIED_STATES = setOf("current", "updateavailable", "updatesavailable")

internal val FleetUiState.updatesFeed: MobileFeed?
    get() = controllerFeed ?: feed

internal fun releaseVersionLabel(version: String?, build: String? = null, failed: Boolean): String? {
    val prefix = if (failed) "Last known" else "Latest"
    return when {
        version != null -> listOfNotNull("$prefix $version", build?.let { "build $it" }).joinToString(" · ")
        build != null -> "$prefix build $build"
        else -> null
    }
}

@Composable
private fun UpdateActionSection(
    action: ControlAction,
    feed: MobileFeed,
    capabilities: List<ControlCapability>,
    enabled: Boolean,
    onRequestUpdate: (ControlAction, List<String>) -> Unit,
) {
    val supported = capabilities.filter { action in it.actions }.sortedBy { it.hostName.lowercase() }
    val available = supported.filter { it.eligibleFor(action) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(action.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            capabilities.isEmpty() -> "Pair to load eligible machines"
                            supported.any { it.updateAvailable(action) } && available.isEmpty() -> "Controller check required"
                            available.isEmpty() -> "No update available"
                            else -> "${available.size} update${if (available.size == 1) "" else "s"} available"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(
                    onClick = { onRequestUpdate(action, available.map { it.hostId }) },
                    enabled = enabled && available.isNotEmpty(),
                ) {
                    Text("Update all")
                }
            }
            supported.forEach { capability ->
                val installed = feed.installedVersion(capability.hostId, action)
                val unavailable = capability.isUnavailable
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(capability.safeHostName(), fontWeight = FontWeight.SemiBold)
                        Text(
                            listOf(
                                installed ?: "Installed version unavailable",
                                capability.controllerUpdateReport(action),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalButton(
                        onClick = { onRequestUpdate(action, listOf(capability.hostId)) },
                        enabled = enabled && capability.updateAvailable(action) && !unavailable,
                    ) {
                        Text(capability.updateButtonLabel(action, installed))
                    }
                }
            }
            if (supported.isEmpty()) {
                Text(
                    if (capabilities.isEmpty()) "Machine status loads after pairing" else "No machines support ${action.title}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val inconsistent = capabilities.filter { it.updateAvailable(action) && action !in it.actions }
            if (inconsistent.isNotEmpty()) {
                Text(
                    "Controller status is inconsistent for ${inconsistent.joinToString { it.safeHostName() }}. " +
                        "Refresh or check the controller before updating.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun RestartActionSection(
    feed: MobileFeed,
    capabilities: List<ControlCapability>,
    enabled: Boolean,
    onRequestRestart: (ControlAction, List<String>) -> Unit,
) {
    val linuxMachines = capabilities
        .filter { ControlAction.LINUX_OS in it.actions || ControlAction.RESTART_LINUX in it.actions }
        .sortedBy { it.hostName.lowercase() }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text("Restart Linux", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Restart one machine at a time after reviewing the interruption warning",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            linuxMachines.forEach { capability ->
                val canRestart = ControlAction.RESTART_LINUX in capability.actions && capability.restartRequired
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(capability.safeHostName(), fontWeight = FontWeight.SemiBold)
                        Text(
                            listOf(
                                feed.installedVersion(capability.hostId, ControlAction.LINUX_OS)
                                    ?: "Installed version unavailable",
                                capability.controllerRestartReport,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalButton(
                        onClick = { onRequestRestart(ControlAction.RESTART_LINUX, listOf(capability.hostId)) },
                        enabled = enabled && canRestart && !capability.isUnavailable,
                    ) {
                        Text(
                            when {
                                capability.isUnavailable || ControlAction.RESTART_LINUX !in capability.actions -> "Unavailable"
                                capability.restartRequired -> "Restart"
                                else -> "Not required"
                            },
                        )
                    }
                }
            }
            if (linuxMachines.isEmpty()) {
                Text(
                    if (capabilities.isEmpty()) "Machine status loads after pairing" else "No Linux machines support remote restart",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun JobProgressCard(job: ControlJob, error: String?, onDismiss: () -> Unit) {
    val terminal = job.state.isTerminal
    val tone = when (job.state) {
        ControlJobState.FAILED, ControlJobState.PARTIAL -> MaterialTheme.colorScheme.errorContainer
        ControlJobState.SUCCEEDED -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    Card(colors = CardDefaults.cardColors(containerColor = tone)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!terminal) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("${job.action.title} · ${job.state.name.lowercase().replaceFirstChar(Char::uppercase)}", fontWeight = FontWeight.Bold)
                    Text("${job.completedCount} of ${job.total} machines complete", style = MaterialTheme.typography.bodySmall)
                }
                if (terminal) TextButton(onClick = onDismiss) { Text("Done") }
            }
            job.targets.forEach { target ->
                Text(
                    "${target.hostName}: ${target.displayProgress}${target.message?.let { " · $it" }.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ControlMessageCard(text: String, error: Boolean = false) {
    Card(colors = CardDefaults.cardColors(containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer)) {
        Text(text, modifier = Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun UpdateCard(update: LinuxUpdate) {
    val needsAttention = update.restartRequired || update.availableCount > 0 || update.state.lowercase() in setOf("failed", "error", "offline")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (needsAttention) Icons.Outlined.WarningAmber else Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = if (needsAttention) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(update.hostName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        buildList {
                            add(update.state.replaceFirstChar(Char::uppercase))
                            update.packageManager?.let(::add)
                        }.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (update.availableCount > 0) SummaryChip("updates", update.availableCount, MaterialTheme.colorScheme.primaryContainer)
                if (update.restartRequired) SummaryChip("restart", 1, MaterialTheme.colorScheme.tertiaryContainer)
            }
            update.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            update.checkedAt?.let {
                Text("Checked ${relativeTime(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EventsScreen(feed: MobileFeed?) {
    if (feed == null) {
        EmptyState(Icons.Outlined.Event, "No events yet", "Connect a feed to see confirmed incidents and recoveries.")
        return
    }
    val events = feed.incidents.sortedByDescending(FleetIncident::startedAt)
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SectionHeading("Events", "${events.size} recent event${if (events.size == 1) "" else "s"}") }
        if (events.isEmpty()) item { InlineEmpty("No confirmed incidents") }
        items(events, key = FleetIncident::id) { EventCard(it) }
    }
}

@Composable
private fun EventCard(event: FleetIncident) {
    val needsAttention = event.severity.lowercase() in setOf("warning", "error", "critical")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        ListItem(
            colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = {
                StatusOrb(healthy = !needsAttention, Modifier.size(34.dp), if (needsAttention) HostState.ATTENTION else HostState.ONLINE)
            },
            headlineContent = { Text(event.title, fontWeight = FontWeight.SemiBold) },
            supportingContent = {
                Column {
                    Text("${event.hostName} · ${event.kind.replaceFirstChar(Char::uppercase)}")
                    event.detail?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    Text(dateTime(event.startedAt), style = MaterialTheme.typography.labelSmall)
                }
            },
            trailingContent = {
                Text(
                    event.severity.replaceFirstChar(Char::uppercase),
                    color = if (needsAttention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.labelMedium,
                )
            },
        )
    }
}

@Composable
private fun SettingsScreen(
    state: FleetUiState,
    onSaveEndpoints: (List<String>) -> Unit,
    onStagePairing: (String, String) -> Unit,
    onForgetControl: () -> Unit,
) {
    var drafts by rememberSaveable(state.endpoints) { mutableStateOf(state.endpoints.ifEmpty { listOf("") }) }
    var validation by rememberSaveable { mutableStateOf<String?>(null) }
    var pairingEndpoint by rememberSaveable(state.controlEndpoint, state.activeEndpoint) {
        mutableStateOf(state.controlEndpoint ?: state.activeEndpoint ?: state.endpoints.firstOrNull().orEmpty())
    }
    var pairingCode by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        SectionHeading("Feed endpoints", "Fleetlight tries every endpoint and uses the freshest valid feed")
        drafts.forEachIndexed { index, value ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { updated -> drafts = drafts.toMutableList().also { it[index] = updated } },
                    modifier = Modifier.weight(1f),
                    label = { Text("HTTPS endpoint ${index + 1}") },
                    singleLine = true,
                    isError = value.isNotBlank() && EndpointPolicy.normalize(value) == null,
                )
                if (drafts.size > 1 || value.isNotEmpty()) {
                    IconButton(onClick = { drafts = drafts.toMutableList().also { it.removeAt(index) }.ifEmpty { listOf("") } }) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "Remove endpoint")
                    }
                }
            }
        }
        validation?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { drafts = drafts + "" },
                enabled = drafts.size < EndpointPolicy.MAX_ENDPOINTS,
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Add endpoint")
            }
            FilledTonalButton(onClick = {
                val nonBlank = drafts.filter(String::isNotBlank)
                if (nonBlank.any { EndpointPolicy.normalize(it) == null }) {
                    validation = "Use complete HTTPS URLs without credentials or fragments."
                } else {
                    validation = null
                    onSaveEndpoints(nonBlank)
                }
            }) {
                Text("Save & refresh")
            }
        }

        HorizontalDivider()
        SectionHeading("Update controller", "The paired Mac runs allowlisted fleet jobs; credentials never leave it")
        if (state.controlEndpoint != null) {
            SettingsInfoCard(
                icon = Icons.Outlined.Shield,
                title = state.controlStatus?.controllerName ?: "Paired controller",
                text = listOfNotNull(
                    ControlEndpointPolicy.authorityForFeed(state.controlEndpoint),
                    if (state.controlStatus?.commandAuthorityEnabled == true && state.controlStatus.jobJournalAvailable) "Commands ready" else "Commands unavailable",
                ).joinToString(" · "),
            )
            OutlinedButton(onClick = onForgetControl, enabled = state.activeJob?.state?.isTerminal != false) {
                Text("Forget controller on this phone")
            }
            Text(
                "To invalidate the controller token everywhere, revoke this Android device from Fleetlight on the Mac.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            OutlinedTextField(
                value = pairingEndpoint,
                onValueChange = { pairingEndpoint = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Observer feed endpoint") },
                singleLine = true,
                isError = pairingEndpoint.isNotBlank() && EndpointPolicy.normalize(pairingEndpoint) == null,
            )
            OutlinedTextField(
                value = pairingCode,
                onValueChange = { pairingCode = it.filter(Char::isDigit).take(8) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("8-digit pairing code") },
                singleLine = true,
            )
            FilledTonalButton(
                onClick = { onStagePairing(pairingEndpoint, pairingCode) },
                enabled = !state.pairing && EndpointPolicy.normalize(pairingEndpoint) != null && pairingCode.length == 8,
            ) {
                if (state.pairing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (state.pairing) "Pairing…" else "Pair update controls")
            }
        }
        state.controlError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        HorizontalDivider()
        SettingsInfoCard(
            icon = Icons.Outlined.Computer,
            title = "Versions",
            text = "Android ${BuildConfig.VERSION_NAME} · Observer ${state.feed?.observer?.appVersion ?: "not reported"}",
        )
        SettingsInfoCard(
            icon = Icons.Outlined.Shield,
            title = "Scoped control by design",
            text = "Fleet status stays read-only until you explicitly pair. Update requests contain only an allowlisted action and machine IDs; SSH and sudo credentials remain on the controller Mac.",
        )
        SettingsInfoCard(
            icon = Icons.Outlined.Storage,
            title = "Resilient refresh",
            text = "All endpoints are checked every 60 seconds. The freshest schema 1 feed wins, and the last good response stays available offline.",
        )
        SettingsInfoCard(
            icon = Icons.Outlined.AccessTime,
            title = "Private configuration link",
            text = "Propose endpoints with fleetlight://configure?endpoint=https%3A%2F%2Fexample.invalid%2Ffeed.json. Fleetlight asks before saving or contacting them.",
        )
        state.activeEndpoint?.let {
            Text("Active source: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsInfoCard(icon: ImageVector, title: String, text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, message: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(icon, contentDescription = null, modifier = Modifier.padding(18.dp).size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun InlineEmpty(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Text(text, modifier = Modifier.fillMaxWidth().padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusOrb(healthy: Boolean, modifier: Modifier, state: HostState = if (healthy) HostState.ONLINE else HostState.ATTENTION) {
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

@Composable
private fun stateColor(state: HostState): Color = when (state) {
    HostState.ONLINE -> MaterialTheme.colorScheme.secondary
    HostState.SLOW -> MaterialTheme.colorScheme.tertiary
    HostState.OFFLINE, HostState.ACCESS, HostState.ATTENTION -> MaterialTheme.colorScheme.error
    HostState.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun hostPriority(host: FleetHost): Int = when (host.state) {
    HostState.OFFLINE -> 0
    HostState.ACCESS -> 1
    HostState.ATTENTION -> 2
    HostState.SLOW -> 3
    HostState.UNKNOWN -> 4
    HostState.ONLINE -> if (host.issueTypes.isEmpty()) 6 else 5
}

private fun observerSubtitle(state: FleetUiState): String {
    val observer = state.feed?.observer?.name ?: "Secure fleet companion"
    val sourceVersion = state.feed?.observer?.appVersion?.let { "Observer $it" }
    val connection = when (state.connection) {
        FeedConnection.LIVE -> "Live"
        FeedConnection.CACHED -> "Cached"
        FeedConnection.ERROR -> "Feed unavailable"
        FeedConnection.EMPTY -> null
    }
    return listOfNotNull(observer, sourceVersion, "Android ${BuildConfig.VERSION_NAME}", connection).joinToString(" · ")
}

private fun relativeTime(instant: Instant, now: Instant = Instant.now()): String {
    val seconds = Duration.between(instant, now).seconds.coerceAtLeast(0)
    return when {
        seconds < 20 -> "now"
        seconds < 60 -> "${seconds}s ago"
        seconds < 3_600 -> "${seconds / 60}m ago"
        seconds < 86_400 -> "${seconds / 3_600}h ago"
        else -> "${seconds / 86_400}d ago"
    }
}

private fun dateTime(instant: Instant): String = DateTimeFormatter.ofPattern("MMM d, HH:mm")
    .withZone(ZoneId.systemDefault())
    .format(instant)

private fun formatDecimal(value: Double): String = if (value == value.roundToInt().toDouble()) {
    value.roundToInt().toString()
} else {
    "%.1f".format(value)
}

private val ControlCapability.isUnavailable: Boolean
    get() = !commandReachable

private fun ControlCapability.controllerUpdateReport(action: ControlAction): String = when {
    state.equals("offline", ignoreCase = true) || state.equals("unreachable", ignoreCase = true) ->
        "Controller reports offline"
    isUnavailable -> "Controller check required"
    updateAvailable(action) -> "Controller reports update available"
    else -> "Controller reports current"
}

private val ControlCapability.controllerRestartReport: String
    get() = when {
        state.equals("offline", ignoreCase = true) || state.equals("unreachable", ignoreCase = true) ->
            "Controller reports offline"
        isUnavailable || ControlAction.RESTART_LINUX !in actions -> "Controller check required"
        restartRequired -> "Controller reports restart required"
        else -> "Controller reports restart not required"
    }

private fun ControlCapability.updateButtonLabel(action: ControlAction, installedVersion: String?): String = when {
    updateAvailable(action) && action != ControlAction.LINUX_OS && installedVersion == null -> "Install"
    updateAvailable(action) -> "Update"
    isUnavailable -> "Unavailable"
    else -> "Current"
}

private fun MobileFeed.installedVersion(hostId: String, action: ControlAction): String? = when (action) {
    ControlAction.CODEX_CLI -> hosts.firstOrNull { it.id == hostId }?.codexCliVersion?.let { "Installed $it" }
    ControlAction.CODEX_MAC_APP -> hosts.firstOrNull { it.id == hostId }?.let { host ->
        host.codexMacAppVersion?.let { version ->
            listOfNotNull("Installed $version", host.codexMacAppBuild?.let { "build $it" }).joinToString(" · ")
        }
    }
    ControlAction.LINUX_OS, ControlAction.RESTART_LINUX ->
        hosts.firstOrNull { it.id == hostId }?.operatingSystem?.let { "Installed $it" }
}

private val app.fleetlight.mobile.data.ControlJobTarget.displayProgress: String
    get() = when (state) {
        app.fleetlight.mobile.data.ControlTargetState.ISSUING -> "Issuing restart"
        app.fleetlight.mobile.data.ControlTargetState.WAITING_FOR_OFFLINE -> "Waiting to go offline"
        app.fleetlight.mobile.data.ControlTargetState.WAITING_FOR_ONLINE -> "Waiting to return online"
        app.fleetlight.mobile.data.ControlTargetState.VERIFYING -> "Verifying"
        else -> phase?.replace(Regex("([a-z])([A-Z])"), "$1 $2")?.lowercase()
            ?: state.name.lowercase().replace('_', ' ')
    }

@Preview(showBackground = true, widthDp = 412, heightDp = 860)
@Composable
private fun FleetPreview() {
    FleetlightTheme(dynamicColor = false) {
        FleetlightContent(
            state = FleetUiState(feed = DemoFeed.value, connection = FeedConnection.LIVE),
            onRefresh = {},
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
            FleetHost("workstation", "Design Workstation", "macOS", HostState.ONLINE, "online", pingMs = 8.0, health = 100),
            FleetHost("server", "Media Server", "Linux", HostState.SLOW, "slow", issueTypes = listOf("High latency"), pingMs = 74.0, health = 91),
            FleetHost("lab", "Lab Computer", "Linux", HostState.OFFLINE, "offline", detail = "Last reachable 18 minutes ago"),
        ),
        linuxUpdates = listOf(LinuxUpdate("server", "Media Server", "updates available", availableCount = 4)),
        incidents = listOf(FleetIncident("sample", "lab", "Lab Computer", "availability", "warning", "Machine went offline", startedAt = generatedAt)),
        metrics = emptyList(),
    )
}
