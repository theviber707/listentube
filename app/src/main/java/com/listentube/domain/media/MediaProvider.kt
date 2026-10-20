// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube.domain.media

import com.listentube.core.result.AppResult
import com.listentube.data.listenbrainz.model.ScrobbleTrack

/**
 * Identifies the backing origin of a [MediaTrack] or [ResolvedMediaStream].
 */
enum class MediaSourceType {
    YOUTUBE_MUSIC,
    LOCAL_STORAGE,
}

/**
 * Unified domain representation of a playable audio track across both YouTube Music
 * and the device's local `MediaStore`.
 */
data class MediaTrack(
    val id: String,
    val title: String,
    val artistName: String,
    val durationMs: Long,
    val albumName: String? = null,
    val artworkUrl: String? = null,
    val trackNumber: Int? = null,
    val releaseYear: Int? = null,
    val sourceType: MediaSourceType,
    val contentUri: String? = null,
    val mimeType: String? = null,
    val bitrateBps: Int? = null,
    val fileSizeBytes: Long? = null,
    val folderPath: String? = null,
    val folderName: String? = null,
) {
    /**
     * Converts this [MediaTrack] into a [ScrobbleTrack] for ListenBrainz reporting via `ScrobbleManager`.
     */
    fun toScrobbleTrack(appVersion: String? = null): ScrobbleTrack = ScrobbleTrack(
        videoId = id,
        artistName = artistName,
        trackName = title,
        durationMs = durationMs,
        releaseName = albumName,
        trackNumber = trackNumber,
        appVersion = appVersion,
    )
}

/**
 * Playable audio stream URI and codec metadata resolved by a [MediaProvider].
 */
data class ResolvedMediaStream(
    val trackId: String,
    val streamUrl: String,
    val mimeType: String,
    val codecs: String? = null,
    val itag: Int? = null,
    val bitrateBps: Int? = null,
    val sampleRateHz: Int? = null,
    val channels: Int? = null,
    val contentLengthBytes: Long? = null,
    val expiresAtEpochSeconds: Long? = null,
    val loudnessDb: Double? = null,
    val sourceType: MediaSourceType,
) {
    /**
     * Returns `true` if this resolved stream has an expiration timestamp that has already passed
     * (or will expire within [safetyMarginSeconds]).
     */
    fun isExpired(
        nowEpochSeconds: Long,
        safetyMarginSeconds: Long = 60L,
    ): Boolean {
        val exp = expiresAtEpochSeconds ?: return false
        return nowEpochSeconds + safetyMarginSeconds >= exp
    }
}

/**
 * Paginated search result page returned by [MediaProvider.searchTracks].
 */
data class MediaSearchPage(
    val tracks: List<MediaTrack>,
    val continuationToken: String? = null,
)

/**
 * Distinguishes official albums/singles/EPs from user or editorial playlists.
 */
enum class MediaCollectionType {
    ALBUM,
    PLAYLIST,
}

/**
 * An ordered, playable group of tracks (an album or a playlist) discovered via search.
 *
 * [playlistId] identifies the underlying track list (e.g. `OLAK5uy_…` for YouTube Music albums
 * or `PL…` for playlists) and is used to fetch the tracks that form an isolated playback queue.
 */
data class MediaCollection(
    val playlistId: String,
    val title: String,
    val subtitle: String,
    val type: MediaCollectionType,
    val artistName: String? = null,
    val artworkUrl: String? = null,
    val sourceType: MediaSourceType,
)

/**
 * Contract for discovering, inspecting, and resolving playable audio streams from a media source.
 *
 * Implementations MUST propagate all network, cipher, permission, and storage errors upward
 * via [AppResult.Failure] without swallowing exceptions.
 */
interface MediaProvider {
    val sourceType: MediaSourceType

    /**
     * Searches for audio tracks matching [query], optionally resuming from [continuationToken].
     */
    suspend fun searchTracks(
        query: String,
        continuationToken: String? = null,
    ): AppResult<MediaSearchPage>

    /**
     * Fetches canonical track metadata for [trackId].
     */
    suspend fun getTrackMetadata(trackId: String): AppResult<MediaTrack>

    /**
     * Resolves a directly playable audio stream URL or content URI for [trackId], performing
     * any required signature cipher decryption before returning.
     */
    suspend fun resolveAudioStream(trackId: String): AppResult<ResolvedMediaStream>

    /**
     * Lists tracks available in the provider's local/default library (used primarily by local storage
     * or cached catalogs).
     */
    suspend fun getLibraryTracks(
        limit: Int = DEFAULT_PAGE_LIMIT,
        offset: Int = 0,
    ): AppResult<List<MediaTrack>>

    companion object {
        const val DEFAULT_PAGE_LIMIT = 100
    }
}
