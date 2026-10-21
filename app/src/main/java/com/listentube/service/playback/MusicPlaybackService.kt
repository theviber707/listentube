// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube.service.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.listentube.core.result.AppResult
import com.listentube.core.result.DomainError
import com.listentube.data.innertube.YouTubeMusicProvider
import com.listentube.data.listenbrainz.model.ScrobbleTrack
import com.listentube.data.localmedia.LocalMediaStoreProvider
import com.listentube.domain.media.MediaSourceType
import com.listentube.domain.media.MediaTrack
import com.listentube.domain.media.ResolvedMediaStream
import com.listentube.domain.scrobble.ScrobbleManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * [IOException] wrapper that carries a typed [DomainError] through Media3's [ResolvingDataSource]
 * pipeline so `Player.Listener.onPlayerError` can inspect the exact upstream failure without
 * losing domain error context.
 */
class DomainErrorDataSourceException(
    val domainError: DomainError,
) : IOException(domainError.message, domainError.cause)

/**
 * Background [MediaSessionService] hosting [ExoPlayer], on-demand stream resolution via
 * [ResolvingDataSource], and real-time ListenBrainz scrobble tracking via [ScrobbleManager].
 */
@OptIn(UnstableApi::class)
open class MusicPlaybackService : MediaSessionService() {

    /**
     * Service-level dependency container that can be supplied via DI (Hilt/custom factory) or
     * overridden in subclasses/tests prior to `onCreate()`.
     */
    data class Dependencies(
        val youTubeMusicProvider: YouTubeMusicProvider,
        val localMediaStoreProvider: LocalMediaStoreProvider? = null,
        val scrobbleManager: ScrobbleManager,
        val okHttpClient: OkHttpClient = createDefaultPlaybackOkHttpClient(),
        val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var playerListener: Player.Listener? = null
    private var streamResolver: OnDemandStreamResolver? = null

    private val _lastPlaybackError = MutableStateFlow<DomainError?>(null)

    /**
     * Exposes the most recent typed [DomainError] encountered during stream resolution or playback.
     */
    val lastPlaybackError: StateFlow<DomainError?> = _lastPlaybackError.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        val deps = resolveDependencies() ?: return
        initializePlayerAndSession(deps)
    }

