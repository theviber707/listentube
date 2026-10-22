// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube.data.lyrics

import com.listentube.core.result.AppResult
import com.listentube.data.innertube.YouTubeMusicProvider
import com.listentube.domain.lyrics.TrackLyrics
import com.listentube.domain.media.MediaSourceType
import com.listentube.domain.media.MediaTrack
import java.util.concurrent.ConcurrentHashMap

/**
 * Aggregates lyrics lookup across LRCLIB (for synchronized karaoke-style LRC lyrics)
 * and YouTube Music Innertube (for official catalog lyrics fallback).
 */
class LyricsRepository(
    private val lrclibProvider: LrclibLyricsProvider,
    private val youTubeMusicProvider: YouTubeMusicProvider,
) {
    private val memoryCache = ConcurrentHashMap<String, TrackLyrics>()

    suspend fun getLyrics(track: MediaTrack): AppResult<TrackLyrics?> {
        memoryCache[track.id]?.let { return AppResult.Success(it) }

        // 1. Query LRCLIB (supports both synchronized and plain lyrics)
        val lrclibResult = lrclibProvider.fetchLyrics(track)
        if (lrclibResult is AppResult.Success && lrclibResult.data != null) {
            val lyrics = lrclibResult.data
            memoryCache[track.id] = lyrics
            return AppResult.Success(lyrics)
        }

        // 2. If LRCLIB had no lyrics and this is a YouTube Music track, query Innertube
        if (track.sourceType == MediaSourceType.YOUTUBE_MUSIC) {
            val ytmResult = youTubeMusicProvider.getLyrics(track.id)
            if (ytmResult is AppResult.Success && ytmResult.data != null) {
                val lyrics = ytmResult.data
                memoryCache[track.id] = lyrics
                return AppResult.Success(lyrics)
            }
        }

        return AppResult.Success(null)
    }

    fun clearCache() {
        memoryCache.clear()
    }
}
