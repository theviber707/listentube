// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube.domain.lyrics

/**
 * A single line of lyrics, optionally tagged with a playback timestamp in milliseconds.
 *
 * @param timeMs The timestamp at which this line begins in milliseconds, or `null` if unsynchronized.
 * @param text The lyrical text of this line.
 */
data class LyricLine(
    val timeMs: Long? = null,
    val text: String,
)

/**
 * Parsed lyrics associated with a [com.listentube.domain.media.MediaTrack].
 *
 * @param trackId The canonical track identifier.
 * @param lines Ordered list of [LyricLine]s.
 * @param source Origin or attribution label (e.g. "LRCLIB", "Musixmatch", "YouTube Music").
 * @param isSynced `true` if at least one line has a non-null playback timestamp.
 */
data class TrackLyrics(
    val trackId: String,
    val lines: List<LyricLine>,
    val source: String? = null,
    val isSynced: Boolean = lines.any { it.timeMs != null },
)
