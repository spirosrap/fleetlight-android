package app.fleetlight.mobile.data

import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ControlRepository(
    private val transport: ControlTransport,
    private val credentials: ControlCredentialStore,
    private val parser: ControlParser = ControlParser(),
) {
    fun isPaired(endpoint: String): Boolean {
        val authority = ControlEndpointPolicy.authorityForFeed(endpoint) ?: return false
        val base = ControlEndpointPolicy.baseForFeed(endpoint) ?: return false
        return credentials.read(authority)?.controlBase == base
    }

    suspend fun pair(
        endpoint: String,
        code: String,
        deviceId: String,
        deviceName: String,
    ): ControlStatus {
        val normalizedCode = ControlEndpointPolicy.validPairingCode(code)
            ?: throw ControlProtocolException("Enter the 8-digit pairing code")
        val authority = ControlEndpointPolicy.authorityForFeed(endpoint)
            ?: throw ControlProtocolException("Pairing requires a valid HTTPS feed endpoint")
        val url = ControlEndpointPolicy.route(endpoint, "pair")
            ?: throw ControlProtocolException("Pairing endpoint is invalid")
        val body = buildJsonObject {
            put("code", normalizedCode)
            put("deviceId", deviceId)
            put("deviceName", deviceName.take(80))
        }.toString()
        val paired = parser.pair(transport.request(ControlHttpRequest("POST", url, body = body)))
        val controlBase = requireNotNull(ControlEndpointPolicy.baseForFeed(endpoint))
        val session = ControlSession(authority, controlBase, paired.token, paired.observerId)
        credentials.write(session)
        return runCatching { status(endpoint) }.getOrElse { error ->
            credentials.remove(authority)
            throw error
        }
    }

    suspend fun status(endpoint: String): ControlStatus {
        val session = session(endpoint)
        val url = requireNotNull(ControlEndpointPolicy.route(endpoint, "status"))
        val status = parser.status(transport.request(ControlHttpRequest("GET", url, token = session.token)))
        if (session.observerId != status.observerId) {
            credentials.remove(session.authority)
            throw ControlProtocolException("The paired observer identity changed; pair it again")
        }
        status.recentJobs.forEach(::validateTargetCount)
        return status
    }

    suspend fun createCheck(
        endpoint: String,
        requestId: String = UUID.randomUUID().toString(),
    ): ControlCheck {
        require(requestId.canonicalUuidOrNull() != null) { "requestId must be a UUID" }
        val session = session(endpoint)
        val url = requireNotNull(ControlEndpointPolicy.route(endpoint, "checks"))
        val body = buildJsonObject { put("requestId", requestId) }.toString()
        val check = parser.check(
            transport.request(
                ControlHttpRequest(
                    method = "POST",
                    url = url,
                    token = session.token,
                    body = body,
                    idempotencyKey = requestId,
                ),
            ),
        )
        validateCheck(check, expectedRequestId = requestId)
        return check
    }

    suspend fun check(
        endpoint: String,
        checkId: String,
        expectedRequestId: String,
    ): ControlCheck {
        require(checkId.canonicalUuidOrNull() != null) { "Invalid update check id" }
        require(expectedRequestId.canonicalUuidOrNull() != null) { "Invalid request id" }
        val session = session(endpoint)
        val url = requireNotNull(ControlEndpointPolicy.route(endpoint, "checks/$checkId"))
        val check = parser.check(
            transport.request(ControlHttpRequest("GET", url, token = session.token)),
        )
        validateCheck(check, expectedId = checkId, expectedRequestId = expectedRequestId)
        return check
    }

    suspend fun createJob(
        endpoint: String,
        action: ControlAction,
        targetHostIds: List<String>,
        requestId: String = UUID.randomUUID().toString(),
    ): ControlJob {
        require(requestId.canonicalUuidOrNull() != null) { "requestId must be a UUID" }
        val targets = targetHostIds.map(String::trim).filter(String::isNotEmpty).distinct()
        require(targets.isNotEmpty()) { "At least one target is required" }
        require(!action.requiresExactlyOneTarget || targets.size == 1) {
            "${action.title} must target exactly one machine"
        }
        val session = session(endpoint)
        val url = requireNotNull(ControlEndpointPolicy.route(endpoint, "jobs"))
        val body = buildJsonObject {
            put("requestId", requestId)
            put("action", action.wireValue)
            put("targetHostIds", JsonArray(targets.map(::JsonPrimitive)))
        }.toString()
        val raw = transport.request(
            ControlHttpRequest(
                method = "POST",
                url = url,
                token = session.token,
                body = body,
                idempotencyKey = requestId,
            ),
        )
        return parser.job(raw, action).also { job ->
            if (job.action != action || !sameUuid(job.requestId, requestId) || job.targetHostIds != targets) {
                throw ControlProtocolException("Controller returned a mismatched job")
            }
            validateTargetCount(job)
        }
    }

    suspend fun job(
        endpoint: String,
        jobId: String,
        fallbackAction: ControlAction,
        expectedRequestId: String? = null,
        expectedTargetHostIds: List<String> = emptyList(),
    ): ControlJob {
        require(JOB_ID.matches(jobId)) { "Invalid job id" }
        val session = session(endpoint)
        val url = requireNotNull(ControlEndpointPolicy.route(endpoint, "jobs/$jobId"))
        return parser.job(
            transport.request(ControlHttpRequest("GET", url, token = session.token)),
            fallbackAction,
        ).also { job ->
            if (!sameJobId(job.id, jobId) || job.action != fallbackAction ||
                (expectedRequestId != null && !sameUuid(job.requestId, expectedRequestId)) ||
                (expectedTargetHostIds.isNotEmpty() && job.targetHostIds != expectedTargetHostIds)
            ) {
                throw ControlProtocolException("Controller returned a mismatched job")
            }
            validateTargetCount(job)
        }
    }

    suspend fun jobByRequest(
        endpoint: String,
        requestId: String,
        fallbackAction: ControlAction,
        expectedTargetHostIds: List<String> = emptyList(),
    ): ControlJob {
        require(requestId.canonicalUuidOrNull() != null) { "Invalid request id" }
        val session = session(endpoint)
        val url = requireNotNull(ControlEndpointPolicy.route(endpoint, "jobs/by-request/$requestId"))
        return parser.job(
            transport.request(ControlHttpRequest("GET", url, token = session.token)),
            fallbackAction,
        ).also { job ->
            if (!sameUuid(job.requestId, requestId) || job.action != fallbackAction ||
                (expectedTargetHostIds.isNotEmpty() && job.targetHostIds != expectedTargetHostIds)
            ) {
                throw ControlProtocolException("Controller returned a mismatched job")
            }
            validateTargetCount(job)
        }
    }

    fun revoke(endpoint: String) {
        ControlEndpointPolicy.authorityForFeed(endpoint)?.let(credentials::remove)
    }

    private fun session(endpoint: String): ControlSession {
        val authority = ControlEndpointPolicy.authorityForFeed(endpoint)
            ?: throw ControlProtocolException("Control endpoint is invalid")
        val expectedBase = ControlEndpointPolicy.baseForFeed(endpoint)
            ?: throw ControlProtocolException("Control endpoint is invalid")
        return credentials.read(authority)?.takeIf { it.controlBase == expectedBase }
            ?: throw ControlProtocolException("Pair this observer before starting updates")
    }

    private fun validateTargetCount(job: ControlJob) {
        if (job.action.requiresExactlyOneTarget && job.targetHostIds.size != 1) {
            throw ControlProtocolException("Controller returned an invalid multi-machine restart")
        }
    }

    private fun sameUuid(actual: String?, expected: String): Boolean {
        val actualValue = actual?.canonicalUuidOrNull()
        val expectedValue = expected.canonicalUuidOrNull()
        return actualValue != null && expectedValue != null && actualValue == expectedValue
    }

    private fun sameJobId(actual: String, expected: String): Boolean {
        val actualValue = actual.canonicalUuidOrNull()
        val expectedValue = expected.canonicalUuidOrNull()
        return if (actualValue != null || expectedValue != null) {
            actualValue != null && expectedValue != null && actualValue == expectedValue
        } else {
            actual == expected
        }
    }

    private fun validateCheck(
        check: ControlCheck,
        expectedId: String? = null,
        expectedRequestId: String,
    ) {
        val actualId = check.id.canonicalUuidOrNull()
        val actualRequestId = check.requestId.canonicalUuidOrNull()
        val expectedIdValue = expectedId?.canonicalUuidOrNull()
        val expectedRequestIdValue = expectedRequestId.canonicalUuidOrNull()
        if (actualId == null || actualRequestId == null || expectedRequestIdValue == null ||
            (expectedId != null && actualId != expectedIdValue) ||
            actualRequestId != expectedRequestIdValue
        ) {
            throw ControlProtocolException("Controller returned a mismatched update check")
        }
    }

    private companion object {
        val JOB_ID = Regex("^[A-Za-z0-9-]{1,128}$")
    }
}
