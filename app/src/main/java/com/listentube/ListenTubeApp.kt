// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube

import android.app.Application
import androidx.room.Room
import com.listentube.data.innertube.YouTubeMusicProvider
import com.listentube.data.innertube.cipher.CipherDecryptor
import com.listentube.data.lyrics.LrclibLyricsProvider
import com.listentube.data.lyrics.LyricsRepository
import com.listentube.data.listenbrainz.local.ScrobbleDatabase
import com.listentube.data.listenbrainz.remote.ListenBrainzApiClient
import com.listentube.data.localmedia.LocalMediaStoreProvider
import com.listentube.data.preferences.DataStoreScrobblePreferencesStore
import com.listentube.domain.scrobble.ScrobbleManager
import com.listentube.service.playback.MusicPlaybackService

/**
 * Application-level container wiring networking, Room offline scrobble persistence,
 * YouTube Music extraction, local MediaStore querying, and [MusicPlaybackService] dependencies.
 */
class ListenTubeApp : Application() {

    lateinit var preferencesStore: DataStoreScrobblePreferencesStore
        private set

    lateinit var database: ScrobbleDatabase
        private set

    lateinit var listenBrainzApiClient: ListenBrainzApiClient
        private set

    lateinit var scrobbleManager: ScrobbleManager
        private set

    lateinit var youTubeMusicProvider: YouTubeMusicProvider
        private set

    lateinit var localMediaStoreProvider: LocalMediaStoreProvider
        private set

    lateinit var lyricsRepository: LyricsRepository
        private set

    override fun onCreate() {
        super.onCreate()

        val json = ListenBrainzApiClient.createDefaultJson()
        val httpClient = ListenBrainzApiClient.createDefaultHttpClient(json)

        preferencesStore = DataStoreScrobblePreferencesStore(applicationContext)

        database = Room.databaseBuilder(
            applicationContext,
            ScrobbleDatabase::class.java,
            ScrobbleDatabase.DATABASE_NAME,
        ).fallbackToDestructiveMigration().build()

        listenBrainzApiClient = ListenBrainzApiClient(
            httpClient = httpClient,
            json = json,
        )

        scrobbleManager = ScrobbleManager(
            apiClient = listenBrainzApiClient,
            scrobbleDao = database.scrobbleDao(),
            userTokenProvider = { preferencesStore.getUserToken() },
            scrobblingEnabledProvider = { preferencesStore.isScrobblingEnabled() },
        )

        val cipherDecryptor = CipherDecryptor(httpClient = httpClient)
        youTubeMusicProvider = YouTubeMusicProvider(
            httpClient = httpClient,
            cipherDecryptor = cipherDecryptor,
            json = json,
        )

        localMediaStoreProvider = LocalMediaStoreProvider(
            contentResolver = applicationContext.contentResolver,
        )

        val lrclibProvider = LrclibLyricsProvider(
            httpClient = httpClient,
            json = json,
        )
        lyricsRepository = LyricsRepository(
            lrclibProvider = lrclibProvider,
            youTubeMusicProvider = youTubeMusicProvider,
        )

        MusicPlaybackService.dependencyProvider = {
            MusicPlaybackService.Dependencies(
                youTubeMusicProvider = youTubeMusicProvider,
                localMediaStoreProvider = localMediaStoreProvider,
                scrobbleManager = scrobbleManager,
            )
        }
    }
}
