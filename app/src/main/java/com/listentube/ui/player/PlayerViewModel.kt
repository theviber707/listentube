// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import com.listentube.core.result.AppResult
import com.listentube.core.result.DomainError
import com.listentube.data.innertube.YouTubeMusicProvider
import com.listentube.data.listenbrainz.local.QueuedScrobbleEntity
import com.listentube.data.listenbrainz.model.RateLimitInfo
import com.listentube.data.listenbrainz.remote.ListenBrainzApiClient
import com.listentube.data.localmedia.LocalMediaStoreProvider
import com.listentube.data.lyrics.LyricsRepository
import com.listentube.domain.lyrics.TrackLyrics
import com.listentube.domain.media.MediaCollection
import com.listentube.domain.media.MediaCollectionType
import com.listentube.domain.media.MediaSourceType
import com.listentube.domain.media.MediaTrack
import com.listentube.domain.scrobble.ScrobbleManager
import com.listentube.domain.scrobble.ScrobbleSessionState
import com.listentube.service.playback.DomainErrorDataSourceException
import com.listentube.service.playback.MusicPlaybackService
import com.listentube.service.playback.toMediaItem
import com.listentube.ui.i18n.AppLocale
import com.listentube.ui.i18n.stringsForLocale
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Controls whether the Now Playing sheet displays album artwork or renders the live
 * ExoPlayer video surface.
 */
enum class PlayerRenderMode {
    AUDIO_ARTWORK,
    VIDEO_SURFACE,
}

/**
 * Top-level navigation tabs in `MainScreen`.
 */
enum class MainDestination {
    EXPLORE,
    LOCAL_LIBRARY,
    SCROBBLE_QUEUE,
}

/**
 * Representation of a detected local audio directory with track count and inclusion state.
 */
data class LocalAudioFolder(
    val path: String,
    val name: String,
    val trackCount: Int,
    val isIncluded: Boolean,
)

/**
 * Immutable UI state snapshot consumed by `MainScreen`, `NowPlayingPlayerSheet`,
 * and `ScrobbleQueueScreen`.
 */
data class PlayerUiState(
    val currentDestination: MainDestination = MainDestination.EXPLORE,
    val currentLocale: AppLocale = AppLocale.fromLocale(),

    // Search & Library State
    val isSearchActive: Boolean = false,
    val searchQuery: String = "",
    val submittedSearchQuery: String? = null,
    val isSearching: Boolean = false,
    val isLoadingMoreSearch: Boolean = false,
    val searchResults: List<MediaTrack> = emptyList(),
    val searchAlbums: List<MediaCollection> = emptyList(),
    val searchPlaylists: List<MediaCollection> = emptyList(),
    val searchContinuationToken: String? = null,

    // Local Storage State & Folder Selection
    val allLocalTracks: List<MediaTrack> = emptyList(),
    val localTracks: List<MediaTrack> = emptyList(),
    val localFilterQuery: String = "",
    val isLoadingLocalTracks: Boolean = false,
    val discoveredFolders: List<LocalAudioFolder> = emptyList(),
    val excludedFolderPaths: Set<String> = emptySet(),
    val isFolderFilterSheetVisible: Boolean = false,

    // Album / Playlist Detail State
    val openCollection: MediaCollection? = null,
    val collectionTracks: List<MediaTrack> = emptyList(),
    val isLoadingCollection: Boolean = false,
    val pendingPlayCollectionId: String? = null,

    // Active Playback & Queue State
    val currentTrack: MediaTrack? = null,
    val playbackQueue: List<MediaTrack> = emptyList(),
    val currentQueueIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffleModeEnabled: Boolean = false,
    val renderMode: PlayerRenderMode = PlayerRenderMode.AUDIO_ARTWORK,
    val hasVideoTrack: Boolean = false,
    val isPlayerSheetExpanded: Boolean = false,

    // Lyrics State
    val isLyricsActive: Boolean = false,
    val isLoadingLyrics: Boolean = false,
    val currentLyrics: TrackLyrics? = null,
    val lyricsError: String? = null,

    // ListenBrainz & Offline Scrobble Queue State
    val scrobbleSession: ScrobbleSessionState = ScrobbleSessionState(),
    val rateLimitInfo: RateLimitInfo = RateLimitInfo(),
    val queuedPlayingNow: QueuedScrobbleEntity? = null,
    val pendingCompletedScrobbles: List<QueuedScrobbleEntity> = emptyList(),
    val pendingCompletedCount: Int = 0,
    val totalQueuedCount: Int = 0,
    val listenBrainzTokenInput: String = "",
    val configuredTokenMasked: String? = null,
    val validatedUserName: String? = null,
    val isTokenValid: Boolean? = null,
    val isValidatingToken: Boolean = false,
    val scrobblingEnabled: Boolean = true,
    val isFlushingQueue: Boolean = false,

    // Diagnostics & User Feedback
    val activeError: DomainError? = null,
    val statusBannerMessage: String? = null,
) {
    val filteredLocalTracks: List<MediaTrack>
        get() {
            val q = if (currentDestination == MainDestination.LOCAL_LIBRARY && isSearchActive) {
                searchQuery.trim()
            } else {
                localFilterQuery.trim()
            }
            if (q.isEmpty()) return localTracks
            return localTracks.filter { track ->
                track.title.contains(q, ignoreCase = true) ||
                    track.artistName.contains(q, ignoreCase = true) ||
                    (track.albumName?.contains(q, ignoreCase = true) == true)
            }
        }
}

