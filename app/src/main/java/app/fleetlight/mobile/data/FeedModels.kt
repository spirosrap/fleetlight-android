package app.fleetlight.mobile.data

import java.time.Instant

data class MobileFeed(
    val schemaVersion: Int,
    val generatedAt: Instant,
    val metricsWindowHours: Int? = null,
    val metricsSampleIntervalSeconds: Int? = null,
    val observer: FeedObserver,
    val summary: FleetSummary,
    val hosts: List<FleetHost>,
    val linuxUpdates: List<LinuxUpdate>,
    val incidents: List<FleetIncident>,
    val metrics: List<HostMetric>,
    val timingComparisons: List<TimingComparison> = emptyList(),
)

data class FeedObserver(
    val id: String = "",
    val name: String = "Fleetlight",
    val appVersion: String? = null,
    val status: String? = null,
    val lastRefreshDurationMilliseconds: Int? = null,
)

data class FleetSummary(
    val total: Int = 0,
    val online: Int = 0,
    val offline: Int = 0,
    val slowConnections: Int = 0,
    val accessIssues: Int = 0,
    val alerts: Int = 0,
    val updatesAvailable: Int = 0,
    val restartRequired: Int = 0,
) {
    val issueCount: Int
        get() = offline + slowConnections + accessIssues + alerts + updatesAvailable + restartRequired
}

enum class HostState {
    ONLINE,
    SLOW,
    OFFLINE,
    ACCESS,
    ATTENTION,
    UNKNOWN;

    companion object {
        fun from(raw: String?): HostState = when (raw?.lowercase()) {
            "online", "healthy", "current", "ready", "ok" -> ONLINE
            "slow", "degraded", "warning" -> SLOW
            "offline", "unreachable", "down" -> OFFLINE
            "access", "accessissue", "access-issue" -> ACCESS
            "attention", "failed", "error", "critical" -> ATTENTION
            else -> UNKNOWN
        }
    }
}

enum class CodexDesktopAppState {
    CURRENT,
    UPDATE_AVAILABLE,
    MISSING,
    OFFLINE,
    UNAVAILABLE;

    companion object {
        fun fromWire(raw: String?): CodexDesktopAppState? = when (
            raw?.trim()?.lowercase()?.replace("_", "-")
        ) {
            "current" -> CURRENT
            "update-available" -> UPDATE_AVAILABLE
            "missing" -> MISSING
            "offline" -> OFFLINE
            "unavailable" -> UNAVAILABLE
            else -> null
        }
    }
}

data class FleetHost(
    val id: String,
    val name: String,
    val platform: String = "Unknown",
    val state: HostState = HostState.UNKNOWN,
    val status: String = state.name.lowercase(),
    val detail: String? = null,
    val checkedAt: Instant? = null,
    val issueTypes: List<String> = emptyList(),
    val health: Int? = null,
    val pingMs: Double? = null,
    val jitterMs: Double? = null,
    val packetLossPercent: Double? = null,
    val sshReadyMs: Double? = null,
    val fullProbeMs: Double? = null,
    val operatingSystem: String? = null,
    val codexCliVersion: String? = null,
    val fleetlightVersion: String? = null,
    val diskPercent: Double? = null,
    val memoryPercent: Double? = null,
    val loadAverage: Double? = null,
    val bootDescription: String? = null,
    val restartRequired: Boolean = false,
    val services: List<HostService> = emptyList(),
    val warnings: List<HostWarning> = emptyList(),
    val codexDesktopAppPlatform: String? = null,
    val codexDesktopAppProvider: String? = null,
    val codexDesktopAppVersion: String? = null,
    val codexDesktopAppBuild: String? = null,
    val codexDesktopAppAvailableVersion: String? = null,
    val codexDesktopAppState: CodexDesktopAppState? = null,
    val codexDesktopAppUpdateAvailable: Boolean? = null,
    val codexDesktopAppCheckedAt: Instant? = null,
    val isPinned: Boolean = false,
)

data class HostService(
    val kind: String = "service",
    val name: String,
    val state: String = "unknown",
    val detail: String? = null,
)

data class HostWarning(
    val kind: String = "warning",
    val title: String,
    val detail: String? = null,
)

data class LinuxUpdate(
    val hostId: String,
    val hostName: String,
    val state: String = "unknown",
    val detail: String? = null,
    val packageManager: String? = null,
    val availableCount: Int = 0,
    val securityCount: Int = 0,
    val snapCount: Int = 0,
    val flatpakCount: Int = 0,
    val restartRequired: Boolean = false,
    val checkedAt: Instant? = null,
)

