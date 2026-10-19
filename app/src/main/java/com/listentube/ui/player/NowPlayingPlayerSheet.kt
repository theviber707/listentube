// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube.ui.player

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.listentube.domain.media.MediaTrack
import com.listentube.domain.scrobble.ScrobbleManager
import com.listentube.domain.scrobble.ScrobbleSessionState
import com.listentube.ui.i18n.LocalAppStrings
import java.util.Locale

/**
 * Expandable Now Playing bottom sheet supporting both high-resolution audio artwork
 * ([PlayerRenderMode.AUDIO_ARTWORK]) and live Media3 video surface rendering
 * ([PlayerRenderMode.VIDEO_SURFACE]), along with real-time ListenBrainz scrobble threshold telemetry.
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
    onSelectPlaybackSpeed: (Float) -> Unit,
    onSelectRenderMode: (PlayerRenderMode) -> Unit,
    onSelectQueueIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = uiState.currentTrack ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
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
            onSelectPlaybackSpeed = onSelectPlaybackSpeed,
            onSelectRenderMode = onSelectRenderMode,
            onSelectQueueIndex = onSelectQueueIndex,
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
    onSelectPlaybackSpeed: (Float) -> Unit,
    onSelectRenderMode: (PlayerRenderMode) -> Unit,
    onSelectQueueIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
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

        // Media Viewport: Switches smoothly between Album Artwork and ExoPlayer Video Surface
        AnimatedContent(
            targetState = uiState.renderMode,
            label = "PlayerViewportMode",
        ) { renderMode ->
            when (renderMode) {
                PlayerRenderMode.AUDIO_ARTWORK -> {
                    AudioArtworkViewport(
                        track = track,
                        isPlaying = uiState.isPlaying,
                    )
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

        // Track Title, Artist, Album & Live ListenBrainz Scrobble Pill
        Column(
            modifier = Modifier.fillMaxWidth(),
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

            Spacer(modifier = Modifier.height(10.dp))

            ListenBrainzLiveStatusPill(
                sessionState = uiState.scrobbleSession,
                queuedPlayingNowPresent = uiState.queuedPlayingNow != null,
                scrobblingEnabled = uiState.scrobblingEnabled,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Seekbar with Scrobble Threshold Marker
        PlaybackSeekBar(
            currentPositionMs = uiState.currentPositionMs,
            durationMs = uiState.durationMs.coerceAtLeast(track.durationMs),
            scrobbleThresholdMs = uiState.scrobbleSession.thresholdMs.takeIf { it > 0L }
                ?: ScrobbleManager.calculateThresholdMs(uiState.durationMs.coerceAtLeast(track.durationMs)),
            onSeekTo = onSeekTo,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Transport Controls Row
        TransportControlsRow(
            isPlaying = uiState.isPlaying,
            isBuffering = uiState.isBuffering,
            shuffleEnabled = uiState.shuffleModeEnabled,
            repeatMode = uiState.repeatMode,
            playbackSpeed = uiState.playbackSpeed,
            onToggleShuffle = onToggleShuffle,
            onSkipPrevious = onSkipPrevious,
            onTogglePlayPause = onTogglePlayPause,
            onSkipNext = onSkipNext,
            onCycleRepeat = onCycleRepeat,
            onSelectPlaybackSpeed = onSelectPlaybackSpeed,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Up Next Queue Preview
        if (uiState.playbackQueue.size > 1) {
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = strings.upNextHeader(uiState.playbackQueue.size),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Start),
            )
            Spacer(modifier = Modifier.height(6.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
            ) {
                itemsIndexed(
                    items = uiState.playbackQueue,
                    key = { index, item -> "${item.id}_$index" },
                ) { index, queueTrack ->
                    val isCurrent = index == uiState.currentQueueIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isCurrent) {
                                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                                } else {
                                    Color.Transparent
                                },
                            )
                            .clickable { onSelectQueueIndex(index) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${index + 1}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(28.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = queueTrack.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = queueTrack.artistName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = formatDurationMs(queueTrack.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
 * Renders the album cover artwork with subtle play-state scale animation and a fallback icon.
 */
@Composable
private fun AudioArtworkViewport(
    track: MediaTrack,
    isPlaying: Boolean,
) {
    val artworkScale by animateFloatAsState(
        targetValue = if (isPlaying) 1.0f else 0.92f,
        label = "ArtworkScale",
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth(0.82f)
            .aspectRatio(1f)
            .scale(artworkScale),
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

/**
 * Displays live ListenBrainz status for the currently playing track (`playing_now` state
 * and exact duration threshold progress toward completed scrobble).
 */
@Composable
private fun ListenBrainzLiveStatusPill(
    sessionState: ScrobbleSessionState,
    queuedPlayingNowPresent: Boolean,
    scrobblingEnabled: Boolean,
) {
    val strings = LocalAppStrings.current

    if (!scrobblingEnabled) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                text = strings.lbStatusScrobblingPaused,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        return
    }

    val containerColor = when {
        sessionState.scrobbleCompleted -> MaterialTheme.colorScheme.primaryContainer
        sessionState.playingNowDispatched -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = if (sessionState.scrobbleCompleted) {
                            Icons.Default.CheckCircle
                        } else {
                            Icons.Default.CloudQueue
                        },
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    val statusLabel = when {
                        sessionState.scrobbleCompleted -> strings.lbStatusScrobbled
                        sessionState.playingNowDispatched -> strings.lbStatusPlayingNowActive
                        queuedPlayingNowPresent -> strings.lbStatusPlayingNowQueuedOffline
                        !sessionState.isEligibleForScrobble -> strings.lbStatusTrackTooShort
                        else -> strings.lbStatusTrackingDuration
                    }
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                if (sessionState.isEligibleForScrobble && sessionState.thresholdMs > 0L) {
                    Text(
                        text = "${formatDurationMs(sessionState.accumulatedPlayedMs)} / ${formatDurationMs(sessionState.thresholdMs)}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            if (sessionState.isEligibleForScrobble && sessionState.thresholdMs > 0L && !sessionState.scrobbleCompleted) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { sessionState.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}

@Composable
private fun PlaybackSeekBar(
    currentPositionMs: Long,
    durationMs: Long,
    scrobbleThresholdMs: Long,
    onSeekTo: (Long) -> Unit,
) {
    val strings = LocalAppStrings.current
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
            if (scrobbleThresholdMs in 1L..safeDurationMs) {
                Text(
                    text = strings.scrobbleAtTime(formatDurationMs(scrobbleThresholdMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
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
    playbackSpeed: Float,
    onToggleShuffle: () -> Unit,
    onSkipPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onCycleRepeat: () -> Unit,
    onSelectPlaybackSpeed: (Float) -> Unit,
) {
    val strings = LocalAppStrings.current
    val availableSpeeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
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

        Spacer(modifier = Modifier.height(6.dp))

        AssistChip(
            onClick = {
                val idx = availableSpeeds.indexOfFirst { it == playbackSpeed }
                val nextSpeed = availableSpeeds[(idx + 1) % availableSpeeds.size]
                onSelectPlaybackSpeed(nextSpeed)
            },
            label = {
                Text(strings.playbackSpeedFormat(playbackSpeed))
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
        )
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
