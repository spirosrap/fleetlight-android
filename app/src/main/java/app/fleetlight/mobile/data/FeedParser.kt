package app.fleetlight.mobile.data

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class FeedParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

class FeedParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun parse(raw: String): MobileFeed {
        val root = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (error: Exception) {
            throw FeedParseException("Feed is not valid JSON", error)
        }

        val schemaVersion = root.int("schemaVersion")
            ?: throw FeedParseException("Feed is missing schemaVersion")
        if (schemaVersion != 1) {
            throw FeedParseException("Unsupported feed schema $schemaVersion")
        }
        val generatedAt = root.instant("generatedAt")
            ?: throw FeedParseException("Feed is missing a valid generatedAt timestamp")

        return MobileFeed(
            schemaVersion = schemaVersion,
            generatedAt = generatedAt,
            observer = parseObserver(root["observer"]),
            summary = parseSummary(root.obj("summary")),
            hosts = root.array("hosts").mapIndexedNotNull(::parseHost),
            linuxUpdates = root.array("linuxUpdates").mapIndexedNotNull(::parseLinuxUpdate),
            incidents = root.array("incidents").mapIndexedNotNull(::parseIncident),
            metrics = root.array("metrics").mapIndexedNotNull(::parseMetric),
        ).withDerivedSummary()
    }

    private fun parseObserver(element: JsonElement?): FeedObserver {
        if (element is JsonPrimitive) {
            return FeedObserver(name = element.contentOrNull?.ifBlank { "Fleetlight" } ?: "Fleetlight")
        }
        val objectValue = element as? JsonObject ?: return FeedObserver()
        return FeedObserver(
            id = objectValue.string("id", "hostId") ?: "",
            name = objectValue.string("name", "hostName") ?: "Fleetlight",
            appVersion = objectValue.string("appVersion", "version"),
            status = objectValue.string("status", "state"),
            lastRefreshDurationMilliseconds = objectValue.int("lastRefreshDurationMilliseconds", "refreshDurationMs"),
        )
    }

    private fun parseSummary(value: JsonObject?): FleetSummary {
        if (value == null) return FleetSummary()
        return FleetSummary(
            total = value.int("total", "hostCount") ?: 0,
            online = value.int("online", "onlineCount") ?: 0,
            offline = value.int("offline", "offlineCount") ?: 0,
            slowConnections = value.int("slowConnections", "slow", "slowCount") ?: 0,
            accessIssues = value.int("accessIssues", "accessIssueCount") ?: 0,
            alerts = value.int("alerts", "alertCount", "warningCount") ?: 0,
            updatesAvailable = value.int("updatesAvailable", "updatesAvailableCount", "updateCount") ?: 0,
            restartRequired = value.int("restartRequired", "restartRequiredCount") ?: 0,
        )
    }

    private fun parseHost(index: Int, element: JsonElement): FleetHost? {
        val value = element as? JsonObject ?: return null
        val id = value.string("id", "hostId") ?: "host-$index"
        val rawState = value.string("state") ?: value.string("status")
        return FleetHost(
            id = id,
            name = value.string("name", "hostName") ?: id,
            platform = value.string("platform", "os", "operatingSystem") ?: "Unknown",
            state = HostState.from(rawState),
            status = value.string("status") ?: rawState ?: "unknown",
            detail = value.string("detail", "message"),
            checkedAt = value.instant("checkedAt", "lastCheckedAt", "updatedAt"),
            issueTypes = value.stringArray("issueTypes", "issues", "attentionReasons"),
            health = value.int("health", "healthScore"),
            pingMs = value.double("pingMs", "latencyMs", "pingMilliseconds"),
            jitterMs = value.double("jitterMs", "jitterMilliseconds"),
            packetLossPercent = value.double("packetLossPercent", "lossPercent", "packetLoss"),
            sshReadyMs = value.double("sshReadyMs", "readyMs", "connectionReadyMs"),
            fullProbeMs = value.double("fullProbeMs", "probeMs", "fullProbeMilliseconds"),
            operatingSystem = value.string("operatingSystem", "osVersion", "systemVersion"),
            codexCliVersion = value.string("codexCliVersion", "codexVersion"),
            fleetlightVersion = value.string("fleetlightVersion", "appVersion"),
            diskPercent = value.double("diskPercent", "diskUsedPercent", "rootDiskPercent"),
            memoryPercent = value.double("memoryPercent", "memoryUsedPercent"),
            loadAverage = value.double("loadAverage", "load", "load1"),
            bootDescription = value.string("bootDescription", "boot", "uptime"),
            restartRequired = value.bool("restartRequired", "rebootRequired") ?: false,
            services = value.serviceArray("services"),
            warnings = value.warningArray("warnings", "alerts"),
            codexMacAppVersion = value.string("codexMacAppVersion", "codexAppVersion"),
            codexMacAppBuild = value.string("codexMacAppBuild", "codexAppBuild"),
            isPinned = value.bool("isPinned", "pinned") ?: false,
        )
    }

    private fun parseLinuxUpdate(index: Int, element: JsonElement): LinuxUpdate? {
        val value = element as? JsonObject ?: return null
        val hostId = value.string("hostId", "id") ?: "update-$index"
        return LinuxUpdate(
            hostId = hostId,
            hostName = value.string("hostName", "name") ?: hostId,
            state = value.string("state", "status", "phase") ?: "unknown",
            detail = value.string("detail", "message"),
            packageManager = value.string("packageManager"),
            availableCount = value.int("availableCount", "updateCount", "packagesAvailable") ?: 0,
            securityCount = value.int("securityCount") ?: 0,
            snapCount = value.int("snapCount") ?: 0,
            flatpakCount = value.int("flatpakCount") ?: 0,
            restartRequired = value.bool("restartRequired", "rebootRequired") ?: false,
            checkedAt = value.instant("checkedAt", "updatedAt"),
        )
    }

    private fun parseIncident(index: Int, element: JsonElement): FleetIncident? {
        val value = element as? JsonObject ?: return null
        val startedAt = value.instant("startedAt", "timestamp", "occurredAt", "date") ?: return null
        val kind = value.string("kind", "category") ?: "status"
        return FleetIncident(
            id = value.string("id") ?: "incident-$index-${startedAt.toEpochMilli()}",
            hostId = value.string("hostId"),
            hostName = value.string("hostName", "name") ?: "Fleet",
            kind = kind,
            severity = value.string("severity", "level") ?: "info",
            title = value.string("title", "summary") ?: kind.replaceFirstChar(Char::uppercase),
            detail = value.string("detail", "message"),
            startedAt = startedAt,
        )
    }

    private fun parseMetric(index: Int, element: JsonElement): HostMetric? {
        val value = element as? JsonObject ?: return null
        val hostId = value.string("hostId", "id") ?: "metric-$index"
        val capturedAt = value.instant("capturedAt", "timestamp", "checkedAt") ?: return null
        return HostMetric(
            hostId = hostId,
            capturedAt = capturedAt,
            state = value.string("state", "status") ?: "unknown",
            pingMs = value.double("pingMs", "latencyMs", "pingMilliseconds"),
            jitterMs = value.double("jitterMs", "jitterMilliseconds"),
            packetLossPercent = value.double("packetLossPercent", "lossPercent", "packetLoss"),
            sshReadyMs = value.double("sshReadyMs", "readyMs", "connectionReadyMs"),
            fullProbeMs = value.double("fullProbeMs", "probeMs", "fullProbeMilliseconds"),
            health = value.int("health", "healthScore"),
            diskPercent = value.double("diskPercent", "diskUsedPercent"),
            memoryPercent = value.double("memoryPercent", "memoryUsedPercent"),
            loadAverage = value.double("loadAverage", "load", "load1"),
        )
    }
}

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())

