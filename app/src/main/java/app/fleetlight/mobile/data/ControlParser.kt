package app.fleetlight.mobile.data

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

class ControlProtocolException(message: String) : Exception(message)
class ControlHttpException(val status: Int, message: String) : Exception(message)

class ControlParser(private val json: Json = Json { ignoreUnknownKeys = true }) {
    fun pair(raw: String): ControlPairResult {
        val root = objectRoot(raw)
        val token = root.text("token")
            ?: throw ControlProtocolException("Pairing response did not include a control token")
        if (token.length > MAX_TOKEN_LENGTH) throw ControlProtocolException("Pairing token was invalid")
        val controllerId = root.text("controllerId")
            ?: throw ControlProtocolException("Pairing response did not identify the controller")
        return ControlPairResult(token = token, observerId = controllerId)
    }

    fun status(raw: String): ControlStatus {
        val root = objectRoot(raw)
        val controllerId = root.text("controllerId")
            ?: throw ControlProtocolException("Control status did not identify the controller")
        val authorityEnabled = root.boolean("commandAuthorityEnabled") ?: false
        val capabilities = root.arrayValue("capabilities").mapNotNull { element ->
            val value = element as? JsonObject ?: return@mapNotNull null
            val hostId = value.text("hostId") ?: return@mapNotNull null
            val actions = value.arrayValue("actions").mapNotNull { action ->
                ControlAction.fromWire((action as? JsonPrimitive)?.contentOrNull)
            }.toSet()
            ControlCapability(
                hostId = hostId,
                hostName = value.text("hostName") ?: hostId,
                state = value.text("state") ?: "unknown",
                actions = actions,
                codexCliUpdateAvailable = value.boolean("codexCliUpdateAvailable") ?: false,
                codexMacAppUpdateAvailable = value.boolean("codexMacAppUpdateAvailable") ?: false,
                linuxUpdateAvailable = value.boolean("linuxUpdateAvailable") ?: false,
                restartRequired = value.boolean("restartRequired") ?: false,
                linuxCheckedAt = value.instant("linuxCheckedAt"),
            )
        }
        if (authorityEnabled && capabilities.isEmpty()) {
            throw ControlProtocolException("Control status did not include capabilities")
        }
        capabilities.forEach { capability ->
            val inconsistent = when {
                capability.codexCliUpdateAvailable && ControlAction.CODEX_CLI !in capability.actions -> "Codex CLI"
                capability.codexMacAppUpdateAvailable && ControlAction.CODEX_MAC_APP !in capability.actions -> "Codex Mac app"
                capability.linuxUpdateAvailable && ControlAction.LINUX_OS !in capability.actions -> "Linux OS"
                capability.restartRequired && ControlAction.RESTART_LINUX !in capability.actions -> "Linux restart"
                else -> null
            }
            if (inconsistent != null) {
                throw ControlProtocolException("Controller reported inconsistent $inconsistent capability status")
            }
        }
        val recentJobs = root.arrayValue("recentJobs").mapNotNull { element ->
            val value = element as? JsonObject ?: return@mapNotNull null
            val action = ControlAction.fromWire(value.text("action")) ?: return@mapNotNull null
            runCatching { job(value.toString(), action) }.getOrNull()
        }
        return ControlStatus(
            observerId = controllerId,
            controllerName = root.text("controllerName"),
            commandAuthorityEnabled = authorityEnabled,
            jobJournalAvailable = root.boolean("jobJournalAvailable") ?: false,
            actions = capabilities.flatMap { it.actions }.toSet(),
            capabilities = capabilities,
            busy = root.boolean("busy") ?: false,
            activeJobId = root.text("activeJobId"),
            checkingUpdates = root.boolean("checkingUpdates") ?: false,
            activeCheckId = root.text("activeCheckId"),
            latestCodexCliVersion = root.text("latestCodexCliVersion")?.take(MAX_VERSION_LENGTH),
            codexCliCheckedAt = root.instant("codexCliCheckedAt"),
            codexCliCheckFailed = root.boolean("codexCliCheckFailed") ?: false,
            latestCodexMacAppVersion = root.text("latestCodexMacAppVersion")?.take(MAX_VERSION_LENGTH),
            latestCodexMacAppBuild = root.text("latestCodexMacAppBuild")?.take(MAX_BUILD_LENGTH),
            codexMacAppCheckedAt = root.instant("codexMacAppCheckedAt"),
            codexMacAppCheckFailed = root.boolean("codexMacAppCheckFailed") ?: false,
            recentJobs = recentJobs.map { it.withCapabilityNames(capabilities) },
        )
    }

