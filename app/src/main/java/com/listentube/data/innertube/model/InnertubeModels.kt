// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube.data.innertube.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// =============================================================================
// Innertube Request Payloads
// =============================================================================

@Serializable
data class InnertubeContext(
    @SerialName("client")
    val client: InnertubeClientInfo,
    @SerialName("thirdParty")
    val thirdParty: InnertubeThirdPartyContext? = null,
    @SerialName("request")
    val request: InnertubeRequestConfig = InnertubeRequestConfig(),
    @SerialName("user")
    val user: InnertubeUserConfig = InnertubeUserConfig(),
) {
    companion object {
        /**
         * Default `WEB_REMIX` client context used for YouTube Music search and metadata queries.
         */
        fun webRemix(
            hl: String = "en",
            gl: String = "US",
            visitorData: String? = null,
        ): InnertubeContext = InnertubeContext(
            client = InnertubeClientInfo(
                clientName = "WEB_REMIX",
                clientVersion = "1.20250115.01.00",
                hl = hl,
                gl = gl,
                platform = "DESKTOP",
                userAgent = WEB_REMIX_USER_AGENT,
                visitorData = visitorData,
            ),
        )

        /**
         * Embedded TV HTML5 client context used when extracting streams that require `signatureTimestamp`
         * (`sts`) and `signatureCipher` decryption via `CipherDecryptor`.
         */
        fun tvEmbedded(
            hl: String = "en",
            gl: String = "US",
            visitorData: String? = null,
        ): InnertubeContext = InnertubeContext(
            client = InnertubeClientInfo(
                clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
                clientVersion = "2.0",
                hl = hl,
                gl = gl,
                platform = "TV",
                visitorData = visitorData,
            ),
            thirdParty = InnertubeThirdPartyContext(
                embedUrl = "https://www.youtube.com/",
            ),
        )

        /**
         * Android client context for direct muxed (`itag 18`) and adaptive stream extraction.
         */
        fun android(
            hl: String = "en",
            gl: String = "US",
            visitorData: String? = null,
        ): InnertubeContext = InnertubeContext(
            client = InnertubeClientInfo(
                clientName = "ANDROID",
                clientVersion = "20.03.32",
                hl = hl,
                gl = gl,
                osName = "Android",
                osVersion = "14",
                androidSdkVersion = 34,
                userAgent = ANDROID_USER_AGENT,
                visitorData = visitorData,
            ),
        )

        /**
         * iOS client context for direct adaptive stream extraction fallback.
         */
        fun ios(
            hl: String = "en",
            gl: String = "US",
            visitorData: String? = null,
        ): InnertubeContext = InnertubeContext(
            client = InnertubeClientInfo(
                clientName = "IOS",
                clientVersion = "20.03.02",
                hl = hl,
                gl = gl,
                deviceMake = "Apple",
                deviceModel = "iPhone16,2",
                osName = "iPhone",
                osVersion = "18.2.1.22C161",
                userAgent = IOS_USER_AGENT,
                visitorData = visitorData,
            ),
        )

        /**
         * Android VR client context for direct adaptive stream extraction.
         */
        fun androidVr(
            hl: String = "en",
            gl: String = "US",
            visitorData: String? = null,
        ): InnertubeContext = InnertubeContext(
            client = InnertubeClientInfo(
                clientName = "ANDROID_VR",
                clientVersion = "1.60.19",
                hl = hl,
                gl = gl,
                osName = "Android",
                osVersion = "14",
                androidSdkVersion = 34,
                userAgent = ANDROID_VR_USER_AGENT,
                visitorData = visitorData,
            ),
        )

        const val WEB_REMIX_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0"
        const val ANDROID_USER_AGENT =
            "com.google.android.youtube/20.03.32 (Linux; U; Android 14) gzip"
        const val IOS_USER_AGENT =
            "com.google.ios.youtube/20.03.02 (iPhone16,2; U; CPU iOS 18_2_1 like Mac OS X;)"
        const val ANDROID_VR_USER_AGENT =
            "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 14; en_US; Quest 3; Build/UP1A.231005.007.A1; Cronet/132.0.6808.3)"
    }
}

