// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube.domain.lyrics

/**
 * Parser for LRC synchronized lyrics files and unsynchronized plain-text lyrics.
 */
object LrcParser {

    private val TIME_TAG_REGEX = Regex("""\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?\]""")

    /**
     * Parses an LRC-formatted string (containing timestamp tags like `[01:23.45]`) into
     * an ordered list of [LyricLine]s sorted chronologically.
     */
    fun parseLrc(lrcContent: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()

        for (rawLine in lrcContent.lineSequence()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue

            val matches = TIME_TAG_REGEX.findAll(trimmed).toList()
            if (matches.isNotEmpty()) {
                val text = trimmed.replace(TIME_TAG_REGEX, "").trim()
                if (text.isNotEmpty()) {
                    for (match in matches) {
                        val (minStr, secStr, fracStr) = match.destructured
                        val min = minStr.toLongOrNull() ?: 0L
                        val sec = secStr.toLongOrNull() ?: 0L
                        val ms = when (fracStr.length) {
                            1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
                            2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
                            3 -> fracStr.toLongOrNull() ?: 0L
                            else -> 0L
                        }
                        val timeMs = min * 60_000L + sec * 1_000L + ms
                        lines.add(LyricLine(timeMs = timeMs, text = text))
                    }
                }
            }
        }

        return lines.sortedBy { it.timeMs ?: Long.MAX_VALUE }
    }

    /**
     * Parses unsynchronized multi-line text into a list of [LyricLine]s without timestamps.
     */
    fun parsePlain(plainContent: String): List<LyricLine> {
        return plainContent.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { LyricLine(timeMs = null, text = it) }
            .toList()
    }
}
