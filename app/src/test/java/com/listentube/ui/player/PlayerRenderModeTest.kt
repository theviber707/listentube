// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerRenderModeTest {

    @Test
    fun playerRenderMode_hasExpectedEnumValuesAndOrder() {
        val modes = PlayerRenderMode.entries
        assertEquals(3, modes.size)
        assertEquals(PlayerRenderMode.AUDIO_ARTWORK, modes[0])
        assertEquals(PlayerRenderMode.VINYL, modes[1])
        assertEquals(PlayerRenderMode.VIDEO_SURFACE, modes[2])
    }

    @Test
    fun toggleRenderMode_cyclesThroughAllThreeModes() {
        var mode = PlayerRenderMode.AUDIO_ARTWORK

        fun cycle(current: PlayerRenderMode): PlayerRenderMode = when (current) {
            PlayerRenderMode.AUDIO_ARTWORK -> PlayerRenderMode.VINYL
            PlayerRenderMode.VINYL -> PlayerRenderMode.VIDEO_SURFACE
            PlayerRenderMode.VIDEO_SURFACE -> PlayerRenderMode.AUDIO_ARTWORK
        }

        mode = cycle(mode)
        assertEquals(PlayerRenderMode.VINYL, mode)

        mode = cycle(mode)
        assertEquals(PlayerRenderMode.VIDEO_SURFACE, mode)

        mode = cycle(mode)
        assertEquals(PlayerRenderMode.AUDIO_ARTWORK, mode)
    }
}
