// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.listentube.service.playback.MusicPlaybackService
import com.listentube.ui.main.MainScreen
import com.listentube.ui.player.PlayerViewModel
import com.listentube.ui.theme.ListenTubeTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Primary entry-point Activity that binds a Media3 [MediaController] to [MusicPlaybackService],
 * requests audio media permissions when necessary, and renders [MainScreen].
 */
class MainActivity : ComponentActivity() {

    private val playerViewModel: PlayerViewModel by viewModels {
        val app = application as ListenTubeApp
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return PlayerViewModel(
                    youTubeMusicProvider = app.youTubeMusicProvider,
                    localMediaStoreProvider = app.localMediaStoreProvider,
                    listenBrainzApiClient = app.listenBrainzApiClient,
                    scrobbleManager = app.scrobbleManager,
                    preferencesStore = app.preferencesStore,
                    lyricsRepository = app.lyricsRepository,
                ) as T
            }
        }
    }

    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.any { it }) {
            playerViewModel.loadLocalLibraryTracks()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestAudioPermissionsIfNeeded()

        setContent {
            val artworkUrl by remember(playerViewModel) {
                playerViewModel.uiState
                    .map { it.currentTrack?.artworkUrl }
                    .distinctUntilChanged()
            }.collectAsState(initial = null)

            ListenTubeTheme(artworkUrl = artworkUrl) {
                MainScreen(viewModel = playerViewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val sessionToken = SessionToken(
            this,
            ComponentName(this, MusicPlaybackService::class.java),
        )
        val future = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                runCatching { future.get() }
                    .getOrNull()
                    ?.let { controller ->
                        playerViewModel.attachPlayer(controller)
                    }
            },
            MoreExecutors.directExecutor(),
        )
    }

    override fun onStop() {
        playerViewModel.detachPlayer()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    private fun requestAudioPermissionsIfNeeded() {
        val permissionsToRequest = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.READ_MEDIA_AUDIO,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    add(Manifest.permission.READ_MEDIA_AUDIO)
                }
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            } else {
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
        }
        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
}
