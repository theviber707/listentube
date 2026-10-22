// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube.ui.player

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.listentube.domain.lyrics.TrackLyrics
import com.listentube.domain.media.MediaTrack
import com.listentube.service.playback.AudioSpectrumAnalyzer
import com.listentube.ui.i18n.LocalAppStrings
import kotlinx.coroutines.delay
import java.util.Locale

private const val PI_F = Math.PI.toFloat()
private const val TWO_PI = 2f * PI_F
private const val HALF_PI = PI_F / 2f

/** Per-second exponential smoothing rates for visualizer bars rising / falling. */
private const val VISUALIZER_ATTACK_RATE = 35f
private const val VISUALIZER_RELEASE_RATE = 9f

/**
 * Expandable Now Playing bottom sheet supporting both high-resolution audio artwork
 * ([PlayerRenderMode.AUDIO_ARTWORK]) and live Media3 video surface rendering
 * ([PlayerRenderMode.VIDEO_SURFACE]), along with the playback queue.
 */
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingPlayerSheet(
    uiState: PlayerUiState,
    player: Player?,
    onDismiss: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onSelectRenderMode: (PlayerRenderMode) -> Unit,
    onSelectQueueIndex: (Int) -> Unit,
    onToggleLyrics: () -> Unit = {},
    onRetryLyrics: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val track = uiState.currentTrack ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        modifier = modifier,
    ) {
        NowPlayingSheetContent(
            track = track,
            uiState = uiState,
            player = player,
            onCollapse = onDismiss,
            onTogglePlayPause = onTogglePlayPause,
            onSeekTo = onSeekTo,
            onSkipPrevious = onSkipPrevious,
            onSkipNext = onSkipNext,
            onToggleShuffle = onToggleShuffle,
            onCycleRepeat = onCycleRepeat,
            onSelectRenderMode = onSelectRenderMode,
            onSelectQueueIndex = onSelectQueueIndex,
            onToggleLyrics = onToggleLyrics,
            onRetryLyrics = onRetryLyrics,
        )
    }
}

