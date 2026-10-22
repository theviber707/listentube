// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube.domain.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun parseLrc_handlesStandardTimestamps() {
        val lrc = """
            [00:12.34]First line
            [01:05.678]Second line with 3-digit ms
            [02:00]Third line without ms
        """.trimIndent()

        val lines = LrcParser.parseLrc(lrc)
        assertEquals(3, lines.size)

        assertEquals(12_340L, lines[0].timeMs)
        assertEquals("First line", lines[0].text)

        assertEquals(65_678L, lines[1].timeMs)
        assertEquals("Second line with 3-digit ms", lines[1].text)

        assertEquals(120_000L, lines[2].timeMs)
        assertEquals("Third line without ms", lines[2].text)
    }

    @Test
    fun parseLrc_handlesMultiTimestampsAndSorts() {
        val lrc = """
            [01:00.00]Chorus line 2
            [00:30.00][01:30.00]Repeated chorus line 1
        """.trimIndent()

        val lines = LrcParser.parseLrc(lrc)
        assertEquals(3, lines.size)

        assertEquals(30_000L, lines[0].timeMs)
        assertEquals("Repeated chorus line 1", lines[0].text)

        assertEquals(60_000L, lines[1].timeMs)
        assertEquals("Chorus line 2", lines[1].text)

        assertEquals(90_000L, lines[2].timeMs)
        assertEquals("Repeated chorus line 1", lines[2].text)
    }

    @Test
    fun parsePlain_splitsNonEmptyLines() {
        val plain = """
            First verse

            Second verse line 1
            Second verse line 2
        """.trimIndent()

        val lines = LrcParser.parsePlain(plain)
        assertEquals(3, lines.size)
        assertEquals("First verse", lines[0].text)
        assertEquals("Second verse line 1", lines[1].text)
        assertEquals("Second verse line 2", lines[2].text)
        lines.forEach { line ->
            assertEquals(null, line.timeMs)
        }
    }
}
