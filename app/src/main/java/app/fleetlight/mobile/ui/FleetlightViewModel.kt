package app.fleetlight.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.fleetlight.mobile.data.AndroidControlCredentialStore
import app.fleetlight.mobile.data.ControlAction
import app.fleetlight.mobile.data.ControlAuthorityStore
import app.fleetlight.mobile.data.ControlCheck
import app.fleetlight.mobile.data.ControlCheckOrchestrator
import app.fleetlight.mobile.data.ControlCheckState
import app.fleetlight.mobile.data.ControlCheckStore
import app.fleetlight.mobile.data.ControlEndpointPolicy
import app.fleetlight.mobile.data.ControlHttpException
import app.fleetlight.mobile.data.ControlJob
import app.fleetlight.mobile.data.ControlJobStore
import app.fleetlight.mobile.data.ControlProtocolException
import app.fleetlight.mobile.data.ControlRepository
import app.fleetlight.mobile.data.ControlStatus
import app.fleetlight.mobile.data.ControllerFeedLoader
import app.fleetlight.mobile.data.DeviceIdentityStore
import app.fleetlight.mobile.data.EndpointPolicy
import app.fleetlight.mobile.data.EndpointStore
import app.fleetlight.mobile.data.eligibleFor
import app.fleetlight.mobile.data.safeHostName
import app.fleetlight.mobile.data.isRetryableControlFailure
import app.fleetlight.mobile.data.withCapabilityNames
import app.fleetlight.mobile.data.FeedRefreshResult
import app.fleetlight.mobile.data.FeedRepository
import app.fleetlight.mobile.data.FileFeedCache
import app.fleetlight.mobile.data.HttpsControlSource
import app.fleetlight.mobile.data.HttpsFeedSource
import app.fleetlight.mobile.data.MobileFeed
import app.fleetlight.mobile.data.PendingControlAction
import app.fleetlight.mobile.data.PendingPairing
import app.fleetlight.mobile.data.StoredControlJob
import app.fleetlight.mobile.data.StoredControlCheck
import java.time.Duration
import java.time.Instant
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
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
    val controllerFeed: MobileFeed? = null,
    val endpoints: List<String> = emptyList(),
    val connection: FeedConnection = FeedConnection.EMPTY,
    val refreshing: Boolean = false,
    val banner: String? = null,
    val activeEndpoint: String? = null,
    val refreshedAt: Instant? = null,
    val pendingEndpoints: List<String> = emptyList(),
    val controlStatus: ControlStatus? = null,
    val controlEndpoint: String? = null,
    val controlChecking: Boolean = false,
    val controlError: String? = null,
    val pendingControlAction: PendingControlAction? = null,
    val pendingPairing: PendingPairing? = null,
    val pairing: Boolean = false,
    val activeJob: ControlJob? = null,
    val jobError: String? = null,
    val activeCheck: ControlCheck? = null,
    val updateCheckSubmitting: Boolean = false,
    val updateCheckError: String? = null,
    val checkSyncPending: Boolean = false,
)