@Serializable
data class InnertubeClientInfo(
    @SerialName("clientName")
    val clientName: String,
    @SerialName("clientVersion")
    val clientVersion: String,
    @SerialName("hl")
    val hl: String = "en",
    @SerialName("gl")
    val gl: String = "US",
    @SerialName("platform")
    val platform: String? = null,
    @SerialName("deviceMake")
    val deviceMake: String? = null,
    @SerialName("deviceModel")
    val deviceModel: String? = null,
    @SerialName("osName")
    val osName: String? = null,
    @SerialName("osVersion")
    val osVersion: String? = null,
    @SerialName("androidSdkVersion")
    val androidSdkVersion: Int? = null,
    @SerialName("userAgent")
    val userAgent: String? = null,
    @SerialName("visitorData")
    val visitorData: String? = null,
)

@Serializable
data class InnertubeThirdPartyContext(
    @SerialName("embedUrl")
    val embedUrl: String,
)

@Serializable
data class InnertubeRequestConfig(
    @SerialName("useSsl")
    val useSsl: Boolean = true,
    @SerialName("internalExperimentFlags")
    val internalExperimentFlags: List<String> = emptyList(),
)

@Serializable
data class InnertubeUserConfig(
    @SerialName("lockedSafetyMode")
    val lockedSafetyMode: Boolean = false,
)

@Serializable
data class InnertubePlaybackContext(
    @SerialName("contentPlaybackContext")
    val contentPlaybackContext: ContentPlaybackContext,
)

@Serializable
data class ContentPlaybackContext(
    @SerialName("signatureTimestamp")
    val signatureTimestamp: Int? = null,
    @SerialName("html5Preference")
    val html5Preference: String = "HTML5_PREF_WANTS",
)

@Serializable
data class InnertubePlayerRequest(
    @SerialName("context")
    val context: InnertubeContext,
    @SerialName("videoId")
    val videoId: String,
    @SerialName("playbackContext")
    val playbackContext: InnertubePlaybackContext? = null,
    @SerialName("racyCheckOk")
    val racyCheckOk: Boolean = true,
    @SerialName("contentCheckOk")
    val contentCheckOk: Boolean = true,
)

@Serializable
data class InnertubeSearchRequest(
    @SerialName("context")
    val context: InnertubeContext,
    @SerialName("query")
    val query: String? = null,
    @SerialName("params")
    val params: String? = null,
    @SerialName("continuation")
    val continuation: String? = null,
) {
    companion object {
        /**
         * Protobuf-encoded filter restricting YouTube Music search results to "Songs".
         */
        const val SONGS_FILTER_PARAMS = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"

        /**
         * Protobuf-encoded filter restricting YouTube Music search results to "Albums".
         */
        const val ALBUMS_FILTER_PARAMS = "EgWKAQIYAWoKEAkQAxAEEAkQBQ%3D%3D"

        /**
         * Protobuf-encoded filter restricting YouTube Music search results to "Community playlists".
         */
        const val PLAYLISTS_FILTER_PARAMS = "EgeKAQQoAEABagoQAxAEEAoQCRAF"
    }
}

@Serializable
data class InnertubeBrowseRequest(
    @SerialName("context")
    val context: InnertubeContext,
    @SerialName("browseId")
    val browseId: String? = null,
    @SerialName("continuation")
    val continuation: String? = null,
)

// =============================================================================
// Innertube `/youtubei/v1/player` Response Models
// =============================================================================

@Serializable
data class InnertubeResponseContext(
    @SerialName("visitorData")
    val visitorData: String? = null,
)

@Serializable
data class InnertubePlayerResponse(
    @SerialName("responseContext")
    val responseContext: InnertubeResponseContext? = null,
    @SerialName("playabilityStatus")
    val playabilityStatus: PlayabilityStatus? = null,
    @SerialName("streamingData")
    val streamingData: StreamingData? = null,
    @SerialName("videoDetails")
    val videoDetails: VideoDetails? = null,
    @SerialName("playerConfig")
    val playerConfig: PlayerConfig? = null,
)

@Serializable
data class PlayabilityStatus(
    @SerialName("status")
    val status: String = "UNKNOWN",
    @SerialName("reason")
    val reason: String? = null,
    @SerialName("playableInEmbed")
    val playableInEmbed: Boolean? = null,
) {
    val isPlayable: Boolean
        get() = status.equals("OK", ignoreCase = true)
}

@Serializable
data class StreamingData(
    @SerialName("expiresInSeconds")
    val expiresInSeconds: String? = null,
    @SerialName("formats")
    val formats: List<AdaptiveFormat> = emptyList(),
    @SerialName("adaptiveFormats")
    val adaptiveFormats: List<AdaptiveFormat> = emptyList(),
    @SerialName("hlsManifestUrl")
    val hlsManifestUrl: String? = null,
    @SerialName("dashManifestUrl")
    val dashManifestUrl: String? = null,
)