fun MobileFeed.withReconciledLinuxReachability(): MobileFeed {
    val linuxHostIds = linuxUpdates.mapTo(mutableSetOf(), LinuxUpdate::hostId)
    val reconciledHosts = hosts.map { host ->
        if (host.id !in linuxHostIds) return@map host
        when (host.state) {
            HostState.OFFLINE, HostState.ACCESS, HostState.UNKNOWN -> host.copy(
                issueTypes = host.issueTypes.filterNot { issue ->
                    issue.normalizedLinuxUpdateState() in LIVE_UNREACHABLE_LINUX_ISSUE_TYPES
                },
                restartRequired = false,
            )
            HostState.ONLINE, HostState.SLOW, HostState.ATTENTION -> host
        }
    }
    val hostsById = reconciledHosts.associateBy(FleetHost::id)
    val reconciledUpdates = linuxUpdates.map { update ->
        when (hostsById[update.hostId]?.state) {
            HostState.OFFLINE -> update.copy(
                state = "offline",
                detail = "Machine offline · showing the last package check",
                availableCount = 0,
                restartRequired = false,
            )
            HostState.ACCESS -> update.copy(
                state = "accessIssue",
                detail = "Monitoring access issue · showing the last package check",
                availableCount = 0,
                restartRequired = false,
            )
            HostState.UNKNOWN -> update.copy(
                state = "connectionChecking",
                detail = "Checking machine connection · showing the last package check",
                availableCount = 0,
                restartRequired = false,
            )
            HostState.ONLINE, HostState.SLOW, HostState.ATTENTION -> when (
                update.state.normalizedLinuxUpdateState()
            ) {
                "offline", "packagestale" -> update.copy(
                    state = "packageStale",
                    availableCount = 0,
                    restartRequired = false,
                )
                "checking", "packagechecking" -> update.copy(
                    state = "packageChecking",
                    availableCount = 0,
                )
                else -> update
            }
            null -> update
        }
    }
    return copy(
        hosts = reconciledHosts,
        linuxUpdates = reconciledUpdates,
        summary = summary.copy(
            updatesAvailable = reconciledUpdates.count {
                it.availableCount > 0 && it.state.normalizedLinuxUpdateState() in LINUX_UPDATE_AVAILABLE_STATES
            },
            restartRequired = reconciledUpdates.count(LinuxUpdate::restartRequired),
        ),
    )
}

private fun String.normalizedLinuxUpdateState(): String =
    trim().lowercase().replace("-", "").replace("_", "")

private val LINUX_UPDATE_AVAILABLE_STATES = setOf("updateavailable", "updatesavailable")
private val LIVE_UNREACHABLE_LINUX_ISSUE_TYPES = setOf(
    "update",
    "updates",
    "updatesavailable",
    "restart",
    "restartrequired",
)

data class FleetIncident(
    val id: String,
    val hostId: String? = null,
    val hostName: String = "Fleet",
    val kind: String = "status",
    val severity: String = "info",
    val title: String,
    val detail: String? = null,
    val startedAt: Instant,
)

data class HostMetric(
    val hostId: String,
    val capturedAt: Instant,
    val state: String = "unknown",
    val pingMs: Double? = null,
    val jitterMs: Double? = null,
    val packetLossPercent: Double? = null,
    val sshReadyMs: Double? = null,
    val fullProbeMs: Double? = null,
    val health: Int? = null,
    val diskPercent: Double? = null,
    val memoryPercent: Double? = null,
    val loadAverage: Double? = null,
)

data class TimingComparison(
    val hostId: String,
    val metric: String,
    val windowHours: Int,
    val currentAverageMs: Double? = null,
    val currentSampleCount: Int = 0,
    val currentCoverageSeconds: Double? = null,
    val previousAverageMs: Double? = null,
    val previousSampleCount: Int = 0,
    val previousCoverageSeconds: Double? = null,
)

fun MobileFeed.withDerivedSummary(): MobileFeed {
    if (summary.total > 0 || hosts.isEmpty()) return this
    val derived = FleetSummary(
        total = hosts.size,
        online = hosts.count { it.state == HostState.ONLINE },
        offline = hosts.count { it.state == HostState.OFFLINE },
        slowConnections = hosts.count { it.state == HostState.SLOW || it.issueTypes.any { issue -> issue.equals("slow", true) } },
        accessIssues = hosts.count { it.state == HostState.ACCESS || it.issueTypes.any { issue -> issue.contains("access", true) } },
        alerts = hosts.count { it.warnings.isNotEmpty() },
        updatesAvailable = linuxUpdates.count { it.availableCount > 0 },
        restartRequired = linuxUpdates.count { it.restartRequired },
    )
    return copy(summary = derived)
}
