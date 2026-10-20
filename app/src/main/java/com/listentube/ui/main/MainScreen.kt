// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.listentube.core.result.DomainError
import com.listentube.domain.media.MediaCollection
import com.listentube.domain.media.MediaCollectionType
import com.listentube.domain.media.MediaTrack
import com.listentube.ui.i18n.LocalAppStrings
import com.listentube.ui.i18n.ProvideAppStrings
import com.listentube.ui.i18n.localizedMessage
import com.listentube.ui.i18n.rememberSystemAppLocale
import com.listentube.ui.player.MainDestination
import com.listentube.ui.player.NowPlayingPlayerSheet
import com.listentube.ui.player.PlayerUiState
import com.listentube.ui.player.PlayerViewModel
import com.listentube.ui.player.formatDurationMs
import com.listentube.ui.scrobble.ScrobbleQueueScreen

/**
 * Root application screen hosting YouTube Music search, Local MediaStore library browsing,
 * the ListenBrainz Scrobble Queue & Configuration tab, a persistent mini-player bar,
 * and the expandable [NowPlayingPlayerSheet].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectedPlayer by viewModel.connectedPlayer.collectAsState()
    val systemLocale = rememberSystemAppLocale()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(systemLocale) {
        viewModel.onSystemLocaleChanged(systemLocale)
    }

    LaunchedEffect(uiState.isPlayerSheetExpanded) {
        if (uiState.isPlayerSheetExpanded) {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    ProvideAppStrings(locale = systemLocale) {
        val strings = LocalAppStrings.current

        BackHandler(enabled = uiState.openCollection != null || uiState.isSearchActive) {
            if (uiState.openCollection != null) {
                viewModel.closeCollection()
            } else if (uiState.isSearchActive) {
                viewModel.setSearchActive(false)
                focusManager.clearFocus()
                keyboardController?.hide()
            }
        }

        Scaffold(
            modifier = modifier.fillMaxSize(),
            topBar = {
                if (uiState.isSearchActive) {
                    val searchFocusRequester = remember { FocusRequester() }
                    LaunchedEffect(Unit) {
                        searchFocusRequester.requestFocus()
                    }
                    TopAppBar(
                        navigationIcon = {
                            IconButton(
                                onClick = {
                                    viewModel.setSearchActive(false)
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = strings.cdBack,
                                )
                            }
                        },
                        title = {
                            TextField(
                                value = uiState.searchQuery,
                                onValueChange = viewModel::onSearchQueryChanged,
                                placeholder = { Text(strings.searchYtmPlaceholder) },
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    disabledContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(
                                    onSearch = {
                                        focusManager.clearFocus()
                                        keyboardController?.hide()
                                        viewModel.submitSearch()
                                    },
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(searchFocusRequester),
                            )
                        },
                        actions = {
                            if (uiState.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = strings.cdClearSearch,
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                    viewModel.submitSearch()
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = strings.actionSearch,
                                )
                            }
                        },
                    )
                } else {
                    TopAppBar(
                        navigationIcon = {
                            if (uiState.openCollection != null && uiState.currentDestination == MainDestination.EXPLORE) {
                                IconButton(onClick = { viewModel.closeCollection() }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = strings.cdBack,
                                    )
                                }
                            }
                        },
                        title = {
                            if (uiState.openCollection != null && uiState.currentDestination == MainDestination.EXPLORE) {
                                Text(
                                    text = uiState.openCollection?.title.orEmpty(),
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            } else {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = strings.appName,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    if (uiState.scrobbleSession.scrobbleCompleted) {
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                        ) {
                                            Text(
                                                text = strings.badgeScrobbled,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = {
                                    viewModel.selectDestination(MainDestination.EXPLORE)
                                    viewModel.setSearchActive(true)
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = strings.cdSearch,
                                )
                            }
                            IconButton(
                                onClick = { viewModel.selectDestination(MainDestination.SCROBBLE_QUEUE) },
                            ) {
                                BadgedBox(
                                    badge = {
                                        if (uiState.totalQueuedCount > 0) {
                                            Badge { Text(uiState.totalQueuedCount.toString()) }
                                        }
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudQueue,
                                        contentDescription = strings.cdScrobbleQueue,
                                    )
                                }
                            }
                        },
                    )
                }
            },
            bottomBar = {
                Column {
                    if (uiState.currentTrack != null) {
                        MiniPlayerBar(
                            uiState = uiState,
                            onExpandSheet = { viewModel.setPlayerSheetExpanded(true) },
                            onTogglePlayPause = viewModel::togglePlayPause,
                            onSkipNext = viewModel::skipToNext,
                        )
                    }

                    NavigationBar {
                        NavigationBarItem(
                            selected = uiState.currentDestination == MainDestination.EXPLORE,
                            onClick = { viewModel.selectDestination(MainDestination.EXPLORE) },
                            icon = { Icon(Icons.Default.Search, contentDescription = null) },
                            label = { Text(strings.navYoutubeMusic) },
                        )
                        NavigationBarItem(
                            selected = uiState.currentDestination == MainDestination.LOCAL_LIBRARY,
                            onClick = { viewModel.selectDestination(MainDestination.LOCAL_LIBRARY) },
                            icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                            label = { Text(strings.navLocalAudio) },
                        )
                        NavigationBarItem(
                            selected = uiState.currentDestination == MainDestination.SCROBBLE_QUEUE,
                            onClick = { viewModel.selectDestination(MainDestination.SCROBBLE_QUEUE) },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (uiState.totalQueuedCount > 0) {
                                            Badge { Text(uiState.totalQueuedCount.toString()) }
                                        }
                                    },
                                ) {
                                    Icon(Icons.Default.CloudQueue, contentDescription = null)
                                }
                            },
                            label = { Text(strings.navListenBrainz) },
                        )
                    }
                }
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                uiState.activeError?.let { error ->
                    DomainErrorBanner(
                        error = error,
                        onDismiss = viewModel::dismissError,
                    )
                }

                uiState.statusBannerMessage?.let { statusMessage ->
                    StatusMessageBanner(
                        message = statusMessage,
                        onDismiss = viewModel::dismissStatusBanner,
                    )
                }

                when (uiState.currentDestination) {
                    MainDestination.EXPLORE -> {
                        YouTubeExploreTab(
                            uiState = uiState,
                            onOpenCollection = viewModel::openCollection,
                            onCloseCollection = viewModel::closeCollection,
                            onPlayCollection = viewModel::playCollection,
                            onEnqueueCollection = viewModel::addCollectionToQueue,
                            onPlayTrack = { track ->
                                viewModel.playTrack(
                                    track = track,
                                    queueContext = uiState.searchResults,
                                    expandSheet = true,
                                )
                            },
                            onEnqueueTrack = viewModel::addTrackToQueue,
                            onLoadMore = viewModel::loadMoreSearchResults,
                            onActivateSearch = { viewModel.setSearchActive(true) },
                        )
                    }

                    MainDestination.LOCAL_LIBRARY -> {
                        LocalLibraryTab(
                            uiState = uiState,
                            onFilterQueryChanged = viewModel::onLocalFilterQueryChanged,
                            onRefresh = viewModel::loadLocalLibraryTracks,
                            onPlayTrack = { track ->
                                viewModel.playTrack(
                                    track = track,
                                    queueContext = uiState.filteredLocalTracks,
                                    expandSheet = true,
                                )
                            },
                            onEnqueueTrack = viewModel::addTrackToQueue,
                        )
                    }

                    MainDestination.SCROBBLE_QUEUE -> {
                        ScrobbleQueueScreen(
                            uiState = uiState,
                            onTokenInputChanged = viewModel::onListenBrainzTokenInputChanged,
                            onValidateAndSaveToken = viewModel::validateAndSaveListenBrainzToken,
                            onToggleScrobblingEnabled = viewModel::setScrobblingEnabled,
                            onFlushQueueNow = viewModel::flushScrobbleQueueNow,
                            onRemoveQueuedItem = viewModel::removeQueuedScrobble,
                            onClearQueue = viewModel::clearAllQueuedScrobbles,
                        )
                    }
                }
            }
        }

        if (uiState.isPlayerSheetExpanded && uiState.currentTrack != null) {
            NowPlayingPlayerSheet(
                uiState = uiState,
                player = connectedPlayer,
                onDismiss = { viewModel.setPlayerSheetExpanded(false) },
                onTogglePlayPause = viewModel::togglePlayPause,
                onSeekTo = viewModel::seekTo,
                onSkipPrevious = viewModel::skipToPrevious,
                onSkipNext = viewModel::skipToNext,
                onToggleShuffle = viewModel::toggleShuffleMode,
                onCycleRepeat = viewModel::cycleRepeatMode,
                onSelectRenderMode = viewModel::setRenderMode,
                onSelectQueueIndex = viewModel::skipToQueueIndex,
            )
        }
    }
}

@Composable
private fun YouTubeExploreTab(
    uiState: PlayerUiState,
    onOpenCollection: (MediaCollection) -> Unit,
    onCloseCollection: () -> Unit,
    onPlayCollection: (MediaCollection, Int) -> Unit,
    onEnqueueCollection: (MediaCollection) -> Unit,
    onPlayTrack: (MediaTrack) -> Unit,
    onEnqueueTrack: (MediaTrack) -> Unit,
    onLoadMore: () -> Unit,
    onActivateSearch: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    if (uiState.openCollection != null) {
        CollectionDetailContent(
            collection = uiState.openCollection,
            tracks = uiState.collectionTracks,
            isLoading = uiState.isLoadingCollection,
            currentTrackId = uiState.currentTrack?.id,
            onBack = onCloseCollection,
            onPlayTrackAtIndex = { index ->
                focusManager.clearFocus()
                keyboardController?.hide()
                onPlayCollection(uiState.openCollection, index)
            },
            onPlayAll = {
                focusManager.clearFocus()
                keyboardController?.hide()
                onPlayCollection(uiState.openCollection, 0)
            },
            onEnqueueCollection = { onEnqueueCollection(uiState.openCollection) },
            onEnqueueTrack = onEnqueueTrack,
        )
        return
    }

    if (uiState.isSearching) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    val hasAnyResults = uiState.searchAlbums.isNotEmpty() ||
        uiState.searchPlaylists.isNotEmpty() ||
        uiState.searchResults.isNotEmpty()

    if (!hasAnyResults) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 24.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.LibraryMusic,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = strings.emptyYtmSearchPrompt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onActivateSearch) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(strings.actionSearch)
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (uiState.searchAlbums.isNotEmpty()) {
            item(key = "section_header_albums") {
                Text(
                    text = strings.sectionAlbums,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
            items(
                items = uiState.searchAlbums,
                key = { "album_${it.playlistId}" },
            ) { album ->
                CollectionListItem(
                    collection = album,
                    onOpen = { onOpenCollection(album) },
                    onPlay = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                        onPlayCollection(album, 0)
                    },
                    onEnqueue = { onEnqueueCollection(album) },
                )
            }
        }

        if (uiState.searchPlaylists.isNotEmpty()) {
            item(key = "section_header_playlists") {
                Text(
                    text = strings.sectionPlaylists,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
            }
            items(
                items = uiState.searchPlaylists,
                key = { "playlist_${it.playlistId}" },
            ) { playlist ->
                CollectionListItem(
                    collection = playlist,
                    onOpen = { onOpenCollection(playlist) },
                    onPlay = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                        onPlayCollection(playlist, 0)
                    },
                    onEnqueue = { onEnqueueCollection(playlist) },
                )
            }
        }

        if (uiState.searchResults.isNotEmpty()) {
            item(key = "section_header_songs") {
                Text(
                    text = strings.sectionSongs,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
            }
            items(
                items = uiState.searchResults,
                key = { "song_${it.id}" },
            ) { track ->
                TrackListItem(
                    track = track,
                    isCurrentlyPlaying = uiState.currentTrack?.id == track.id,
                    onPlay = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                        onPlayTrack(track)
                    },
                    onEnqueue = { onEnqueueTrack(track) },
                )
            }

            if (!uiState.searchContinuationToken.isNullOrBlank()) {
                item(key = "load_more_button") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Button(
                            onClick = onLoadMore,
                            enabled = !uiState.isLoadingMoreSearch,
                        ) {
                            if (uiState.isLoadingMoreSearch) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(strings.actionLoadMore)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionListItem(
    collection: MediaCollection,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onEnqueue: () -> Unit,
) {
    val strings = LocalAppStrings.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                if (!collection.artworkUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = collection.artworkUrl,
                        contentDescription = collection.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (collection.type == MediaCollectionType.ALBUM) Icons.Default.Album else Icons.Default.LibraryMusic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = collection.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = collection.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconButton(onClick = onPlay) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = strings.cdPlay,
                )
            }

            IconButton(onClick = onEnqueue) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = strings.cdAddToQueue,
                )
            }
        }
    }
}

@Composable
private fun CollectionDetailContent(
    collection: MediaCollection,
    tracks: List<MediaTrack>,
    isLoading: Boolean,
    currentTrackId: String?,
    onBack: () -> Unit,
    onPlayTrackAtIndex: (Int) -> Unit,
    onPlayAll: () -> Unit,
    onEnqueueCollection: () -> Unit,
    onEnqueueTrack: (MediaTrack) -> Unit,
) {
    val strings = LocalAppStrings.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "collection_header") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier.size(100.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shadowElevation = 4.dp,
                    ) {
                        if (!collection.artworkUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = collection.artworkUrl,
                                contentDescription = collection.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (collection.type == MediaCollectionType.ALBUM) Icons.Default.Album else Icons.Default.LibraryMusic,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (collection.type == MediaCollectionType.ALBUM) strings.sectionAlbums else strings.sectionPlaylists,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = collection.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = collection.subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = onPlayAll,
                        enabled = tracks.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(strings.cdPlayAll)
                    }

                    IconButton(
                        onClick = onEnqueueCollection,
                        enabled = tracks.isNotEmpty(),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                            contentDescription = strings.cdAddToQueue,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        if (isLoading) {
            item(key = "collection_loading") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        } else if (tracks.isEmpty()) {
            item(key = "collection_empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = strings.playlistEmptyTracks,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            itemsIndexed(
                items = tracks,
                key = { index, track -> "${track.id}_$index" },
            ) { index, track ->
                val isCurrentlyPlaying = currentTrackId == track.id
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPlayTrackAtIndex(index) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isCurrentlyPlaying) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(32.dp),
                        )

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isCurrentlyPlaying) FontWeight.Bold else FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = track.artistName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        if (track.durationMs > 0L) {
                            Text(
                                text = formatDurationMs(track.durationMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp),
                            )
                        }

                        IconButton(onClick = { onEnqueueTrack(track) }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                contentDescription = strings.cdAddToQueue,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalLibraryTab(
    uiState: PlayerUiState,
    onFilterQueryChanged: (String) -> Unit,
    onRefresh: () -> Unit,
    onPlayTrack: (MediaTrack) -> Unit,
    onEnqueueTrack: (MediaTrack) -> Unit,
) {
    val strings = LocalAppStrings.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val tracks = uiState.filteredLocalTracks

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = uiState.localFilterQuery,
                onValueChange = onFilterQueryChanged,
                placeholder = { Text(strings.filterLocalPlaceholder) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.weight(1f),
            )

            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = strings.cdRefreshLocalLibrary)
            }
        }

        if (uiState.isLoadingLocalTracks) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (tracks.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = strings.emptyLocalLibrary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    items = tracks,
                    key = { it.id },
                ) { track ->
                    TrackListItem(
                        track = track,
                        isCurrentlyPlaying = uiState.currentTrack?.id == track.id,
                        onPlay = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            onPlayTrack(track)
                        },
                        onEnqueue = { onEnqueueTrack(track) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackListItem(
    track: MediaTrack,
    isCurrentlyPlaying: Boolean,
    onPlay: () -> Unit,
    onEnqueue: () -> Unit,
) {
    val strings = LocalAppStrings.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentlyPlaying) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                if (!track.artworkUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = track.artworkUrl,
                        contentDescription = track.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isCurrentlyPlaying) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(track.artistName)
                        if (!track.albumName.isNullOrBlank()) {
                            append(" • ")
                            append(track.albumName)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (track.durationMs > 0L) {
                Text(
                    text = formatDurationMs(track.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }

            IconButton(onClick = onEnqueue) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = strings.cdAddToQueue,
                )
            }
        }
    }
}

@Composable
private fun MiniPlayerBar(
    uiState: PlayerUiState,
    onExpandSheet: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val track = uiState.currentTrack ?: return
    val duration = uiState.durationMs.coerceAtLeast(track.durationMs).coerceAtLeast(1L)
    val playbackFraction = (uiState.currentPositionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

    Surface(
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onExpandSheet),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            LinearProgressIndicator(
                progress = { playbackFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    if (!track.artworkUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = track.artworkUrl,
                            contentDescription = track.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.MusicNote, contentDescription = null)
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track.artistName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                IconButton(onClick = onTogglePlayPause) {
                    if (uiState.isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (uiState.isPlaying) strings.cdPause else strings.cdPlay,
                        )
                    }
                }

                IconButton(onClick = onSkipNext) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = strings.cdNextTrack,
                    )
                }
            }
        }
    }
}

@Composable
private fun DomainErrorBanner(
    error: DomainError,
    onDismiss: () -> Unit,
) {
    val strings = LocalAppStrings.current

    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = error.localizedMessage(strings),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = strings.cdDismissError,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun StatusMessageBanner(
    message: String,
    onDismiss: () -> Unit,
) {
    val strings = LocalAppStrings.current

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = strings.cdDismissMessage,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}
