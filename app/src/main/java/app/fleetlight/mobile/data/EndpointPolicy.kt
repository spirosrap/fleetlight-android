package app.fleetlight.mobile.data

import java.net.URI

object EndpointPolicy {
    const val MAX_ENDPOINTS = 4

    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.host.isNullOrBlank() || uri.userInfo != null) return null
        if (uri.fragment != null) return null
        return uri.normalize().toASCIIString()
    }

    fun normalizeAll(values: Iterable<String>): List<String> =
        values.mapNotNull(::normalize).distinct().take(MAX_ENDPOINTS)
}