/**
 * Abstraction for reading and persisting the user's ListenBrainz token, scrobble preferences,
 * and excluded local storage folders.
 */
interface ScrobblePreferencesStore {
    suspend fun getUserToken(): String?
    suspend fun setUserToken(token: String, validatedUserName: String?)
    suspend fun getValidatedUserName(): String?
    suspend fun isScrobblingEnabled(): Boolean
    suspend fun setScrobblingEnabled(enabled: Boolean)
    suspend fun getExcludedFolders(): Set<String>?
    suspend fun setExcludedFolders(folders: Set<String>)
}

/**
 * Default in-memory implementation of [ScrobblePreferencesStore] when no external DataStore is supplied.
 */
class InMemoryScrobblePreferencesStore(
    initialToken: String? = null,
    initialEnabled: Boolean = true,
) : ScrobblePreferencesStore {
    @Volatile
    private var token: String? = initialToken

    @Volatile
    private var userName: String? = null

    @Volatile
    private var enabled: Boolean = initialEnabled

    @Volatile
    private var excludedFolders: Set<String>? = null

    override suspend fun getUserToken(): String? = token

    override suspend fun setUserToken(token: String, validatedUserName: String?) {
        this.token = token.trim().takeIf { it.isNotEmpty() }
        this.userName = validatedUserName
    }

    override suspend fun getValidatedUserName(): String? = userName

    override suspend fun isScrobblingEnabled(): Boolean = enabled

    override suspend fun setScrobblingEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    override suspend fun getExcludedFolders(): Set<String>? = excludedFolders

    override suspend fun setExcludedFolders(folders: Set<String>) {
        this.excludedFolders = folders
    }
}

/**
 * Primary [ViewModel] coordinating YouTube Music search, local `MediaStore` browsing,
 * Media3 [Player] control, video/artwork rendering mode, and ListenBrainz scrobble management.
 */