@Serializable
data class AdaptiveFormat(
    @SerialName("itag")
    val itag: Int,
    @SerialName("url")
    val url: String? = null,
    @SerialName("signatureCipher")
    val signatureCipher: String? = null,
    @SerialName("cipher")
    val cipher: String? = null,
    @SerialName("mimeType")
    val mimeType: String,
    @SerialName("bitrate")
    val bitrate: Int? = null,
    @SerialName("averageBitrate")
    val averageBitrate: Int? = null,
    @SerialName("contentLength")
    val contentLength: String? = null,
    @SerialName("audioQuality")
    val audioQuality: String? = null,
    @SerialName("audioSampleRate")
    val audioSampleRate: String? = null,
    @SerialName("audioChannels")
    val audioChannels: Int? = null,
    @SerialName("approxDurationMs")
    val approxDurationMs: String? = null,
    @SerialName("loudnessDb")
    val loudnessDb: Double? = null,
) {
    /**
     * Returns whichever cipher field (`signatureCipher` or legacy `cipher`) was populated by Innertube.
     */
    val rawCipher: String?
        get() = signatureCipher?.takeIf { it.isNotBlank() } ?: cipher?.takeIf { it.isNotBlank() }

    /**
     * Whether this format requires cipher decryption via `CipherDecryptor`.
     */
    val requiresCipherDecryption: Boolean
        get() = url.isNullOrBlank() && !rawCipher.isNullOrBlank()

    /**
     * Whether this format is an audio-only stream (`audio/webm`, `audio/mp4`, etc.).
     */
    val isAudioOnly: Boolean
        get() = mimeType.startsWith("audio/", ignoreCase = true)

    val effectiveBitrate: Int
        get() = averageBitrate ?: bitrate ?: 0

    /**
     * Extracts the base container MIME type (e.g., `"audio/webm"` from `"audio/webm; codecs=\"opus\""`).
     */
    fun containerMimeType(): String =
        mimeType.substringBefore(';').trim().lowercase()

    /**
     * Extracts the codec parameter (e.g., `"opus"` or `"mp4a.40.2"`), if present.
     */
    fun codecsParameter(): String? {
        val codecsPart = mimeType.substringAfter("codecs=", missingDelimiterValue = "")
            .trim()
            .removeSurrounding("\"")
        return codecsPart.takeIf { it.isNotEmpty() }
    }

    /**
     * Ranking score prioritizing Opus (`itag 251`) and high-bitrate M4A (`itag 140`) audio streams.
     */
    fun audioQualityRank(): Int {
        val qualityBonus = when (audioQuality) {
            "AUDIO_QUALITY_HIGH" -> 300_000
            "AUDIO_QUALITY_MEDIUM" -> 200_000
            "AUDIO_QUALITY_LOW" -> 100_000
            else -> 0
        }
        val opusBonus = if (codecsParameter()?.contains("opus", ignoreCase = true) == true) 25_000 else 0
        return qualityBonus + opusBonus + effectiveBitrate
    }
}

@Serializable
data class VideoDetails(
    @SerialName("videoId")
    val videoId: String,
    @SerialName("title")
    val title: String = "",
    @SerialName("lengthSeconds")
    val lengthSeconds: String = "0",
    @SerialName("author")
    val author: String = "",
    @SerialName("channelId")
    val channelId: String? = null,
    @SerialName("shortDescription")
    val shortDescription: String? = null,
    @SerialName("thumbnail")
    val thumbnail: ThumbnailContainer? = null,
    @SerialName("viewCount")
    val viewCount: String? = null,
    @SerialName("musicVideoType")
    val musicVideoType: String? = null,
) {
    val durationMs: Long
        get() = (lengthSeconds.toLongOrNull() ?: 0L).coerceAtLeast(0L) * 1_000L

    fun bestThumbnailUrl(): String? =
        thumbnail?.thumbnails?.maxByOrNull { it.width * it.height }?.url
}

@Serializable
data class PlayerConfig(
    @SerialName("audioConfig")
    val audioConfig: AudioConfig? = null,
)

@Serializable
data class AudioConfig(
    @SerialName("loudnessDb")
    val loudnessDb: Double? = null,
    @SerialName("perceptualLoudnessDb")
    val perceptualLoudnessDb: Double? = null,
)

@Serializable
data class ThumbnailContainer(
    @SerialName("thumbnails")
    val thumbnails: List<InnertubeThumbnail> = emptyList(),
)

