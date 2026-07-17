package app.fleetlight.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.fleetlight.mobile.data.EndpointPolicy
import app.fleetlight.mobile.data.EndpointStore
import app.fleetlight.mobile.data.FeedRefreshResult
import app.fleetlight.mobile.data.FeedRepository
import app.fleetlight.mobile.data.FileFeedCache
import app.fleetlight.mobile.data.HttpsFeedSource
import app.fleetlight.mobile.data.MobileFeed
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class FeedConnection { EMPTY, LIVE, CACHED, ERROR }

data class FleetUiState(
    val feed: MobileFeed? = null,
    val endpoints: List<String> = emptyList(),
    val connection: FeedConnection = FeedConnection.EMPTY,
    val refreshing: Boolean = false,
    val banner: String? = null,
    val activeEndpoint: String? = null,
    val refreshedAt: Instant? = null,
    val pendingEndpoints: List<String> = emptyList(),
)

class FleetlightViewModel(
    application: Application,
    private val endpointStore: EndpointStore = EndpointStore(application),
    private val repository: FeedRepository = FeedRepository(
        source = HttpsFeedSource(),
        cache = FileFeedCache(application),
    ),
    private val now: () -> Instant = Instant::now,
) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(FleetUiState(endpoints = endpointStore.endpoints()))
    val state: StateFlow<FleetUiState> = mutableState.asStateFlow()
    private val refreshMutex = Mutex()
    private var endpointObservation: AutoCloseable? = null
    private var autoRefreshJob: Job? = null

    init {
        endpointObservation = endpointStore.observe { endpoints ->
            mutableState.value = mutableState.value.copy(endpoints = endpoints)
        }
        viewModelScope.launch {
            repository.cached()?.let(::applySuccess)
            refresh()
        }
        autoRefreshJob = viewModelScope.launch {
            while (true) {
                delay(AUTO_REFRESH_MILLIS)
                if (mutableState.value.endpoints.isNotEmpty()) refresh()
            }
        }
    }

    fun refreshNow() {
        viewModelScope.launch { refresh() }
    }

    fun saveEndpoints(values: List<String>) {
        endpointStore.replace(values)
        viewModelScope.launch { refresh() }
    }

    fun stageEndpoints(values: List<String>) {
        val existing = endpointStore.endpoints()
        val candidates = EndpointPolicy.normalizeAll(existing + values)
        val pending = candidates.filterNot(existing::contains)
        if (pending.isNotEmpty()) {
            mutableState.value = mutableState.value.copy(pendingEndpoints = pending)
        }
    }

    fun confirmPendingEndpoints() {
        val pending = mutableState.value.pendingEndpoints
        if (pending.isEmpty()) return
        endpointStore.add(pending)
        mutableState.value = mutableState.value.copy(pendingEndpoints = emptyList())
        viewModelScope.launch { refresh() }
    }

    fun dismissPendingEndpoints() {
        mutableState.value = mutableState.value.copy(pendingEndpoints = emptyList())
    }

    private suspend fun refresh() = refreshMutex.withLock {
        val endpoints = endpointStore.endpoints()
        mutableState.value = mutableState.value.copy(refreshing = true, endpoints = endpoints)
        when (val result = repository.refresh(endpoints)) {
            is FeedRefreshResult.Success -> applySuccess(result)
            FeedRefreshResult.NoEndpoints -> mutableState.value = mutableState.value.copy(
                refreshing = false,
                connection = FeedConnection.EMPTY,
                banner = "Add an HTTPS feed endpoint in Settings",
                activeEndpoint = null,
            )
            is FeedRefreshResult.Failure -> mutableState.value = mutableState.value.copy(
                refreshing = false,
                connection = FeedConnection.ERROR,
                banner = result.message,
                activeEndpoint = null,
                refreshedAt = now(),
            )
        }
    }

    private fun applySuccess(result: FeedRefreshResult.Success) {
        val stale = Duration.between(result.feed.generatedAt, now()).let { it.isNegative.not() && it > STALE_AFTER }
        val banner = when {
            result.fromCache -> "Offline — showing the last good feed from ${relativeAge(result.feed.generatedAt)}"
            stale -> "Feed is stale — generated ${relativeAge(result.feed.generatedAt)}"
            else -> null
        }
        mutableState.value = mutableState.value.copy(
            feed = result.feed,
            refreshing = false,
            connection = if (result.fromCache) FeedConnection.CACHED else FeedConnection.LIVE,
            banner = banner,
            activeEndpoint = result.endpoint,
            refreshedAt = result.fetchedAt,
        )
    }

    private fun relativeAge(instant: Instant): String {
        val seconds = Duration.between(instant, now()).seconds.coerceAtLeast(0)
        return when {
            seconds < 60 -> "just now"
            seconds < 3_600 -> "${seconds / 60}m ago"
            seconds < 86_400 -> "${seconds / 3_600}h ago"
            else -> "${seconds / 86_400}d ago"
        }
    }

    override fun onCleared() {
        endpointObservation?.close()
        autoRefreshJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val AUTO_REFRESH_MILLIS = 60_000L
        private val STALE_AFTER: Duration = Duration.ofMinutes(2)

        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return FleetlightViewModel(application) as T
                }
            }
    }
}