    fun check(raw: String): ControlCheck {
        val envelope = objectRoot(raw)
        val root = envelope.objectValue("check") ?: envelope
        val id = root.text("id")
            ?: throw ControlProtocolException("Update check response did not include an id")
        val requestId = root.text("requestId")
            ?: throw ControlProtocolException("Update check response did not include a request id")
        val state = ControlCheckState.fromWire(root.text("state"))
        if (state == ControlCheckState.UNKNOWN) {
            throw ControlProtocolException("Update check response had an invalid state")
        }
        val phase = root.text("phase")
            ?: throw ControlProtocolException("Update check response did not include a phase")
        val detail = root.text("detail")
            ?: throw ControlProtocolException("Update check response did not include detail")
        val total = root.integer("total")?.coerceIn(0, MAX_CHECK_TOTAL)
        val completed = root.integer("completed")?.coerceIn(0, total ?: MAX_CHECK_TOTAL)
        val progress = root.arrayValue("progress")
            .take(MAX_CHECK_PROGRESS_ITEMS)
            .mapNotNull { element ->
                val value = element as? JsonObject ?: return@mapNotNull null
                val progressState = ControlCheckProgressState.fromWire(value.text("state"))
                    ?: return@mapNotNull null
                ControlCheckProgress(
                    id = value.text("id")?.take(MAX_PROGRESS_ID_LENGTH) ?: return@mapNotNull null,
                    name = value.text("name")?.take(MAX_PROGRESS_NAME_LENGTH) ?: return@mapNotNull null,
                    category = value.text("category")?.take(MAX_PROGRESS_CATEGORY_LENGTH) ?: return@mapNotNull null,
                    state = progressState,
                    detail = value.text("detail")?.take(MAX_MESSAGE_LENGTH) ?: return@mapNotNull null,
                )
            }
            .distinctBy(ControlCheckProgress::id)
        return ControlCheck(
            id = id,
            requestId = requestId,
            state = state,
            phase = phase.take(MAX_PHASE_LENGTH),
            detail = detail.take(MAX_MESSAGE_LENGTH),
            startedAt = root.strictInstant("startedAt"),
            finishedAt = root.strictInstant("finishedAt"),
            completed = completed,
            total = total,
            progress = progress,
        )
    }

    fun job(raw: String, fallbackAction: ControlAction): ControlJob {
        val envelope = objectRoot(raw)
        val root = envelope.objectValue("job") ?: envelope
        val id = root.text("id", "jobId")
            ?: throw ControlProtocolException("Job response did not include an id")
        val action = ControlAction.fromWire(root.text("action")) ?: fallbackAction
        val progress = root.arrayValue("progress").mapNotNull { element ->
            val value = element as? JsonObject ?: return@mapNotNull null
            val hostId = value.text("hostId") ?: return@mapNotNull null
            val phase = value.text("phase")
            ControlJobTarget(
                hostId = hostId,
                state = ControlTargetState.fromWire(phase),
                phase = phase,
                message = value.text("detail")?.take(MAX_MESSAGE_LENGTH),
            )
        }
        val targetIds = root.stringArray("targetHostIds")
        return ControlJob(
            id = id,
            requestId = root.text("requestId"),
            action = action,
            state = ControlJobState.fromWire(root.text("state")),
            targetHostIds = targetIds,
            targets = progress,
            completed = root.integer("completed") ?: 0,
            total = root.integer("total") ?: targetIds.size,
            message = root.text("message")?.take(MAX_MESSAGE_LENGTH),
            createdAt = root.instant("createdAt"),
            updatedAt = root.instant("finishedAt", "startedAt"),
        )
    }

    fun errorMessage(raw: String): String? = runCatching {
        val root = objectRoot(raw)
        (root.objectValue("error") ?: root).text("message", "detail")?.take(MAX_MESSAGE_LENGTH)
    }.getOrNull()

    private fun objectRoot(raw: String): JsonObject = try {
        json.parseToJsonElement(raw).jsonObject
    } catch (_: Exception) {
        throw ControlProtocolException("Control response was not valid JSON")
    }

    private companion object {
        const val MAX_MESSAGE_LENGTH = 400
        const val MAX_TOKEN_LENGTH = 4096
        const val MAX_VERSION_LENGTH = 80
        const val MAX_BUILD_LENGTH = 40
        const val MAX_PHASE_LENGTH = 80
        const val MAX_CHECK_TOTAL = 64
        const val MAX_CHECK_PROGRESS_ITEMS = 32
        const val MAX_PROGRESS_ID_LENGTH = 80
        const val MAX_PROGRESS_NAME_LENGTH = 120
        const val MAX_PROGRESS_CATEGORY_LENGTH = 48
    }
}

private fun JsonObject.text(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
}

private fun JsonObject.objectValue(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.arrayValue(vararg keys: String): JsonArray =
    keys.firstNotNullOfOrNull { this[it] as? JsonArray } ?: JsonArray(emptyList())

private fun JsonObject.boolean(vararg keys: String): Boolean? = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)?.booleanOrNull
}

private fun JsonObject.integer(vararg keys: String): Int? = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)?.intOrNull
}

private fun JsonObject.stringArray(vararg keys: String): List<String> =
    arrayValue(*keys).mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }

private fun JsonObject.instant(vararg keys: String): Instant? = text(*keys)?.let { raw ->
    runCatching { Instant.parse(raw) }.getOrNull()
}

private fun JsonObject.strictInstant(key: String): Instant? {
    val raw = text(key) ?: return null
    return runCatching { Instant.parse(raw) }.getOrElse {
        throw ControlProtocolException("Update check response had an invalid $key timestamp")
    }
}
