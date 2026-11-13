// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

private const val THEME_ANIMATION_DURATION_MS = 500
private const val ARTWORK_SAMPLE_SIZE_PX = 128

/**
 * Application-wide Material 3 theme driven by the active track's cover art.
 *
 * When [artworkUrl] is unavailable or fails to yield a palette, falls back to the system theme
 * (Android 12+ dynamic wallpaper colors or default Material 3 light/dark colors).
 */
@Composable
fun ListenTubeTheme(
    artworkUrl: String? = null,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val seedColor = rememberArtworkSeedColor(artworkUrl = artworkUrl)
    val targetColorScheme = remember(context, seedColor, darkTheme) {
        if (seedColor != null) {
            coverArtColorScheme(seedColor = seedColor, darkTheme = darkTheme)
        } else {
            systemColorScheme(context = context, darkTheme = darkTheme)
        }
    }

    MaterialTheme(
        colorScheme = animateColorScheme(targetColorScheme),
        content = content,
    )
}

/**
 * Returns the system fallback [ColorScheme] for the current [darkTheme] setting.
 */
fun systemColorScheme(
    context: Context,
    darkTheme: Boolean,
): ColorScheme = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    darkTheme -> darkColorScheme()
    else -> lightColorScheme()
}

/**
 * Remembers and asynchronously extracts a representative ARGB seed color from [artworkUrl].
 *
 * Retains the previous seed color while a new non-blank [artworkUrl] is loading to prevent
 * flashing back to the system theme between track changes, and resets to `null` when
 * [artworkUrl] is null/blank or extraction fails.
 */
@Composable
fun rememberArtworkSeedColor(artworkUrl: String?): Int? {
    val context = LocalContext.current
    var seedColor by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(artworkUrl) {
        if (artworkUrl.isNullOrBlank()) {
            seedColor = null
            return@LaunchedEffect
        }
        seedColor = extractArtworkSeedColor(context = context, artworkUrl = artworkUrl)
    }

    return seedColor
}

/**
 * Loads [artworkUrl] via Coil and extracts a dominant/vibrant seed ARGB color using [Palette].
 */
suspend fun extractArtworkSeedColor(
    context: Context,
    artworkUrl: String,
): Int? = withContext(Dispatchers.IO) {
    try {
        val imageLoader = SingletonImageLoader.get(context)
        val request = ImageRequest.Builder(context)
            .data(artworkUrl)
            .size(ARTWORK_SAMPLE_SIZE_PX, ARTWORK_SAMPLE_SIZE_PX)
            .allowHardware(false)
            .build()
        val result = imageLoader.execute(request)
        if (result !is SuccessResult) return@withContext null

        val bitmap = try {
            result.image.toBitmap()
        } catch (_: Throwable) {
            (result.image as? coil3.BitmapImage)?.bitmap
        } ?: return@withContext null

        val palette = Palette.from(bitmap).generate()
        palette.dominantSwatch?.rgb
            ?: palette.vibrantSwatch?.rgb
            ?: palette.mutedSwatch?.rgb
            ?: palette.swatches.maxByOrNull { it.population }?.rgb
    } catch (_: Exception) {
        null
    }
}

/**
 * Generates a full Material 3 [ColorScheme] from an ARGB [seedColor] for light or dark mode.
 */
