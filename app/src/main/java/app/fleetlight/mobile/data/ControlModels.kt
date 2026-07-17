package app.fleetlight.mobile.data

import java.time.Instant

enum class ControlAction(val wireValue: String, val title: String) {
    CODEX_CLI("codex-cli", "Codex CLI"),
    CODEX_MAC_APP("codex-mac-app", "Codex Mac app"),
    LINUX_OS("linux-os", "Linux OS"),
    RESTART_LINUX("restart-linux", "Restart Linux");

    val isUpdate: Boolean
        get() = this != RESTART_LINUX

    val requiresExactlyOneTarget: Boolean
        get() = this == RESTART_LINUX

    companion object {
        fun fromWire(raw: String?): ControlAction? = entries.firstOrNull {
            it.wireValue.equals(raw, ignoreCase = true) || it.name.equals(raw, ignoreCase = true)
        }
    }
}

enum class ControlJobState {
    QUEUED,
    RUNNING,
    PARTIAL,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    UNKNOWN;

    val isTerminal: Boolean
        get() = this in setOf(PARTIAL, SUCCEEDED, FAILED, CANCELLED)

    companion object {
        fun fromWire(raw: String?): ControlJobState = when (raw?.lowercase()) {
            "queued", "pending", "notattempted", "not-attempted", "waiting" -> QUEUED
            "running", "in-progress", "in_progress", "verifying" -> RUNNING
            "partial", "partially-failed", "partially_failed", "partial-success" -> PARTIAL
            "succeeded", "success", "completed", "complete" -> SUCCEEDED
            "failed", "error" -> FAILED
            "cancelled", "canceled" -> CANCELLED
            else -> UNKNOWN
        }
    }
}

enum class ControlTargetState {
    QUEUED,
    RUNNING,
    ISSUING,
    WAITING_FOR_OFFLINE,
    WAITING_FOR_ONLINE,
    VERIFYING,
    SUCCEEDED,
    FAILED,
    SKIPPED,
    CANCELLED,
    OFFLINE,
    UNKNOWN;

    val isTerminal: Boolean
        get() = this in setOf(SUCCEEDED, FAILED, SKIPPED, CANCELLED, OFFLINE)

    companion object {
        fun fromWire(raw: String?): ControlTargetState = when (
            raw?.lowercase()?.replace("-", "")?.replace("_", "")
        ) {
            "queued", "pending", "notattempted", "waiting" -> QUEUED
            "running", "updating", "installing", "downloading", "restarting" -> RUNNING
            "issuing", "issuingrestart" -> ISSUING
            "waitingforoffline" -> WAITING_FOR_OFFLINE
            "waitingforonline" -> WAITING_FOR_ONLINE
            "verifying", "checking" -> VERIFYING
            "succeeded", "success", "completed", "complete", "current" -> SUCCEEDED
            "failed", "error" -> FAILED
            "skipped", "ineligible" -> SKIPPED
            "cancelled", "canceled" -> CANCELLED
            "offline", "unreachable" -> OFFLINE
            else -> UNKNOWN
        }
    }
}

data class ControlSession(
    val authority: String,
    val controlBase: String,
    val token: String,
    val observerId: String,
)

data class ControlPairResult(
    val token: String,
    val observerId: String,
)

data class ControlStatus(
    val observerId: String,
    val controllerName: String? = null,
    val commandAuthorityEnabled: Boolean = false,
    val jobJournalAvailable: Boolean = false,
    val actions: Set<ControlAction> = emptySet(),
    val capabilities: List<ControlCapability> = emptyList(),
    val busy: Boolean = false,
    val activeJobId: String? = null,
    val recentJobs: List<ControlJob> = emptyList(),
)

data class ControlCapability(
    val hostId: String,
    val hostName: String = hostId,
    val state: String = "unknown",
    val actions: Set<ControlAction> = emptySet(),
    val codexCliUpdateAvailable: Boolean = false,
    val codexMacAppUpdateAvailable: Boolean = false,
    val linuxUpdateAvailable: Boolean = false,
    val restartRequired: Boolean = false,
)

