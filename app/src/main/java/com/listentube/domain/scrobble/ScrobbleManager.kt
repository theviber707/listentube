// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube.domain.scrobble

import android.os.SystemClock
import com.listentube.core.result.AppResult
import com.listentube.core.result.DomainError
import com.listentube.data.listenbrainz.local.QueuedScrobbleEntity
import com.listentube.data.listenbrainz.local.ScrobbleDao
import com.listentube.data.listenbrainz.model.ScrobbleTrack
import com.listentube.data.listenbrainz.remote.ListenBrainzApiClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Snapshot of the active playback scrobble session for UI observability and diagnostics.
 */
data class ScrobbleSessionState(
    val currentTrack: ScrobbleTrack? = null,
    val startedAtEpochSeconds: Long = 0L,
    val accumulatedPlayedMs: Long = 0L,
    val thresholdMs: Long = 0L,
    val isPlaying: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val isEligibleForScrobble: Boolean = false,
    val playingNowDispatched: Boolean = false,
    val scrobbleCompleted: Boolean = false,
    val rateLimitResetAtElapsedMs: Long = 0L,
) {
    val progressFraction: Float
        get() = if (thresholdMs > 0L) {
            (accumulatedPlayedMs.toFloat() / thresholdMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

/**
 * Orchestrates ListenBrainz `playing_now` and completed listen (`single` / `import`) submissions.
 *
 * Key responsibilities:
 * 1. **Exact Duration Threshold Tracking**:
 *    - Accrues only real, active playback time using a monotonic clock ([elapsedRealtimeMsProvider]).
 *    - Pauses accrual immediately when playback is paused/buffered/stopped.
 *    - Ignores forward/backward seek jumps so scrubbing does not artificially trigger scrobbles.
 *    - Computes the exact ListenBrainz threshold: `min(durationMs / 2, 240_000 ms)` (50% of track
 *      duration or 4 minutes, whichever comes first) for tracks at least 30 seconds long.
 * 2. **HTTP 429 `X-RateLimit-Reset-In` Backoff**:
 *    - Honors `X-RateLimit-Reset-In` (in seconds) returned by [ListenBrainzApiClient].
 *    - Persists the backoff window into [ScrobbleDao] and schedules an automatic wakeup job
 *      to flush queued listens as soon as the reset window elapses.
 * 3. **Offline Room Queueing for Both `playing_now` and Completed Listens**:
 *    - Queues failed or offline `playing_now` notifications in Room via [ScrobbleDao.replacePlayingNow]
 *      with a track-duration TTL so only the latest non-expired `playing_now` is sent upon reconnect.
 *    - Uses write-ahead Room queueing ([ScrobbleDao.insertCompletedListen]) for completed listens
 *      and flushes them in batches (`single` or `import`) with exponential backoff and batch bisection
 *      on HTTP 400 errors.
 */
class ScrobbleManager(
    private val apiClient: ListenBrainzApiClient,
    private val scrobbleDao: ScrobbleDao,
    private val userTokenProvider: suspend () -> String?,
    private val scrobblingEnabledProvider: suspend () -> Boolean = { true },
    private val elapsedRealtimeMsProvider: () -> Long = { SystemClock.elapsedRealtime() },
    private val currentEpochMillisProvider: () -> Long = { System.currentTimeMillis() },
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    externalScope: CoroutineScope? = null,
) {
    private val scope: CoroutineScope = externalScope ?: CoroutineScope(SupervisorJob() + dispatcher)
    private val sessionMutex = Mutex()
    private val flushMutex = Mutex()

    private var activeSession: ActivePlaybackSession? = null
    private var thresholdTickerJob: Job? = null
    private var scheduledRetryJob: Job? = null
    private var rateLimitResetAtElapsedMs: Long = 0L

    private val _sessionState = MutableStateFlow(ScrobbleSessionState())
    val sessionState: StateFlow<ScrobbleSessionState> = _sessionState.asStateFlow()

    val rateLimitState = apiClient.rateLimitState
    val pendingCompletedCount: Flow<Int> = scrobbleDao.observePendingCompletedCount()
    val pendingCompletedListens: Flow<List<QueuedScrobbleEntity>> = scrobbleDao.observePendingCompletedListens()
    val totalQueuedCount: Flow<Int> = scrobbleDao.observeTotalQueuedCount()
    val queuedPlayingNow: Flow<QueuedScrobbleEntity?> = scrobbleDao.observeQueuedPlayingNow()

    /**
     * Removes a single queued scrobble entry by [id].
     */
    suspend fun removeQueuedScrobble(id: Long) {
        scrobbleDao.deleteById(id)
    }

    /**
     * Clears all pending `playing_now` and `completed` items from the offline Room queue.
     */
    suspend fun clearOfflineQueue() {
        scrobbleDao.clearPlayingNowQueue()
        scrobbleDao.clearCompletedQueue()
    }

    /**
     * Mutable internal representation of a single track playback session.
     */
    private class ActivePlaybackSession(
        val track: ScrobbleTrack,
        val startedAtEpochSeconds: Long,
        val thresholdMs: Long,
        val isEligible: Boolean,
        var accumulatedPlayedMs: Long = 0L,
        var lastResumeElapsedMs: Long? = null,
        var playbackSpeed: Float = 1.0f,
        var playingNowDispatched: Boolean = false,
        var scrobbleCompleted: Boolean = false,
    ) {
        val isPlaying: Boolean
            get() = lastResumeElapsedMs != null

        /**
         * Returns the exact accumulated playback time in milliseconds at [nowElapsedMs]
         * without mutating state.
         */
        fun currentPlayedMs(nowElapsedMs: Long): Long {
            val resumeAt = lastResumeElapsedMs ?: return accumulatedPlayedMs
            val deltaElapsedMs = (nowElapsedMs - resumeAt).coerceAtLeast(0L)
            val scaledDeltaMs = (deltaElapsedMs * playbackSpeed.coerceAtLeast(0f)).roundToLong()
            return accumulatedPlayedMs + scaledDeltaMs
        }

        /**
         * Commits elapsed playback time since [lastResumeElapsedMs] into [accumulatedPlayedMs]
         * and resets the anchor to [nowElapsedMs] (if [keepPlaying] is true) or `null` (if paused).
         */
        fun accrueUpTo(nowElapsedMs: Long, keepPlaying: Boolean) {
            val resumeAt = lastResumeElapsedMs
            if (resumeAt != null) {
                val deltaElapsedMs = (nowElapsedMs - resumeAt).coerceAtLeast(0L)
                val scaledDeltaMs = (deltaElapsedMs * playbackSpeed.coerceAtLeast(0f)).roundToLong()
                accumulatedPlayedMs += scaledDeltaMs
            }
            lastResumeElapsedMs = if (keepPlaying) nowElapsedMs else null
        }

        /**
         * Returns how many real-time milliseconds remain until [thresholdMs] is reached at the
         * current [playbackSpeed], or `0L` if already reached.
         */
        fun remainingRealtimeMsToThreshold(nowElapsedMs: Long): Long {
            val remainingTrackMs = (thresholdMs - currentPlayedMs(nowElapsedMs)).coerceAtLeast(0L)
            if (remainingTrackMs == 0L) return 0L
            val speed = playbackSpeed.coerceAtLeast(MIN_PLAYBACK_SPEED)
            return (remainingTrackMs / speed).roundToLong().coerceAtLeast(1L)
        }
    }

    /**
     * Invoked when a new track starts playing or the player transitions to a different media item.
     */
    suspend fun onTrackStarted(
        track: ScrobbleTrack,
        isPlaying: Boolean = true,
        playbackSpeed: Float = 1.0f,
    ) {
        if (!scrobblingEnabledProvider()) return

        var previousToScrobble: Pair<ScrobbleTrack, Long>? = null

        sessionMutex.withLock {
            val nowElapsedMs = elapsedRealtimeMsProvider()
            val nowEpochMs = currentEpochMillisProvider()

            // Finalize any previous active session in case it crossed the threshold right before transition
            activeSession?.let { prev ->
                prev.accrueUpTo(nowElapsedMs, keepPlaying = false)
                if (prev.isEligible && !prev.scrobbleCompleted && prev.accumulatedPlayedMs >= prev.thresholdMs) {
                    prev.scrobbleCompleted = true
                    previousToScrobble = prev.track to prev.startedAtEpochSeconds
                }
            }

            thresholdTickerJob?.cancel()
            thresholdTickerJob = null

            val isEligible = isTrackEligibleForScrobble(track.durationMs)
            val thresholdMs = if (isEligible) calculateThresholdMs(track.durationMs) else 0L
            val startedAtEpochSeconds = (nowEpochMs / 1_000L).coerceAtLeast(1L)

            val newSession = ActivePlaybackSession(
                track = track,
                startedAtEpochSeconds = startedAtEpochSeconds,
                thresholdMs = thresholdMs,
                isEligible = isEligible,
                accumulatedPlayedMs = 0L,
                lastResumeElapsedMs = if (isPlaying) nowElapsedMs else null,
                playbackSpeed = playbackSpeed.coerceAtLeast(MIN_PLAYBACK_SPEED),
            )
            activeSession = newSession
            publishSessionStateLocked(nowElapsedMs)

            if (isPlaying && isEligible) {
                startThresholdWatcherLocked()
            }
        }

        // If the outgoing track just crossed the threshold, persist and flush it
        previousToScrobble?.let { (prevTrack, prevStartedAt) ->
            enqueueAndFlushCompletedListen(prevTrack, prevStartedAt)
        }

        // Dispatch or queue `playing_now` for the newly started track
        dispatchOrQueuePlayingNow(track)
    }

    /**
     * Updates the current session's track duration and recalculates its exact scrobble threshold
     * if the track duration was unknown (`<= 0L`) prior to ExoPlayer reaching `STATE_READY`.
     */
    suspend fun onTrackDurationResolved(
        videoId: String,
        durationMs: Long,
    ) {
        if (durationMs <= 0L) return
        var readyToScrobble: Pair<ScrobbleTrack, Long>? = null

        sessionMutex.withLock {
            val session = activeSession ?: return
            if (session.track.videoId != videoId || session.track.durationMs == durationMs) return

            val nowElapsedMs = elapsedRealtimeMsProvider()
            val wasPlaying = session.isPlaying
            session.accrueUpTo(nowElapsedMs, keepPlaying = wasPlaying)

            val updatedTrack = session.track.copy(durationMs = durationMs)
            val isEligible = isTrackEligibleForScrobble(durationMs)
            val thresholdMs = if (isEligible) calculateThresholdMs(durationMs) else 0L

            val updatedSession = ActivePlaybackSession(
                track = updatedTrack,
                startedAtEpochSeconds = session.startedAtEpochSeconds,
                thresholdMs = thresholdMs,
                isEligible = isEligible,
                accumulatedPlayedMs = session.accumulatedPlayedMs,
                lastResumeElapsedMs = session.lastResumeElapsedMs,
                playbackSpeed = session.playbackSpeed,
                playingNowDispatched = session.playingNowDispatched,
                scrobbleCompleted = session.scrobbleCompleted,
            )

            if (updatedSession.isEligible &&
                !updatedSession.scrobbleCompleted &&
                updatedSession.accumulatedPlayedMs >= updatedSession.thresholdMs
            ) {
                updatedSession.scrobbleCompleted = true
                readyToScrobble = updatedSession.track to updatedSession.startedAtEpochSeconds
            }

            activeSession = updatedSession
            if (wasPlaying && updatedSession.isEligible && !updatedSession.scrobbleCompleted) {
                startThresholdWatcherLocked()
            } else {
                thresholdTickerJob?.cancel()
                thresholdTickerJob = null
            }
            publishSessionStateLocked(nowElapsedMs)
        }

        readyToScrobble?.let { (track, startedAt) ->
            enqueueAndFlushCompletedListen(track, startedAt)
        }
    }

    /**
     * Invoked whenever the player's play/pause state or playback speed changes.
     */
    suspend fun onPlaybackStateChanged(
        isPlaying: Boolean,
        playbackSpeed: Float? = null,
    ) {
        var readyToScrobble: Pair<ScrobbleTrack, Long>? = null

        sessionMutex.withLock {
            val session = activeSession ?: return
            val nowElapsedMs = elapsedRealtimeMsProvider()

            // Accrue time with the speed that was active during the elapsed interval
            session.accrueUpTo(nowElapsedMs, keepPlaying = isPlaying)
            if (playbackSpeed != null) {
                session.playbackSpeed = playbackSpeed.coerceAtLeast(MIN_PLAYBACK_SPEED)
            }

            if (session.isEligible && !session.scrobbleCompleted && session.accumulatedPlayedMs >= session.thresholdMs) {
                session.scrobbleCompleted = true
                readyToScrobble = session.track to session.startedAtEpochSeconds
            }

            if (isPlaying && session.isEligible && !session.scrobbleCompleted) {
                startThresholdWatcherLocked()
            } else {
                thresholdTickerJob?.cancel()
                thresholdTickerJob = null
            }

            publishSessionStateLocked(nowElapsedMs)
        }

        readyToScrobble?.let { (track, startedAt) ->
            enqueueAndFlushCompletedListen(track, startedAt)
        }
    }

    /**
     * Invoked when the user seeks to a new position in the current track.
     *
     * Accrues only the real monotonic time played up to the seek instant and resets the monotonic
     * anchor so skipped media time is never counted toward the scrobble threshold.
     */
    suspend fun onSeek() {
        var readyToScrobble: Pair<ScrobbleTrack, Long>? = null

        sessionMutex.withLock {
            val session = activeSession ?: return
            val nowElapsedMs = elapsedRealtimeMsProvider()
            val wasPlaying = session.isPlaying

            session.accrueUpTo(nowElapsedMs, keepPlaying = wasPlaying)

            if (session.isEligible && !session.scrobbleCompleted && session.accumulatedPlayedMs >= session.thresholdMs) {
                session.scrobbleCompleted = true
                readyToScrobble = session.track to session.startedAtEpochSeconds
            }

            if (wasPlaying && session.isEligible && !session.scrobbleCompleted) {
                startThresholdWatcherLocked()
            }
            publishSessionStateLocked(nowElapsedMs)
        }

        readyToScrobble?.let { (track, startedAt) ->
            enqueueAndFlushCompletedListen(track, startedAt)
        }
    }

    /**
     * Invoked when the current track finishes playback or repeats from the beginning.
     *
     * @param isRepeating If `true`, starts a fresh scrobble session for the same track after
     *   evaluating the completed session.
     */
    suspend fun onTrackEnded(isRepeating: Boolean = false) {
        var readyToScrobble: Pair<ScrobbleTrack, Long>? = null
        var repeatTrack: Pair<ScrobbleTrack, Float>? = null

        sessionMutex.withLock {
            val session = activeSession ?: return
            val nowElapsedMs = elapsedRealtimeMsProvider()

            session.accrueUpTo(nowElapsedMs, keepPlaying = false)
            thresholdTickerJob?.cancel()
            thresholdTickerJob = null

            if (session.isEligible && !session.scrobbleCompleted && session.accumulatedPlayedMs >= session.thresholdMs) {
                session.scrobbleCompleted = true
                readyToScrobble = session.track to session.startedAtEpochSeconds
            }

            if (isRepeating) {
                repeatTrack = session.track to session.playbackSpeed
            } else {
                activeSession = null
                _sessionState.value = ScrobbleSessionState(
                    rateLimitResetAtElapsedMs = rateLimitResetAtElapsedMs,
                )
            }
        }

        readyToScrobble?.let { (track, startedAt) ->
            enqueueAndFlushCompletedListen(track, startedAt)
        }

        repeatTrack?.let { (track, speed) ->
            onTrackStarted(track = track, isPlaying = true, playbackSpeed = speed)
        }
    }

    /**
     * Stops tracking the current session and flushes any threshold-qualified scrobble.
     */
    suspend fun onStop() {
        onTrackEnded(isRepeating = false)
    }

    /**
     * Launches a coroutine that wakes up periodically and at the exact millisecond the
     * scrobble threshold will be reached during active playback.
     * Must be called while holding [sessionMutex].
     */
    private fun startThresholdWatcherLocked() {
        thresholdTickerJob?.cancel()
        val session = activeSession ?: return
        if (!session.isPlaying || !session.isEligible || session.scrobbleCompleted) return

        thresholdTickerJob = scope.launch {
            while (isActive) {
                var waitMs = CHECKPOINT_INTERVAL_MS
                var qualifiedListen: Pair<ScrobbleTrack, Long>? = null

                sessionMutex.withLock {
                    val current = activeSession
                    if (current == null || !current.isPlaying || !current.isEligible || current.scrobbleCompleted) {
                        return@launch
                    }

                    val nowElapsedMs = elapsedRealtimeMsProvider()
                    val playedMs = current.currentPlayedMs(nowElapsedMs)
                    publishSessionStateLocked(nowElapsedMs)

                    if (playedMs >= current.thresholdMs) {
                        current.accrueUpTo(nowElapsedMs, keepPlaying = true)
                        current.scrobbleCompleted = true
                        publishSessionStateLocked(nowElapsedMs)
                        qualifiedListen = current.track to current.startedAtEpochSeconds
                    } else {
                        val remainingMs = current.remainingRealtimeMsToThreshold(nowElapsedMs)
                        waitMs = min(remainingMs, CHECKPOINT_INTERVAL_MS).coerceAtLeast(MIN_TICK_DELAY_MS)
                    }
                }

                if (qualifiedListen != null) {
                    val (track, startedAt) = qualifiedListen!!
                    enqueueAndFlushCompletedListen(track, startedAt)
                    return@launch
                }

                delay(waitMs)
            }
        }
    }

    /**
     * Dispatches a `playing_now` notification to ListenBrainz or queues it in Room if offline,
     * rate-limited, or unauthenticated.
     */
    private suspend fun dispatchOrQueuePlayingNow(track: ScrobbleTrack) {
        if (track.validateMetadata().isFailure) return

        val nowEpochMs = currentEpochMillisProvider()
        val nowElapsedMs = elapsedRealtimeMsProvider()
        val token = userTokenProvider()?.trim().orEmpty()

        // If missing token or currently inside an active HTTP 429 rate-limit backoff window,
        // immediately persist `playing_now` into the offline Room queue.
        if (token.isEmpty() || isRateLimitCoolingDown(nowElapsedMs)) {
            val nextRetryAtEpochMs = if (isRateLimitCoolingDown(nowElapsedMs)) {
                nowEpochMs + (rateLimitResetAtElapsedMs - nowElapsedMs).coerceAtLeast(1_000L)
            } else {
                nowEpochMs
            }
            scrobbleDao.replacePlayingNow(
                QueuedScrobbleEntity.forPlayingNow(
                    track = track,
                    nowEpochMs = nowEpochMs,
                    nextRetryAtEpochMs = nextRetryAtEpochMs,
                    lastError = if (token.isEmpty()) "Missing ListenBrainz token" else "Rate limit active",
                ),
            )
            return
        }

        when (val result = apiClient.submitPlayingNow(userToken = token, track = track)) {
            is AppResult.Success -> {
                scrobbleDao.clearPlayingNowQueue()
                sessionMutex.withLock {
                    if (activeSession?.track?.videoId == track.videoId) {
                        activeSession?.playingNowDispatched = true
                        publishSessionStateLocked(elapsedRealtimeMsProvider())
                    }
                }
                // Opportunistically flush any queued completed listens now that connectivity is confirmed
                scope.launch { flushQueue() }
            }

            is AppResult.Failure -> {
                val error = result.error
                if (error is DomainError.Network.RateLimited) {
                    handleRateLimitedError(error)
                }
                if (error.shouldQueueOffline) {
                    val retryDelayMs = when (error) {
                        is DomainError.Network.RateLimited -> error.resetInMillis
                        else -> calculateExponentialBackoffMs(attemptCount = 1)
                    }
                    scrobbleDao.replacePlayingNow(
                        QueuedScrobbleEntity.forPlayingNow(
                            track = track,
                            nowEpochMs = nowEpochMs,
                            nextRetryAtEpochMs = nowEpochMs + retryDelayMs,
                            lastError = error.message,
                        ),
                    )
                    scheduleDelayedFlush(retryDelayMs)
                }
            }
        }
    }

    /**
     * Persists a completed listen to Room first (write-ahead durability) and triggers [flushQueue].
     */
    private suspend fun enqueueAndFlushCompletedListen(
        track: ScrobbleTrack,
        startedAtEpochSeconds: Long,
    ) {
        if (!scrobblingEnabledProvider()) return
        if (track.validateMetadata().isFailure) return

        val nowEpochMs = currentEpochMillisProvider()
        val nowElapsedMs = elapsedRealtimeMsProvider()
        val initialNextRetryMs = if (isRateLimitCoolingDown(nowElapsedMs)) {
            nowEpochMs + (rateLimitResetAtElapsedMs - nowElapsedMs).coerceAtLeast(1_000L)
        } else {
            nowEpochMs
        }

        val entity = QueuedScrobbleEntity.forCompletedListen(
            track = track,
            listenedAtEpochSeconds = startedAtEpochSeconds,
            nowEpochMs = nowEpochMs,
            nextRetryAtEpochMs = initialNextRetryMs,
        )
        scrobbleDao.insertCompletedListen(entity)
        flushQueue()
    }

    /**
     * Flushes both queued `playing_now` notifications and queued completed listens from Room.
     *
     * Safe to invoke concurrently from playback callbacks, network connectivity monitors, or WorkManager;
     * serialized internally via [flushMutex].
     */
    suspend fun flushQueue(): AppResult<Int> = flushMutex.withLock {
        if (!scrobblingEnabledProvider()) {
            return AppResult.Success(0)
        }

        val nowEpochMs = currentEpochMillisProvider()
        val nowElapsedMs = elapsedRealtimeMsProvider()

        // 1. Prune expired `playing_now` notifications and dead-letter items exceeding max retries
        scrobbleDao.pruneExpiredPlayingNow(nowEpochMs)
        scrobbleDao.pruneDeadLetters(ScrobbleDao.MAX_RETRY_ATTEMPTS)

        // 2. Respect active HTTP 429 X-RateLimit-Reset-In backoff window
        if (isRateLimitCoolingDown(nowElapsedMs)) {
            val remainingMs = (rateLimitResetAtElapsedMs - nowElapsedMs).coerceAtLeast(1_000L)
            scheduleDelayedFlush(remainingMs)
            return AppResult.Failure(
                DomainError.Network.RateLimited(
                    resetInSeconds = (remainingMs + 999L) / 1_000L,
                ),
            )
        }

        val token = userTokenProvider()?.trim().orEmpty()
        if (token.isEmpty()) {
            return AppResult.Failure(DomainError.Validation.MissingToken())
        }

        var totalFlushed = 0

        // 3. Flush pending `playing_now` notification if one is queued and still fresh
        val pendingPlayingNow = scrobbleDao.getReadyPlayingNow(nowEpochMs)
        if (pendingPlayingNow != null) {
            when (
                val playingNowResult = apiClient.submitPlayingNow(
                    userToken = token,
                    track = pendingPlayingNow.toScrobbleTrack(),
                )
            ) {
                is AppResult.Success -> {
                    scrobbleDao.deleteById(pendingPlayingNow.id)
                    totalFlushed += 1
                    sessionMutex.withLock {
                        if (activeSession?.track?.videoId == pendingPlayingNow.videoId) {
                            activeSession?.playingNowDispatched = true
                            publishSessionStateLocked(elapsedRealtimeMsProvider())
                        }
                    }
                }

                is AppResult.Failure -> {
                    when (val error = playingNowResult.error) {
                        is DomainError.Network.BadRequest,
                        is DomainError.Validation,
                        -> {
                            // Unrecoverable payload error: remove malformed `playing_now`
                            scrobbleDao.deleteById(pendingPlayingNow.id)
                        }

                        is DomainError.Network.RateLimited -> {
                            handleRateLimitedError(error)
                            return AppResult.Failure(error)
                        }

                        else -> {
                            val attemptedAt = currentEpochMillisProvider()
                            val backoffMs = calculateExponentialBackoffMs(pendingPlayingNow.attemptCount + 1)
                            scrobbleDao.recordFailedAttempt(
                                ids = listOf(pendingPlayingNow.id),
                                attemptedAtMs = attemptedAt,
                                nextRetryAtMs = attemptedAt + backoffMs,
                                errorMessage = error.message,
                            )
                            scheduleDelayedFlush(backoffMs)
                            return AppResult.Failure(error)
                        }
                    }
                }
            }
        }

        // 4. Flush pending completed listens in chronological batches
        while (true) {
            val loopNowEpochMs = currentEpochMillisProvider()
            val loopNowElapsedMs = elapsedRealtimeMsProvider()
            if (isRateLimitCoolingDown(loopNowElapsedMs)) {
                val remainingMs = (rateLimitResetAtElapsedMs - loopNowElapsedMs).coerceAtLeast(1_000L)
                scheduleDelayedFlush(remainingMs)
                break
            }

            val batch = scrobbleDao.getReadyCompletedListens(
                nowEpochMs = loopNowEpochMs,
                limit = MAX_IMPORT_BATCH_SIZE,
            )
            if (batch.isEmpty()) break

            val validItems = mutableListOf<QueuedScrobbleEntity>()
            val invalidIds = mutableListOf<Long>()
            for (item in batch) {
                val track = item.toScrobbleTrack()
                val listenedAt = item.listenedAtEpochSeconds
                if (listenedAt == null || listenedAt <= 0L || track.validateMetadata().isFailure) {
                    invalidIds += item.id
                } else {
                    validItems += item
                }
            }
            if (invalidIds.isNotEmpty()) {
                scrobbleDao.deleteByIds(invalidIds)
            }
            if (validItems.isEmpty()) continue

            val batchPayloads = validItems.map { entity ->
                entity.toScrobbleTrack() to entity.listenedAtEpochSeconds!!
            }
            val batchIds = validItems.map { it.id }

            when (val batchResult = apiClient.submitImportBatch(userToken = token, completedListens = batchPayloads)) {
                is AppResult.Success -> {
                    scrobbleDao.deleteByIds(batchIds)
                    totalFlushed += validItems.size
                    syncClientRateLimitWindow()
                }

                is AppResult.Failure -> {
                    when (val error = batchResult.error) {
                        is DomainError.Network.RateLimited -> {
                            handleRateLimitedError(error)
                            return AppResult.Failure(error)
                        }

                        is DomainError.Network.BadRequest -> {
                            if (validItems.size == 1) {
                                // Single malformed item: drop it so it cannot poison the queue
                                scrobbleDao.deleteByIds(batchIds)
                            } else {
                                // Batch contained at least one malformed item: isolate by submitting individually
                                totalFlushed += flushBatchIndividually(token, validItems)
                            }
                        }

                        is DomainError.Network.Unauthorized -> {
                            return AppResult.Failure(error)
                        }

                        else -> {
                            val attemptedAt = currentEpochMillisProvider()
                            val maxAttemptInBatch = validItems.maxOf { it.attemptCount } + 1
                            val backoffMs = calculateExponentialBackoffMs(maxAttemptInBatch)
                            scrobbleDao.recordFailedAttempt(
                                ids = batchIds,
                                attemptedAtMs = attemptedAt,
                                nextRetryAtMs = attemptedAt + backoffMs,
                                errorMessage = error.message,
                            )
                            scheduleDelayedFlush(backoffMs)
                            return AppResult.Failure(error)
                        }
                    }
                }
            }
        }

        // If any items remain scheduled for a future retry timestamp, ensure a wakeup timer is set
        val earliestNextRetryAtMs = scrobbleDao.getEarliestNextRetryAtMs()
        if (earliestNextRetryAtMs != null) {
            val delayMs = (earliestNextRetryAtMs - currentEpochMillisProvider()).coerceAtLeast(1_000L)
            scheduleDelayedFlush(delayMs)
        }

        return AppResult.Success(totalFlushed)
    }

    /**
     * Fallback used when an `import` batch fails with HTTP 400 Bad Request, submitting each item
     * individually so valid listens succeed and only malformed items are discarded.
     */
    private suspend fun flushBatchIndividually(
        token: String,
        items: List<QueuedScrobbleEntity>,
    ): Int {
        var flushed = 0
        for (item in items) {
            val listenedAt = item.listenedAtEpochSeconds ?: continue
            when (
                val singleResult = apiClient.submitSingleListen(
                    userToken = token,
                    track = item.toScrobbleTrack(),
                    listenedAtEpochSeconds = listenedAt,
                )
            ) {
                is AppResult.Success -> {
                    scrobbleDao.deleteById(item.id)
                    flushed += 1
                    syncClientRateLimitWindow()
                }

                is AppResult.Failure -> {
                    when (val error = singleResult.error) {
                        is DomainError.Network.BadRequest,
                        is DomainError.Validation,
                        -> scrobbleDao.deleteById(item.id)

                        is DomainError.Network.RateLimited -> {
                            handleRateLimitedError(error)
                            break
                        }

                        else -> {
                            val attemptedAt = currentEpochMillisProvider()
                            val backoffMs = calculateExponentialBackoffMs(item.attemptCount + 1)
                            scrobbleDao.recordFailedAttempt(
                                ids = listOf(item.id),
                                attemptedAtMs = attemptedAt,
                                nextRetryAtMs = attemptedAt + backoffMs,
                                errorMessage = error.message,
                            )
                            scheduleDelayedFlush(backoffMs)
                            break
                        }
                    }
                }
            }
        }
        return flushed
    }

    /**
     * Synchronizes proactive rate-limit exhaustion (`X-RateLimit-Remaining == 0`) from [apiClient].
     */
    private suspend fun syncClientRateLimitWindow() {
        val rlState = apiClient.rateLimitState.value
        val nowElapsedMs = elapsedRealtimeMsProvider()
        if (rlState.isCoolingDown(nowElapsedMs)) {
            val remainingSeconds = rlState.remainingCooldownSeconds(nowElapsedMs)
            handleRateLimitedError(
                DomainError.Network.RateLimited(
                    resetInSeconds = remainingSeconds,
                    limit = rlState.limit,
                    remaining = rlState.remaining ?: 0,
                ),
            )
        }
    }

    /**
     * Applies an HTTP 429 `X-RateLimit-Reset-In` backoff window to both memory state and Room,
     * and schedules an automatic queue flush when the window resets.
     */
    private suspend fun handleRateLimitedError(error: DomainError.Network.RateLimited) {
        val nowElapsedMs = elapsedRealtimeMsProvider()
        val nowEpochMs = currentEpochMillisProvider()
        val backoffMs = error.resetInMillis

        val targetElapsedMs = nowElapsedMs + backoffMs
        val targetEpochMs = nowEpochMs + backoffMs

        sessionMutex.withLock {
            rateLimitResetAtElapsedMs = maxOf(rateLimitResetAtElapsedMs, targetElapsedMs)
            publishSessionStateLocked(nowElapsedMs)
        }

        scrobbleDao.applyGlobalRateLimitBackoff(
            rateLimitResetAtEpochMs = targetEpochMs,
            reason = error.message,
        )
        scheduleDelayedFlush(backoffMs)
    }

    private fun isRateLimitCoolingDown(nowElapsedMs: Long): Boolean {
        val clientResetAt = apiClient.rateLimitState.value.resetAtElapsedMs
        val effectiveResetAt = maxOf(rateLimitResetAtElapsedMs, clientResetAt)
        return nowElapsedMs < effectiveResetAt
    }

    /**
     * Schedules a single coalesced coroutine to invoke [flushQueue] after [delayMs].
     */
    private fun scheduleDelayedFlush(delayMs: Long) {
        val safeDelayMs = delayMs.coerceAtLeast(MIN_RETRY_DELAY_MS)
        scheduledRetryJob?.cancel()
        scheduledRetryJob = scope.launch {
            delay(safeDelayMs)
            flushQueue()
        }
    }

    private fun publishSessionStateLocked(nowElapsedMs: Long) {
        val current = activeSession
        val effectiveRateLimitReset = maxOf(
            rateLimitResetAtElapsedMs,
            apiClient.rateLimitState.value.resetAtElapsedMs,
        )
        _sessionState.update {
            if (current == null) {
                ScrobbleSessionState(rateLimitResetAtElapsedMs = effectiveRateLimitReset)
            } else {
                ScrobbleSessionState(
                    currentTrack = current.track,
                    startedAtEpochSeconds = current.startedAtEpochSeconds,
                    accumulatedPlayedMs = current.currentPlayedMs(nowElapsedMs),
                    thresholdMs = current.thresholdMs,
                    isPlaying = current.isPlaying,
                    playbackSpeed = current.playbackSpeed,
                    isEligibleForScrobble = current.isEligible,
                    playingNowDispatched = current.playingNowDispatched,
                    scrobbleCompleted = current.scrobbleCompleted,
                    rateLimitResetAtElapsedMs = effectiveRateLimitReset,
                )
            }
        }
    }

    companion object {
        /**
         * Tracks shorter than 30 seconds are not scrobbled per ListenBrainz / Audioscrobbler rules.
         */
        const val MIN_TRACK_DURATION_MS = 30_000L

        /**
         * Maximum scrobble threshold cap: 4 minutes (240,000 ms).
         */
        const val MAX_SCROBBLE_THRESHOLD_MS = 240_000L

        /**
         * Maximum number of completed listens submitted in a single `import` request.
         */
        const val MAX_IMPORT_BATCH_SIZE = 50

        private const val CHECKPOINT_INTERVAL_MS = 1_000L
        private const val MIN_TICK_DELAY_MS = 50L
        private const val MIN_PLAYBACK_SPEED = 0.25f

        private const val MIN_RETRY_DELAY_MS = 1_000L
        private const val BASE_RETRY_BACKOFF_MS = 5_000L
        private const val MAX_RETRY_BACKOFF_MS = 30 * 60 * 1_000L // 30 minutes cap

        /**
         * Returns `true` if a track of [durationMs] is long enough to be scrobbled (>= 30 seconds).
         */
        fun isTrackEligibleForScrobble(durationMs: Long): Boolean =
            durationMs >= MIN_TRACK_DURATION_MS

        /**
         * Calculates the exact playback duration (in ms) required to qualify as a completed listen:
         * `min(durationMs / 2, 240_000L)` (half the track duration or 4 minutes, whichever comes first).
         */
        fun calculateThresholdMs(durationMs: Long): Long {
            if (!isTrackEligibleForScrobble(durationMs)) return Long.MAX_VALUE
            return min(durationMs / 2L, MAX_SCROBBLE_THRESHOLD_MS)
        }

        /**
         * Computes truncated exponential backoff with bounded jitter for transient network failures.
         */
        fun calculateExponentialBackoffMs(attemptCount: Int): Long {
            val clampedShift = (attemptCount - 1).coerceIn(0, 10)
            val exponential = (BASE_RETRY_BACKOFF_MS shl clampedShift).coerceAtMost(MAX_RETRY_BACKOFF_MS)
            val jitter = Random.nextLong(from = 0L, until = (exponential / 5L).coerceAtLeast(1L))
            return (exponential + jitter).coerceAtMost(MAX_RETRY_BACKOFF_MS)
        }
    }
}
