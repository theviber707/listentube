// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.listentube.domain.media.MediaTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dynamic Material You color scheme extracted from track cover art.
 */
data class VinylPalette(
    val primary: Color,
    val onPrimary: Color,
    val accent: Color,
)

/**
 * Dynamically derives a Material You [VinylPalette] from the track artwork image.
 *
 * If artwork is loading or unavailable, defaults to the active [MaterialTheme.colorScheme] tokens.
 */
@Composable
fun rememberVinylPalette(
    artworkUrl: String?,
    isDarkTheme: Boolean = isSystemInDarkTheme(),
    fallbackPrimary: Color = MaterialTheme.colorScheme.primary,
    fallbackOnPrimary: Color = MaterialTheme.colorScheme.onPrimary,
    fallbackAccent: Color = MaterialTheme.colorScheme.tertiary,
): VinylPalette {
    val context = LocalContext.current
    var palette by remember(artworkUrl, isDarkTheme) {
        mutableStateOf(
            VinylPalette(
                primary = fallbackPrimary,
                onPrimary = fallbackOnPrimary,
                accent = fallbackAccent,
            ),
        )
    }

    LaunchedEffect(artworkUrl, isDarkTheme) {
        if (artworkUrl.isNullOrBlank()) {
            palette = VinylPalette(fallbackPrimary, fallbackOnPrimary, fallbackAccent)
            return@LaunchedEffect
        }

        withContext(Dispatchers.IO) {
            try {
                val imageLoader = SingletonImageLoader.get(context)
                val request = ImageRequest.Builder(context)
                    .data(artworkUrl)
                    .size(128, 128)
                    .allowHardware(false)
                    .build()
                val result = imageLoader.execute(request)
                if (result is SuccessResult) {
                    val bitmap = try {
                        result.image.toBitmap()
                    } catch (_: Throwable) {
                        (result.image as? coil3.BitmapImage)?.bitmap
                    }

                    if (bitmap != null) {
                        val p = Palette.from(bitmap).generate()
                        val seedRgb = p.dominantSwatch?.rgb
                            ?: p.vibrantSwatch?.rgb
                            ?: p.mutedSwatch?.rgb
                            ?: p.swatches.maxByOrNull { it.population }?.rgb

                        if (seedRgb != null) {
                            val hsl = FloatArray(3)
                            ColorUtils.colorToHSL(seedRgb, hsl)

                            val (primaryColor, onPrimaryColor, accentColor) = if (isDarkTheme) {
                                // Material 3 Dark theme tonal roles: Primary (Tone ~75), OnPrimary (Tone ~12, near black)
                                val primHsl = floatArrayOf(hsl[0], hsl[1].coerceIn(0.45f, 0.90f), 0.72f)
                                val onPrimHsl = floatArrayOf(hsl[0], hsl[1].coerceAtMost(0.35f), 0.12f)
                                val accHsl = floatArrayOf((hsl[0] + 45f) % 360f, hsl[1].coerceIn(0.40f, 0.85f), 0.68f)
                                Triple(
                                    Color(ColorUtils.HSLToColor(primHsl)),
                                    Color(ColorUtils.HSLToColor(onPrimHsl)),
                                    Color(ColorUtils.HSLToColor(accHsl)),
                                )
                            } else {
                                // Material 3 Light theme tonal roles: Primary (Tone ~38), OnPrimary (Tone ~97, near white)
                                val primHsl = floatArrayOf(hsl[0], hsl[1].coerceIn(0.50f, 0.95f), 0.38f)
                                val onPrimHsl = floatArrayOf(hsl[0], hsl[1].coerceAtMost(0.20f), 0.97f)
                                val accHsl = floatArrayOf((hsl[0] + 45f) % 360f, hsl[1].coerceIn(0.45f, 0.85f), 0.45f)
                                Triple(
                                    Color(ColorUtils.HSLToColor(primHsl)),
                                    Color(ColorUtils.HSLToColor(onPrimHsl)),
                                    Color(ColorUtils.HSLToColor(accHsl)),
                                )
                            }

                            withContext(Dispatchers.Main) {
                                palette = VinylPalette(primaryColor, onPrimaryColor, accentColor)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Keep fallback on error
            }
        }
    }

    return palette
}

/**
 * Viewport displaying the spinning vinyl art.
 *
 * Uses the primary color token extracted from the track cover for the grooved vinyl disc,
 * and the contrasting onPrimary token for the center label ring, housing a circular crop
 * of the album cover thumbnail and a central spindle hole.
 */
@Composable
fun VinylArtworkViewport(
    track: MediaTrack,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val vinylPalette = rememberVinylPalette(artworkUrl = track.artworkUrl)

    var rotationAngle by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isPlaying) {
        var lastFrameNanos = 0L
        val degreesPerSecond = 360f / 3.5f
        while (true) {
            withFrameNanos { frameNanos ->
                if (lastFrameNanos != 0L && isPlaying) {
                    val dt = (frameNanos - lastFrameNanos) / 1_000_000_000f
                    rotationAngle = (rotationAngle + degreesPerSecond * dt) % 360f
                }
                lastFrameNanos = frameNanos
            }
            if (!isPlaying) break
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        // Radiating circular spectrum visualizer
        AudioVisualizerBehindCover(
            isPlaying = isPlaying,
            modifier = Modifier.fillMaxSize(),
            primaryColor = vinylPalette.primary,
            accentColor = vinylPalette.accent,
            isCircular = true,
        )

        // Physical vinyl disc container with drop shadow & smooth rotation
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.78f)
                .aspectRatio(1f)
                .graphicsLayer { rotationZ = rotationAngle },
            shape = CircleShape,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
            color = Color.Transparent,
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                // Grooved vinyl disc with specular light sheen
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val discRadius = size.width / 2f

                    // 1. Solid Primary Vinyl Disc
                    drawCircle(
                        color = vinylPalette.primary,
                        radius = discRadius,
                        center = center,
                    )

                    // 2. Vinyl Run-in Outer Rim
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.25f),
                        radius = discRadius - 1.dp.toPx(),
                        center = center,
                        style = Stroke(width = 2.dp.toPx()),
                    )

                    // 3. Subtle Concentric Sound Grooves
                    val labelRadius = discRadius * 0.40f
                    val grooveArea = (discRadius * 0.94f) - (labelRadius + 8.dp.toPx())
                    val grooveSteps = 20
                    for (step in 0 until grooveSteps) {
                        val r = labelRadius + 8.dp.toPx() + (grooveArea * (step.toFloat() / grooveSteps))
                        drawCircle(
                            color = Color.Black.copy(alpha = 0.12f),
                            radius = r,
                            center = center,
                            style = Stroke(width = 1.2.dp.toPx()),
                        )
                        drawCircle(
                            color = Color.White.copy(alpha = 0.08f),
                            radius = r + 0.6.dp.toPx(),
                            center = center,
                            style = Stroke(width = 0.8.dp.toPx()),
                        )
                    }

                    // 4. Specular Sheen (Dual-Wedge Light Reflection)
                    val sheenBrush = Brush.sweepGradient(
                        0.0f to Color.White.copy(alpha = 0.0f),
                        0.10f to Color.White.copy(alpha = 0.15f),
                        0.25f to Color.White.copy(alpha = 0.0f),
                        0.50f to Color.White.copy(alpha = 0.0f),
                        0.60f to Color.White.copy(alpha = 0.15f),
                        0.75f to Color.White.copy(alpha = 0.0f),
                        1.0f to Color.White.copy(alpha = 0.0f),
                        center = center,
                    )
                    drawCircle(
                        brush = sheenBrush,
                        radius = discRadius * 0.96f,
                        center = center,
                    )
                }

                // 5. Center Piece (Label) using contrasting onPrimary token
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.40f)
                        .aspectRatio(1f)
                        .clip(CircleShape)
                        .background(vinylPalette.onPrimary)
                        .border(
                            width = 2.dp,
                            color = vinylPalette.primary.copy(alpha = 0.5f),
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    // Circular album artwork thumbnail inside the center piece
                    if (!track.artworkUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = track.artworkUrl,
                            contentDescription = track.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth(0.68f)
                                .aspectRatio(1f)
                                .clip(CircleShape),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = vinylPalette.primary,
                        )
                    }

                    // Turntable center spindle hole cutout
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(vinylPalette.onPrimary)
                            .border(
                                width = 1.5.dp,
                                color = Color.Black.copy(alpha = 0.4f),
                                shape = CircleShape,
                            ),
                    )
                }
            }
        }
    }
}
