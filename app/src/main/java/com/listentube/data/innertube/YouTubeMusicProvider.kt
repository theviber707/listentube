// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube.data.innertube

import com.listentube.core.result.AppResult
import com.listentube.core.result.DomainError
import com.listentube.core.result.appRunCatching
import com.listentube.core.result.flatMap
import com.listentube.core.result.map
import com.listentube.data.innertube.cipher.CipherDecryptor
import com.listentube.data.innertube.model.AdaptiveFormat
import com.listentube.data.innertube.model.ContentPlaybackContext
import com.listentube.data.innertube.model.InnertubeContext
import com.listentube.data.innertube.model.InnertubePlaybackContext
import com.listentube.data.innertube.model.InnertubePlayerRequest
import com.listentube.data.innertube.model.InnertubePlayerResponse
import com.listentube.data.innertube.model.InnertubeSearchRequest
import com.listentube.data.innertube.model.InnertubeSearchResponse
import com.listentube.data.innertube.model.MusicResponsiveListItemRenderer
import com.listentube.data.innertube.model.MusicShelfRenderer
import com.listentube.domain.media.MediaProvider
import com.listentube.domain.media.MediaSearchPage
import com.listentube.domain.media.MediaSourceType
import com.listentube.domain.media.MediaTrack
import com.listentube.domain.media.ResolvedMediaStream
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

/**
 * [MediaProvider] implementation backed by YouTube Music's Innertube v1 API and [CipherDecryptor].
 *
 * Guarantees that all HTTP, JSON deserialization, playability status, and signature cipher errors
 * are explicitly returned upward via [AppResult.Failure] without swallowing exceptions.
 */