class PlayerViewModel(
    private val youTubeMusicProvider: YouTubeMusicProvider,
    private val localMediaStoreProvider: LocalMediaStoreProvider,
    private val listenBrainzApiClient: ListenBrainzApiClient,
    private val scrobbleManager: ScrobbleManager,
    private val preferencesStore: ScrobblePreferencesStore = InMemoryScrobblePreferencesStore(),
    private val lyricsRepository: LyricsRepository? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _connectedPlayer = MutableStateFlow<Player?>(null)

    /**
     * Live Media3 [Player] reference used by `NowPlayingPlayerSheet` to bind its video `SurfaceView`.
     */
    val connectedPlayer: StateFlow<Player?> = _connectedPlayer.asStateFlow()

    private var positionTickerJob: Job? = null

    private val uiPlayerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncPlayerState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncPlayerState()
            if (isPlaying) {
                startPositionTicker()
            } else {
                stopPositionTicker()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            syncPlayerState()
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            syncPlayerState()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            syncPlayerState()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            syncPlayerState()
        }

        override fun onTracksChanged(tracks: Tracks) {
            val videoPresent = tracks.containsType(C.TRACK_TYPE_VIDEO)
            _uiState.update { it.copy(hasVideoTrack = videoPresent) }
        }

        override fun onPlayerError(error: PlaybackException) {
            val domainError = extractDomainError(error)
                ?: DomainError.Playback.PlayerError(
                    message = error.localizedMessage ?: "Playback failed (${error.errorCodeName})",
                    cause = error,
                )
            _uiState.update {
                it.copy(
                    isPlaying = false,
                    isBuffering = false,
                    activeError = domainError,
                )
            }
        }
    }

    init {
        observeScrobbleStreams()
        loadInitialPreferences()
    }

    /**
     * Attaches a Media3 [Player] (such as a `MediaController` connected to [MusicPlaybackService]
     * or a direct `ExoPlayer` instance) to this ViewModel.
     */
    fun attachPlayer(player: Player) {
        val previous = _connectedPlayer.value
        if (previous === player) return
        previous?.removeListener(uiPlayerListener)

        _connectedPlayer.value = player
        player.addListener(uiPlayerListener)
        syncPlayerState()
        if (player.isPlaying) {
            startPositionTicker()
        }
    }

    /**
     * Detaches the active [Player] listener when the UI lifecycle stops.
     */
    fun detachPlayer() {
        stopPositionTicker()
        _connectedPlayer.value?.removeListener(uiPlayerListener)
        _connectedPlayer.value = null
    }

    fun selectDestination(destination: MainDestination) {
        _uiState.update { it.copy(currentDestination = destination, isSearchActive = false) }
        if (destination == MainDestination.LOCAL_LIBRARY && _uiState.value.allLocalTracks.isEmpty()) {
            loadLocalLibraryTracks()
        }
    }

    /**
     * Synchronizes the active application locale resolved from Android system / per-app language
     * settings (`en-US`, `pt-BR`, `es-MX`, or `ru-RU`) and updates [YouTubeMusicProvider]'s
     * Innertube `hl`/`gl` context.
     */
    fun onSystemLocaleChanged(locale: AppLocale) {
        youTubeMusicProvider.updateLocale(hl = locale.innertubeHl, gl = locale.innertubeGl)
        _uiState.update { it.copy(currentLocale = locale) }
    }

    // -------------------------------------------------------------------------
    // Search & Local Library Actions
    // -------------------------------------------------------------------------

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    /**
     * Shows or hides the top app bar search field (LibreTube-style search icon).
     */
    fun setSearchActive(active: Boolean) {
        _uiState.update { it.copy(isSearchActive = active) }
    }

    /**
     * Searches YouTube Music for songs, albums, and playlists in parallel so albums such as
     * "The Wall" can be found and played as a self-contained queue.
     */
    fun submitSearch() {
        val query = _uiState.value.searchQuery.trim()
        if (query.isEmpty()) return
        if (_uiState.value.currentDestination == MainDestination.LOCAL_LIBRARY) {
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    currentDestination = MainDestination.EXPLORE,
                    submittedSearchQuery = query,
                    isSearching = true,
                    activeError = null,
                    searchContinuationToken = null,
                    openCollection = null,
                    collectionTracks = emptyList(),
                )
            }

            val (songsResult, albumsResult, playlistsResult) = coroutineScope {
                val songs = async { youTubeMusicProvider.searchTracks(query = query, continuationToken = null) }
                val albums = async { youTubeMusicProvider.searchCollections(query, MediaCollectionType.ALBUM) }
                val playlists = async { youTubeMusicProvider.searchCollections(query, MediaCollectionType.PLAYLIST) }
                Triple(songs.await(), albums.await(), playlists.await())
            }

            // Stale response guard: user may have submitted another query meanwhile.
            if (_uiState.value.submittedSearchQuery != query) return@launch

            val firstError = listOf(songsResult, albumsResult, playlistsResult)
                .firstNotNullOfOrNull { it.errorOrNull() }

            _uiState.update {
                it.copy(
                    isSearching = false,
                    searchResults = songsResult.getOrNull()?.tracks.orEmpty(),
                    searchContinuationToken = songsResult.getOrNull()?.continuationToken,
                    searchAlbums = albumsResult.getOrNull().orEmpty(),
                    searchPlaylists = playlistsResult.getOrNull().orEmpty(),
                    activeError = firstError,
                )
            }
        }
    }

    fun loadMoreSearchResults() {
        val state = _uiState.value
        val continuation = state.searchContinuationToken
        if (state.isSearching || state.isLoadingMoreSearch || continuation.isNullOrBlank()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMoreSearch = true) }
            when (
                val result = youTubeMusicProvider.searchTracks(
                    query = state.submittedSearchQuery ?: state.searchQuery,
                    continuationToken = continuation,
                )
            ) {
                is AppResult.Success -> {
                    _uiState.update { current ->
                        val merged = (current.searchResults + result.data.tracks)
                            .distinctBy { it.id }
                        current.copy(
                            isLoadingMoreSearch = false,
                            searchResults = merged,
                            searchContinuationToken = result.data.continuationToken,
                        )
                    }
                }

                is AppResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoadingMoreSearch = false,
                            activeError = result.error,
                        )
                    }
                }
            }
        }
    }

    fun onLocalFilterQueryChanged(query: String) {
        _uiState.update { it.copy(localFilterQuery = query) }
    }

    fun loadLocalLibraryTracks() {
        if (_uiState.value.isLoadingLocalTracks) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingLocalTracks = true, activeError = null) }
            when (val result = localMediaStoreProvider.getLibraryTracks(limit = 10_000, offset = 0)) {
                is AppResult.Success -> {
                    val rawTracks = result.data
                    val savedExcluded = preferencesStore.getExcludedFolders()

                    val folderMap = linkedMapOf<String, MutableList<MediaTrack>>()
                    for (track in rawTracks) {
                        val key = track.folderPath ?: "Unknown"
                        folderMap.getOrPut(key) { mutableListOf() }.add(track)
                    }

                    val effectiveExcluded = if (savedExcluded != null) {
                        savedExcluded
                    } else {
                        // Default exclusion: exclude junk audio folders like WhatsApp, Telegram, etc.
                        folderMap.keys.filter { folderPath ->
                            val folderName = folderMap[folderPath]?.firstOrNull()?.folderName ?: folderPath
                            LocalMediaStoreProvider.isJunkAudioFolder(folderPath, folderName)
                        }.toSet()
                    }

                    val folders = folderMap.map { (path, tracksInFolder) ->
                        val name = tracksInFolder.firstOrNull()?.folderName ?: path.substringAfterLast('/')
                        LocalAudioFolder(
                            path = path,
                            name = name,
                            trackCount = tracksInFolder.size,
                            isIncluded = path !in effectiveExcluded,
                        )
                    }.sortedByDescending { it.trackCount }

                    val filteredTracks = rawTracks.filter { it.folderPath !in effectiveExcluded }

                    _uiState.update {
                        it.copy(
                            isLoadingLocalTracks = false,
                            allLocalTracks = rawTracks,
                            localTracks = filteredTracks,
                            discoveredFolders = folders,
                            excludedFolderPaths = effectiveExcluded,
                        )
                    }
                }

                is AppResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoadingLocalTracks = false,
                            activeError = result.error,
                        )
                    }
                }
            }
        }
    }

    fun setFolderFilterSheetVisible(visible: Boolean) {
        _uiState.update { it.copy(isFolderFilterSheetVisible = visible) }
        if (visible && _uiState.value.allLocalTracks.isEmpty()) {
            loadLocalLibraryTracks()
        }
    }

    fun toggleFolderInclusion(folderPath: String) {
        val currentExcluded = _uiState.value.excludedFolderPaths
        val newExcluded = if (folderPath in currentExcluded) {
            currentExcluded - folderPath
        } else {
            currentExcluded + folderPath
        }
        applyExcludedFolders(newExcluded)
    }

    fun setAllFoldersIncluded(included: Boolean) {
        val newExcluded = if (included) {
            emptySet()
        } else {
            _uiState.value.discoveredFolders.map { it.path }.toSet()
        }
        applyExcludedFolders(newExcluded)
    }

    private fun applyExcludedFolders(newExcluded: Set<String>) {
        viewModelScope.launch {
            preferencesStore.setExcludedFolders(newExcluded)
            _uiState.update { state ->
                val updatedFolders = state.discoveredFolders.map { folder ->
                    folder.copy(isIncluded = folder.path !in newExcluded)
                }
                val updatedTracks = state.allLocalTracks.filter { it.folderPath !in newExcluded }
                state.copy(
                    excludedFolderPaths = newExcluded,
                    discoveredFolders = updatedFolders,
                    localTracks = updatedTracks,
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Album & Playlist Collection Actions
    // -------------------------------------------------------------------------

    fun openCollection(collection: MediaCollection) {
        _uiState.update {
            it.copy(
                openCollection = collection,
                collectionTracks = emptyList(),
                isLoadingCollection = true,
                activeError = null,
            )
        }
        viewModelScope.launch {
            when (val result = youTubeMusicProvider.getCollectionTracks(collection)) {
                is AppResult.Success -> {
                    _uiState.update {
                        if (it.openCollection?.playlistId == collection.playlistId) {
                            it.copy(
                                collectionTracks = result.data,
                                isLoadingCollection = false,
                            )
                        } else it
                    }
                }

                is AppResult.Failure -> {
                    _uiState.update {
                        if (it.openCollection?.playlistId == collection.playlistId) {
                            it.copy(
                                isLoadingCollection = false,
                                activeError = result.error,
                            )
                        } else it
                    }
                }
            }
        }
    }

    fun closeCollection() {
        _uiState.update {
            it.copy(
                openCollection = null,
                collectionTracks = emptyList(),
                isLoadingCollection = false,
            )
        }
    }

    fun playCollection(collection: MediaCollection, startIndex: Int = 0) {
        val currentOpen = _uiState.value.openCollection
        val loadedTracks = if (currentOpen?.playlistId == collection.playlistId) {
            _uiState.value.collectionTracks
        } else emptyList()

        if (loadedTracks.isNotEmpty()) {
            val clampedIndex = startIndex.coerceIn(loadedTracks.indices)
            playTrack(
                track = loadedTracks[clampedIndex],
                queueContext = loadedTracks,
                expandSheet = true,
            )
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(pendingPlayCollectionId = collection.playlistId, activeError = null) }
            when (val result = youTubeMusicProvider.getCollectionTracks(collection)) {
                is AppResult.Success -> {
                    val tracks = result.data
                    _uiState.update { it.copy(pendingPlayCollectionId = null) }
                    if (tracks.isNotEmpty()) {
                        val clampedIndex = startIndex.coerceIn(tracks.indices)
                        playTrack(
                            track = tracks[clampedIndex],
                            queueContext = tracks,
                            expandSheet = true,
                        )
                    }
                }

                is AppResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            pendingPlayCollectionId = null,
                            activeError = result.error,
                        )
                    }
                }
            }
        }
    }

    fun addCollectionToQueue(collection: MediaCollection) {
        viewModelScope.launch {
            when (val result = youTubeMusicProvider.getCollectionTracks(collection)) {
                is AppResult.Success -> {
                    val tracks = result.data
                    if (tracks.isNotEmpty()) {
                        val player = _connectedPlayer.value
                        tracks.forEach { player?.addMediaItem(it.toMediaItem()) }
                        _uiState.update { current ->
                            val strings = stringsForLocale(current.currentLocale)
                            current.copy(
                                playbackQueue = current.playbackQueue + tracks,
                                statusBannerMessage = strings.statusAddedToQueue(collection.title),
                            )
                        }
                    }
                }

                is AppResult.Failure -> {
                    _uiState.update { it.copy(activeError = result.error) }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Playback & Transport Controls
    // -------------------------------------------------------------------------

    /**
     * Loads [track] (and optional [queueContext]) into the attached [Player] and starts playback.
     */
    fun playTrack(
        track: MediaTrack,
        queueContext: List<MediaTrack> = listOf(track),
        expandSheet: Boolean = true,
    ) {
        val effectiveQueue = if (queueContext.isEmpty()) listOf(track) else queueContext
        val startIndex = effectiveQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)

        _uiState.update {
            it.copy(
                currentTrack = track,
                playbackQueue = effectiveQueue,
                currentQueueIndex = startIndex,
                durationMs = track.durationMs,
                currentPositionMs = 0L,
                isPlayerSheetExpanded = expandSheet,
                activeError = null,
            )
        }
        if (_uiState.value.isLyricsActive) {
            loadLyricsForTrack(track)
        }

        val player = _connectedPlayer.value
        if (player != null) {
            val mediaItems = effectiveQueue.map { it.toMediaItem() }
            player.setMediaItems(mediaItems, startIndex, 0L)
            player.prepare()
            player.play()
        } else {
            // Even when running headless without an attached ExoPlayer instance, notify ScrobbleManager
            viewModelScope.launch {
                scrobbleManager.onTrackStarted(
                    track = track.toScrobbleTrack(),
                    isPlaying = true,
                    playbackSpeed = _uiState.value.playbackSpeed,
                )
            }
        }
    }

    fun addTrackToQueue(track: MediaTrack) {
        val player = _connectedPlayer.value
        player?.addMediaItem(track.toMediaItem())
        _uiState.update { current ->
            val strings = stringsForLocale(current.currentLocale)
            val updatedQueue = current.playbackQueue + track
            current.copy(
                playbackQueue = updatedQueue,
                statusBannerMessage = strings.statusAddedToQueue(track.title),
            )
        }
    }

    fun togglePlayPause() {
        val player = _connectedPlayer.value ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            } else if (player.playbackState == Player.STATE_ENDED) {
                player.seekToDefaultPosition()
            }
            player.play()
        }
    }

    fun seekTo(positionMs: Long) {
        val player = _connectedPlayer.value
        val clamped = positionMs.coerceAtLeast(0L)
        player?.seekTo(clamped)
        _uiState.update { it.copy(currentPositionMs = clamped) }
    }

    fun skipToNext() {
        val player = _connectedPlayer.value ?: return
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
        }
    }

    fun skipToPrevious() {
        val player = _connectedPlayer.value ?: return
        if (player.currentPosition > RESTART_TRACK_THRESHOLD_MS || !player.hasPreviousMediaItem()) {
            player.seekTo(0L)
        } else {
            player.seekToPreviousMediaItem()
        }
    }

    fun skipToQueueIndex(index: Int) {
        val queue = _uiState.value.playbackQueue
        if (index !in queue.indices) return
        val player = _connectedPlayer.value
        if (player != null && index < player.mediaItemCount) {
            player.seekTo(index, 0L)
            player.play()
        }
    }

    fun cycleRepeatMode() {
        val player = _connectedPlayer.value
        val nextMode = when (_uiState.value.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        player?.repeatMode = nextMode
        _uiState.update { it.copy(repeatMode = nextMode) }
    }

    fun toggleShuffleMode() {
        val player = _connectedPlayer.value
        val nextShuffle = !_uiState.value.shuffleModeEnabled
        player?.shuffleModeEnabled = nextShuffle
        _uiState.update { it.copy(shuffleModeEnabled = nextShuffle) }
    }

    fun setRenderMode(mode: PlayerRenderMode) {
        _uiState.update { it.copy(renderMode = mode) }
    }

    fun toggleRenderMode() {
        _uiState.update { current ->
            val next = if (current.renderMode == PlayerRenderMode.AUDIO_ARTWORK) {
                PlayerRenderMode.VIDEO_SURFACE
            } else {
                PlayerRenderMode.AUDIO_ARTWORK
            }
            current.copy(renderMode = next)
        }
    }

    private var lyricsJob: Job? = null

    /**
     * Toggles visibility of lyrics. When activated, immediately triggers a lyrics lookup
     * for the currently playing track if not already fetched.
     */
    fun toggleLyrics() {
        val nextActive = !_uiState.value.isLyricsActive
        _uiState.update { it.copy(isLyricsActive = nextActive) }
        if (nextActive) {
            loadLyricsForCurrentTrack()
        }
    }

    /**
     * Retries fetching lyrics for the active track, bypassing memory cache if previous attempt failed.
     */
    fun retryLyrics() {
        loadLyricsForCurrentTrack(force = true)
    }

    private fun loadLyricsForCurrentTrack(force: Boolean = false) {
        val track = _uiState.value.currentTrack ?: return
        loadLyricsForTrack(track, force)
    }

    private fun loadLyricsForTrack(track: MediaTrack, force: Boolean = false) {
        val repo = lyricsRepository ?: return
        lyricsJob?.cancel()
        if (!force && _uiState.value.currentLyrics?.trackId == track.id) return

        _uiState.update { it.copy(isLoadingLyrics = true, lyricsError = null) }
        lyricsJob = viewModelScope.launch {
            when (val result = repo.getLyrics(track)) {
                is AppResult.Success -> {
                    _uiState.update { state ->
                        state.copy(
                            isLoadingLyrics = false,
                            currentLyrics = result.data,
                            lyricsError = null,
                        )
                    }
                }

                is AppResult.Failure -> {
                    _uiState.update { state ->
                        state.copy(
                            isLoadingLyrics = false,
                            currentLyrics = null,
                            lyricsError = result.error.message,
                        )
                    }
                }
            }
        }
    }

    fun setPlayerSheetExpanded(expanded: Boolean) {
        _uiState.update { it.copy(isPlayerSheetExpanded = expanded) }
    }

    // -------------------------------------------------------------------------
    // ListenBrainz Token Configuration & Offline Queue Management
    // -------------------------------------------------------------------------

    fun onListenBrainzTokenInputChanged(tokenInput: String) {
        _uiState.update { it.copy(listenBrainzTokenInput = tokenInput) }
    }

    /**
     * Validates [listenBrainzTokenInput] against ListenBrainz's `GET /1/validate-token` endpoint,
     * persists the token if valid, and immediately triggers an offline queue flush.
     */
    fun validateAndSaveListenBrainzToken() {
        val candidateToken = _uiState.value.listenBrainzTokenInput.trim()
        if (candidateToken.isEmpty()) {
            _uiState.update {
                it.copy(
                    isTokenValid = false,
                    activeError = DomainError.Validation.MissingToken(),
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isValidatingToken = true,
                    activeError = null,
                    statusBannerMessage = null,
                )
            }

            when (val validationResult = listenBrainzApiClient.validateToken(candidateToken)) {
                is AppResult.Success -> {
                    val response = validationResult.data
                    if (response.valid) {
                        preferencesStore.setUserToken(candidateToken, response.userName)
                        _uiState.update { state ->
                            val strings = stringsForLocale(state.currentLocale)
                            val displayUser = response.userName?.takeIf { it.isNotBlank() }
                                ?: strings.statusVerifiedUserFallback
                            state.copy(
                                isValidatingToken = false,
                                isTokenValid = true,
                                validatedUserName = response.userName,
                                configuredTokenMasked = maskToken(candidateToken),
                                statusBannerMessage = strings.statusConnectedToListenBrainz(displayUser),
                            )
                        }
                        flushScrobbleQueueNow()
                    } else {
                        _uiState.update { state ->
                            val strings = stringsForLocale(state.currentLocale)
                            state.copy(
                                isValidatingToken = false,
                                isTokenValid = false,
                                validatedUserName = null,
                                activeError = DomainError.Network.Unauthorized(
                                    message = response.message.ifBlank { strings.errorNetworkUnauthorized },
                                ),
                            )
                        }
                    }
                }

                is AppResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isValidatingToken = false,
                            isTokenValid = false,
                            activeError = validationResult.error,
                        )
                    }
                }
            }
        }
    }

    fun setScrobblingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesStore.setScrobblingEnabled(enabled)
            _uiState.update { state ->
                val strings = stringsForLocale(state.currentLocale)
                state.copy(
                    scrobblingEnabled = enabled,
                    statusBannerMessage = if (enabled) strings.statusScrobblingEnabled else strings.statusScrobblingPaused,
                )
            }
            if (enabled) {
                flushScrobbleQueueNow()
            }
        }
    }

    /**
     * Triggers an immediate flush of all pending offline `playing_now` and `completed` scrobbles.
     */
    fun flushScrobbleQueueNow() {
        if (_uiState.value.isFlushingQueue) return

        viewModelScope.launch {
            _uiState.update { it.copy(isFlushingQueue = true, activeError = null) }
            when (val flushResult = scrobbleManager.flushQueue()) {
                is AppResult.Success -> {
                    val count = flushResult.data
                    _uiState.update { state ->
                        val strings = stringsForLocale(state.currentLocale)
                        state.copy(
                            isFlushingQueue = false,
                            statusBannerMessage = if (count > 0) {
                                strings.statusFlushedQueueCount(count)
                            } else {
                                strings.statusQueueUpToDate
                            },
                        )
                    }
                }

                is AppResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isFlushingQueue = false,
                            activeError = flushResult.error,
                        )
                    }
                }
            }
        }
    }

    fun removeQueuedScrobble(id: Long) {
        viewModelScope.launch {
            scrobbleManager.removeQueuedScrobble(id)
        }
    }

    fun clearAllQueuedScrobbles() {
        viewModelScope.launch {
            scrobbleManager.clearOfflineQueue()
            _uiState.update { state ->
                val strings = stringsForLocale(state.currentLocale)
                state.copy(statusBannerMessage = strings.statusClearedOfflineQueue)
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(activeError = null) }
    }

    fun dismissStatusBanner() {
        _uiState.update { it.copy(statusBannerMessage = null) }
    }

    // -------------------------------------------------------------------------
    // Internal State Synchronization
    // -------------------------------------------------------------------------

    private fun observeScrobbleStreams() {
        viewModelScope.launch {
            scrobbleManager.sessionState.collectLatest { session ->
                _uiState.update { it.copy(scrobbleSession = session) }
            }
        }

        viewModelScope.launch {
            scrobbleManager.rateLimitState.collectLatest { rateLimit ->
                _uiState.update { it.copy(rateLimitInfo = rateLimit) }
            }
        }

        viewModelScope.launch {
            scrobbleManager.queuedPlayingNow.collectLatest { playingNow ->
                _uiState.update { it.copy(queuedPlayingNow = playingNow) }
            }
        }

        viewModelScope.launch {
            scrobbleManager.pendingCompletedListens.collectLatest { completedList ->
                _uiState.update {
                    it.copy(
                        pendingCompletedScrobbles = completedList,
                        pendingCompletedCount = completedList.size,
                    )
                }
            }
        }

        viewModelScope.launch {
            scrobbleManager.totalQueuedCount.collectLatest { totalCount ->
                _uiState.update { it.copy(totalQueuedCount = totalCount) }
            }
        }
    }

    private fun loadInitialPreferences() {
        viewModelScope.launch {
            val savedToken = preferencesStore.getUserToken().orEmpty()
            val savedUser = preferencesStore.getValidatedUserName()
            val enabled = preferencesStore.isScrobblingEnabled()
            val systemLocale = AppLocale.fromLocale()
            youTubeMusicProvider.updateLocale(
                hl = systemLocale.innertubeHl,
                gl = systemLocale.innertubeGl,
            )
            _uiState.update {
                it.copy(
                    currentLocale = systemLocale,
                    listenBrainzTokenInput = savedToken,
                    configuredTokenMasked = savedToken.takeIf(String::isNotBlank)?.let(::maskToken),
                    validatedUserName = savedUser,
                    isTokenValid = if (savedToken.isNotBlank()) true else null,
                    scrobblingEnabled = enabled,
                )
            }
        }
    }

    private fun syncPlayerState() {
        val player = _connectedPlayer.value ?: return
        val mediaItem = player.currentMediaItem
        val currentIndex = player.currentMediaItemIndex
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
            ?: _uiState.value.currentTrack?.durationMs
            ?: 0L
        val position = player.currentPosition.coerceAtLeast(0L)
        val buffered = player.bufferedPosition.coerceAtLeast(position)
        val hasVideo = player.currentTracks.containsType(C.TRACK_TYPE_VIDEO)

        val previousTrackId = _uiState.value.currentTrack?.id
        _uiState.update { state ->
            val resolvedTrack = mediaItem?.let { item ->
                state.playbackQueue.firstOrNull { it.id == item.mediaId }
                    ?: mediaItemToMediaTrack(item, duration, state.currentLocale)
            }
            state.copy(
                currentTrack = resolvedTrack ?: state.currentTrack,
                currentQueueIndex = if (currentIndex != C.INDEX_UNSET) currentIndex else state.currentQueueIndex,
                isPlaying = player.isPlaying,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
                currentPositionMs = position,
                durationMs = duration,
                bufferedPositionMs = buffered,
                playbackSpeed = player.playbackParameters.speed,
                repeatMode = player.repeatMode,
                shuffleModeEnabled = player.shuffleModeEnabled,
                hasVideoTrack = hasVideo,
            )
        }
        val currentTrack = _uiState.value.currentTrack
        if (currentTrack != null && currentTrack.id != previousTrackId && _uiState.value.isLyricsActive) {
            loadLyricsForTrack(currentTrack)
        }
    }

    private fun startPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = viewModelScope.launch {
            while (isActive) {
                val player = _connectedPlayer.value
                if (player != null && player.isPlaying) {
                    val pos = player.currentPosition.coerceAtLeast(0L)
                    val buf = player.bufferedPosition.coerceAtLeast(pos)
                    val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
                    _uiState.update { state ->
                        state.copy(
                            currentPositionMs = pos,
                            bufferedPositionMs = buf,
                            durationMs = dur ?: state.durationMs,
                        )
                    }
                }
                delay(POSITION_POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = null
    }

    private fun mediaItemToMediaTrack(
        item: MediaItem,
        fallbackDurationMs: Long,
        locale: AppLocale,
    ): MediaTrack? {
        val extras = item.mediaMetadata.extras
        val id = extras?.getString(MusicPlaybackService.EXTRA_VIDEO_ID)
            ?: item.mediaId.takeIf { it.isNotBlank() }
            ?: return null
        val title = extras?.getString(MusicPlaybackService.EXTRA_TITLE)
            ?: item.mediaMetadata.title?.toString()
            ?: return null
        val artist = extras?.getString(MusicPlaybackService.EXTRA_ARTIST_NAME)
            ?: item.mediaMetadata.artist?.toString()
            ?: stringsForLocale(locale).unknownArtist
        val album = extras?.getString(MusicPlaybackService.EXTRA_ALBUM_NAME)
            ?: item.mediaMetadata.albumTitle?.toString()
        val duration = extras?.getLong(MusicPlaybackService.EXTRA_DURATION_MS, 0L)
            ?.takeIf { it > 0L }
            ?: fallbackDurationMs
        val sourceTypeName = extras?.getString(MusicPlaybackService.EXTRA_SOURCE_TYPE)
        val sourceType = runCatching {
            sourceTypeName?.let(MediaSourceType::valueOf)
        }.getOrNull() ?: MediaSourceType.YOUTUBE_MUSIC

        return MediaTrack(
            id = id,
            title = title,
            artistName = artist,
            albumName = album,
            durationMs = duration,
            artworkUrl = item.mediaMetadata.artworkUri?.toString(),
            sourceType = sourceType,
        )
    }

    private fun extractDomainError(throwable: Throwable?): DomainError? {
        var current = throwable
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

    private fun maskToken(rawToken: String): String {
        val trimmed = rawToken.trim()
        if (trimmed.length <= 8) return "••••••••"
        return "${trimmed.take(4)}••••••••${trimmed.takeLast(4)}"
    }

    override fun onCleared() {
        detachPlayer()
        super.onCleared()
    }

    companion object {
        private const val POSITION_POLL_INTERVAL_MS = 250L
        private const val RESTART_TRACK_THRESHOLD_MS = 3_000L
    }
}
