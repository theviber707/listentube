// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube.data.lyrics

import com.listentube.core.result.AppResult
import com.listentube.data.innertube.YouTubeMusicProvider
import com.listentube.data.innertube.cipher.CipherDecryptor
import com.listentube.domain.lyrics.LyricLine
import com.listentube.domain.lyrics.TrackLyrics
import com.listentube.domain.media.MediaSourceType
import com.listentube.domain.media.MediaTrack
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsRepositoryTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val sampleTrack = MediaTrack(
        id = "video_999",
        title = "Zombie Ritual",
        artistName = "Death",
        durationMs = 274_000L,
        sourceType = MediaSourceType.YOUTUBE_MUSIC,
    )

    @Test
    fun getLyrics_returnsLrclibLyricsAndCaches() = runTest {
        var lrclibCalls = 0
        val mockEngine = MockEngine { request ->
            lrclibCalls++
            respond(
                content = """
                    {
                        "id": 10,
                        "trackName": "Zombie Ritual",
                        "artistName": "Death",
                        "syncedLyrics": "[00:20.00]Zombie ritual"
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
        }

        val lrclibProvider = LrclibLyricsProvider(httpClient = client, json = json)
        val ytmProvider = YouTubeMusicProvider(
            httpClient = client,
            cipherDecryptor = CipherDecryptor(client),
            json = json,
        )
        val repository = LyricsRepository(
            lrclibProvider = lrclibProvider,
            youTubeMusicProvider = ytmProvider,
        )

        // First call hits network
        val result1 = repository.getLyrics(sampleTrack)
        assertTrue(result1 is AppResult.Success)
        val lyrics1 = (result1 as AppResult.Success).data
        assertNotNull(lyrics1)
        assertEquals(1, lrclibCalls)

        // Second call hits cache, no new network calls
        val result2 = repository.getLyrics(sampleTrack)
        assertTrue(result2 is AppResult.Success)
        val lyrics2 = (result2 as AppResult.Success).data
        assertEquals(lyrics1, lyrics2)
        assertEquals(1, lrclibCalls)
    }

    @Test
    fun getLyrics_fallsBackToInnertubeWhenLrclibEmpty() = runTest {
        val mockEngine = MockEngine { request ->
            when {
                request.url.encodedPath.startsWith("/api/") -> {
                    respond(
                        content = "{\"error\": \"Not Found\"}",
                        status = HttpStatusCode.NotFound,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }

                request.url.encodedPath.endsWith("/next") -> {
                    respond(
                        content = """
                            {
                                "contents": {
                                    "singleColumnMusicWatchNextResultsRenderer": {
                                        "tabbedRenderer": {
                                            "watchNextTabbedResultsRenderer": {
                                                "tabs": [
                                                    {
                                                        "tabRenderer": {
                                                            "title": "Lyrics",
                                                            "endpoint": {
                                                                "browseEndpoint": {
                                                                    "browseId": "MPLY_sample_123"
                                                                }
                                                            }
                                                        }
                                                    }
                                                ]
                                            }
                                        }
                                    }
                                }
                            }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }

                request.url.encodedPath.endsWith("/browse") -> {
                    respond(
                        content = """
                            {
                                "contents": {
                                    "sectionListRenderer": {
                                        "contents": [
                                            {
                                                "musicDescriptionShelfRenderer": {
                                                    "description": {
                                                        "runs": [
                                                            { "text": "Innertube plain lyrics line 1\nInnertube plain lyrics line 2" }
                                                        ]
                                                    },
                                                    "footer": {
                                                        "runs": [
                                                            { "text": "Source: Musixmatch" }
                                                        ]
                                                    }
                                                }
                                            }
                                        ]
                                    }
                                }
                            }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }

                else -> respond(
                    content = "{}",
                    status = HttpStatusCode.NotFound,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
        }

        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
        }

        val lrclibProvider = LrclibLyricsProvider(httpClient = client, json = json)
        val ytmProvider = YouTubeMusicProvider(
            httpClient = client,
            cipherDecryptor = CipherDecryptor(client),
            json = json,
        )
        val repository = LyricsRepository(
            lrclibProvider = lrclibProvider,
            youTubeMusicProvider = ytmProvider,
        )

        val result = repository.getLyrics(sampleTrack)
        assertTrue(result is AppResult.Success)
        val lyrics = (result as AppResult.Success).data
        assertNotNull(lyrics)
        assertEquals("video_999", lyrics?.trackId)
        assertEquals(false, lyrics?.isSynced)
        assertEquals(2, lyrics?.lines?.size)
        assertEquals("Innertube plain lyrics line 1", lyrics?.lines?.get(0)?.text)
        assertEquals("Source: Musixmatch", lyrics?.source)
    }
}