@Serializable
data class InnertubeThumbnail(
    @SerialName("url")
    val url: String,
    @SerialName("width")
    val width: Int = 0,
    @SerialName("height")
    val height: Int = 0,
)

// =============================================================================
// Innertube `/youtubei/v1/search` Response Models
// =============================================================================

@Serializable
data class InnertubeSearchResponse(
    @SerialName("responseContext")
    val responseContext: InnertubeResponseContext? = null,
    @SerialName("contents")
    val contents: SearchContents? = null,
    @SerialName("continuationContents")
    val continuationContents: ContinuationContents? = null,
)

@Serializable
data class SearchContents(
    @SerialName("tabbedSearchResultsRenderer")
    val tabbedSearchResultsRenderer: TabbedSearchResultsRenderer? = null,
)

@Serializable
data class TabbedSearchResultsRenderer(
    @SerialName("tabs")
    val tabs: List<SearchTab> = emptyList(),
)

@Serializable
data class SearchTab(
    @SerialName("tabRenderer")
    val tabRenderer: TabRenderer? = null,
)

@Serializable
data class TabRenderer(
    @SerialName("content")
    val content: TabContent? = null,
)

@Serializable
data class TabContent(
    @SerialName("sectionListRenderer")
    val sectionListRenderer: SectionListRenderer? = null,
)

@Serializable
data class SectionListRenderer(
    @SerialName("contents")
    val contents: List<SectionContent> = emptyList(),
)

@Serializable
data class SectionContent(
    @SerialName("musicShelfRenderer")
    val musicShelfRenderer: MusicShelfRenderer? = null,
    @SerialName("musicPlaylistShelfRenderer")
    val musicPlaylistShelfRenderer: MusicShelfRenderer? = null,
)

@Serializable
data class ContinuationContents(
    @SerialName("musicShelfContinuation")
    val musicShelfContinuation: MusicShelfRenderer? = null,
    @SerialName("musicPlaylistShelfContinuation")
    val musicPlaylistShelfContinuation: MusicShelfRenderer? = null,
)

@Serializable
data class MusicShelfRenderer(
    @SerialName("contents")
    val contents: List<MusicShelfItem> = emptyList(),
    @SerialName("continuations")
    val continuations: List<ContinuationItem> = emptyList(),
)

@Serializable
data class ContinuationItem(
    @SerialName("nextContinuationData")
    val nextContinuationData: NextContinuationData? = null,
)

@Serializable
data class NextContinuationData(
    @SerialName("continuation")
    val continuation: String? = null,
)

@Serializable
data class MusicShelfItem(
    @SerialName("musicResponsiveListItemRenderer")
    val musicResponsiveListItemRenderer: MusicResponsiveListItemRenderer? = null,
    @SerialName("continuationItemRenderer")
    val continuationItemRenderer: ContinuationItemRenderer? = null,
)

@Serializable
data class ContinuationItemRenderer(
    @SerialName("continuationEndpoint")
    val continuationEndpoint: ContinuationEndpoint? = null,
)

@Serializable
data class ContinuationEndpoint(
    @SerialName("continuationCommand")
    val continuationCommand: ContinuationCommand? = null,
)

@Serializable
data class ContinuationCommand(
    @SerialName("token")
    val token: String? = null,
)

@Serializable
data class MusicResponsiveListItemRenderer(
    @SerialName("flexColumns")
    val flexColumns: List<MusicResponsiveFlexColumn> = emptyList(),
    @SerialName("fixedColumns")
    val fixedColumns: List<MusicResponsiveFixedColumn> = emptyList(),
    @SerialName("playlistItemData")
    val playlistItemData: PlaylistItemData? = null,
    @SerialName("overlay")
    val overlay: MusicItemOverlay? = null,
    @SerialName("thumbnail")
    val thumbnail: MusicThumbnailRendererContainer? = null,
    @SerialName("navigationEndpoint")
    val navigationEndpoint: NavigationEndpoint? = null,
    @SerialName("musicItemRendererDisplayPolicy")
    val musicItemRendererDisplayPolicy: String? = null,
)

@Serializable
data class MusicResponsiveFixedColumn(
    @SerialName("musicResponsiveListItemFixedColumnRenderer")
    val renderer: FlexColumnRenderer? = null,
)

@Serializable
data class PlaylistItemData(
    @SerialName("videoId")
    val videoId: String? = null,
)

