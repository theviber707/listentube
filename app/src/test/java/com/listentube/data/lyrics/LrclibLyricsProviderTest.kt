// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube.data.lyrics

import com.listentube.core.result.AppResult
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

class LrclibLyricsProviderTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val sampleTrack = MediaTrack(
        id = "track_123",
        title = "Infernal Death (Official Video)",
        artistName = "Death",
        albumName = "Scream Bloody Gore",
        durationMs = 174_000L,
        sourceType = MediaSourceType.YOUTUBE_MUSIC,
    )

    @Test
    fun fetchLyrics_exactMatchSynced_returnsSyncedLyrics() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals("/api/get", request.url.encodedPath)
            assertEquals("Infernal Death", request.url.parameters["track_name"])
            assertEquals("Death", request.url.parameters["artist_name"])

            respond(
                content = """
                    {
                        "id": 1,
                        "trackName": "Infernal Death",
                        "artistName": "Death",
                        "syncedLyrics": "[00:10.00]Piling the bodies\n[00:15.50]Burn them in the night",
                        "plainLyrics": "Piling the bodies\nBurn them in the night"
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
        }

        val provider = LrclibLyricsProvider(httpClient = client, json = json)
        val result = provider.fetchLyrics(sampleTrack)

        assertTrue(result is AppResult.Success)
        val lyrics = (result as AppResult.Success).data
        assertNotNull(lyrics)
        assertEquals("track_123", lyrics?.trackId)
        assertTrue(lyrics?.isSynced == true)
        assertEquals(2, lyrics?.lines?.size)
        assertEquals(10_000L, lyrics?.lines?.get(0)?.timeMs)
        assertEquals("Piling the bodies", lyrics?.lines?.get(0)?.text)
        assertEquals(15_500L, lyrics?.lines?.get(1)?.timeMs)
        assertEquals("Burn them in the night", lyrics?.lines?.get(1)?.text)
    }

    @Test
    fun fetchLyrics_notFoundOnGet_fallsBackToSearch() = runTest {
        val mockEngine = MockEngine { request ->
            if (request.url.encodedPath == "/api/get") {
                respond(
                    content = "{\"error\": \"Not Found\"}",
                    status = HttpStatusCode.NotFound,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            } else {
                assertEquals("/api/search", request.url.encodedPath)
                respond(
                    content = """
                        [
                            {
                                "id": 2,
                                "trackName": "Infernal Death",
                                "artistName": "Death",
                                "plainLyrics": "Line 1\nLine 2"
                            }
                        ]
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
        }

        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
        }

        val provider = LrclibLyricsProvider(httpClient = client, json = json)
        val result = provider.fetchLyrics(sampleTrack)

        assertTrue(result is AppResult.Success)
        val lyrics = (result as AppResult.Success).data
        assertNotNull(lyrics)
        assertEquals(false, lyrics?.isSynced)
        assertEquals(2, lyrics?.lines?.size)
        assertEquals("Line 1", lyrics?.lines?.get(0)?.text)
        assertEquals("Line 2", lyrics?.lines?.get(1)?.text)
    }
}