    /**
     * Initializes [ExoPlayer], [ResolvingDataSource.Factory], [ScrobblePlayerListener], and [MediaSession].
     * Can be called directly with explicit [Dependencies] when configuring the service programmatically.
     */
    fun initializePlayerAndSession(deps: Dependencies) {
        if (exoPlayer != null) return

        val resolver = OnDemandStreamResolver(
            youTubeMusicProvider = deps.youTubeMusicProvider,
            localMediaStoreProvider = deps.localMediaStoreProvider,
            ioDispatcher = deps.ioDispatcher,
            onError = { domainError -> _lastPlaybackError.value = domainError },
        )
        streamResolver = resolver

        val okHttpDataSourceFactory = OkHttpDataSource.Factory(deps.okHttpClient)
            .setUserAgent(PLAYBACK_USER_AGENT)
        val upstreamDataSourceFactory = DefaultDataSource.Factory(this, okHttpDataSourceFactory)
        val resolvingDataSourceFactory = ResolvingDataSource.Factory(
            upstreamDataSourceFactory,
            resolver,
        )
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(resolvingDataSourceFactory)

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        // Tee the decoded PCM into the spectrum analyzer that drives the Now Playing visualizer.
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(TeeAudioProcessor(AudioSpectrumAnalyzer)))
                .setAudioTrackBufferSizeProvider(AudioSpectrumAnalyzer.bufferSizeProvider)
                .build()
        }

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        val listener = ScrobblePlayerListener(
            player = player,
            scrobbleManager = deps.scrobbleManager,
            streamResolver = resolver,
            scope = serviceScope,
            onDomainError = { domainError -> _lastPlaybackError.value = domainError },
        )
        player.addListener(listener)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                AudioSpectrumAnalyzer.onPlaybackActiveChanged(isPlaying)
            }
        })

        val session = MediaSession.Builder(this, player)
            .setCallback(PlaybackSessionCallback())
            .build()

        this.exoPlayer = player
        this.playerListener = listener
        this.mediaSession = session
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = exoPlayer
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_ENDED
        ) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        val deps = resolveDependencies()
        if (deps != null) {
            runBlocking {
                deps.scrobbleManager.onStop()
            }
        }

        mediaSession?.run {
            playerListener?.let { player.removeListener(it) }
            player.release()
            release()
        }
        mediaSession = null
        exoPlayer = null
        playerListener = null
        streamResolver?.clearCache()
        streamResolver = null
        serviceScope.cancel()
        super.onDestroy()
    }

    protected open fun resolveDependencies(): Dependencies? = dependencyProvider?.invoke()

    /**
     * [ResolvingDataSource.Resolver] that intercepts custom `ytm://track/<videoId>`,
     * `https://music.youtube.com/watch?v=<videoId>`, and `local://track/<id>` URIs on ExoPlayer's
     * loader thread, resolving playable stream URLs on demand via [YouTubeMusicProvider] or
     * [LocalMediaStoreProvider].
     */
    internal class OnDemandStreamResolver(
        private val youTubeMusicProvider: YouTubeMusicProvider,
        private val localMediaStoreProvider: LocalMediaStoreProvider?,
        private val ioDispatcher: CoroutineDispatcher,
        private val currentEpochSecondsProvider: () -> Long = { System.currentTimeMillis() / 1_000L },
        private val onError: (DomainError) -> Unit,
    ) : ResolvingDataSource.Resolver {

        private val resolvedStreamCache = ConcurrentHashMap<String, ResolvedMediaStream>()

        override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
            val uri = dataSpec.uri
            val scheme = uri.scheme?.lowercase().orEmpty()

            // Pass through already-resolved local content/file URIs and direct googlevideo CDN URLs
            if (scheme == ContentResolverScheme.CONTENT || scheme == ContentResolverScheme.FILE) {
                return dataSpec
            }

            if (scheme == SCHEME_LOCAL_TRACK) {
                val localTrackId = extractTrackIdFromUri(uri)
                return resolveLocalTrackDataSpec(dataSpec, localTrackId)
            }

            val youtubeVideoId = extractYouTubeVideoId(uri)
            if (youtubeVideoId != null) {
                return resolveYouTubeDataSpec(dataSpec, youtubeVideoId)
            }

            return dataSpec
        }

        private fun resolveYouTubeDataSpec(
            dataSpec: DataSpec,
            videoId: String,
        ): DataSpec {
            val nowEpochSeconds = currentEpochSecondsProvider()
            val cached = resolvedStreamCache[videoId]
            if (cached != null && !cached.isExpired(nowEpochSeconds)) {
                return dataSpec.buildUpon()
                    .setUri(Uri.parse(cached.streamUrl))
                    .setKey(videoId)
                    .build()
            }

            val resolutionResult = runBlocking(ioDispatcher) {
                youTubeMusicProvider.resolveAudioStream(videoId)
            }

            return when (resolutionResult) {
                is AppResult.Success -> {
                    val resolvedStream = resolutionResult.data
                    resolvedStreamCache[videoId] = resolvedStream
                    dataSpec.buildUpon()
                        .setUri(Uri.parse(resolvedStream.streamUrl))
                        .setKey(videoId)
                        .build()
                }

                is AppResult.Failure -> {
                    resolvedStreamCache.remove(videoId)
                    onError(resolutionResult.error)
                    throw DomainErrorDataSourceException(resolutionResult.error)
                }
            }
        }

        private fun resolveLocalTrackDataSpec(
            dataSpec: DataSpec,
            trackId: String,
        ): DataSpec {
            val provider = localMediaStoreProvider ?: run {
                val error = DomainError.Playback.LocalMediaQueryFailed(
                    message = "LocalMediaStoreProvider is not configured for local track '$trackId'",
                )
                onError(error)
                throw DomainErrorDataSourceException(error)
            }

            val resolutionResult = runBlocking(ioDispatcher) {
                provider.resolveAudioStream(trackId)
            }

            return when (resolutionResult) {
                is AppResult.Success -> {
                    dataSpec.buildUpon()
                        .setUri(Uri.parse(resolutionResult.data.streamUrl))
                        .setKey(trackId)
                        .build()
                }

                is AppResult.Failure -> {
                    onError(resolutionResult.error)
                    throw DomainErrorDataSourceException(resolutionResult.error)
                }
            }
        }

        fun invalidateTrack(videoId: String) {
            resolvedStreamCache.remove(videoId)
        }

        fun clearCache() {
            resolvedStreamCache.clear()
        }

        private fun extractYouTubeVideoId(uri: Uri): String? {
            val scheme = uri.scheme?.lowercase().orEmpty()
            if (scheme == SCHEME_YTM_TRACK) {
                return extractTrackIdFromUri(uri).takeIf { it.isNotBlank() }
            }
            val host = uri.host?.lowercase().orEmpty()
            return when {
                host == "music.youtube.com" || host == "www.youtube.com" || host == "youtube.com" ->
                    uri.getQueryParameter("v")?.trim()?.takeIf { it.isNotEmpty() }

                host == "youtu.be" ->
                    uri.lastPathSegment?.trim()?.takeIf { it.isNotEmpty() }

                else -> null
            }
        }

        private fun extractTrackIdFromUri(uri: Uri): String =
            uri.lastPathSegment?.takeIf { it.isNotBlank() }
                ?: uri.host?.takeIf { it.isNotBlank() }
                ?: uri.schemeSpecificPart.trimStart('/')
    }

    /**
     * [Player.Listener] bridging [ExoPlayer] lifecycle events to [ScrobbleManager] for both
     * `playing_now` notifications and exact-threshold completed listen scrobbles.
     */
    internal class ScrobblePlayerListener(
        private val player: Player,
        private val scrobbleManager: ScrobbleManager,
        private val streamResolver: OnDemandStreamResolver,
        private val scope: CoroutineScope,
        private val onDomainError: (DomainError) -> Unit,
    ) : Player.Listener {

        override fun onMediaItemTransition(
            mediaItem: MediaItem?,
            reason: Int,
        ) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                scope.launch {
                    scrobbleManager.onTrackEnded(isRepeating = true)
                }
                return
            }

            if (mediaItem == null) {
                scope.launch {
                    scrobbleManager.onStop()
                }
                return
            }

            val resolvedDurationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
            val scrobbleTrack = mediaItem.toScrobbleTrack(fallbackDurationMs = resolvedDurationMs)
            if (scrobbleTrack != null) {
                val isPlaying = player.isPlaying
                val speed = player.playbackParameters.speed
                scope.launch {
                    scrobbleManager.onTrackStarted(
                        track = scrobbleTrack,
                        isPlaying = isPlaying,
                        playbackSpeed = speed,
                    )
                }
            } else {
                scope.launch {
                    scrobbleManager.onStop()
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val currentItem = player.currentMediaItem
            val playerDurationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
            val speed = player.playbackParameters.speed

            scope.launch {
                if (currentItem != null && playerDurationMs != null) {
                    val videoId = currentItem.mediaId.ifBlank {
                        currentItem.toScrobbleTrack(playerDurationMs)?.videoId.orEmpty()
                    }
                    if (videoId.isNotBlank()) {
                        scrobbleManager.onTrackDurationResolved(
                            videoId = videoId,
                            durationMs = playerDurationMs,
                        )
                    }
                }
                scrobbleManager.onPlaybackStateChanged(
                    isPlaying = isPlaying,
                    playbackSpeed = speed,
                )
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    val currentItem = player.currentMediaItem
                    val playerDurationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
                    val isPlaying = player.isPlaying
                    val speed = player.playbackParameters.speed

                    scope.launch {
                        if (currentItem != null && playerDurationMs != null) {
                            val videoId = currentItem.mediaId.ifBlank {
                                currentItem.toScrobbleTrack(playerDurationMs)?.videoId.orEmpty()
                            }
                            if (videoId.isNotBlank()) {
                                scrobbleManager.onTrackDurationResolved(
                                    videoId = videoId,
                                    durationMs = playerDurationMs,
                                )
                            }
                        }
                        scrobbleManager.onPlaybackStateChanged(
                            isPlaying = isPlaying,
                            playbackSpeed = speed,
                        )
                    }
                }

                Player.STATE_BUFFERING -> {
                    // Pause duration accrual while buffering so stalled network waits do not count as playback
                    val speed = player.playbackParameters.speed
                    scope.launch {
                        scrobbleManager.onPlaybackStateChanged(
                            isPlaying = false,
                            playbackSpeed = speed,
                        )
                    }
                }

                Player.STATE_ENDED -> {
                    scope.launch {
                        scrobbleManager.onTrackEnded(isRepeating = false)
                    }
                }

                Player.STATE_IDLE -> {
                    scope.launch {
                        scrobbleManager.onStop()
                    }
                }
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            when (reason) {
                Player.DISCONTINUITY_REASON_SEEK,
                Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT,
                -> {
                    scope.launch {
                        scrobbleManager.onSeek()
                    }
                }

                Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> {
                    if (oldPosition.mediaItemIndex == newPosition.mediaItemIndex &&
                        oldPosition.mediaItem?.mediaId == newPosition.mediaItem?.mediaId
                    ) {
                        scope.launch {
                            scrobbleManager.onTrackEnded(isRepeating = true)
                        }
                    }
                }

                else -> Unit
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            val isPlaying = player.isPlaying
            val speed = playbackParameters.speed
            scope.launch {
                scrobbleManager.onPlaybackStateChanged(
                    isPlaying = isPlaying,
                    playbackSpeed = speed,
                )
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val currentMediaId = player.currentMediaItem?.mediaId
            if (!currentMediaId.isNullOrBlank()) {
                streamResolver.invalidateTrack(currentMediaId)
            }

            val unwrappedDomainError = findDomainErrorInCauseChain(error)
                ?: DomainError.Playback.PlayerError(
                    message = error.localizedMessage ?: "ExoPlayer error (${error.errorCodeName})",
                    cause = error,
                )
            onDomainError(unwrappedDomainError)

            scope.launch {
                scrobbleManager.onPlaybackStateChanged(isPlaying = false)
            }
        }

        private fun findDomainErrorInCauseChain(throwable: Throwable?): DomainError? {
            var current: Throwable? = throwable
            var depth = 0
            while (current != null && depth < 10) {
                if (current is DomainErrorDataSourceException) {
                    return current.domainError
                }
                current = current.cause
                depth += 1
            }
            return null
        }
    }

    /**
     * Ensures [MediaItem] instances added via external `MediaController` calls retain their
     * resolvable URI (`ytm://track/<videoId>` or `requestMetadata.mediaUri`).
     */
    private class PlaybackSessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<List<MediaItem>> {
            val hydrated = mediaItems.map { item ->
                if (item.localConfiguration?.uri != null) {
                    item
                } else {
                    val fallbackUri = item.requestMetadata.mediaUri
                        ?: item.mediaId.takeIf { it.isNotBlank() }?.let { id ->
                            Uri.parse("$SCHEME_YTM_TRACK://track/$id")
                        }
                    if (fallbackUri != null) {
                        item.buildUpon().setUri(fallbackUri).build()
                    } else {
                        item
                    }
                }
            }
            return Futures.immediateFuture(hydrated)
        }
    }

    private object ContentResolverScheme {
        const val CONTENT = "content"
        const val FILE = "file"
    }

    companion object {
        const val SCHEME_YTM_TRACK = "ytm"
        const val SCHEME_LOCAL_TRACK = "local"

        const val EXTRA_VIDEO_ID = "com.listentube.extra.VIDEO_ID"
        const val EXTRA_TITLE = "com.listentube.extra.TITLE"
        const val EXTRA_ARTIST_NAME = "com.listentube.extra.ARTIST_NAME"
        const val EXTRA_ALBUM_NAME = "com.listentube.extra.ALBUM_NAME"
        const val EXTRA_DURATION_MS = "com.listentube.extra.DURATION_MS"
        const val EXTRA_TRACK_NUMBER = "com.listentube.extra.TRACK_NUMBER"
        const val EXTRA_SOURCE_TYPE = "com.listentube.extra.SOURCE_TYPE"

        private const val PLAYBACK_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        @Volatile
        var dependencyProvider: (() -> Dependencies)? = null

        fun createDefaultPlaybackOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/**
 * Converts a domain [MediaTrack] into a Media3 [MediaItem] configured for on-demand stream
 * resolution via [MusicPlaybackService] and scrobble metadata extraction.
 */
fun MediaTrack.toMediaItem(): MediaItem {
    val playbackUri: Uri = when (sourceType) {
        MediaSourceType.YOUTUBE_MUSIC ->
            Uri.parse("${MusicPlaybackService.SCHEME_YTM_TRACK}://track/$id")

        MediaSourceType.LOCAL_STORAGE ->
            contentUri?.let(Uri::parse)
                ?: Uri.parse("${MusicPlaybackService.SCHEME_LOCAL_TRACK}://track/$id")
    }

    val extras = Bundle().apply {
        putString(MusicPlaybackService.EXTRA_VIDEO_ID, id)
        putString(MusicPlaybackService.EXTRA_TITLE, title)
        putString(MusicPlaybackService.EXTRA_ARTIST_NAME, artistName)
        albumName?.let { putString(MusicPlaybackService.EXTRA_ALBUM_NAME, it) }
        putLong(MusicPlaybackService.EXTRA_DURATION_MS, durationMs)
        trackNumber?.let { putInt(MusicPlaybackService.EXTRA_TRACK_NUMBER, it) }
        putString(MusicPlaybackService.EXTRA_SOURCE_TYPE, sourceType.name)
    }

    val metadataBuilder = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artistName)
        .setAlbumTitle(albumName)
        .setTrackNumber(trackNumber)
        .setReleaseYear(releaseYear)
        .setIsPlayable(true)
        .setExtras(extras)

    artworkUrl?.takeIf { it.isNotBlank() }?.let { art ->
        metadataBuilder.setArtworkUri(Uri.parse(art))
    }

    val requestMetadata = MediaItem.RequestMetadata.Builder()
        .setMediaUri(playbackUri)
        .build()

    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(playbackUri)
        .setMediaMetadata(metadataBuilder.build())
        .setRequestMetadata(requestMetadata)
        .build()
}

/**
 * Extracts a [ScrobbleTrack] from a Media3 [MediaItem], using [fallbackDurationMs] (from `ExoPlayer.duration`)
 * if the item's bundled metadata duration was `0L`.
 */
fun MediaItem.toScrobbleTrack(
    fallbackDurationMs: Long = 0L,
    appVersion: String? = null,
): ScrobbleTrack? {
    val extras = mediaMetadata.extras
    val videoId = extras?.getString(MusicPlaybackService.EXTRA_VIDEO_ID)?.takeIf { it.isNotBlank() }
        ?: mediaId.takeIf { it.isNotBlank() }
        ?: localConfiguration?.uri?.lastPathSegment?.takeIf { it.isNotBlank() }
        ?: return null

    val title = extras?.getString(MusicPlaybackService.EXTRA_TITLE)?.takeIf { it.isNotBlank() }
        ?: mediaMetadata.title?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null

    val artist = extras?.getString(MusicPlaybackService.EXTRA_ARTIST_NAME)?.takeIf { it.isNotBlank() }
        ?: mediaMetadata.artist?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null

    val album = extras?.getString(MusicPlaybackService.EXTRA_ALBUM_NAME)?.takeIf { it.isNotBlank() }
        ?: mediaMetadata.albumTitle?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    val bundledDurationMs = extras?.getLong(MusicPlaybackService.EXTRA_DURATION_MS, 0L) ?: 0L
    val effectiveDurationMs = if (bundledDurationMs > 0L) bundledDurationMs else fallbackDurationMs.coerceAtLeast(0L)

    val trackNumber = extras?.getInt(MusicPlaybackService.EXTRA_TRACK_NUMBER, 0)?.takeIf { it > 0 }
        ?: mediaMetadata.trackNumber?.takeIf { it > 0 }

    return ScrobbleTrack(
        videoId = videoId,
        artistName = artist,
        trackName = title,
        durationMs = effectiveDurationMs,
        releaseName = album,
        trackNumber = trackNumber,
        appVersion = appVersion,
    )
}