private fun JsonObject.string(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
}

private fun JsonObject.int(vararg keys: String): Int? = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)?.intOrNull
}

private fun JsonObject.double(vararg keys: String): Double? = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)?.doubleOrNull
}

private fun JsonObject.bool(vararg keys: String): Boolean? = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)?.booleanOrNull
}

private fun JsonObject.instant(vararg keys: String): Instant? = string(*keys)?.let { raw ->
    runCatching { Instant.parse(raw) }.getOrNull()
}

private fun JsonObject.stringArray(vararg keys: String): List<String> {
    val element = keys.firstNotNullOfOrNull { this[it] } ?: return emptyList()
    return when (element) {
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }.filter(String::isNotEmpty)
        is JsonPrimitive -> element.contentOrNull?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
        else -> emptyList()
    }
}

private fun JsonObject.serviceArray(key: String): List<HostService> {
    val values = this[key] as? JsonArray ?: return emptyList()
    return values.mapNotNull { element ->
        when (element) {
            is JsonPrimitive -> element.contentOrNull?.takeIf(String::isNotBlank)?.let { HostService(name = it) }
            is JsonObject -> {
                val name = element.string("name", "id", "label") ?: return@mapNotNull null
                HostService(
                    kind = element.string("kind", "type") ?: "service",
                    name = name,
                    state = element.string("state", "status") ?: "unknown",
                    detail = element.string("detail", "message"),
                )
            }
            else -> null
        }
    }
}

private fun JsonObject.warningArray(vararg keys: String): List<HostWarning> {
    val values = keys.firstNotNullOfOrNull { this[it] as? JsonArray } ?: return emptyList()
    return values.mapNotNull { element ->
        when (element) {
            is JsonPrimitive -> element.contentOrNull?.takeIf(String::isNotBlank)?.let { HostWarning(title = it) }
            is JsonObject -> {
                val title = element.string("title", "name", "kind") ?: return@mapNotNull null
                HostWarning(
                    kind = element.string("kind", "type") ?: "warning",
                    title = title,
                    detail = element.string("detail", "message"),
                )
            }
            else -> null
        }
    }
}
