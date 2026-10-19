// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube.ui.main

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.listentube.core.result.DomainError
import com.listentube.domain.media.MediaTrack
import com.listentube.ui.i18n.AppLocale
import com.listentube.ui.i18n.LocalAppStrings
import com.listentube.ui.i18n.ProvideAppStrings
import com.listentube.ui.i18n.localizedMessage
import com.listentube.ui.player.MainDestination
import com.listentube.ui.player.NowPlayingPlayerSheet
import com.listentube.ui.player.PlayerUiState
import com.listentube.ui.player.PlayerViewModel
import com.listentube.ui.player.formatDurationMs
import com.listentube.ui.scrobble.ScrobbleQueueScreen

/**
 * Root application screen hosting YouTube Music search, Local MediaStore library browsing,
 * the ListenBrainz Scrobble Queue & Configuration tab, a persistent mini-player bar,
 * runtime locale selection (`en-US`, `pt-BR`, `es-MX`, `ru-RU`), and the expandable [NowPlayingPlayerSheet].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectedPlayer by viewModel.connectedPlayer.collectAsState()

    ProvideAppStrings(locale = uiState.currentLocale) {
        val strings = LocalAppStrings.current

        Scaffold(
            modifier = modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
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
                    },
                    actions = {
                        LocaleSelectorAction(
                            currentLocale = uiState.currentLocale,
                            onSelectLocale = viewModel::selectLocale,
                        )

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
                            onSearchQueryChanged = viewModel::onSearchQueryChanged,
                            onSubmitSearch = viewModel::submitSearch,
                            onLoadMore = viewModel::loadMoreSearchResults,
                            onPlayTrack = { track ->
                                viewModel.playTrack(
                                    track = track,
                                    queueContext = uiState.searchResults,
                                    expandSheet = true,
                                )
                            },
                            onEnqueueTrack = viewModel::addTrackToQueue,
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
                onSelectPlaybackSpeed = viewModel::setPlaybackSpeed,
                onSelectRenderMode = viewModel::setRenderMode,
                onSelectQueueIndex = viewModel::skipToQueueIndex,
            )
        }
    }
}

@Composable
private fun LocaleSelectorAction(
    currentLocale: AppLocale,
    onSelectLocale: (AppLocale) -> Unit,
) {
    val strings = LocalAppStrings.current
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Default.Language,
                contentDescription = strings.cdSelectLanguage,
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            AppLocale.entries.forEach { locale ->
                val isSelected = locale == currentLocale
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "${locale.nativeDisplayName} (${locale.languageTag})",
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    trailingIcon = {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelectLocale(locale)
                    },
                )
            }
        }
    }
}

@Composable
private fun YouTubeExploreTab(
    uiState: PlayerUiState,
    onSearchQueryChanged: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onLoadMore: () -> Unit,
    onPlayTrack: (MediaTrack) -> Unit,
    onEnqueueTrack: (MediaTrack) -> Unit,
) {
    val strings = LocalAppStrings.current

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = onSearchQueryChanged,
                placeholder = { Text(strings.searchYtmPlaceholder) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChanged("") }) {
                            Icon(Icons.Default.Close, contentDescription = strings.cdClearSearch)
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmitSearch() }),
                modifier = Modifier.weight(1f),
            )

            Button(
                onClick = onSubmitSearch,
                enabled = !uiState.isSearching && uiState.searchQuery.isNotBlank(),
            ) {
                Text(strings.actionSearch)
            }
        }

        if (uiState.isSearching) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (uiState.searchResults.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.LibraryMusic,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = strings.emptyYtmSearchPrompt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    items = uiState.searchResults,
                    key = { it.id },
                ) { track ->
                    TrackListItem(
                        track = track,
                        isCurrentlyPlaying = uiState.currentTrack?.id == track.id,
                        onPlay = { onPlayTrack(track) },
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
                        onPlay = { onPlayTrack(track) },
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
                    imageVector = Icons.Default.PlaylistAdd,
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