@Composable
fun NowPlayingSheetContent(
    track: MediaTrack,
    uiState: PlayerUiState,
    player: Player?,
    onCollapse: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onSelectRenderMode: (PlayerRenderMode) -> Unit,
    onSelectQueueIndex: (Int) -> Unit,
    onToggleLyrics: () -> Unit = {},
    onRetryLyrics: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item(key = "player_main_section") {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Header Row: Collapse button + Audio Artwork / Video Surface Mode Switcher
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(onClick = onCollapse) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = strings.cdCollapsePlayer,
                        )
                    }

                    RenderModeToggleBar(
                        currentMode = uiState.renderMode,
                        onSelectMode = onSelectRenderMode,
                    )

                    Spacer(modifier = Modifier.width(48.dp))
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Media Viewport: Switches smoothly between Album Artwork, Lyrics, and ExoPlayer Video Surface
                AnimatedContent(
                    targetState = Pair(uiState.renderMode, uiState.isLyricsActive),
                    label = "PlayerViewportMode",
                ) { (renderMode, isLyricsActive) ->
                    when (renderMode) {
                        PlayerRenderMode.AUDIO_ARTWORK -> {
                            if (isLyricsActive) {
                                LyricsViewport(
                                    lyrics = uiState.currentLyrics,
                                    isLoading = uiState.isLoadingLyrics,
                                    lyricsError = uiState.lyricsError,
                                    currentPositionMs = uiState.currentPositionMs,
                                    onSeekTo = onSeekTo,
                                    onRetry = onRetryLyrics,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f),
                                )
                            } else {
                                AudioArtworkViewport(
                                    track = track,
                                    isPlaying = uiState.isPlaying,
                                )
                            }
                        }

                        PlayerRenderMode.VIDEO_SURFACE -> {
                            VideoSurfaceViewport(
                                player = player,
                                track = track,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Track Title, Artist, Album & Lyrics Button (Right side, aligned with title/artist)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.Start,
                    ) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = buildString {
                                append(track.artistName)
                                if (!track.albumName.isNullOrBlank()) {
                                    append(" • ")
                                    append(track.albumName)
                                }
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    FilledIconToggleButton(
                        checked = uiState.isLyricsActive,
                        onCheckedChange = { onToggleLyrics() },
                        colors = IconButtonDefaults.filledIconToggleButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lyrics,
                            contentDescription = strings.cdToggleLyrics,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Seekbar
                PlaybackSeekBar(
                    currentPositionMs = uiState.currentPositionMs,
                    durationMs = uiState.durationMs.coerceAtLeast(track.durationMs),
                    onSeekTo = onSeekTo,
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Transport Controls Row
                TransportControlsRow(
                    isPlaying = uiState.isPlaying,
                    isBuffering = uiState.isBuffering,
                    shuffleEnabled = uiState.shuffleModeEnabled,
                    repeatMode = uiState.repeatMode,
                    onToggleShuffle = onToggleShuffle,
                    onSkipPrevious = onSkipPrevious,
                    onTogglePlayPause = onTogglePlayPause,
                    onSkipNext = onSkipNext,
                    onCycleRepeat = onCycleRepeat,
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Under the video view, display lyrics directly under the video & controls when activated
                AnimatedVisibility(
                    visible = uiState.renderMode == PlayerRenderMode.VIDEO_SURFACE && uiState.isLyricsActive,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = strings.lyricsTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (uiState.currentLyrics?.isSynced == true) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                ) {
                                    Text(
                                        text = "SYNCED",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LyricsViewport(
                            lyrics = uiState.currentLyrics,
                            isLoading = uiState.isLoadingLyrics,
                            lyricsError = uiState.lyricsError,
                            currentPositionMs = uiState.currentPositionMs,
                            onSeekTo = onSeekTo,
                            onRetry = onRetryLyrics,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                        )
                    }
                }
            }
        }

        // Up Next Queue Section
        if (uiState.playbackQueue.size > 1) {
            item(key = "up_next_header") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = strings.upNextHeader(uiState.playbackQueue.size),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            itemsIndexed(
                items = uiState.playbackQueue,
                key = { index, item -> "${item.id}_$index" },
            ) { index, queueTrack ->
                val isCurrent = index == uiState.currentQueueIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isCurrent) {
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                            } else {
                                Color.Transparent
                            },
                        )
                        .clickable { onSelectQueueIndex(index) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${index + 1}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(32.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = queueTrack.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = queueTrack.artistName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = formatDurationMs(queueTrack.durationMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RenderModeToggleBar(
    currentMode: PlayerRenderMode,
    onSelectMode: (PlayerRenderMode) -> Unit,
) {
    val strings = LocalAppStrings.current

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = currentMode == PlayerRenderMode.AUDIO_ARTWORK,
            onClick = { onSelectMode(PlayerRenderMode.AUDIO_ARTWORK) },
            label = { Text(strings.renderModeAudioArt) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Album,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
        )
        FilterChip(
            selected = currentMode == PlayerRenderMode.VIDEO_SURFACE,
            onClick = { onSelectMode(PlayerRenderMode.VIDEO_SURFACE) },
            label = { Text(strings.renderModeVideoSurface) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.OndemandVideo,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
        )
    }
}

/**
 * Renders the album cover artwork with an ambient audio visualizer radiating behind it.
 */
@Composable
private fun AudioArtworkViewport(
    track: MediaTrack,
    isPlaying: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        AudioVisualizerBehindCover(
            isPlaying = isPlaying,
            modifier = Modifier.fillMaxSize(),
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth(0.78f)
                .aspectRatio(1f),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
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
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(96.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Renders the lyrics surface with support for both synchronized karaoke line highlighting
 * and unsynchronized plain text, replacing album artwork in audio mode or displaying below video.
 */
@Composable
private fun LyricsViewport(
    lyrics: TrackLyrics?,
    isLoading: Boolean,
    lyricsError: String?,
    currentPositionMs: Long,
    onSeekTo: (Long) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                isLoading && (lyrics == null || lyrics.lines.isEmpty()) -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = strings.lyricsLoading,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                lyrics == null || lyrics.lines.isEmpty() -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lyrics,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = strings.lyricsEmpty,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        if (lyricsError != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            FilledTonalButton(onClick = onRetry) {
                                Text(strings.lyricsRetry)
                            }
                        }
                    }
                }

                else -> {
                    if (lyrics.isSynced) {
                        SyncedLyricsList(
                            lyrics = lyrics,
                            currentPositionMs = currentPositionMs,
                            onSeekTo = onSeekTo,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        PlainLyricsList(
                            lyrics = lyrics,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncedLyricsList(
    lyrics: TrackLyrics,
    currentPositionMs: Long,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val listState = rememberLazyListState()

    val activeIndex = remember(lyrics, currentPositionMs) {
        val idx = lyrics.lines.indexOfLast { it.timeMs != null && it.timeMs <= currentPositionMs }
        if (idx >= 0) idx else 0
    }

    var isUserInteracting by remember { mutableStateOf(false) }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            isUserInteracting = true
        } else {
            delay(2500L)
            isUserInteracting = false
        }
    }

    LaunchedEffect(activeIndex, isUserInteracting) {
        if (!isUserInteracting && activeIndex in lyrics.lines.indices) {
            val targetIndex = (activeIndex - 1).coerceAtLeast(0)
            listState.animateScrollToItem(targetIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 24.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(
            items = lyrics.lines,
            key = { index, line -> "${line.timeMs}_$index" },
        ) { index, line ->
            val isActive = index == activeIndex
            val scale by animateFloatAsState(
                targetValue = if (isActive) 1.04f else 1.0f,
                label = "LyricScale",
            )

            Text(
                text = line.text,
                style = if (isActive) {
                    MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                },
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        line.timeMs?.let(onSeekTo)
                    }
                    .padding(vertical = 8.dp, horizontal = 12.dp),
            )
        }

        if (!lyrics.source.isNullOrBlank()) {
            item(key = "lyrics_source_footer") {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = strings.lyricsSource(lyrics.source),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun PlainLyricsList(
    lyrics: TrackLyrics,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 20.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(
            items = lyrics.lines,
            key = { index, _ -> index },
        ) { _, line ->
            Text(
                text = line.text,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            )
        }

        if (!lyrics.source.isNullOrBlank()) {
            item(key = "plain_lyrics_source_footer") {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = strings.lyricsSource(lyrics.source),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }
}

/**
 * Real-time audio spectrum visualizer radiating outward from behind the album art card perimeter.
 *
 * Bar heights are driven by [AudioSpectrumAnalyzer], which analyzes the decoded PCM ExoPlayer is
 * actually playing. The spectrum is mirrored left/right with bass at the bottom and treble at the
 * top. When playback pauses the bars fall back to small resting dots and the frame loop stops.
 */
@Composable
private fun AudioVisualizerBehindCover(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 48,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    accentColor: Color = MaterialTheme.colorScheme.tertiary,
) {
    val levels = remember(barCount) { FloatArray(barCount) }
    val bands = remember { FloatArray(AudioSpectrumAnalyzer.BAND_COUNT) }
    // Fractional band index for each bar: bottom (angle π/2) = bass, top = treble, mirrored.
    val barBandPosition = remember(barCount) {
        FloatArray(barCount) { i ->
            val angle = (i.toFloat() / barCount) * TWO_PI
            val fromBottom = kotlin.math.abs(((angle - HALF_PI + PI_F) % TWO_PI + TWO_PI) % TWO_PI - PI_F)
            (fromBottom / PI_F) * (AudioSpectrumAnalyzer.BAND_COUNT - 1)
        }
    }
    var frameTick by remember { mutableLongStateOf(0L) }

    val restScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.35f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "VisualizerRestScale",
    )

    LaunchedEffect(isPlaying, barCount) {
        var lastFrameNanos = 0L
        while (true) {
            val settled = withFrameNanos { frameNanos ->
                val hasAudio = AudioSpectrumAnalyzer.readBands(System.nanoTime(), bands)
                val dt = if (lastFrameNanos == 0L) {
                    1f / 60f
                } else {
                    ((frameNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
                }
                lastFrameNanos = frameNanos

                var maxLevel = 0f
                for (i in 0 until barCount) {
                    val target = if (hasAudio) {
                        val position = barBandPosition[i]
                        val low = position.toInt().coerceAtMost(bands.size - 1)
                        val high = (low + 1).coerceAtMost(bands.size - 1)
                        val fraction = position - low
                        bands[low] + (bands[high] - bands[low]) * fraction
                    } else {
                        0f
                    }
                    val current = levels[i]
                    // Fast attack so beats punch, slower release so bars fall gracefully.
                    val rate = if (target > current) VISUALIZER_ATTACK_RATE else VISUALIZER_RELEASE_RATE
                    val next = current + (target - current) * (1f - kotlin.math.exp(-rate * dt))
                    levels[i] = next
                    if (next > maxLevel) maxLevel = next
                }
                frameTick = frameNanos
                !isPlaying && maxLevel < 0.002f
            }
            if (settled) break
        }
    }

    Canvas(modifier = modifier) {
        // Reading the tick subscribes this draw scope to the frame loop's level updates.
        @Suppress("UNUSED_VARIABLE")
        val redrawTick = frameTick

        val center = Offset(size.width / 2f, size.height / 2f)
        val coverHalfWidth = (size.width * 0.78f) / 2f
        val minHeightPx = 3.dp.toPx() * restScale
        val maxHeightPx = 30.dp.toPx()

        for (i in 0 until barCount) {
            val angle = (i.toFloat() / barCount) * TWO_PI
            val cosA = kotlin.math.cos(angle)
            val sinA = kotlin.math.sin(angle)
            val maxCosSin = maxOf(kotlin.math.abs(cosA), kotlin.math.abs(sinA)).coerceAtLeast(0.001f)
            val baseRadius = (coverHalfWidth / maxCosSin) * 0.94f

            val level = levels[i]
            val dynamicHeight = minHeightPx + (maxHeightPx - minHeightPx) * level

            val startX = center.x + cosA * baseRadius
            val startY = center.y + sinA * baseRadius
            val endX = center.x + cosA * (baseRadius + dynamicHeight)
            val endY = center.y + sinA * (baseRadius + dynamicHeight)

            val barColor = lerp(primaryColor, accentColor, level).copy(
                alpha = (0.35f + 0.65f * level) * restScale.coerceAtLeast(0.5f),
            )

            drawLine(
                color = barColor,
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = 4.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * Renders the live Media3 [PlayerView] video surface bound to [player], detaching the surface
 * cleanly when disposed or switched back to audio artwork mode.
 */
@OptIn(UnstableApi::class)
@Composable
private fun VideoSurfaceViewport(
    player: Player?,
    track: MediaTrack,
) {
    val strings = LocalAppStrings.current

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f),
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 6.dp,
        color = Color.Black,
    ) {
        if (player != null) {
            var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }

            DisposableEffect(player) {
                onDispose {
                    playerViewRef?.player = null
                }
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    PlayerView(context).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        keepScreenOn = true
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        this.player = player
                        playerViewRef = this
                    }
                },
                update = { view ->
                    if (view.player !== player) {
                        view.player = player
                    }
                },
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = strings.videoSurfaceReady(track.title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun PlaybackSeekBar(
    currentPositionMs: Long,
    durationMs: Long,
    onSeekTo: (Long) -> Unit,
) {
    val safeDurationMs = durationMs.coerceAtLeast(1L)
    var isDragging by remember { mutableStateOf(false) }
    var dragPositionMs by remember { mutableFloatStateOf(0f) }

    val sliderValue = if (isDragging) {
        dragPositionMs
    } else {
        currentPositionMs.coerceIn(0L, safeDurationMs).toFloat()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = sliderValue,
            onValueChange = { newVal ->
                isDragging = true
                dragPositionMs = newVal
            },
            onValueChangeFinished = {
                isDragging = false
                onSeekTo(dragPositionMs.toLong())
            },
            valueRange = 0f..safeDurationMs.toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatDurationMs(sliderValue.toLong()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatDurationMs(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TransportControlsRow(
    isPlaying: Boolean,
    isBuffering: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    onToggleShuffle: () -> Unit,
    onSkipPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onCycleRepeat: () -> Unit,
) {
    val strings = LocalAppStrings.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggleShuffle) {
            Icon(
                imageVector = Icons.Default.Shuffle,
                contentDescription = strings.cdToggleShuffle,
                tint = if (shuffleEnabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        IconButton(
            onClick = onSkipPrevious,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.Default.SkipPrevious,
                contentDescription = strings.cdPreviousTrack,
                modifier = Modifier.size(32.dp),
            )
        }

        FilledIconButton(
            onClick = onTogglePlayPause,
            shape = CircleShape,
            modifier = Modifier.size(68.dp),
        ) {
            if (isBuffering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) strings.cdPause else strings.cdPlay,
                    modifier = Modifier.size(36.dp),
                )
            }
        }

        IconButton(
            onClick = onSkipNext,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.Default.SkipNext,
                contentDescription = strings.cdNextTrack,
                modifier = Modifier.size(32.dp),
            )
        }

        IconButton(onClick = onCycleRepeat) {
            val repeatIcon = if (repeatMode == Player.REPEAT_MODE_ONE) {
                Icons.Default.RepeatOne
            } else {
                Icons.Default.Repeat
            }
            val repeatActive = repeatMode != Player.REPEAT_MODE_OFF
            Icon(
                imageVector = repeatIcon,
                contentDescription = strings.cdCycleRepeat,
                tint = if (repeatActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/**
 * Formats milliseconds into `M:SS` or `H:MM:SS`.
 */
fun formatDurationMs(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0L) / 1_000L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