fun ControlCapability.updateAvailable(action: ControlAction): Boolean = when (action) {
    ControlAction.CODEX_CLI -> codexCliUpdateAvailable
    ControlAction.CODEX_MAC_APP -> codexMacAppUpdateAvailable
    ControlAction.LINUX_OS -> linuxUpdateAvailable
    ControlAction.RESTART_LINUX -> false
}

val ControlCapability.commandReachable: Boolean
    get() = state.trim().equals("online", ignoreCase = true)

fun ControlCapability.eligibleFor(action: ControlAction): Boolean = when (action) {
    ControlAction.RESTART_LINUX -> commandReachable && action in actions && restartRequired
    else -> commandReachable && action in actions && updateAvailable(action)
}

data class ControlJobTarget(
    val hostId: String,
    val hostName: String = hostId,
    val state: ControlTargetState = ControlTargetState.UNKNOWN,
    val phase: String? = null,
    val message: String? = null,
)

data class ControlJob(
    val id: String,
    val requestId: String? = null,
    val action: ControlAction,
    val state: ControlJobState,
    val targetHostIds: List<String> = emptyList(),
    val targets: List<ControlJobTarget> = emptyList(),
    val completed: Int = 0,
    val total: Int = targetHostIds.size,
    val message: String? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) {
    val completedCount: Int
        get() = completed.coerceAtLeast(targets.count { it.state.isTerminal })
}

fun ControlCapability.safeHostName(): String = hostName
    .trim()
    .take(80)
    .takeIf { it.isNotEmpty() && it != hostId }
    ?: "Machine"

fun ControlJob.withCapabilityNames(capabilities: List<ControlCapability>): ControlJob {
    val names = capabilities.associate { it.hostId to it.safeHostName() }
    return copy(
        targets = targets.map { target ->
            val existing = target.hostName.trim().take(80)
                .takeIf { it.isNotEmpty() && it != target.hostId }
            target.copy(hostName = names[target.hostId] ?: existing ?: "Machine")
        },
    )
}

data class PendingControlAction(
    val action: ControlAction,
    val targetHostIds: List<String>,
    val targetHostNames: List<String>,
)

data class ControlConfirmationCopy(
    val title: String,
    val description: String,
    val confirmLabel: String,
)

fun PendingControlAction.confirmationCopy(): ControlConfirmationCopy {
    if (action == ControlAction.RESTART_LINUX) {
        require(targetHostIds.size == 1 && targetHostNames.size == 1) {
            "A Linux restart must target exactly one machine"
        }
        val machine = targetHostNames.single()
        return ControlConfirmationCopy(
            title = "Restart $machine?",
            description = "Active work and services on $machine will be interrupted. " +
                "Fleetlight will wait for the machine to go offline and return before checking its status.",
            confirmLabel = "Restart $machine",
        )
    }

    require(targetHostIds.isNotEmpty() && targetHostIds.size == targetHostNames.size) {
        "An update must include matching machine ids and names"
    }
    val warning = when (action) {
        ControlAction.CODEX_CLI -> "Active Codex CLI sessions may be interrupted."
        ControlAction.CODEX_MAC_APP -> "The Codex Mac app restarts automatically after updating."
        ControlAction.LINUX_OS -> "Packages will update sequentially. Machines will not reboot automatically."
        ControlAction.RESTART_LINUX -> error("Handled above")
    }
    val count = targetHostNames.size
    return ControlConfirmationCopy(
        title = "Update ${action.title}?",
        description = buildString {
            append("$count machine${if (count == 1) "" else "s"} will update sequentially:\n")
            targetHostNames.forEach { append("• $it\n") }
            append(warning)
        },
        confirmLabel = if (count == 1) "Update ${targetHostNames.single()}" else "Update all $count",
    )
}

data class PendingPairing(
    val endpoint: String,
    val code: String,
)

data class StoredControlJob(
    val endpoint: String,
    val controlBase: String,
    val jobId: String?,
    val requestId: String,
    val action: ControlAction,
    val targetHostIds: List<String>,
)
