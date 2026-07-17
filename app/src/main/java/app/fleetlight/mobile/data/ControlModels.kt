package app.fleetlight.mobile.data

import java.time.Instant

enum class ControlAction(val wireValue: String, val title: String) {
    CODEX_CLI("codex-cli", "Codex CLI"),
    CODEX_MAC_APP("codex-mac-app", "Codex Mac app"),
    LINUX_OS("linux-os", "Linux OS");

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
        fun fromWire(raw: String?): ControlTargetState = when (raw?.lowercase()) {
            "queued", "pending", "notattempted", "not-attempted", "waiting" -> QUEUED
            "running", "updating", "installing", "downloading" -> RUNNING
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
)

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

data class PendingControlAction(
    val action: ControlAction,
    val targetHostIds: List<String>,
    val targetHostNames: List<String>,
)

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
