// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube.ui.player

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
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
import com.listentube.ui.i18n.LocalAppStrings
import java.util.Locale

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
 * Animated audio spectrum visualizer radiating outward from behind the album art card perimeter.
 * Responds smoothly to [isPlaying], shrinking down when playback is paused.
 */
@Composable
private fun AudioVisualizerBehindCover(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 48,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    accentColor: Color = MaterialTheme.colorScheme.tertiary,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "VisualizerTransition")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "VisualizerPhase",
    )

    val activeMultiplier by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.06f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "VisualizerActiveMultiplier",
    )

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val coverHalfWidth = (size.width * 0.78f) / 2f

        for (i in 0 until barCount) {
            val angle = (i.toFloat() / barCount) * (2f * Math.PI.toFloat())
            val cosA = kotlin.math.cos(angle)
            val sinA = kotlin.math.sin(angle)
            val maxCosSin = maxOf(kotlin.math.abs(cosA), kotlin.math.abs(sinA)).coerceAtLeast(0.001f)
            val baseRadius = (coverHalfWidth / maxCosSin) * 0.94f

            val bass = kotlin.math.sin(phase * 2.2f + i * 0.28f)
            val mid = kotlin.math.sin(phase * 3.8f - i * 0.42f)
            val treble = kotlin.math.cos(phase * 5.4f + i * 0.65f)
            val harmonic = (bass * 0.5f + mid * 0.35f + treble * 0.15f)
            val normalizedHarmonic = ((harmonic + 1f) / 2f).coerceIn(0f, 1f)

            val minHeightPx = 3.dp.toPx()
            val maxHeightPx = 26.dp.toPx()
            val dynamicHeight = (minHeightPx + (maxHeightPx - minHeightPx) * normalizedHarmonic) * activeMultiplier

            val startX = center.x + cosA * baseRadius
            val startY = center.y + sinA * baseRadius
            val endX = center.x + cosA * (baseRadius + dynamicHeight)
            val endY = center.y + sinA * (baseRadius + dynamicHeight)

            val t = ((kotlin.math.sin(angle + phase * 0.5f) + 1f) / 2f).coerceIn(0f, 1f)
            val barColor = lerp(primaryColor, accentColor, t).copy(
                alpha = (0.55f + 0.45f * normalizedHarmonic) * activeMultiplier.coerceAtLeast(0.2f),
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