class YouTubeMusicProvider(
    private val httpClient: HttpClient,
    private val cipherDecryptor: CipherDecryptor,
    private val json: Json,
    private val ytmBaseUrl: String = DEFAULT_YTM_BASE_URL,
    private val ytBaseUrl: String = DEFAULT_YT_BASE_URL,
    @Volatile var hl: String = "en",
    @Volatile var gl: String = "US",
) : MediaProvider {

    override val sourceType: MediaSourceType = MediaSourceType.YOUTUBE_MUSIC

    /**
     * Updates the Innertube client language (`hl`) and country (`gl`) parameters at runtime.
     */
    fun updateLocale(hl: String, gl: String) {
        this.hl = hl
        this.gl = gl
    }

    /**
     * Searches YouTube Music for songs matching [query], or fetches the next page using [continuationToken].
     */
    override suspend fun searchTracks(
        query: String,
        continuationToken: String?,
    ): AppResult<MediaSearchPage> {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty() && continuationToken.isNullOrBlank()) {
            return AppResult.Failure(
                DomainError.Validation.InvalidMetadata("search query cannot be blank"),
            )
        }

        val requestPayload = if (!continuationToken.isNullOrBlank()) {
            InnertubeSearchRequest(
                context = InnertubeContext.webRemix(hl = hl, gl = gl),
                continuation = continuationToken,
            )
        } else {
            InnertubeSearchRequest(
                context = InnertubeContext.webRemix(hl = hl, gl = gl),
                query = trimmedQuery,
                params = InnertubeSearchRequest.SONGS_FILTER_PARAMS,
            )
        }

        return postInnertube<InnertubeSearchRequest, InnertubeSearchResponse>(
            endpointUrl = "${ytmBaseUrl.trimEnd('/')}$ENDPOINT_SEARCH",
            originHeader = DEFAULT_YTM_BASE_URL,
            userAgent = InnertubeContext.WEB_REMIX_USER_AGENT,
            continuationToken = continuationToken,
            requestBody = requestPayload,
        ).map { response ->
            parseSearchResponse(response)
        }
    }

    /**
     * Fetches canonical track details for [trackId] from the Innertube `/youtubei/v1/player` endpoint.
     */
    override suspend fun getTrackMetadata(trackId: String): AppResult<MediaTrack> {
        val sanitizedId = trackId.trim()
        if (sanitizedId.isEmpty()) {
            return AppResult.Failure(DomainError.Validation.InvalidMetadata("trackId"))
        }

        val request = InnertubePlayerRequest(
            context = InnertubeContext.webRemix(hl = hl, gl = gl),
            videoId = sanitizedId,
        )

        return postInnertube<InnertubePlayerRequest, InnertubePlayerResponse>(
            endpointUrl = "${ytmBaseUrl.trimEnd('/')}$ENDPOINT_PLAYER",
            originHeader = DEFAULT_YTM_BASE_URL,
            userAgent = InnertubeContext.WEB_REMIX_USER_AGENT,
            requestBody = request,
        ).flatMap { playerResponse ->
            validatePlayability(videoId = sanitizedId, response = playerResponse).flatMap {
                val details = playerResponse.videoDetails
                if (details == null || details.videoId.isBlank() || details.title.isBlank()) {
                    AppResult.Failure(
                        DomainError.Playback.StreamExtractionFailed(
                            videoId = sanitizedId,
                            message = "Innertube player response did not contain valid videoDetails for '$sanitizedId'",
                        ),
                    )
                } else {
                    AppResult.Success(
                        MediaTrack(
                            id = details.videoId,
                            title = details.title,
                            artistName = details.author.ifBlank { UNKNOWN_ARTIST },
                            durationMs = details.durationMs,
                            artworkUrl = details.bestThumbnailUrl(),
                            sourceType = MediaSourceType.YOUTUBE_MUSIC,
                        ),
                    )
                }
            }
        }
    }

    /**
     * Resolves the highest-quality audio stream for [trackId].
     *
     * If the selected adaptive audio format uses a `signatureCipher`, this method delegates
     * `base.js` extraction and cipher evaluation to [cipherDecryptor], propagating any network
     * or cipher failure upward via [AppResult.Failure].
     */
    override suspend fun resolveAudioStream(trackId: String): AppResult<ResolvedMediaStream> {
        val sanitizedId = trackId.trim()
        if (sanitizedId.isEmpty()) {
            return AppResult.Failure(DomainError.Validation.InvalidMetadata("trackId"))
        }

        return fetchPlayerResponseForStream(sanitizedId).flatMap { playerResponse ->
            validatePlayability(videoId = sanitizedId, response = playerResponse).flatMap {
                selectAndDecryptBestAudioFormat(videoId = sanitizedId, response = playerResponse)
            }
        }
    }

    /**
     * Returns an empty list by default unless a user query is issued via [searchTracks].
     */
    override suspend fun getLibraryTracks(
        limit: Int,
        offset: Int,
    ): AppResult<List<MediaTrack>> {
        if (limit <= 0 || offset < 0) {
            return AppResult.Failure(
                DomainError.Validation.InvalidMetadata("limit must be > 0 and offset >= 0"),
            )
        }
        return AppResult.Success(emptyList())
    }

    /**
     * Requests `/youtubei/v1/player` with `signatureTimestamp` (`sts`) when needed so ciphered
     * formats can be decrypted deterministically.
     */
    private suspend fun fetchPlayerResponseForStream(videoId: String): AppResult<InnertubePlayerResponse> {
        // First request using the TV embedded client with signatureTimestamp from CipherDecryptor
        return cipherDecryptor.getSignatureTimestamp().flatMap { sts ->
            val embeddedRequest = InnertubePlayerRequest(
                context = InnertubeContext.tvEmbedded(hl = hl, gl = gl),
                videoId = videoId,
                playbackContext = InnertubePlaybackContext(
                    contentPlaybackContext = ContentPlaybackContext(signatureTimestamp = sts),
                ),
            )
            postInnertube<InnertubePlayerRequest, InnertubePlayerResponse>(
                endpointUrl = "${ytBaseUrl.trimEnd('/')}$ENDPOINT_PLAYER",
                originHeader = DEFAULT_YT_BASE_URL,
                userAgent = InnertubeContext.WEB_REMIX_USER_AGENT,
                requestBody = embeddedRequest,
            )
        }
    }

    private fun validatePlayability(
        videoId: String,
        response: InnertubePlayerResponse,
    ): AppResult<Unit> {
        val status = response.playabilityStatus
            ?: return AppResult.Failure(
                DomainError.Playback.StreamExtractionFailed(
                    videoId = videoId,
                    message = "Missing playabilityStatus in Innertube response for '$videoId'",
                ),
            )

        if (!status.isPlayable) {
            return AppResult.Failure(
                DomainError.Playback.UnplayableVideo(
                    videoId = videoId,
                    playabilityStatus = status.status,
                    reason = status.reason,
                ),
            )
        }
        return AppResult.Success(Unit)
    }

    private suspend fun selectAndDecryptBestAudioFormat(
        videoId: String,
        response: InnertubePlayerResponse,
    ): AppResult<ResolvedMediaStream> {
        val streamingData = response.streamingData
            ?: return AppResult.Failure(
                DomainError.Playback.NoAudioStreamFound(
                    videoId = videoId,
                    message = "Innertube response contained no streamingData for '$videoId'",
                ),
            )

        val candidateAudioFormats = (streamingData.adaptiveFormats + streamingData.formats)
            .filter { it.isAudioOnly && (!it.url.isNullOrBlank() || !it.rawCipher.isNullOrBlank()) }
            .sortedByDescending { it.audioQualityRank() }

        val bestFormat = candidateAudioFormats.firstOrNull()
            ?: return AppResult.Failure(
                DomainError.Playback.NoAudioStreamFound(
                    videoId = videoId,
                    message = "No audio-only adaptiveFormats found for '$videoId'",
                ),
            )

        // Delegate URL resolution & cipher decryption to CipherDecryptor without swallowing errors
        return cipherDecryptor.resolveFormatUrl(
            videoId = videoId,
            format = bestFormat,
        ).map { decryptedStreamUrl ->
            val expireEpochSeconds = extractExpireEpochSeconds(decryptedStreamUrl)
            val loudness = bestFormat.loudnessDb
                ?: response.playerConfig?.audioConfig?.loudnessDb
                ?: response.playerConfig?.audioConfig?.perceptualLoudnessDb

            ResolvedMediaStream(
                trackId = videoId,
                streamUrl = decryptedStreamUrl,
                mimeType = bestFormat.containerMimeType(),
                codecs = bestFormat.codecsParameter(),
                itag = bestFormat.itag,
                bitrateBps = bestFormat.effectiveBitrate.takeIf { it > 0 },
                sampleRateHz = bestFormat.audioSampleRate?.toIntOrNull(),
                channels = bestFormat.audioChannels,
                contentLengthBytes = bestFormat.contentLength?.toLongOrNull(),
                expiresAtEpochSeconds = expireEpochSeconds,
                loudnessDb = loudness,
                sourceType = MediaSourceType.YOUTUBE_MUSIC,
            )
        }
    }

    /**
     * Executes an authenticated/contextualized POST request against an Innertube endpoint,
     * mapping all HTTP and serialization failures to [AppResult.Failure].
     */
    private suspend inline fun <reified Req : Any, reified Res : Any> postInnertube(
        endpointUrl: String,
        originHeader: String,
        userAgent: String,
        continuationToken: String? = null,
        requestBody: Req,
    ): AppResult<Res> {
        return appRunCatching {
            val response: HttpResponse = httpClient.post(endpointUrl) {
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                header(HttpHeaders.Origin, originHeader)
                header(HttpHeaders.Referrer, "$originHeader/")
                header(HttpHeaders.UserAgent, userAgent)
                parameter("prettyPrint", "false")
                if (!continuationToken.isNullOrBlank()) {
                    parameter("ctoken", continuationToken)
                    parameter("continuation", continuationToken)
                    parameter("type", "next")
                }
                setBody(requestBody)
            }

            val status = response.status
            val rawBody = response.bodyAsText()

            if (!status.isSuccess()) {
                return@appRunCatching AppResult.Failure(mapHttpStatusToDomainError(status, rawBody))
            }

            if (rawBody.isBlank()) {
                return@appRunCatching AppResult.Failure(
                    DomainError.Network.Serialization(
                        message = "Innertube returned an empty response body from '$endpointUrl'",
                    ),
                )
            }

            AppResult.Success(json.decodeFromString<Res>(rawBody))
        }.let { outer ->
            when (outer) {
                is AppResult.Success -> outer.data
                is AppResult.Failure -> outer
            }
        }
    }

    private fun mapHttpStatusToDomainError(
        status: HttpStatusCode,
        rawBody: String,
    ): DomainError {
        val snippet = rawBody.take(256).ifBlank { status.description }
        return when (status) {
            HttpStatusCode.BadRequest -> DomainError.Network.BadRequest(
                httpCode = status.value,
                message = "Innertube HTTP 400 Bad Request: $snippet",
            )

            HttpStatusCode.Unauthorized,
            HttpStatusCode.Forbidden,
            -> DomainError.Network.Unauthorized(
                message = "Innertube HTTP ${status.value}: $snippet",
            )

            HttpStatusCode.TooManyRequests -> DomainError.Network.RateLimited(
                resetInSeconds = 30L,
                message = "Innertube HTTP 429 Too Many Requests: $snippet",
            )

            HttpStatusCode.InternalServerError,
            HttpStatusCode.BadGateway,
            HttpStatusCode.ServiceUnavailable,
            HttpStatusCode.GatewayTimeout,
            -> DomainError.Network.ServerError(
                httpCode = status.value,
                message = "Innertube HTTP ${status.value}: $snippet",
            )

            else -> DomainError.Network.HttpError(
                httpCode = status.value,
                message = "Innertube HTTP ${status.value}: $snippet",
            )
        }
    }

    private fun parseSearchResponse(response: InnertubeSearchResponse): MediaSearchPage {
        val shelves = mutableListOf<MusicShelfRenderer>()

        response.continuationContents?.musicShelfContinuation?.let { shelves += it }

        val tabs = response.contents?.tabbedSearchResultsRenderer?.tabs.orEmpty()
        for (tab in tabs) {
            val sections = tab.tabRenderer?.content?.sectionListRenderer?.contents.orEmpty()
            for (section in sections) {
                section.musicShelfRenderer?.let { shelves += it }
            }
        }

        val tracks = mutableListOf<MediaTrack>()
        var nextContinuation: String? = null

        for (shelf in shelves) {
            if (nextContinuation == null) {
                nextContinuation = shelf.continuations
                    .firstNotNullOfOrNull { it.nextContinuationData?.continuation?.takeIf(String::isNotBlank) }
            }
            for (item in shelf.contents) {
                val renderer = item.musicResponsiveListItemRenderer ?: continue
                parseShelfItemToTrack(renderer)?.let { tracks += it }
            }
        }

        return MediaSearchPage(
            tracks = tracks,
            continuationToken = nextContinuation,
        )
    }

    private fun parseShelfItemToTrack(renderer: MusicResponsiveListItemRenderer): MediaTrack? {
        val videoId = renderer.playlistItemData?.videoId?.takeIf { it.isNotBlank() }
            ?: renderer.overlay
                ?.musicItemThumbnailOverlayRenderer
                ?.content
                ?.musicPlayButtonRenderer
                ?.playNavigationEndpoint
                ?.watchEndpoint
                ?.videoId
                ?.takeIf { it.isNotBlank() }
            ?: renderer.flexColumns
                .asSequence()
                .flatMap { it.renderer?.text?.runs.orEmpty().asSequence() }
                .firstNotNullOfOrNull { it.navigationEndpoint?.watchEndpoint?.videoId?.takeIf(String::isNotBlank) }
            ?: return null

        val title = renderer.flexColumns
            .getOrNull(0)
            ?.renderer
            ?.text
            ?.joinedText()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val metadataRuns = renderer.flexColumns
            .getOrNull(1)
            ?.renderer
            ?.text
            ?.runs
            .orEmpty()
            .map { it.text.trim() }
            .filter { it.isNotEmpty() && it != "•" && it != "·" && it != "-" }

        val durationText = metadataRuns.lastOrNull { DURATION_TEXT_REGEX.matches(it) }
        val durationMs = durationText?.let(::parseDurationTextToMs) ?: 0L

        val nonDurationParts = if (durationText != null) {
            metadataRuns.dropLast(1)
        } else {
            metadataRuns
        }

        val artistName = nonDurationParts.firstOrNull()?.takeIf { it.isNotBlank() } ?: UNKNOWN_ARTIST
        val albumName = nonDurationParts.getOrNull(1)?.takeIf { it.isNotBlank() }

        val artworkUrl = renderer.thumbnail
            ?.musicThumbnailRenderer
            ?.thumbnail
            ?.thumbnails
            ?.maxByOrNull { it.width * it.height }
            ?.url

        return MediaTrack(
            id = videoId,
            title = title,
            artistName = artistName,
            albumName = albumName,
            durationMs = durationMs,
            artworkUrl = artworkUrl,
            sourceType = MediaSourceType.YOUTUBE_MUSIC,
        )
    }

    private fun parseDurationTextToMs(durationText: String): Long {
        val parts = durationText.trim().split(':').mapNotNull { it.toLongOrNull() }
        val seconds = when (parts.size) {
            2 -> parts[0] * 60L + parts[1]
            3 -> parts[0] * 3_600L + parts[1] * 60L + parts[2]
            else -> 0L
        }
        return seconds * 1_000L
    }

    private fun extractExpireEpochSeconds(streamUrl: String): Long? {
        val query = streamUrl.substringAfter('?', missingDelimiterValue = "")
        if (query.isEmpty()) return null
        for (param in query.split('&')) {
            val eqIdx = param.indexOf('=')
            if (eqIdx <= 0) continue
            val key = param.substring(0, eqIdx)
            if (key.equals("expire", ignoreCase = true)) {
                return param.substring(eqIdx + 1).trim().toLongOrNull()
            }
        }
        return null
    }

    companion object {
        const val DEFAULT_YTM_BASE_URL = "https://music.youtube.com"
        const val DEFAULT_YT_BASE_URL = "https://www.youtube.com"
        const val ENDPOINT_SEARCH = "/youtubei/v1/search"
        const val ENDPOINT_PLAYER = "/youtubei/v1/player"

        private const val UNKNOWN_ARTIST = "Unknown Artist"
        private val DURATION_TEXT_REGEX = Regex("""^\d{1,2}:\d{2}(?::\d{2})?$""")
    }
}
