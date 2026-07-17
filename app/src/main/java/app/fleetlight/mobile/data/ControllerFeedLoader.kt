package app.fleetlight.mobile.data

import java.io.IOException

class ControllerFeedLoader(private val repository: FeedRepository) {
    suspend fun live(endpoint: String): FeedRefreshResult.Success {
        val normalized = EndpointPolicy.normalize(endpoint)
            ?: throw IOException("Paired controller feed endpoint is invalid")
        val result = repository.refresh(listOf(normalized))
        val success = result as? FeedRefreshResult.Success
            ?: throw IOException("Paired controller feed did not return a live snapshot")
        if (success.fromCache || success.endpoint != normalized) {
            throw IOException("Paired controller feed did not return a live exact-endpoint snapshot")
        }
        return success
    }
}