@Serializable
data class MusicItemOverlay(
    @SerialName("musicItemThumbnailOverlayRenderer")
    val musicItemThumbnailOverlayRenderer: MusicItemThumbnailOverlayRenderer? = null,
)

@Serializable
data class MusicItemThumbnailOverlayRenderer(
    @SerialName("content")
    val content: MusicPlayButtonContainer? = null,
)

@Serializable
data class MusicPlayButtonContainer(
    @SerialName("musicPlayButtonRenderer")
    val musicPlayButtonRenderer: MusicPlayButtonRenderer? = null,
)

@Serializable
data class MusicPlayButtonRenderer(
    @SerialName("playNavigationEndpoint")
    val playNavigationEndpoint: NavigationEndpoint? = null,
)

@Serializable
data class MusicThumbnailRendererContainer(
    @SerialName("musicThumbnailRenderer")
    val musicThumbnailRenderer: MusicThumbnailRenderer? = null,
)

@Serializable
data class MusicThumbnailRenderer(
    @SerialName("thumbnail")
    val thumbnail: ThumbnailContainer? = null,
)

@Serializable
data class MusicResponsiveFlexColumn(
    @SerialName("musicResponsiveListItemFlexColumnRenderer")
    val renderer: FlexColumnRenderer? = null,
)

@Serializable
data class FlexColumnRenderer(
    @SerialName("text")
    val text: FormattedText? = null,
)

@Serializable
data class FormattedText(
    @SerialName("runs")
    val runs: List<TextRun> = emptyList(),
) {
    fun joinedText(): String = runs.joinToString(separator = "") { it.text }
}

@Serializable
data class TextRun(
    @SerialName("text")
    val text: String = "",
    @SerialName("navigationEndpoint")
    val navigationEndpoint: NavigationEndpoint? = null,
)

@Serializable
data class NavigationEndpoint(
    @SerialName("watchEndpoint")
    val watchEndpoint: WatchEndpoint? = null,
    @SerialName("watchPlaylistEndpoint")
    val watchPlaylistEndpoint: WatchEndpoint? = null,
    @SerialName("browseEndpoint")
    val browseEndpoint: BrowseEndpoint? = null,
)

@Serializable
data class WatchEndpoint(
    @SerialName("videoId")
    val videoId: String? = null,
    @SerialName("playlistId")
    val playlistId: String? = null,
)

@Serializable
data class BrowseEndpoint(
    @SerialName("browseId")
    val browseId: String? = null,
    @SerialName("browseEndpointContextSupportedConfigs")
    val browseEndpointContextSupportedConfigs: BrowseEndpointContextSupportedConfigs? = null,
) {
    val pageType: String?
        get() = browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType
}

@Serializable
data class BrowseEndpointContextSupportedConfigs(
    @SerialName("browseEndpointContextMusicConfig")
    val browseEndpointContextMusicConfig: BrowseEndpointContextMusicConfig? = null,
)

@Serializable
data class BrowseEndpointContextMusicConfig(
    @SerialName("pageType")
    val pageType: String? = null,
)

// =============================================================================
// Innertube `/youtubei/v1/browse` Response Models (albums & playlists)
// =============================================================================

@Serializable
data class InnertubeBrowseResponse(
    @SerialName("responseContext")
    val responseContext: InnertubeResponseContext? = null,
    @SerialName("contents")
    val contents: BrowseContents? = null,
    @SerialName("continuationContents")
    val continuationContents: ContinuationContents? = null,
    @SerialName("onResponseReceivedActions")
    val onResponseReceivedActions: List<ResponseReceivedAction> = emptyList(),
)

@Serializable
data class BrowseContents(
    @SerialName("twoColumnBrowseResultsRenderer")
    val twoColumnBrowseResultsRenderer: TwoColumnBrowseResultsRenderer? = null,
    @SerialName("singleColumnBrowseResultsRenderer")
    val singleColumnBrowseResultsRenderer: TabbedSearchResultsRenderer? = null,
)

@Serializable
data class TwoColumnBrowseResultsRenderer(
    @SerialName("tabs")
    val tabs: List<SearchTab> = emptyList(),
    @SerialName("secondaryContents")
    val secondaryContents: TabContent? = null,
)

@Serializable
data class ResponseReceivedAction(
    @SerialName("appendContinuationItemsAction")
    val appendContinuationItemsAction: AppendContinuationItemsAction? = null,
)

@Serializable
data class AppendContinuationItemsAction(
    @SerialName("continuationItems")
    val continuationItems: List<MusicShelfItem> = emptyList(),
)
