// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube.data.listenbrainz.model

import com.listentube.core.result.AppResult
import com.listentube.core.result.DomainError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ListenBrainz submission types supported by `POST /1/submit-listens`.
 */
@Serializable
enum class ListenType(val wireName: String) {
    @SerialName("playing_now")
    PLAYING_NOW("playing_now"),

    @SerialName("single")
    SINGLE("single"),

    @SerialName("import")
    IMPORT("import"),
}

/**
 * Top-level request body for `POST /1/submit-listens`.
 *
 * - For [ListenType.PLAYING_NOW], [payload] must contain exactly 1 item and [ListenPayload.listenedAt] MUST be `null`.
 * - For [ListenType.SINGLE], [payload] must contain exactly 1 item and [ListenPayload.listenedAt] MUST be non-null.
 * - For [ListenType.IMPORT], [payload] may contain multiple items and every [ListenPayload.listenedAt] MUST be non-null.
 */
@Serializable
data class SubmitListensRequest(
    @SerialName("listen_type")
    val listenType: ListenType,
    @SerialName("payload")
    val payload: List<ListenPayload>,
)

/**
 * Individual listen entry inside [SubmitListensRequest.payload].
 *
 * When serialized with `explicitNulls = false`, [listenedAt] is completely omitted from JSON
 * when `null` (which is required by ListenBrainz for `playing_now` submissions).
 */
@Serializable
data class ListenPayload(
    @SerialName("listened_at")
    val listenedAt: Long? = null,
    @SerialName("track_metadata")
    val trackMetadata: TrackMetadata,
)

/**
 * Track metadata payload required by ListenBrainz.
 */
@Serializable
data class TrackMetadata(
    @SerialName("artist_name")
    val artistName: String,
    @SerialName("track_name")
    val trackName: String,
    @SerialName("release_name")
    val releaseName: String? = null,
    @SerialName("additional_info")
    val additionalInfo: AdditionalInfo? = null,
)

/**
 * Optional enrichment metadata under `track_metadata.additional_info`.
 */
@Serializable
data class AdditionalInfo(
    @SerialName("duration_ms")
    val durationMs: Long? = null,
    @SerialName("media_player")
    val mediaPlayer: String = DEFAULT_CLIENT_NAME,
    @SerialName("media_player_version")
    val mediaPlayerVersion: String? = null,
    @SerialName("submission_client")
    val submissionClient: String = DEFAULT_CLIENT_NAME,
    @SerialName("submission_client_version")
    val submissionClientVersion: String? = null,
    @SerialName("music_service")
    val musicService: String = DEFAULT_MUSIC_SERVICE,
    @SerialName("origin_url")
    val originUrl: String? = null,
    @SerialName("youtube_id")
    val youtubeId: String? = null,
    @SerialName("recording_mbid")
    val recordingMbid: String? = null,
    @SerialName("release_mbid")
    val releaseMbid: String? = null,
    @SerialName("artist_mbids")
    val artistMbids: List<String>? = null,
    @SerialName("tracknumber")
    val trackNumber: Int? = null,
) {
    companion object {
        const val DEFAULT_CLIENT_NAME = "ListenTube"
        const val DEFAULT_MUSIC_SERVICE = "music.youtube.com"
    }
}

/**
 * Successful response returned by `POST /1/submit-listens` (`{"status": "ok"}`).
 */
@Serializable
data class SubmitListensResponse(
    @SerialName("status")
    val status: String,
)

/**
 * Response returned by `GET /1/validate-token`.
 */
@Serializable
data class ValidateTokenResponse(
    @SerialName("code")
    val code: Int,
    @SerialName("message")
    val message: String,
    @SerialName("valid")
    val valid: Boolean,
    @SerialName("user_name")
    val userName: String? = null,
)

/**
 * Error body returned by ListenBrainz on 4xx/5xx responses (`{"code": 400, "error": "..."}`).
 */
@Serializable
data class ListenBrainzErrorResponse(
    @SerialName("code")
    val code: Int? = null,
    @SerialName("error")
    val error: String? = null,
)

/**
 * Snapshot of ListenBrainz HTTP rate-limit headers (`X-RateLimit-*`).
 *
 * ListenBrainz provides `X-RateLimit-Reset-In` as a relative duration in seconds
 * rather than an absolute epoch timestamp to avoid client clock-skew issues.
 */
data class RateLimitInfo(
    val limit: Int? = null,
    val remaining: Int? = null,
    val resetInSeconds: Long? = null,
    val resetAtElapsedMs: Long = 0L,
) {
    fun isCoolingDown(nowElapsedMs: Long): Boolean = nowElapsedMs < resetAtElapsedMs

    fun remainingCooldownSeconds(nowElapsedMs: Long): Long {
        val diffMs = (resetAtElapsedMs - nowElapsedMs).coerceAtLeast(0L)
        return ((diffMs + 999L) / 1_000L).coerceAtLeast(1L)
    }
}

/**
 * Normalized domain model for a YouTube Music track eligible for ListenBrainz reporting.
 */
data class ScrobbleTrack(
    val videoId: String,
    val artistName: String,
    val trackName: String,
    val durationMs: Long,
    val releaseName: String? = null,
    val trackNumber: Int? = null,
    val recordingMbid: String? = null,
    val releaseMbid: String? = null,
    val artistMbids: List<String>? = null,
    val appVersion: String? = null,
) {
    val originUrl: String
        get() = "https://music.youtube.com/watch?v=$videoId"

    /**
     * Validates that required metadata fields (`artistName`, `trackName`, `videoId`) are non-blank.
     */
    fun validateMetadata(): AppResult<ScrobbleTrack> {
        if (videoId.isBlank()) {
            return AppResult.Failure(DomainError.Validation.InvalidMetadata("videoId"))
        }
        if (artistName.isBlank()) {
            return AppResult.Failure(DomainError.Validation.InvalidMetadata("artist_name"))
        }
        if (trackName.isBlank()) {
            return AppResult.Failure(DomainError.Validation.InvalidMetadata("track_name"))
        }
        return AppResult.Success(this)
    }

    /**
     * Converts this domain track to a [TrackMetadata] wire object.
     */
    fun toTrackMetadata(): TrackMetadata = TrackMetadata(
        artistName = artistName.trim(),
        trackName = trackName.trim(),
        releaseName = releaseName?.trim()?.takeIf { it.isNotEmpty() },
        additionalInfo = AdditionalInfo(
            durationMs = durationMs.takeIf { it > 0L },
            mediaPlayerVersion = appVersion,
            submissionClientVersion = appVersion,
            originUrl = originUrl,
            youtubeId = videoId,
            recordingMbid = recordingMbid?.takeIf { it.isNotBlank() },
            releaseMbid = releaseMbid?.takeIf { it.isNotBlank() },
            artistMbids = artistMbids?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() },
            trackNumber = trackNumber?.takeIf { it > 0 },
        ),
    )

    /**
     * Builds a `playing_now` [ListenPayload] (where `listened_at` MUST be `null`).
     */
    fun toPlayingNowPayload(): ListenPayload = ListenPayload(
        listenedAt = null,
        trackMetadata = toTrackMetadata(),
    )

    /**
     * Builds a completed listen [ListenPayload] with the given Unix epoch seconds [listenedAtEpochSeconds].
     */
    fun toCompletedPayload(listenedAtEpochSeconds: Long): ListenPayload = ListenPayload(
        listenedAt = listenedAtEpochSeconds,
        trackMetadata = toTrackMetadata(),
    )
}
