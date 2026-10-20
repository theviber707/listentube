// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube.data.localmedia

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMediaStoreProviderTest {

    @Test
    fun isJunkAudioFolder_identifiesWhatsAppAudioAndVoiceNotes() {
        assertTrue(
            LocalMediaStoreProvider.isJunkAudioFolder(
                folderPath = "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio",
                folderName = "WhatsApp Audio",
            ),
        )
        assertTrue(
            LocalMediaStoreProvider.isJunkAudioFolder(
                folderPath = "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/202341",
                folderName = "202341",
            ),
        )
    }

    @Test
    fun isJunkAudioFolder_identifiesTelegramAndRecordings() {
        assertTrue(
            LocalMediaStoreProvider.isJunkAudioFolder(
                folderPath = "/storage/emulated/0/Telegram/Telegram Audio",
                folderName = "Telegram Audio",
            ),
        )
        assertTrue(
            LocalMediaStoreProvider.isJunkAudioFolder(
                folderPath = "/storage/emulated/0/Recordings/Call",
                folderName = "Call",
            ),
        )
        assertTrue(
            LocalMediaStoreProvider.isJunkAudioFolder(
                folderPath = "/storage/emulated/0/Notifications",
                folderName = "Notifications",
            ),
        )
    }

    @Test
    fun isJunkAudioFolder_allowsLegitimateMusicFolders() {
        assertFalse(
            LocalMediaStoreProvider.isJunkAudioFolder(
                folderPath = "/storage/emulated/0/Music/Pink Floyd/The Wall",
                folderName = "The Wall",
            ),
        )
        assertFalse(
            LocalMediaStoreProvider.isJunkAudioFolder(
                folderPath = "/storage/emulated/0/Download/Albums",
                folderName = "Albums",
            ),
        )
    }
}