internal fun coverArtColorScheme(
    seedColor: Int,
    darkTheme: Boolean,
): ColorScheme {
    val (hue, rawSat, _) = rgbToHsl(seedColor)
    val isNeutral = rawSat < 0.08f

    val primSat = if (isNeutral) rawSat.coerceAtMost(0.06f) else rawSat.coerceIn(0.42f, 0.88f)
    val secSat = if (isNeutral) rawSat.coerceAtMost(0.05f) else (rawSat * 0.45f).coerceIn(0.14f, 0.40f)
    val tertHue = (hue + 45f) % 360f
    val tertSat = if (isNeutral) rawSat.coerceAtMost(0.06f) else (rawSat * 0.78f).coerceIn(0.32f, 0.78f)
    val neutralSat = if (isNeutral) 0f else (rawSat * 0.14f).coerceIn(0.04f, 0.14f)
    val neutralVarSat = if (isNeutral) 0f else (rawSat * 0.24f).coerceIn(0.06f, 0.24f)

    return if (darkTheme) {
        val primary = Color.hsl(hue, primSat, 0.76f)
        darkColorScheme(
            primary = primary,
            onPrimary = Color.hsl(hue, primSat * 0.60f, 0.15f),
            primaryContainer = Color.hsl(hue, primSat * 0.85f, 0.28f),
            onPrimaryContainer = Color.hsl(hue, primSat * 0.90f, 0.90f),
            inversePrimary = Color.hsl(hue, primSat, 0.40f),
            secondary = Color.hsl(hue, secSat, 0.78f),
            onSecondary = Color.hsl(hue, secSat, 0.18f),
            secondaryContainer = Color.hsl(hue, secSat, 0.28f),
            onSecondaryContainer = Color.hsl(hue, secSat, 0.90f),
            tertiary = Color.hsl(tertHue, tertSat, 0.76f),
            onTertiary = Color.hsl(tertHue, tertSat, 0.18f),
            tertiaryContainer = Color.hsl(tertHue, tertSat * 0.85f, 0.28f),
            onTertiaryContainer = Color.hsl(tertHue, tertSat * 0.90f, 0.90f),
            background = Color.hsl(hue, neutralSat, 0.07f),
            onBackground = Color.hsl(hue, neutralSat, 0.91f),
            surface = Color.hsl(hue, neutralSat, 0.07f),
            onSurface = Color.hsl(hue, neutralSat, 0.91f),
            surfaceVariant = Color.hsl(hue, neutralVarSat, 0.22f),
            onSurfaceVariant = Color.hsl(hue, neutralVarSat, 0.78f),
            surfaceTint = primary,
            inverseSurface = Color.hsl(hue, neutralSat, 0.90f),
            inverseOnSurface = Color.hsl(hue, neutralSat, 0.18f),
            outline = Color.hsl(hue, neutralVarSat, 0.55f),
            outlineVariant = Color.hsl(hue, neutralVarSat, 0.28f),
            surfaceContainerLowest = Color.hsl(hue, neutralSat, 0.04f),
            surfaceContainerLow = Color.hsl(hue, neutralSat, 0.10f),
            surfaceContainer = Color.hsl(hue, neutralSat, 0.12f),
            surfaceContainerHigh = Color.hsl(hue, neutralSat, 0.16f),
            surfaceContainerHighest = Color.hsl(hue, neutralSat, 0.21f),
        )
    } else {
        val primary = Color.hsl(hue, primSat, 0.38f)
        lightColorScheme(
            primary = primary,
            onPrimary = Color.hsl(hue, primSat * 0.30f, 0.98f),
            primaryContainer = Color.hsl(hue, primSat * 0.85f, 0.88f),
            onPrimaryContainer = Color.hsl(hue, primSat, 0.14f),
            inversePrimary = Color.hsl(hue, primSat, 0.78f),
            secondary = Color.hsl(hue, secSat, 0.40f),
            onSecondary = Color.hsl(hue, secSat * 0.30f, 0.98f),
            secondaryContainer = Color.hsl(hue, secSat, 0.89f),
            onSecondaryContainer = Color.hsl(hue, secSat, 0.14f),
            tertiary = Color.hsl(tertHue, tertSat, 0.42f),
            onTertiary = Color.hsl(tertHue, tertSat * 0.30f, 0.98f),
            tertiaryContainer = Color.hsl(tertHue, tertSat * 0.85f, 0.88f),
            onTertiaryContainer = Color.hsl(tertHue, tertSat, 0.14f),
            background = Color.hsl(hue, neutralSat, 0.97f),
            onBackground = Color.hsl(hue, neutralSat, 0.11f),
            surface = Color.hsl(hue, neutralSat, 0.97f),
            onSurface = Color.hsl(hue, neutralSat, 0.11f),
            surfaceVariant = Color.hsl(hue, neutralVarSat, 0.88f),
            onSurfaceVariant = Color.hsl(hue, neutralVarSat, 0.30f),
            surfaceTint = primary,
            inverseSurface = Color.hsl(hue, neutralSat, 0.20f),
            inverseOnSurface = Color.hsl(hue, neutralSat, 0.94f),
            outline = Color.hsl(hue, neutralVarSat, 0.48f),
            outlineVariant = Color.hsl(hue, neutralVarSat, 0.78f),
            surfaceContainerLowest = Color.hsl(hue, neutralSat, 0.99f),
            surfaceContainerLow = Color.hsl(hue, neutralSat, 0.95f),
            surfaceContainer = Color.hsl(hue, neutralSat, 0.93f),
            surfaceContainerHigh = Color.hsl(hue, neutralSat, 0.91f),
            surfaceContainerHighest = Color.hsl(hue, neutralSat, 0.89f),
        )
    }
}

