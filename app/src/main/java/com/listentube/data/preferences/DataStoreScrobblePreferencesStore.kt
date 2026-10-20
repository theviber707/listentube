// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.listentube.ui.player.ScrobblePreferencesStore
import kotlinx.coroutines.flow.first

private val Context.listenTubeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "listentube_preferences",
)

/**
 * Persistent [ScrobblePreferencesStore] backed by Jetpack Preferences [DataStore].
 */
class DataStoreScrobblePreferencesStore(
    private val context: Context,
) : ScrobblePreferencesStore {

    override suspend fun getUserToken(): String? {
        val prefs = context.listenTubeDataStore.data.first()
        return prefs[KEY_USER_TOKEN]?.takeIf { it.isNotBlank() }
    }

    override suspend fun setUserToken(token: String, validatedUserName: String?) {
        val sanitized = token.trim()
        context.listenTubeDataStore.edit { prefs ->
            if (sanitized.isEmpty()) {
                prefs.remove(KEY_USER_TOKEN)
                prefs.remove(KEY_VALIDATED_USER_NAME)
            } else {
                prefs[KEY_USER_TOKEN] = sanitized
                if (!validatedUserName.isNullOrBlank()) {
                    prefs[KEY_VALIDATED_USER_NAME] = validatedUserName.trim()
                } else {
                    prefs.remove(KEY_VALIDATED_USER_NAME)
                }
            }
        }
    }

    override suspend fun getValidatedUserName(): String? {
        val prefs = context.listenTubeDataStore.data.first()
        return prefs[KEY_VALIDATED_USER_NAME]?.takeIf { it.isNotBlank() }
    }

    override suspend fun isScrobblingEnabled(): Boolean {
        val prefs = context.listenTubeDataStore.data.first()
        return prefs[KEY_SCROBBLING_ENABLED] ?: true
    }

    override suspend fun setScrobblingEnabled(enabled: Boolean) {
        context.listenTubeDataStore.edit { prefs ->
            prefs[KEY_SCROBBLING_ENABLED] = enabled
        }
    }

    override suspend fun getExcludedFolders(): Set<String>? {
        val prefs = context.listenTubeDataStore.data.first()
        return prefs[KEY_EXCLUDED_FOLDERS]
    }

    override suspend fun setExcludedFolders(folders: Set<String>) {
        context.listenTubeDataStore.edit { prefs ->
            prefs[KEY_EXCLUDED_FOLDERS] = folders
        }
    }

    companion object {
        private val KEY_USER_TOKEN = stringPreferencesKey("listenbrainz_user_token")
        private val KEY_VALIDATED_USER_NAME = stringPreferencesKey("listenbrainz_validated_username")
        private val KEY_SCROBBLING_ENABLED = booleanPreferencesKey("listenbrainz_scrobbling_enabled")
        private val KEY_EXCLUDED_FOLDERS = stringSetPreferencesKey("excluded_local_folders")
    }
}
