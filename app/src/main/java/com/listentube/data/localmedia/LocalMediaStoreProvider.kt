// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube.data.localmedia

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.listentube.core.result.AppResult
import com.listentube.core.result.DomainError
import com.listentube.core.result.appRunCatching
import com.listentube.core.result.flatMap
import com.listentube.core.result.map
import com.listentube.domain.media.MediaProvider
import com.listentube.domain.media.MediaSearchPage
import com.listentube.domain.media.MediaSourceType
import com.listentube.domain.media.MediaTrack
import com.listentube.domain.media.ResolvedMediaStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [MediaProvider] implementation backed by Android's local [MediaStore.Audio.Media] content provider.
 *
 * Explicitly propagates permission, SQLite/cursor, and missing-track errors via [AppResult.Failure]
 * without swallowing exceptions.
 */
class LocalMediaStoreProvider(
    private val contentResolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MediaProvider {

    override val sourceType: MediaSourceType = MediaSourceType.LOCAL_STORAGE

    /**
     * Searches local audio files matching [query] across title, artist, or album columns.
     */
    override suspend fun searchTracks(
        query: String,
        continuationToken: String?,
    ): AppResult<MediaSearchPage> = withContext(ioDispatcher) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            return@withContext AppResult.Failure(
                DomainError.Validation.InvalidMetadata("search query cannot be blank"),
            )
        }

        val offset = continuationToken?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val likePattern = "%$trimmedQuery%"
        val selection = buildString {
            append("${MediaStore.Audio.Media.IS_MUSIC} != 0")
            append(" AND ${MediaStore.Audio.Media.DURATION} >= ?")
            append(" AND (")
            append("${MediaStore.Audio.Media.TITLE} LIKE ?")
            append(" OR ${MediaStore.Audio.Media.ARTIST} LIKE ?")
            append(" OR ${MediaStore.Audio.Media.ALBUM} LIKE ?")
            append(")")
        }
        val selectionArgs = arrayOf(
            MIN_AUDIO_DURATION_MS.toString(),
            likePattern,
            likePattern,
            likePattern,
        )

        queryMediaStoreTracks(
            selection = selection,
            selectionArgs = selectionArgs,
            sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
            limit = MediaProvider.DEFAULT_PAGE_LIMIT,
            offset = offset,
        ).map { tracks ->
            val nextContinuation = if (tracks.size >= MediaProvider.DEFAULT_PAGE_LIMIT) {
                (offset + tracks.size).toString()
            } else {
                null
            }
            MediaSearchPage(
                tracks = tracks,
                continuationToken = nextContinuation,
            )
        }
    }

    /**
     * Queries canonical metadata for a single local audio track identified by its `MediaStore` row ID.
     */
    override suspend fun getTrackMetadata(trackId: String): AppResult<MediaTrack> =
        withContext(ioDispatcher) {
            val numericId = trackId.trim().toLongOrNull()
                ?: return@withContext AppResult.Failure(
                    DomainError.Validation.InvalidMetadata(
                        field = "trackId",
                        message = "Local MediaStore trackId must be a numeric ID, got '$trackId'",
                    ),
                )

            val selection = "${MediaStore.Audio.Media._ID} = ?"
            val selectionArgs = arrayOf(numericId.toString())

            queryMediaStoreTracks(
                selection = selection,
                selectionArgs = selectionArgs,
                sortOrder = null,
                limit = 1,
                offset = 0,
            ).flatMap { tracks ->
                val track = tracks.firstOrNull()
                if (track != null) {
                    AppResult.Success(track)
                } else {
                    AppResult.Failure(
                        DomainError.Playback.NoAudioStreamFound(
                            videoId = trackId,
                            message = "Local audio track '$trackId' not found in MediaStore",
                        ),
                    )
                }
            }
        }

    /**
     * Resolves a playable `content://` stream URI and verifying its readability for [trackId].
     */
    override suspend fun resolveAudioStream(trackId: String): AppResult<ResolvedMediaStream> =
        withContext(ioDispatcher) {
            getTrackMetadata(trackId).flatMap { track ->
                val contentUriString = track.contentUri
                    ?: return@flatMap AppResult.Failure(
                        DomainError.Playback.NoAudioStreamFound(
                            videoId = trackId,
                            message = "Missing content URI for local track '$trackId'",
                        ),
                    )

                appRunCatching(
                    errorMapper = { throwable -> mapLocalError(throwable, trackId) },
                ) {
                    val uri = Uri.parse(contentUriString)
                    // Verify that the underlying content URI can be opened by ContentResolver
                    val descriptor = contentResolver.openAssetFileDescriptor(uri, "r")
                        ?: throw IllegalStateException("ContentResolver returned null AssetFileDescriptor for '$uri'")
                    descriptor.use { afd ->
                        val verifiedLength = afd.length.takeIf { it > 0L } ?: track.fileSizeBytes
                        ResolvedMediaStream(
                            trackId = track.id,
                            streamUrl = contentUriString,
                            mimeType = track.mimeType ?: DEFAULT_AUDIO_MIME_TYPE,
                            bitrateBps = track.bitrateBps,
                            contentLengthBytes = verifiedLength,
                            expiresAtEpochSeconds = null,
                            sourceType = MediaSourceType.LOCAL_STORAGE,
                        )
                    }
                }
            }
        }

    /**
     * Lists local music tracks stored on the device with pagination ([limit] and [offset]).
     */
    override suspend fun getLibraryTracks(
        limit: Int,
        offset: Int,
    ): AppResult<List<MediaTrack>> = withContext(ioDispatcher) {
        if (limit <= 0 || offset < 0) {
            return@withContext AppResult.Failure(
                DomainError.Validation.InvalidMetadata("limit must be > 0 and offset >= 0"),
            )
        }

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= ?"
        val selectionArgs = arrayOf(MIN_AUDIO_DURATION_MS.toString())

        queryMediaStoreTracks(
            selection = selection,
            selectionArgs = selectionArgs,
            sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC",
            limit = limit,
            offset = offset,
        )
    }

    private fun queryMediaStoreTracks(
        selection: String,
        selectionArgs: Array<String>,
        sortOrder: String?,
        limit: Int,
        offset: Int,
    ): AppResult<List<MediaTrack>> {
        return appRunCatching(
            errorMapper = { throwable -> mapLocalError(throwable, trackId = null) },
        ) {
            val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }

            val projection = buildProjection()
            val cursor: Cursor = contentResolver.query(
                collectionUri,
                projection,
                selection,
                selectionArgs,
                sortOrder,
            ) ?: throw IllegalStateException(
                "ContentResolver.query returned null cursor for '$collectionUri'",
            )

            cursor.use { c ->
                readTracksFromCursor(cursor = c, collectionUri = collectionUri, limit = limit, offset = offset)
            }
        }
    }

    private fun readTracksFromCursor(
        cursor: Cursor,
        collectionUri: Uri,
        limit: Int,
        offset: Int,
    ): List<MediaTrack> {
        if (!cursor.moveToPosition(offset)) {
            return emptyList()
        }

        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val trackNumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
        val yearCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
        val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
        val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
        val bucketCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cursor.getColumnIndex(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
        } else {
            -1
        }
        val relativeCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
        } else {
            -1
        }
        val bitrateCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            cursor.getColumnIndex(MediaStore.Audio.Media.BITRATE)
        } else {
            -1
        }

        val results = ArrayList<MediaTrack>(minOf(limit, cursor.count - offset))
        var count = 0
        do {
            val id = cursor.getLong(idCol)
            val rawTitle = cursor.getString(titleCol)?.trim().orEmpty()
            val rawArtist = cursor.getString(artistCol)?.trim().orEmpty()
            val rawAlbum = cursor.getString(albumCol)?.trim()
            val albumId = cursor.getLong(albumIdCol)
            val durationMs = cursor.getLong(durationCol).coerceAtLeast(0L)
            val rawTrackNumber = cursor.getInt(trackNumCol)
            val normalizedTrackNumber = (rawTrackNumber % 1_000).takeIf { it > 0 }
            val year = cursor.getInt(yearCol).takeIf { it > 0 }
            val mimeType = cursor.getString(mimeCol)?.takeIf { it.isNotBlank() } ?: DEFAULT_AUDIO_MIME_TYPE
            val sizeBytes = cursor.getLong(sizeCol).takeIf { it > 0L }
            val bitrateBps = if (bitrateCol >= 0 && !cursor.isNull(bitrateCol)) {
                cursor.getInt(bitrateCol).takeIf { it > 0 }
            } else {
                null
            }

            val dataPath = if (dataCol >= 0 && !cursor.isNull(dataCol)) cursor.getString(dataCol)?.trim() else null
            val rawBucket = if (bucketCol >= 0 && !cursor.isNull(bucketCol)) cursor.getString(bucketCol)?.trim() else null
            val rawRelative = if (relativeCol >= 0 && !cursor.isNull(relativeCol)) cursor.getString(relativeCol)?.trim() else null

            val resolvedFolderPath = dataPath?.let { java.io.File(it).parent }
                ?: rawRelative?.trimEnd('/')
                ?: rawBucket
                ?: "Music"
            val resolvedFolderName = rawBucket
                ?: rawRelative?.trim('/')?.substringAfterLast('/')
                ?: dataPath?.let { java.io.File(it).parentFile?.name }
                ?: "Music"

            val contentUri = ContentUris.withAppendedId(collectionUri, id).toString()
            val artworkUri = if (albumId > 0L) {
                ContentUris.withAppendedId(ALBUM_ART_BASE_URI, albumId).toString()
            } else {
                null
            }

            results += MediaTrack(
                id = id.toString(),
                title = rawTitle.ifEmpty { "Track $id" },
                artistName = if (rawArtist.isEmpty() || rawArtist == MediaStore.UNKNOWN_STRING) {
                    UNKNOWN_ARTIST
                } else {
                    rawArtist
                },
                albumName = rawAlbum?.takeIf { it.isNotEmpty() && it != MediaStore.UNKNOWN_STRING },
                durationMs = durationMs,
                artworkUrl = artworkUri,
                trackNumber = normalizedTrackNumber,
                releaseYear = year,
                sourceType = MediaSourceType.LOCAL_STORAGE,
                contentUri = contentUri,
                mimeType = mimeType,
                bitrateBps = bitrateBps,
                fileSizeBytes = sizeBytes,
                folderPath = resolvedFolderPath,
                folderName = resolvedFolderName,
            )
            count += 1
        } while (count < limit && cursor.moveToNext())

        return results
    }

    private fun buildProjection(): Array<String> {
        val baseColumns = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATA,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            baseColumns += MediaStore.Audio.Media.BUCKET_DISPLAY_NAME
            baseColumns += MediaStore.Audio.Media.RELATIVE_PATH
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            baseColumns += MediaStore.Audio.Media.BITRATE
        }
        return baseColumns.toTypedArray()
    }

    private fun mapLocalError(
        throwable: Throwable,
        trackId: String?,
    ): DomainError = when (throwable) {
        is SecurityException -> DomainError.Playback.PermissionDenied(
            permission = requiredAudioPermissionName(),
            message = throwable.localizedMessage ?: "Storage/audio permission denied by OS",
            cause = throwable,
        )

        is java.io.FileNotFoundException -> DomainError.Playback.NoAudioStreamFound(
            videoId = trackId ?: "unknown",
            message = throwable.localizedMessage ?: "Local audio file no longer exists on disk",
        )

        else -> DomainError.Playback.LocalMediaQueryFailed(
            message = throwable.localizedMessage ?: "Failed to query local MediaStore",
            cause = throwable,
        )
    }

    private fun requiredAudioPermissionName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "android.permission.READ_MEDIA_AUDIO"
        } else {
            "android.permission.READ_EXTERNAL_STORAGE"
        }

    companion object {
        const val MIN_AUDIO_DURATION_MS = 1_000L
        const val DEFAULT_AUDIO_MIME_TYPE = "audio/*"
        private const val UNKNOWN_ARTIST = "Unknown Artist"

        private val ALBUM_ART_BASE_URI: Uri by lazy {
            Uri.parse("content://media/external/audio/albumart")
        }

        /**
         * Identifies default junk audio directories (WhatsApp voice notes, call recordings, ringtones, etc.)
         * so the local library excludes them out of the box unless explicitly enabled by the user.
         */
        fun isJunkAudioFolder(folderPath: String, folderName: String): Boolean {
            val lowerPath = folderPath.lowercase()
            val lowerName = folderName.lowercase()
            return lowerPath.contains("whatsapp") ||
                lowerName.contains("whatsapp") ||
                lowerPath.contains("telegram") ||
                lowerName.contains("telegram") ||
                lowerPath.contains("voice note") ||
                lowerPath.contains("voicenote") ||
                lowerPath.contains("/call_rec") ||
                lowerPath.contains("/recordings") ||
                lowerPath.contains("/recording") ||
                lowerPath.contains("/notifications") ||
                lowerPath.contains("/ringtones") ||
                lowerPath.contains("/alarms") ||
                lowerName.startsWith(".")
        }
    }
}