/**
 * Pure Kotlin ARGB-to-HSL conversion returning `(hue [0..360), saturation [0..1], lightness [0..1])`.
 */
internal fun rgbToHsl(argb: Int): Triple<Float, Float, Float> {
    val r = ((argb ushr 16) and 0xFF) / 255f
    val g = ((argb ushr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f

    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
    val l = (max + min) / 2f

    if (d == 0f) {
        return Triple(0f, 0f, l)
    }

    val s = d / (1f - abs(2f * l - 1f))
    val rawHue = when (max) {
        r -> ((g - b) / d) + (if (g < b) 6f else 0f)
        g -> ((b - r) / d) + 2f
        else -> ((r - g) / d) + 4f
    }
    val h = ((rawHue * 60f) % 360f + 360f) % 360f
    return Triple(h, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
}

@Composable
private fun animateColorScheme(target: ColorScheme): ColorScheme {
    @Composable
    fun anim(color: Color): Color =
        animateColorAsState(
            targetValue = color,
            animationSpec = tween(durationMillis = THEME_ANIMATION_DURATION_MS),
            label = "themeColor",
        ).value

    return target.copy(
        primary = anim(target.primary),
        onPrimary = anim(target.onPrimary),
        primaryContainer = anim(target.primaryContainer),
        onPrimaryContainer = anim(target.onPrimaryContainer),
        inversePrimary = anim(target.inversePrimary),
        secondary = anim(target.secondary),
        onSecondary = anim(target.onSecondary),
        secondaryContainer = anim(target.secondaryContainer),
        onSecondaryContainer = anim(target.onSecondaryContainer),
        tertiary = anim(target.tertiary),
        onTertiary = anim(target.onTertiary),
        tertiaryContainer = anim(target.tertiaryContainer),
        onTertiaryContainer = anim(target.onTertiaryContainer),
        background = anim(target.background),
        onBackground = anim(target.onBackground),
        surface = anim(target.surface),
        onSurface = anim(target.onSurface),
        surfaceVariant = anim(target.surfaceVariant),
        onSurfaceVariant = anim(target.onSurfaceVariant),
        surfaceTint = anim(target.surfaceTint),
        inverseSurface = anim(target.inverseSurface),
        inverseOnSurface = anim(target.inverseOnSurface),
        outline = anim(target.outline),
        outlineVariant = anim(target.outlineVariant),
        surfaceContainerLowest = anim(target.surfaceContainerLowest),
        surfaceContainerLow = anim(target.surfaceContainerLow),
        surfaceContainer = anim(target.surfaceContainer),
        surfaceContainerHigh = anim(target.surfaceContainerHigh),
        surfaceContainerHighest = anim(target.surfaceContainerHighest),
    )
}
