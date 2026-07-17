package app.fleetlight.mobile.data

import java.net.URI

object ControlEndpointPolicy {
    private val pairingCode = Regex("^[0-9]{8}$")

    fun baseForFeed(rawEndpoint: String): String? {
        val endpoint = EndpointPolicy.normalize(rawEndpoint) ?: return null
        val uri = runCatching { URI(endpoint) }.getOrNull() ?: return null
        val authority = uri.rawAuthority ?: return null
        val feedPath = uri.rawPath.orEmpty().ifBlank { "/" }
        val parent = feedPath.substringBeforeLast('/', missingDelimiterValue = "")
        val controlPath = "${parent.trimEnd('/')}/control/v1".let { if (it.startsWith('/')) it else "/$it" }
        return URI("https", authority, controlPath, null, null).toASCIIString()
    }

    fun authorityForFeed(rawEndpoint: String): String? {
        val base = baseForFeed(rawEndpoint) ?: return null
        return URI(base).rawAuthority?.lowercase()
    }

    fun validPairingCode(raw: String): String? = raw.trim().takeIf(pairingCode::matches)

    fun route(rawEndpoint: String, suffix: String): String? {
        val base = baseForFeed(rawEndpoint) ?: return null
        val safeSuffix = suffix.trim('/').takeIf { it.isNotEmpty() } ?: return base
        if (safeSuffix.split('/').any { it == "." || it == ".." }) return null
        return "$base/$safeSuffix"
    }
}