class FleetlightViewModel(
    application: Application,
    private val endpointStore: EndpointStore = EndpointStore(application),
    private val repository: FeedRepository = FeedRepository(
        source = HttpsFeedSource(),
        cache = FileFeedCache(application),
    ),
    private val controllerFeedLoader: ControllerFeedLoader = ControllerFeedLoader(
        FeedRepository(
            source = HttpsFeedSource(),
            cache = FileFeedCache(application, "controller-feed-cache"),
        ),
    ),
    private val controlRepository: ControlRepository = ControlRepository(
        transport = HttpsControlSource(),
        credentials = AndroidControlCredentialStore(application),
    ),
    private val deviceIdentity: DeviceIdentityStore = DeviceIdentityStore(application),
    private val controlAuthorityStore: ControlAuthorityStore = ControlAuthorityStore(application),
    private val jobStore: ControlJobStore = ControlJobStore(application),
    private val checkStore: ControlCheckStore = ControlCheckStore(application),
    private val checkOrchestrator: ControlCheckOrchestrator = ControlCheckOrchestrator(controlRepository),
    private val now: () -> Instant = Instant::now,
) : AndroidViewModel(application) {
    private val restoredCheck = checkStore.read()
    private val mutableState = MutableStateFlow(
        FleetUiState(
            endpoints = endpointStore.endpoints(),
            controlEndpoint = controlAuthorityStore.read(),
            checkSyncPending = restoredCheck != null,
        ),
    )
    val state: StateFlow<FleetUiState> = mutableState.asStateFlow()
    private val refreshMutex = Mutex()
    private val controllerFeedMutex = Mutex()
    private var endpointObservation: AutoCloseable? = null
    private var autoRefreshJob: Job? = null
    private var controlStatusJob: Job? = null
    private var jobPollingJob: Job? = null
    private var updateCheckJob: Job? = null

    init {
        endpointObservation = endpointStore.observe { endpoints ->
            mutableState.value = mutableState.value.copy(endpoints = endpoints)
        }
        viewModelScope.launch {
            repository.cached()?.let(::applySuccess)
            refresh()
        }
        jobStore.read()?.let(::resumeStoredJob)
        restoredCheck?.let(::resumeStoredCheck)
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

    fun checkForUpdates() {
        checkStore.read()?.let { stored ->
            resumeStoredCheck(stored)
            return
        }
        val current = mutableState.value
        val endpoint = current.controlEndpoint
        val status = current.controlStatus
        if (current.connection != FeedConnection.LIVE || endpoint == null || status == null ||
            !status.commandAuthorityEnabled
        ) {
            mutableState.value = current.copy(updateCheckError = "Pair a live controller before checking for updates")
            return
        }
        if (status.checkingUpdates || current.updateCheckSubmitting ||
            current.activeCheck?.state?.isTerminal == false
        ) {
            mutableState.value = current.copy(updateCheckError = "An update check is already running")
            return
        }
        if (status.busy || current.activeJob?.state?.isTerminal == false) {
            mutableState.value = current.copy(updateCheckError = "Wait for the current fleet operation to finish")
            return
        }
        val controlBase = ControlEndpointPolicy.baseForFeed(endpoint)
        if (controlBase == null) {
            mutableState.value = current.copy(updateCheckError = "The paired controller address is invalid")
            return
        }
        val stored = StoredControlCheck(
            endpoint = endpoint,
            controlBase = controlBase,
            checkId = null,
            requestId = UUID.randomUUID().toString(),
        )
        if (!checkStore.write(stored)) {
            mutableState.value = current.copy(updateCheckError = "The check was not started because its recovery record could not be saved")
            return
        }
        mutableState.value = mutableState.value.copy(checkSyncPending = true)
        runStoredCheck(stored)
    }

    fun saveEndpoints(values: List<String>) {
        endpointStore.replace(values)
        viewModelScope.launch { refresh() }
    }

    fun stageEndpoints(values: List<String>) {
        val existing = endpointStore.endpoints()
        val candidates = EndpointPolicy.normalizeAll(existing + values)
        val pending = candidates.filterNot(existing::contains)
        if (pending.isNotEmpty()) mutableState.value = mutableState.value.copy(pendingEndpoints = pending)
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

    fun stagePairing(endpoint: String, code: String) {
        if (pairingBlockedByControlWork(mutableState.value, jobStore.read() != null, checkStore.read() != null)) {
            mutableState.value = mutableState.value.copy(controlError = PAIRING_WAIT_MESSAGE)
            return
        }
        val normalizedEndpoint = EndpointPolicy.normalize(endpoint)
        val normalizedCode = ControlEndpointPolicy.validPairingCode(code)
        if (normalizedEndpoint == null || normalizedCode == null) {
            mutableState.value = mutableState.value.copy(controlError = "Use a valid HTTPS feed endpoint and 8-digit code")
            return
        }
        mutableState.value = mutableState.value.copy(
            pendingPairing = PendingPairing(normalizedEndpoint, normalizedCode),
            controlError = null,
        )
    }

    fun confirmPendingPairing() {
        if (mutableState.value.pairing) return
        val pending = mutableState.value.pendingPairing ?: return
        if (pairingBlockedByControlWork(mutableState.value, jobStore.read() != null, checkStore.read() != null)) {
            mutableState.value = mutableState.value.copy(
                pendingPairing = null,
                pairing = false,
                controlError = PAIRING_WAIT_MESSAGE,
            )
            return
        }
        mutableState.value = mutableState.value.copy(pairing = true, controlError = null)
        viewModelScope.launch {
            runCatching {
                controlRepository.pair(pending.endpoint, pending.code, deviceIdentity.id, deviceIdentity.name)
            }.onSuccess { status ->
                val controllerChanged = mutableState.value.controlEndpoint != pending.endpoint
                controlStatusJob?.cancel()
                controlAuthorityStore.write(pending.endpoint)
                mutableState.value = mutableState.value.copy(
                    pairing = false,
                    pendingPairing = null,
                    controlStatus = status,
                    controlEndpoint = pending.endpoint,
                    controllerFeed = if (controllerChanged) null else mutableState.value.controllerFeed,
                    controlError = when {
                        !status.commandAuthorityEnabled -> "Remote commands are disabled on this observer"
                        !status.jobJournalAvailable -> "The controller job journal is unavailable; updates are disabled"
                        else -> null
                    },
                )
                viewModelScope.launch { refreshControllerFeedBestEffort(pending.endpoint) }
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(
                    pairing = false,
                    pendingPairing = null,
                    controlStatus = null,
                    controlError = safeMessage(error, "Pairing failed"),
                )
            }
        }
    }

    fun dismissPendingPairing() {
        mutableState.value = mutableState.value.copy(pendingPairing = null)
    }

    fun revokeControl() {
        val endpoint = mutableState.value.controlEndpoint ?: return
        controlRepository.revoke(endpoint)
        controlAuthorityStore.clear()
        jobPollingJob?.cancel()
        updateCheckJob?.cancel()
        jobStore.clear()
        checkStore.clear()
        mutableState.value = mutableState.value.copy(
            controlStatus = null,
            controlEndpoint = null,
            controllerFeed = null,
            controlError = null,
            activeJob = null,
            jobError = null,
            activeCheck = null,
            updateCheckSubmitting = false,
            updateCheckError = null,
            checkSyncPending = false,
        )
    }

    fun requestUpdate(action: ControlAction, hostIds: List<String>) {
        val current = mutableState.value
        val status = current.controlStatus
        if (current.connection != FeedConnection.LIVE || status == null || !status.commandAuthorityEnabled || !status.jobJournalAvailable) {
            mutableState.value = current.copy(controlError = "Connect to and pair a live observer before updating")
            return
        }
        if (status.busy || current.activeJob?.state?.isTerminal == false || jobStore.read() != null) {
            mutableState.value = current.copy(controlError = "Another fleet operation is already active")
            return
        }
        if (status.checkingUpdates || current.updateCheckSubmitting || current.checkSyncPending ||
            current.activeCheck?.state?.isTerminal == false
        ) {
            mutableState.value = current.copy(controlError = "Wait for the live update check to finish")
            return
        }
        val requested = hostIds.distinct()
        if (action.requiresExactlyOneTarget && requested.size != 1) {
            mutableState.value = current.copy(controlError = "Restart exactly one Linux machine at a time")
            return
        }
        val eligible = status.capabilities.filter { capability ->
            capability.hostId in requested && capability.eligibleFor(action)
        }
        if (eligible.isEmpty() || (action.requiresExactlyOneTarget && eligible.size != 1)) {
            val message = if (action == ControlAction.RESTART_LINUX) {
                "That machine does not currently require a Linux restart"
            } else {
                "No selected machine has this update available"
            }
            mutableState.value = current.copy(controlError = message)
            return
        }
        mutableState.value = current.copy(
            pendingControlAction = PendingControlAction(
                action = action,
                targetHostIds = eligible.map { it.hostId },
                targetHostNames = eligible.map { it.safeHostName() },
            ),
            controlError = null,
        )
    }

    fun dismissPendingUpdate() {
        mutableState.value = mutableState.value.copy(pendingControlAction = null)
    }

    fun confirmPendingUpdate() {
        val pending = mutableState.value.pendingControlAction ?: return
        if (pending.action.requiresExactlyOneTarget && pending.targetHostIds.size != 1) {
            mutableState.value = mutableState.value.copy(
                pendingControlAction = null,
                jobError = "The restart was blocked because it did not target exactly one machine",
            )
            return
        }
        val endpoint = mutableState.value.controlEndpoint ?: return
        val controlBase = ControlEndpointPolicy.baseForFeed(endpoint) ?: return
        val stored = StoredControlJob(
            endpoint = endpoint,
            controlBase = controlBase,
            jobId = null,
            requestId = UUID.randomUUID().toString(),
            action = pending.action,
            targetHostIds = pending.targetHostIds,
        )
        if (!jobStore.write(stored)) {
            mutableState.value = mutableState.value.copy(
                pendingControlAction = null,
                jobError = "The update was not started because its recovery record could not be saved",
            )
            return
        }
        mutableState.value = mutableState.value.copy(pendingControlAction = null, jobError = null)
        submitOrRecover(stored)
    }

    fun dismissFinishedJob() {
        if (mutableState.value.activeJob?.state?.isTerminal != true) return
        jobStore.clear()
        mutableState.value = mutableState.value.copy(activeJob = null, jobError = null)
        mutableState.value.controlEndpoint?.let(::checkControl)
    }

    private fun submitOrRecover(stored: StoredControlJob) {
        jobPollingJob?.cancel()
        jobPollingJob = viewModelScope.launch {
            val baseStillMatches = ControlEndpointPolicy.baseForFeed(stored.endpoint) == stored.controlBase
            if (!baseStillMatches) {
                mutableState.value = mutableState.value.copy(jobError = "Saved controller identity no longer matches")
                return@launch
            }
            var accepted: ControlJob? = null
            if (stored.jobId != null) {
                val lookup = runCatching {
                    controlRepository.job(
                        stored.endpoint,
                        stored.jobId,
                        stored.action,
                        stored.requestId,
                        stored.targetHostIds,
                    )
                }
                accepted = lookup.getOrNull()
                if (accepted == null) {
                    val error = requireNotNull(lookup.exceptionOrNull())
                    val retry = storedJobRetryAfterLookupFailure(stored, error)
                    if (retry != null) {
                        scheduleSubmissionRetry(retry, error, recoveringProgress = true)
                    } else {
                        jobStore.clear()
                        mutableState.value = mutableState.value.copy(
                            jobError = safeMessage(error, "Unable to resume progress"),
                        )
                    }
                    return@launch
                }
            } else {
                val lookup = runCatching {
                    controlRepository.jobByRequest(stored.endpoint, stored.requestId, stored.action, stored.targetHostIds)
                }
                accepted = lookup.getOrNull()
                val lookupError = lookup.exceptionOrNull()
                val missing = lookupError is ControlHttpException && lookupError.status == 404
                if (accepted == null && !missing) {
                    if (lookupError != null && retryableSubmissionFailure(lookupError)) {
                        scheduleSubmissionRetry(stored, lookupError)
                    } else if (lookupError != null) {
                        terminalSubmissionFailure(stored, lookupError)
                    }
                    return@launch
                }
                if (accepted == null) {
                    val submission = runCatching {
                        controlRepository.createJob(
                            endpoint = stored.endpoint,
                            action = stored.action,
                            targetHostIds = stored.targetHostIds,
                            requestId = stored.requestId,
                        )
                    }
                    accepted = submission.getOrNull()
                    if (accepted == null) {
                        val error = requireNotNull(submission.exceptionOrNull())
                        if (retryableSubmissionFailure(error)) scheduleSubmissionRetry(stored, error)
                        else terminalSubmissionFailure(stored, error)
                        return@launch
                    }
                }
            }
            accepted = decorateJob(accepted)
            val bound = stored.copy(jobId = accepted.id)
            val persistenceWarning = if (jobStore.write(bound)) null else
                "The job started, but its id could not be saved; recovery will use the original request id"
            mutableState.value = mutableState.value.copy(activeJob = accepted, jobError = persistenceWarning)
            pollJob(bound, accepted)
        }
    }

    private suspend fun scheduleSubmissionRetry(
        stored: StoredControlJob,
        error: Throwable,
        recoveringProgress: Boolean = false,
    ) {
        val controllerBusy = error.isControllerBusyFailure()
        mutableState.value = mutableState.value.copy(
            jobError = if (controllerBusy) {
                "Waiting for the current fleet operation to finish. This update will start automatically."
            } else if (recoveringProgress) {
                "Progress is temporarily unavailable; Fleetlight will retry this same operation. ${safeMessage(error, "")}".trim()
            } else {
                "Submission status is uncertain; Fleetlight will retry only this same request. ${safeMessage(error, "")}".trim()
            },
        )
        delay(if (controllerBusy) BUSY_RETRY_MILLIS else RETRY_SUBMISSION_MILLIS)
        resumeStoredJob(stored)
    }

    private fun terminalSubmissionFailure(stored: StoredControlJob, error: Throwable) {
        jobStore.clear()
        mutableState.value = mutableState.value.copy(
            jobError = safeMessage(error, "The update was not started"),
            activeJob = null,
        )
        if (error is ControlHttpException && error.status == 409) checkControl(stored.endpoint)
    }

    private suspend fun pollJob(stored: StoredControlJob, initial: ControlJob) {
        var job = initial
        val deadline = now().plus(JOB_POLL_DEADLINE)
        var delayMillis = JOB_POLL_MILLIS
        while (!job.state.isTerminal && now().isBefore(deadline)) {
            delay(delayMillis)
            val result = runCatching {
                controlRepository.job(
                    stored.endpoint,
                    requireNotNull(stored.jobId),
                    stored.action,
                    stored.requestId,
                    stored.targetHostIds,
                )
            }
            val refreshed = result.getOrNull()
            if (refreshed == null) {
                val error = requireNotNull(result.exceptionOrNull())
                mutableState.value = mutableState.value.copy(jobError = safeMessage(error, "Update progress is temporarily unavailable"))
                if (!retryableSubmissionFailure(error)) {
                    jobStore.clear()
                    mutableState.value = mutableState.value.copy(
                        activeJob = job.copy(state = app.fleetlight.mobile.data.ControlJobState.FAILED),
                    )
                    return
                }
                delayMillis = (delayMillis * 2).coerceAtMost(MAX_JOB_POLL_MILLIS)
                continue
            }
            job = decorateJob(refreshed)
            delayMillis = JOB_POLL_MILLIS
            mutableState.value = mutableState.value.copy(activeJob = job, jobError = null)
        }
        if (!job.state.isTerminal) {
            mutableState.value = mutableState.value.copy(jobError = "Progress polling paused after six hours; reopen Fleetlight to resume")
            return
        }
        jobStore.clear()
        mutableState.value = mutableState.value.copy(activeJob = job)
        refresh()
    }

    private fun resumeStoredJob(stored: StoredControlJob) {
        submitOrRecover(stored)
    }

    private fun resumeStoredCheck(stored: StoredControlCheck) {
        runStoredCheck(stored)
    }

    private fun runStoredCheck(stored: StoredControlCheck) {
        updateCheckJob?.cancel()
        updateCheckJob = viewModelScope.launch {
            if (ControlEndpointPolicy.baseForFeed(stored.endpoint) != stored.controlBase ||
                mutableState.value.controlEndpoint != stored.endpoint ||
                !controlRepository.isPaired(stored.endpoint)
            ) {
                checkStore.clear()
                mutableState.value = mutableState.value.copy(
                    updateCheckSubmitting = false,
                    checkSyncPending = false,
                    updateCheckError = "Saved update check no longer matches the paired controller",
                )
                return@launch
            }
            mutableState.value = mutableState.value.copy(
                updateCheckSubmitting = stored.checkId == null,
                updateCheckError = null,
                checkSyncPending = true,
                activeCheck = if (stored.checkId == null) null else mutableState.value.activeCheck,
            )
            var persistenceWarning: String? = null
            val result = runCatching {
                checkOrchestrator.execute(
                    stored = stored,
                    onAccepted = { check ->
                        val bound = stored.copy(checkId = check.id)
                        if (!checkStore.write(bound)) {
                            persistenceWarning = "The check started, but recovery progress could not be saved"
                        }
                    },
                    onProgress = { check ->
                        mutableState.value = mutableState.value.copy(
                            activeCheck = check,
                            updateCheckSubmitting = false,
                            updateCheckError = persistenceWarning,
                        )
                    },
                    refreshFeed = { refreshControllerFeedStrict(stored.endpoint) },
                    refreshStatus = { fetchAndApplyControlStatus(stored.endpoint) },
                )
            }
            val completed = result.getOrNull()
            if (completed != null) {
                checkStore.clear()
                val terminalMessage = when (completed.state) {
                    ControlCheckState.SUCCEEDED -> persistenceWarning
                    ControlCheckState.PARTIAL -> completed.detail ?: "Some update sources could not be checked"
                    ControlCheckState.FAILED -> completed.detail ?: "The update check failed"
                    ControlCheckState.CANCELLED -> completed.detail ?: "The update check was cancelled"
                    else -> persistenceWarning
                }
                mutableState.value = mutableState.value.copy(
                    activeCheck = completed,
                    updateCheckSubmitting = false,
                    updateCheckError = terminalMessage,
                    checkSyncPending = false,
                )
            } else {
                val error = requireNotNull(result.exceptionOrNull())
                if (error is CancellationException) throw error
                if (shouldClearStoredCheckAfterFailure(mutableState.value.activeCheck, error)) {
                    checkStore.clear()
                }
                val recoveryPending = checkStore.read() != null
                mutableState.value = mutableState.value.copy(
                    activeCheck = mutableState.value.activeCheck?.takeIf { it.state.isTerminal },
                    updateCheckSubmitting = false,
                    updateCheckError = safeMessage(error, "Update check progress is unavailable"),
                    checkSyncPending = recoveryPending,
                )
            }
        }
    }

    private fun checkControl(endpoint: String) {
        controlStatusJob?.cancel()
        if (!controlRepository.isPaired(endpoint)) {
            mutableState.value = mutableState.value.copy(
                controlStatus = null,
                controllerFeed = null,
                controlChecking = false,
                controlError = "Pair this controller again",
            )
            return
        }
        controlStatusJob = viewModelScope.launch {
            loadControlStatus(endpoint, markChecking = true)
        }
    }

    private suspend fun loadControlStatus(endpoint: String, markChecking: Boolean) {
        if (markChecking) mutableState.value = mutableState.value.copy(controlChecking = true)
        try {
            fetchAndApplyControlStatus(endpoint)
            refreshControllerFeedBestEffort(endpoint)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val normalized = EndpointPolicy.normalize(endpoint)
            if (mutableState.value.controlEndpoint == normalized) {
                mutableState.value = mutableState.value.copy(
                    controlStatus = null,
                    controlChecking = false,
                    controlError = safeMessage(error, "Controller unavailable"),
                )
            }
        }
    }

    private suspend fun fetchAndApplyControlStatus(endpoint: String): ControlStatus {
        val normalized = EndpointPolicy.normalize(endpoint)
            ?: throw ControlProtocolException("Paired controller endpoint is invalid")
        if (!matchesPairedController(normalized, mutableState.value.controlEndpoint)) {
            throw ControlProtocolException("Paired controller changed before status could be refreshed")
        }
        val status = controlRepository.status(normalized)
        if (!matchesPairedController(normalized, mutableState.value.controlEndpoint)) {
            throw ControlProtocolException("Paired controller changed while status was refreshing")
        }
        mutableState.value = mutableState.value.copy(
            controlStatus = status,
            controlChecking = false,
            controlError = when {
                !status.commandAuthorityEnabled -> "Remote commands are disabled on this observer"
                !status.jobJournalAvailable -> "The controller job journal is unavailable; updates are disabled"
                else -> null
            },
        )
        if (mutableState.value.activeJob == null) {
            status.recentJobs.firstOrNull { it.id == status.activeJobId }?.let { job ->
                val requestId = job.requestId ?: return@let
                val targets = job.targetHostIds
                if (targets.isEmpty()) return@let
                val storedJob = StoredControlJob(
                    endpoint,
                    requireNotNull(ControlEndpointPolicy.baseForFeed(endpoint)),
                    job.id,
                    requestId,
                    job.action,
                    targets,
                )
                if (!jobStore.write(storedJob)) {
                    mutableState.value = mutableState.value.copy(
                        jobError = "Active job progress is visible but could not be saved for restart recovery",
                    )
                }
                mutableState.value = mutableState.value.copy(activeJob = decorateJob(job))
                resumeStoredJob(storedJob)
            }
        }
        return status
    }

    private suspend fun refreshControllerFeedStrict(endpoint: String) = controllerFeedMutex.withLock {
        val normalized = EndpointPolicy.normalize(endpoint)
            ?: throw IOException("Paired controller feed endpoint is invalid")
        if (!matchesPairedController(normalized, mutableState.value.controlEndpoint)) {
            throw ControlProtocolException("Paired controller changed before its feed could be refreshed")
        }
        val result = controllerFeedLoader.live(normalized)
        if (!matchesPairedController(normalized, mutableState.value.controlEndpoint)) {
            throw ControlProtocolException("Paired controller changed while its feed was refreshing")
        }
        mutableState.value = mutableState.value.copy(controllerFeed = result.feed)
    }

    private suspend fun refreshControllerFeedBestEffort(endpoint: String) {
        try {
            refreshControllerFeedStrict(endpoint)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Preserve the last exact live controller snapshot until a later cycle succeeds.
        }
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

    private fun decorateJob(job: ControlJob): ControlJob =
        job.withCapabilityNames(mutableState.value.controlStatus?.capabilities.orEmpty())

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
        if (!result.fromCache) mutableState.value.controlEndpoint?.let(::checkControl)
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
        controlStatusJob?.cancel()
        jobPollingJob?.cancel()
        updateCheckJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val AUTO_REFRESH_MILLIS = 60_000L
        private const val JOB_POLL_MILLIS = 1_500L
        private const val MAX_JOB_POLL_MILLIS = 15_000L
        private const val BUSY_RETRY_MILLIS = 2_500L
        private const val RETRY_SUBMISSION_MILLIS = 10_000L
        private const val PAIRING_WAIT_MESSAGE =
            "Wait for the current fleet operation or update check to finish before pairing another controller"
        private val JOB_POLL_DEADLINE: Duration = Duration.ofHours(6)
        private val STALE_AFTER: Duration = Duration.ofMinutes(2)

        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = FleetlightViewModel(application) as T
            }
    }
}

private fun safeMessage(error: Throwable, fallback: String): String = error.message
    ?.replace(Regex("https://[^ ]+"), "the controller")
    ?.take(240)
    ?.takeIf(String::isNotBlank)
    ?: fallback

private fun retryableSubmissionFailure(error: Throwable): Boolean =
    error.isRetryableControlFailure() || error.isControllerBusyFailure()

internal fun Throwable.isControllerBusyFailure(): Boolean =
    this is ControlHttpException && status == 409 && errorCode == "controller-busy"

internal fun storedJobRetryAfterLookupFailure(
    stored: StoredControlJob,
    error: Throwable,
): StoredControlJob? = stored.takeIf { retryableSubmissionFailure(error) }

internal fun shouldClearStoredCheckAfterFailure(activeCheck: ControlCheck?, error: Throwable): Boolean =
    when {
        retryableCheckRecoveryFailure(error) -> false
        activeCheck?.state?.isTerminal == true -> true
        error.message.orEmpty().contains("polling paused", ignoreCase = true) -> false
        else -> true
    }

private fun retryableCheckRecoveryFailure(error: Throwable): Boolean =
    error.isRetryableControlFailure()

internal fun pairingBlockedByControlWork(
    state: FleetUiState,
    hasStoredJob: Boolean,
    hasStoredCheck: Boolean,
): Boolean = hasStoredJob || hasStoredCheck ||
    state.pairing ||
    state.checkSyncPending ||
    state.updateCheckSubmitting ||
    state.activeJob?.state?.isTerminal == false ||
    state.activeCheck?.state?.isTerminal == false

internal fun matchesPairedController(expectedEndpoint: String, currentEndpoint: String?): Boolean =
    EndpointPolicy.normalize(expectedEndpoint)?.let { it == currentEndpoint } == true
