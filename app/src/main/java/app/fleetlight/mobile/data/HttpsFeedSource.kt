package app.fleetlight.mobile.data

import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HttpsFeedSource(
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 8_000,
) : FeedSource {
    override suspend fun load(endpoint: String): String = withContext(Dispatchers.IO) {
        val normalized = requireNotNull(EndpointPolicy.normalize(endpoint)) { "Only HTTPS feed endpoints are allowed" }
        val connection = URL(normalized).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Fleetlight-Android/1")
            val status = connection.responseCode
            require(connection.url.protocol.equals("https", ignoreCase = true)) { "Endpoint redirected outside HTTPS" }
            require(status in 200..299) { "HTTP $status" }
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_FEED_BYTES) { "Feed exceeds 2 MB limit" }
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val MAX_FEED_BYTES = 2 * 1024 * 1024
    }
}
