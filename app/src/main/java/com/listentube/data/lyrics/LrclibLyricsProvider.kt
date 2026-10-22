// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube.data.lyrics

import com.listentube.core.result.AppResult
import com.listentube.core.result.appRunCatching
import com.listentube.domain.lyrics.LrcParser
import com.listentube.domain.lyrics.TrackLyrics
import com.listentube.domain.media.MediaTrack
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LrclibResponse(
    @SerialName("id")
    val id: Long? = null,
    @SerialName("trackName")
    val trackName: String? = null,
    @SerialName("artistName")
    val artistName: String? = null,
    @SerialName("albumName")
    val albumName: String? = null,
    @SerialName("duration")
    val duration: Double? = null,
    @SerialName("instrumental")
    val instrumental: Boolean = false,
    @SerialName("plainLyrics")
    val plainLyrics: String? = null,
    @SerialName("syncedLyrics")
    val syncedLyrics: String? = null,
)

/**
 * Client for the community-driven LRCLIB API (`https://lrclib.net`), providing
 * synchronized (LRC) and plain text lyrics.
 */
class LrclibLyricsProvider(
    private val httpClient: HttpClient,
    private val json: Json,
    private val baseUrl: String = "https://lrclib.net",
) {
    /**
     * Attempts to fetch lyrics for [track] from LRCLIB.
     * First queries `/api/get` with exact track details; falls back to `/api/search` if not found.
     */
    suspend fun fetchLyrics(track: MediaTrack): AppResult<TrackLyrics?> {
        return appRunCatching {
            // 1. Try exact match via /api/get
            val getResponse = httpClient.get("$baseUrl/api/get") {
                header(HttpHeaders.UserAgent, USER_AGENT)
                parameter("track_name", cleanTitle(track.title))
                parameter("artist_name", track.artistName)
                if (!track.albumName.isNullOrBlank()) {
                    parameter("album_name", track.albumName)
                }
                if (track.durationMs > 0L) {
                    parameter("duration", (track.durationMs / 1000L).toInt())
                }
            }

            if (getResponse.status.isSuccess()) {
                val rawBody = getResponse.bodyAsText()
                if (rawBody.isNotBlank()) {
                    val parsed = runCatching { json.decodeFromString<LrclibResponse>(rawBody) }.getOrNull()
                    val converted = parsed?.let { toTrackLyrics(track.id, it) }
                    if (converted != null) return@appRunCatching converted
                }
            } else if (getResponse.status != HttpStatusCode.NotFound) {
                // Return null on other non-fatal statuses
            }

            // 2. Fallback to /api/search
            val searchResponse = httpClient.get("$baseUrl/api/search") {
                header(HttpHeaders.UserAgent, USER_AGENT)
                parameter("track_name", cleanTitle(track.title))
                parameter("artist_name", track.artistName)
            }

            if (searchResponse.status.isSuccess()) {
                val searchBody = searchResponse.bodyAsText()
                if (searchBody.isNotBlank()) {
                    val items = runCatching { json.decodeFromString<List<LrclibResponse>>(searchBody) }.getOrNull()
                    val bestMatch = items?.firstOrNull { !it.syncedLyrics.isNullOrBlank() }
                        ?: items?.firstOrNull { !it.plainLyrics.isNullOrBlank() }
                    if (bestMatch != null) {
                        return@appRunCatching toTrackLyrics(track.id, bestMatch)
                    }
                }
            }

            null
        }
    }

    private fun toTrackLyrics(trackId: String, response: LrclibResponse): TrackLyrics? {
        val synced = response.syncedLyrics?.takeIf { it.isNotBlank() }
        if (synced != null) {
            val lines = LrcParser.parseLrc(synced)
            if (lines.isNotEmpty()) {
                return TrackLyrics(
                    trackId = trackId,
                    lines = lines,
                    source = "LRCLIB",
                    isSynced = true,
                )
            }
        }

        val plain = response.plainLyrics?.takeIf { it.isNotBlank() }
        if (plain != null) {
            val lines = LrcParser.parsePlain(plain)
            if (lines.isNotEmpty()) {
                return TrackLyrics(
                    trackId = trackId,
                    lines = lines,
                    source = "LRCLIB",
                    isSynced = false,
                )
            }
        }

        return null
    }

    /**
     * Cleans common video title decorations such as `(Official Video)` or `[Audio]`
     * to improve LRCLIB search matching.
     */
    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("""(?i)\(official\s*(music\s*)?video\)"""), "")
            .replace(Regex("""(?i)\[official\s*(music\s*)?video\]"""), "")
            .replace(Regex("""(?i)\(audio\)"""), "")
            .replace(Regex("""(?i)\[audio\]"""), "")
            .replace(Regex("""(?i)\(lyric\s*video\)"""), "")
            .replace(Regex("""(?i)\[lyric\s*video\]"""), "")
            .replace(Regex("""(?i)\(official\s*audio\)"""), "")
            .trim()
    }

    companion object {
        private const val USER_AGENT = "ListenTube/1.0 (https://github.com/theviber707/listentube)"
    }
}
