package app.fleetlight.mobile.data

import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface ControlTransport {
    suspend fun request(request: ControlHttpRequest): String
}

data class ControlHttpRequest(
    val method: String,
    val url: String,
    val token: String? = null,
    val body: String? = null,
    val idempotencyKey: String? = null,
)

class HttpsControlSource(
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 15_000,
    private val parser: ControlParser = ControlParser(),
) : ControlTransport {
    override suspend fun request(request: ControlHttpRequest): String = withContext(Dispatchers.IO) {
        require(request.url.startsWith("https://")) { "Control requests require HTTPS" }
        val connection = URL(request.url).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.requestMethod = request.method
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Fleetlight-Android/2")
            request.token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            request.idempotencyKey?.let { connection.setRequestProperty("Idempotency-Key", it) }
            request.body?.let { body ->
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            if (status in 300..399) throw ControlProtocolException("Control endpoint redirects are not allowed")
            val response = readBounded(if (status in 200..299) connection.inputStream else connection.errorStream)
            if (status !in 200..299) {
                val detail = parser.errorMessage(response) ?: "HTTP $status"
                throw ControlHttpException(status, detail, parser.errorCode(response))
            }
            response
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: java.io.InputStream?): String {
        if (input == null) return "{}"
        return input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_RESPONSE_BYTES) { "Control response is too large" }
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 512 * 1024
    }
}
