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

@Composable
internal fun UpdatesScreen(
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
    val ready = state.controlJobReady
    val recentReceipts = recentOperationReceipts(
        jobs = state.controlStatus?.recentJobs.orEmpty(),
        activeJobId = state.activeJob?.id ?: state.controlStatus?.activeJobId,
    )
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { UpdateCheckCard(state, onCheckForUpdates) }
        state.activeJob?.let { job ->
            item { JobProgressCard(job, state.jobError, onDismissJob) }
        }
        if (recentReceipts.isNotEmpty()) {
            item { RecentOperationsSection(recentReceipts) }
        }
        if (state.activeJob == null && state.jobError != null) {
            item {
                ControlMessageCard(
                    state.jobError,
                    error = !state.jobError.startsWith("Waiting for the current fleet operation"),
                )
            }
        }
        if (state.controlStatus == null) {
            item {
                ControlMessageCard(
                    when {
                        state.controlChecking -> "Checking paired update controller…"
                        state.controlEndpoint != null -> controllerAvailabilityNotice(state)
                            ?: "The paired update controller is not ready yet. Fleet status remains available."
                        else -> "Pair an observer in Settings to initiate updates. Status remains available without pairing."
                    },
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
internal fun UpdateCheckCard(state: FleetUiState, onCheckForUpdates: () -> Unit) {
    val status = state.controlStatus
    val localCheck = state.activeCheck
    val updateCenter = updateCenterPresentation(status)
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
    FleetCard(containerColor = resultTone) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Update Center", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        updateCenter.headline,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val checkStatus = when {
                        state.updateCheckSubmitting -> "Starting a live check…"
                        localCheck?.state == ControlCheckState.QUEUED -> "Live check queued${localCheck.phase.asPhaseSuffix()}"
                        localCheck?.state == ControlCheckState.RUNNING -> "Checking${localCheck.phase.asPhaseSuffix()}"
                        status?.checkingUpdates == true -> "Controller is checking…"
                        localCheck?.state == ControlCheckState.SUCCEEDED -> "Live check complete"
                        localCheck?.state == ControlCheckState.PARTIAL -> "Check complete with some failures"
                        localCheck?.state == ControlCheckState.FAILED -> "Live check failed"
                        localCheck?.state == ControlCheckState.CANCELLED -> "Live check cancelled"
                        else -> null
                    }
                    checkStatus?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                FilledTonalButton(onClick = onCheckForUpdates, enabled = canCheck) {
                    if (running) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (running) "Checking…" else "Check all")
                }
            }
            val progressPresentation = checkProgressPresentation(localCheck)
            when {
                progressPresentation.fraction != null -> {
                    LinearProgressIndicator(
                        progress = { progressPresentation.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                running -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            progressPresentation.countLabel?.let { countLabel ->
                Text(
                    countLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            progressPresentation.currentLabel?.let { currentLabel ->
                Text(currentLabel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            progressPresentation.currentDetail?.let { currentDetail ->
                Text(
                    currentDetail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                title = "ChatGPT Desktop App",
                latest = releaseVersionLabel(
                    version = status?.latestCodexDesktopAppVersion,
                    build = status?.latestCodexDesktopAppBuild,
                    failed = status?.codexDesktopAppCheckFailed == true,
                ),
                checkedAt = status?.codexDesktopAppCheckedAt,
                failed = status?.codexDesktopAppCheckFailed == true,
                scope = "macOS latest · Linux checked per machine",
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

internal data class UpdateCenterPresentation(
    val actionableUpdateCount: Int,
    val restartRequiredCount: Int,
    val checkRequiredCount: Int,
    val offlineCount: Int,
    val notInstalledCount: Int,
    val hasManagedTargets: Boolean,
    val controllerAvailable: Boolean,
) {
    val headline: String
        get() {
            if (!controllerAvailable) return "Pair to load update status"
            if (!hasManagedTargets) return "No managed update targets"
            val parts = buildList {
                if (actionableUpdateCount > 0) {
                    add("$actionableUpdateCount update${if (actionableUpdateCount == 1) "" else "s"} available")
                }
                if (restartRequiredCount > 0) {
                    add("$restartRequiredCount restart${if (restartRequiredCount == 1) "" else "s"} required")
                }
                if (checkRequiredCount > 0) {
                    add("$checkRequiredCount check${if (checkRequiredCount == 1) "" else "s"} needed")
                }
                if (offlineCount > 0) add("$offlineCount offline")
                if (notInstalledCount > 0) add("$notInstalledCount not installed")
            }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: "All checked · no updates"
        }
}

internal fun updateCenterPresentation(status: ControlStatus?): UpdateCenterPresentation {
    val capabilities = status?.capabilities.orEmpty()
    val uniqueCapabilities = capabilities.distinctBy(ControlCapability::hostId)
    val managed = uniqueCapabilities.filter { capability ->
        capability.actions.any { action -> action.isUpdate || action == ControlAction.RESTART_LINUX }
    }
    val actionableUpdates = managed.sumOf { capability ->
        ControlAction.entries.count { action -> action.isUpdate && capability.eligibleFor(action) }
    }
    val restartRequired = managed.count { it.eligibleFor(ControlAction.RESTART_LINUX) }
    var checkRequired = 0
    var offline = 0
    var notInstalled = 0
    managed.forEach { capability ->
        val supportsDesktopApp = ControlAction.CODEX_MAC_APP in capability.actions
        val supportsCli = ControlAction.CODEX_CLI in capability.actions
        val supportsLinuxStatus = ControlAction.LINUX_OS in capability.actions ||
            ControlAction.RESTART_LINUX in capability.actions
        val explicitlyOffline = capability.state.trim().lowercase() in setOf("offline", "unreachable", "down")
        val desktopCheckOffline = supportsDesktopApp &&
            capability.codexDesktopAppState == CodexDesktopAppState.OFFLINE
        val cliFresh = !supportsCli ||
            (status?.codexCliCheckedAt != null && !status.codexCliCheckFailed)
        val linuxFresh = !supportsLinuxStatus || capability.linuxCheckedAt != null
        val desktopFresh = !supportsDesktopApp || (
            capability.codexDesktopAppCheckedAt != null &&
                capability.codexDesktopAppState in setOf(
                    CodexDesktopAppState.CURRENT,
                    CodexDesktopAppState.UPDATE_AVAILABLE,
                    CodexDesktopAppState.MISSING,
                )
            )
        val needsCheck = (!capability.commandReachable && !explicitlyOffline) ||
            !cliFresh || !linuxFresh || !desktopFresh
        when {
            explicitlyOffline || desktopCheckOffline -> offline += 1
            needsCheck -> checkRequired += 1
            supportsDesktopApp && capability.codexDesktopAppState == CodexDesktopAppState.MISSING ->
                notInstalled += 1
        }
    }
    return UpdateCenterPresentation(
        actionableUpdateCount = actionableUpdates,
        restartRequiredCount = restartRequired,
        checkRequiredCount = checkRequired,
        offlineCount = offline,
        notInstalledCount = notInstalled,
        hasManagedTargets = managed.isNotEmpty(),
        controllerAvailable = status != null,
    )
}

@Composable
internal fun VersionCheckRow(
    title: String,
    latest: String?,
    checkedAt: Instant?,
    failed: Boolean,
    failureLabel: String = "Check failed",
    scope: String? = null,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            scope?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

internal fun String?.asPhaseSuffix(): String = this?.trim()?.takeIf(String::isNotEmpty)?.let { " · ${it.replaceFirstChar(Char::uppercase)}" }.orEmpty()

internal data class CheckProgressPresentation(
    val fraction: Float?,
    val countLabel: String?,
    val currentLabel: String?,
    val currentDetail: String?,
)

internal fun checkProgressPresentation(check: app.fleetlight.mobile.data.ControlCheck?): CheckProgressPresentation {
    if (check == null) return CheckProgressPresentation(null, null, null, null)
    val total = check.total?.takeIf { it > 0 }
    val completed = check.completed?.coerceIn(0, total ?: Int.MAX_VALUE)
    val current = check.progress.firstOrNull { it.state == app.fleetlight.mobile.data.ControlCheckProgressState.RUNNING }
        ?: check.progress.firstOrNull { it.state == app.fleetlight.mobile.data.ControlCheckProgressState.QUEUED }
        ?: check.progress.firstOrNull {
            it.state == app.fleetlight.mobile.data.ControlCheckProgressState.FAILED ||
                it.state == app.fleetlight.mobile.data.ControlCheckProgressState.PARTIAL
        }
        ?: check.progress.lastOrNull()
    return CheckProgressPresentation(
        fraction = if (total != null && completed != null) completed.toFloat() / total.toFloat() else null,
        countLabel = if (total != null && completed != null) "$completed of $total stages complete" else null,
        currentLabel = current?.let { "${it.name} · ${it.state.displayLabel}" },
        currentDetail = current?.detail,
    )
}

internal val app.fleetlight.mobile.data.ControlCheckProgressState.displayLabel: String
    get() = name.lowercase().replaceFirstChar(Char::uppercase)

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

internal fun String.normalizedLinuxState(): String = lowercase().replace("-", "").replace("_", "")

internal val LINUX_VERIFIED_STATES = setOf("current", "updateavailable", "updatesavailable")

internal val FleetUiState.updatesFeed: MobileFeed?
    get() = controllerFeed ?: feed

internal val FleetUiState.controlJobReady: Boolean
    get() {
        val status = controlStatus ?: return false
        return connection == FeedConnection.LIVE &&
            controlEndpoint != null &&
            status.commandAuthorityEnabled &&
            status.jobJournalAvailable &&
            !status.busy &&
            !status.checkingUpdates &&
            !updateCheckSubmitting &&
            !checkSyncPending &&
            activeCheck?.state?.isTerminal != false &&
            activeJob?.state?.isTerminal != false
    }

internal fun controllerAvailabilityNotice(
    state: FleetUiState,
    includeDetail: Boolean = false,
): String? {
    if (state.controlEndpoint == null || state.controlChecking || state.controllerAvailabilityError == null) return null
    val statusContext = if (state.connection == FeedConnection.LIVE) {
        "Fleet status is live. "
    } else {
        "Fleet status remains available separately. "
    }
    val message = "${statusContext}The paired update controller is temporarily unavailable."
    return if (includeDetail) "$message Technical detail: ${state.controllerAvailabilityError}" else message
}

internal fun releaseVersionLabel(version: String?, build: String? = null, failed: Boolean): String? {
    val prefix = if (failed) "Last known" else "Latest"
    return when {
        version != null -> listOfNotNull("$prefix $version", build?.let { "build $it" }).joinToString(" · ")
        build != null -> "$prefix build $build"
        else -> null
    }
}

@Composable
internal fun UpdateActionSection(
    action: ControlAction,
    feed: MobileFeed,
    capabilities: List<ControlCapability>,
    enabled: Boolean,
    onRequestUpdate: (ControlAction, List<String>) -> Unit,
) {
    val supported = capabilities.filter { action in it.actions }.sortedBy { it.hostName.lowercase() }
    val available = supported.filter { it.eligibleFor(action) }
    FleetCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(action.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    val availability = updateAvailabilitySummary(action, capabilities, supported, available)
                    Text(
                        listOfNotNull(action.platformScope, availability).joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(
                    onClick = { onRequestUpdate(action, available.map { it.hostId }) },
                    enabled = enabled && available.isNotEmpty(),
                ) {
                    Text(updateAllButtonLabel(action, supported, available))
                }
            }
            supported.forEach { capability ->
                val installed = feed.installedVersion(capability.hostId, action, capability)
                val unavailable = capability.isUnavailable
                val host = feed.hosts.firstOrNull { it.id == capability.hostId }
                val platform = (
                    capability.codexDesktopAppPlatform?.desktopAppPlatformLabel()
                        ?: host?.desktopAppPlatformLabel
                    ).takeIf { action == ControlAction.CODEX_MAC_APP }
                val availableVersion = (
                    capability.codexDesktopAppAvailableVersion ?: host?.codexDesktopAppAvailableVersion
                    ).takeIf {
                    action == ControlAction.CODEX_MAC_APP && capability.updateAvailable(action)
                }
                val installedLabel = when {
                    installed != null -> installed
                    action == ControlAction.CODEX_MAC_APP && capability.desktopAppKnownNotInstalled -> "Not installed"
                    else -> "Installed version unavailable"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(capability.safeHostName(), fontWeight = FontWeight.SemiBold)
                        Text(
                            listOfNotNull(
                                platform,
                                installedLabel,
                                capability.controllerUpdateReport(action, installed, availableVersion),
                                desktopAppFreshnessLabel(action, capability, host),
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
internal fun RestartActionSection(
    feed: MobileFeed,
    capabilities: List<ControlCapability>,
    enabled: Boolean,
    onRequestRestart: (ControlAction, List<String>) -> Unit,
) {
    val linuxMachines = capabilities
        .filter { ControlAction.LINUX_OS in it.actions || ControlAction.RESTART_LINUX in it.actions }
        .sortedBy { it.hostName.lowercase() }
    FleetCard {
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
internal fun JobProgressCard(job: ControlJob, error: String?, onDismiss: () -> Unit) {
    val terminal = job.state.isTerminal
    val tone = when (job.state) {
        ControlJobState.FAILED, ControlJobState.PARTIAL -> MaterialTheme.colorScheme.errorContainer
        ControlJobState.SUCCEEDED -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    FleetCard(containerColor = tone) {
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
internal fun RecentOperationsSection(jobs: List<ControlJob>) {
    var expandedJobId by rememberSaveable { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading(
            title = "Recent operations",
            subtitle = "Read-only receipts from the paired controller",
        )
        FleetCard {
            Column {
                jobs.forEachIndexed { index, job ->
                    val expanded = expandedJobId == job.id
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (job.state == ControlJobState.SUCCEEDED) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber,
                                contentDescription = null,
                                tint = if (job.state == ControlJobState.SUCCEEDED) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    MaterialTheme.colorScheme.tertiary
                                },
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "${job.action.title} · ${job.state.displayLabel}",
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    listOfNotNull(
                                        operationReceiptSummary(job),
                                        job.receiptTimestamp?.let { relativeTime(it) },
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = { expandedJobId = if (expanded) null else job.id },
                            ) {
                                Text(if (expanded) "Hide" else "Details")
                            }
                        }
                        if (expanded) {
                            job.message?.takeIf(String::isNotBlank)?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                            if (job.targets.isEmpty()) {
                                Text(
                                    "Per-machine details were not reported",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                job.targets.forEach { target ->
                                    Text(
                                        "${target.hostName}: ${target.displayProgress}${target.message?.let { " · $it" }.orEmpty()}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                    if (index < jobs.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

internal fun recentOperationReceipts(
    jobs: List<ControlJob>,
    activeJobId: String?,
): List<ControlJob> = jobs.asSequence()
    .filter { it.state.isTerminal && it.id != activeJobId }
    .sortedWith(
        compareByDescending<ControlJob> { it.receiptTimestamp ?: Instant.MIN }
            .thenByDescending(ControlJob::id),
    )
    .distinctBy(ControlJob::id)
    .take(MAX_RECENT_OPERATION_RECEIPTS)
    .toList()

internal fun operationReceiptSummary(job: ControlJob): String {
    val counts = job.receiptCounts()
    return buildList {
        if (counts.succeeded > 0) add("${counts.succeeded} succeeded")
        if (counts.failed > 0) add("${counts.failed} failed")
        if (counts.offline > 0) add("${counts.offline} offline")
        if (counts.skipped > 0) add("${counts.skipped} skipped")
        if (counts.cancelled > 0) add("${counts.cancelled} cancelled")
        if (counts.pending > 0) add("${counts.pending} pending")
    }.joinToString(" · ").ifBlank { "No machine results reported" }
}

internal val ControlJobState.displayLabel: String
    get() = name.lowercase().replaceFirstChar(Char::uppercase)

internal const val MAX_RECENT_OPERATION_RECEIPTS = 5

@Composable
internal fun ControlMessageCard(text: String, error: Boolean = false) {
    FleetCard(containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer) {
        Text(text, modifier = Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun UpdateCard(update: LinuxUpdate) {
    val normalizedState = update.state.trim().lowercase().replace("-", "").replace("_", "")
    val needsAttention = update.restartRequired || update.availableCount > 0 ||
        normalizedState in setOf("failed", "error", "offline", "accessissue", "packagestale")
    FleetCard {
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
                            add(linuxUpdateStateLabel(update.state))
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

internal fun linuxUpdateStateLabel(state: String): String = when (
    state.trim().lowercase().replace("-", "").replace("_", "")
) {
    "current" -> "Packages current"
    "updateavailable", "updatesavailable" -> "Updates available"
    "offline" -> "Offline"
    "accessissue" -> "Access issue"
    "connectionchecking" -> "Checking connection"
    "checking", "packagechecking" -> "Checking packages"
    "packagestale" -> "Package check stale"
    "notchecked" -> "Not checked"
    "failed", "error" -> "Check failed"
    else -> state.replaceFirstChar(Char::uppercase)
}


internal val ControlCapability.isUnavailable: Boolean
    get() = !commandReachable

internal fun updateAvailabilitySummary(
    action: ControlAction,
    capabilities: List<ControlCapability>,
    supported: List<ControlCapability>,
    available: List<ControlCapability>,
): String {
    if (capabilities.isEmpty()) return "Pair to load eligible machines"
    if (action != ControlAction.CODEX_MAC_APP) {
        return when {
            supported.any { it.updateAvailable(action) } && available.isEmpty() -> "Controller check required"
            available.isEmpty() -> "No update available"
            else -> "${available.size} update${if (available.size == 1) "" else "s"} available"
        }
    }
    val unknown = supported.count { !it.desktopAppStateKnown }
    val missing = supported.count { it.codexDesktopAppState == CodexDesktopAppState.MISSING }
    val offline = supported.count { it.codexDesktopAppState == CodexDesktopAppState.OFFLINE }
    val unavailable = supported.count { it.codexDesktopAppState == CodexDesktopAppState.UNAVAILABLE }
    return buildList {
        if (available.isNotEmpty()) {
            add("${available.size} update${if (available.size == 1) "" else "s"} available")
        }
        if (unknown > 0) add("$unknown check${if (unknown == 1) "" else "s"} required")
        if (missing > 0) add("$missing not installed")
        if (offline > 0) add("$offline offline")
        if (unavailable > 0) add("$unavailable unavailable")
        if (isEmpty() && supported.any { it.updateAvailable(action) }) add("Controller check required")
        if (isEmpty()) add("No update available")
    }.joinToString(" · ")
}

internal fun updateAllButtonLabel(
    action: ControlAction,
    supported: List<ControlCapability>,
    available: List<ControlCapability>,
): String = when {
    action != ControlAction.CODEX_MAC_APP -> "Update all"
    supported.none { !it.desktopAppStateKnown } -> "Update all"
    available.isEmpty() -> "Check required"
    else -> "Update known"
}

internal fun ControlCapability.controllerUpdateReport(
    action: ControlAction,
    installedVersion: String? = null,
    availableVersion: String? = null,
): String = when {
    state.equals("offline", ignoreCase = true) || state.equals("unreachable", ignoreCase = true) ->
        "Controller reports offline"
    isUnavailable -> "Controller check required"
    action == ControlAction.CODEX_MAC_APP && codexDesktopAppState == CodexDesktopAppState.OFFLINE ->
        "Desktop app check offline"
    action == ControlAction.CODEX_MAC_APP && codexDesktopAppState == CodexDesktopAppState.UNAVAILABLE ->
        "Desktop app unavailable"
    action == ControlAction.CODEX_MAC_APP && desktopAppKnownNotInstalled -> "Not installed"
    action == ControlAction.CODEX_MAC_APP && !desktopAppStateKnown -> "Check required"
    updateAvailable(action) && availableVersion != null -> "Available $availableVersion"
    updateAvailable(action) -> "Controller reports update available"
    else -> "Controller reports current"
}

internal val ControlCapability.controllerRestartReport: String
    get() = when {
        state.equals("offline", ignoreCase = true) || state.equals("unreachable", ignoreCase = true) ->
            "Controller reports offline"
        isUnavailable || ControlAction.RESTART_LINUX !in actions -> "Controller check required"
        restartRequired -> "Controller reports restart required"
        else -> "Controller reports restart not required"
    }

internal fun ControlCapability.updateButtonLabel(action: ControlAction, installedVersion: String?): String = when {
    isUnavailable -> "Unavailable"
    action == ControlAction.CODEX_MAC_APP && codexDesktopAppState == CodexDesktopAppState.OFFLINE -> "Offline"
    action == ControlAction.CODEX_MAC_APP && codexDesktopAppState == CodexDesktopAppState.UNAVAILABLE -> "Unavailable"
    action == ControlAction.CODEX_MAC_APP && desktopAppKnownNotInstalled -> "Not installed"
    action == ControlAction.CODEX_MAC_APP && !desktopAppStateKnown -> "Check required"
    updateAvailable(action) && action != ControlAction.LINUX_OS && installedVersion == null -> "Install"
    updateAvailable(action) -> "Update"
    else -> "Current"
}

internal fun MobileFeed.installedVersion(
    hostId: String,
    action: ControlAction,
    capability: ControlCapability? = null,
): String? = when (action) {
    ControlAction.CODEX_CLI -> hosts.firstOrNull { it.id == hostId }?.codexCliVersion?.let { "Installed $it" }
    ControlAction.CODEX_MAC_APP -> {
        if (capability?.desktopAppKnownNotInstalled == true) {
            null
        } else {
            val host = hosts.firstOrNull { it.id == hostId }
            val version = capability?.codexDesktopAppVersion ?: host?.codexDesktopAppVersion
            val build = host?.codexDesktopAppBuild.takeIf {
                capability?.codexDesktopAppVersion == null || capability.codexDesktopAppVersion == host?.codexDesktopAppVersion
            }
            version?.let {
                listOfNotNull("Installed $it", build?.let { value -> "build $value" }).joinToString(" · ")
            }
        }
    }
    ControlAction.LINUX_OS, ControlAction.RESTART_LINUX ->
        hosts.firstOrNull { it.id == hostId }?.operatingSystem?.let { "Installed $it" }
    ControlAction.REFRESH_HOSTS -> null
}

internal val ControlAction.platformScope: String?
    get() = if (this == ControlAction.CODEX_MAC_APP) "macOS and Linux" else null

internal val FleetHost.desktopAppPlatformLabel: String?
    get() {
        val raw = codexDesktopAppPlatform?.trim()?.takeIf(String::isNotEmpty)
            ?: platform.trim().takeIf(String::isNotEmpty)
            ?: return null
        return raw.desktopAppPlatformLabel()
    }

internal fun String.desktopAppPlatformLabel(): String? = when {
    equals("unknown", ignoreCase = true) -> null
    equals("macOS", ignoreCase = true) || equals("darwin", ignoreCase = true) -> "macOS"
    contains("linux", ignoreCase = true) -> "Linux"
    else -> take(32)
}

internal val FleetHost.hasDesktopAppMetadata: Boolean
    get() = codexDesktopAppPlatform != null ||
        codexDesktopAppProvider != null ||
        codexDesktopAppVersion != null ||
        codexDesktopAppAvailableVersion != null ||
        codexDesktopAppState != null ||
        codexDesktopAppUpdateAvailable != null ||
        codexDesktopAppCheckedAt != null

internal val ControlCapability.desktopAppKnownNotInstalled: Boolean
    get() = codexDesktopAppState == CodexDesktopAppState.MISSING

internal val ControlCapability.desktopAppStateKnown: Boolean
    get() = codexDesktopAppState != null || codexDesktopAppUpdateAvailable != null

internal fun desktopAppFreshnessLabel(
    action: ControlAction,
    capability: ControlCapability,
    host: FleetHost?,
    now: Instant = Instant.now(),
): String? {
    if (action != ControlAction.CODEX_MAC_APP) return null
    val checkedAt = capability.codexDesktopAppCheckedAt ?: host?.codexDesktopAppCheckedAt ?: return null
    return "Checked ${relativeTime(checkedAt, now)}"
}

internal val FleetHost.effectiveDesktopAppState: CodexDesktopAppState?
    get() = codexDesktopAppState ?: when (codexDesktopAppUpdateAvailable) {
        true -> CodexDesktopAppState.UPDATE_AVAILABLE
        false -> CodexDesktopAppState.CURRENT
        null -> null
    }

internal val FleetHost.desktopAppProviderLabel: String?
    get() {
        val raw = codexDesktopAppProvider?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return when {
            raw.equals("macos-appcast", ignoreCase = true) -> "Signed macOS appcast"
            raw.equals("linux-apt", ignoreCase = true) -> "OpenAI APT repository"
            raw.equals("linux-pacman", ignoreCase = true) -> "Configured pacman repository"
            else -> raw.take(80)
        }
    }

internal val app.fleetlight.mobile.data.ControlJobTarget.displayProgress: String
    get() = when (state) {
        app.fleetlight.mobile.data.ControlTargetState.ISSUING -> "Issuing restart"
        app.fleetlight.mobile.data.ControlTargetState.WAITING_FOR_OFFLINE -> "Waiting to go offline"
        app.fleetlight.mobile.data.ControlTargetState.WAITING_FOR_ONLINE -> "Waiting to return online"
        app.fleetlight.mobile.data.ControlTargetState.VERIFYING -> "Verifying"
        else -> phase?.replace(Regex("([a-z])([A-Z])"), "$1 $2")?.lowercase()
            ?: state.name.lowercase().replace('_', ' ')
    }
