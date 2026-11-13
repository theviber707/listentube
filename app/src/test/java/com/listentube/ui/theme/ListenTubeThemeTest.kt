// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube.ui.theme

import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTubeThemeTest {

    @Test
    fun `rgbToHsl converts primary and achromatic colors accurately`() {
        val (redH, redS, redL) = rgbToHsl(0xFFFF0000.toInt())
        assertEquals(0f, redH, 0.5f)
        assertEquals(1f, redS, 0.01f)
        assertEquals(0.5f, redL, 0.01f)

        val (blueH, blueS, blueL) = rgbToHsl(0xFF0000FF.toInt())
        assertEquals(240f, blueH, 0.5f)
        assertEquals(1f, blueS, 0.01f)
        assertEquals(0.5f, blueL, 0.01f)

        val (_, grayS, grayL) = rgbToHsl(0xFF808080.toInt())
        assertEquals(0f, grayS, 0.01f)
        assertEquals(0.502f, grayL, 0.01f)
    }

    @Test
    fun `coverArtColorScheme produces contrasting dark and light schemes for chromatic seed`() {
        val vibrantBlue = 0xFF1E88E5.toInt()

        val dark = coverArtColorScheme(seedColor = vibrantBlue, darkTheme = true)
        assertTrue(dark.background.luminance() < 0.05f)
        assertTrue(dark.onBackground.luminance() > 0.7f)
        assertTrue(dark.primary.luminance() > dark.onPrimary.luminance())
        assertTrue(dark.surfaceContainerHigh.luminance() > dark.surfaceContainerLowest.luminance())

        val light = coverArtColorScheme(seedColor = vibrantBlue, darkTheme = false)
        assertTrue(light.background.luminance() > 0.85f)
        assertTrue(light.onBackground.luminance() < 0.05f)
        assertTrue(light.onPrimary.luminance() > light.primary.luminance())
        assertTrue(light.surfaceContainerLowest.luminance() > light.surfaceContainerHigh.luminance())
    }

    @Test
    fun `coverArtColorScheme keeps neutral cover art desaturated`() {
        val neutralGray = 0xFF777777.toInt()
        val dark = coverArtColorScheme(seedColor = neutralGray, darkTheme = true)

        // Neutral seed should keep surface/primary channels nearly balanced (grayscale)
        val primarySpread = maxOf(dark.primary.red, dark.primary.green, dark.primary.blue) -
            minOf(dark.primary.red, dark.primary.green, dark.primary.blue)
        assertTrue(primarySpread < 0.05f)
    }
}
