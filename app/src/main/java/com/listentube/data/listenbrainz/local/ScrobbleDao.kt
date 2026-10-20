// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube.data.listenbrainz.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.listentube.data.listenbrainz.model.ScrobbleTrack
import kotlinx.coroutines.flow.Flow

/**
 * Distinguishes between a transient `playing_now` state notification and a permanent
 * completed listen record in the offline Room queue.
 */
enum class QueuedListenType(val dbValue: String) {
    PLAYING_NOW("playing_now"),
    COMPLETED("completed"),
    ;

    companion object {
        fun fromDbValue(value: String): QueuedListenType =
            entries.firstOrNull { it.dbValue == value } ?: COMPLETED
    }
}

/**
 * Room type converters for [QueuedListenType].
 */
class ScrobbleTypeConverters {
    @TypeConverter
    fun fromQueuedListenType(type: QueuedListenType): String = type.dbValue

    @TypeConverter
    fun toQueuedListenType(value: String): QueuedListenType = QueuedListenType.fromDbValue(value)
}

/**
 * Persistent Room entity representing either a queued `playing_now` notification or
 * a queued completed listen (`single` / `import`) awaiting delivery to ListenBrainz.
 *
 * - For [QueuedListenType.PLAYING_NOW]:
 *   - [listenedAtEpochSeconds] is `null` (as required by the ListenBrainz spec).
 *   - [expiresAtEpochMs] bounds how long the `playing_now` notification remains valid while offline.
 *   - At most one `playing_now` row is kept at any time via [ScrobbleDao.replacePlayingNow].
 * - For [QueuedListenType.COMPLETED]:
 *   - [listenedAtEpochSeconds] stores the exact Unix epoch timestamp (in seconds) when playback started.
 *   - Deduplicated on `(video_id, listened_at)` so repeated queue insertions of the same play session are idempotent.
 */
@Entity(
    tableName = "scrobble_queue",
    indices = [
        Index(value = ["listen_type", "next_retry_at_ms"]),
        Index(value = ["video_id", "listened_at"], unique = true),
    ],
)
data class QueuedScrobbleEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "listen_type")
    val listenType: QueuedListenType,

    @ColumnInfo(name = "video_id")
    val videoId: String,

    @ColumnInfo(name = "artist_name")
    val artistName: String,

    @ColumnInfo(name = "track_name")
    val trackName: String,

    @ColumnInfo(name = "release_name")
    val releaseName: String? = null,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,

    @ColumnInfo(name = "track_number")
    val trackNumber: Int? = null,

    @ColumnInfo(name = "recording_mbid")
    val recordingMbid: String? = null,

    @ColumnInfo(name = "release_mbid")
    val releaseMbid: String? = null,

    @ColumnInfo(name = "artist_mbids_csv")
    val artistMbidsCsv: String? = null,

    @ColumnInfo(name = "app_version")
    val appVersion: String? = null,

    @ColumnInfo(name = "listened_at")
    val listenedAtEpochSeconds: Long? = null,

    @ColumnInfo(name = "created_at_ms")
    val createdAtEpochMs: Long,

    @ColumnInfo(name = "expires_at_ms")
    val expiresAtEpochMs: Long? = null,

    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,

    @ColumnInfo(name = "last_attempt_at_ms")
    val lastAttemptAtEpochMs: Long? = null,

    @ColumnInfo(name = "next_retry_at_ms")
    val nextRetryAtEpochMs: Long = 0L,

    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
) {
    /**
     * Reconstructs the domain [ScrobbleTrack] from this persisted entity.
     */
    fun toScrobbleTrack(): ScrobbleTrack = ScrobbleTrack(
        videoId = videoId,
        artistName = artistName,
        trackName = trackName,
        durationMs = durationMs,
        releaseName = releaseName,
        trackNumber = trackNumber,
        recordingMbid = recordingMbid,
        releaseMbid = releaseMbid,
        artistMbids = artistMbidsCsv
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() },
        appVersion = appVersion,
    )

    companion object {
        const val DEFAULT_PLAYING_NOW_TTL_MS = 10 * 60 * 1_000L // 10 minutes fallback TTL

        /**
         * Creates a [QueuedScrobbleEntity] for an offline `playing_now` submission.
         */
        fun forPlayingNow(
            track: ScrobbleTrack,
            nowEpochMs: Long,
            nextRetryAtEpochMs: Long = nowEpochMs,
            lastError: String? = null,
        ): QueuedScrobbleEntity {
            val ttlMs = track.durationMs.takeIf { it > 0L } ?: DEFAULT_PLAYING_NOW_TTL_MS
            return QueuedScrobbleEntity(
                listenType = QueuedListenType.PLAYING_NOW,
                videoId = track.videoId,
                artistName = track.artistName,
                trackName = track.trackName,
                releaseName = track.releaseName,
                durationMs = track.durationMs,
                trackNumber = track.trackNumber,
                recordingMbid = track.recordingMbid,
                releaseMbid = track.releaseMbid,
                artistMbidsCsv = track.artistMbids?.joinToString(","),
                appVersion = track.appVersion,
                listenedAtEpochSeconds = null,
                createdAtEpochMs = nowEpochMs,
                expiresAtEpochMs = nowEpochMs + ttlMs,
                attemptCount = 0,
                nextRetryAtEpochMs = nextRetryAtEpochMs,
                lastError = lastError,
            )
        }

        /**
         * Creates a [QueuedScrobbleEntity] for a completed listen (`single` or `import`).
         */
        fun forCompletedListen(
            track: ScrobbleTrack,
            listenedAtEpochSeconds: Long,
            nowEpochMs: Long,
            nextRetryAtEpochMs: Long = nowEpochMs,
            lastError: String? = null,
        ): QueuedScrobbleEntity = QueuedScrobbleEntity(
            listenType = QueuedListenType.COMPLETED,
            videoId = track.videoId,
            artistName = track.artistName,
            trackName = track.trackName,
            releaseName = track.releaseName,
            durationMs = track.durationMs,
            trackNumber = track.trackNumber,
            recordingMbid = track.recordingMbid,
            releaseMbid = track.releaseMbid,
            artistMbidsCsv = track.artistMbids?.joinToString(","),
            appVersion = track.appVersion,
            listenedAtEpochSeconds = listenedAtEpochSeconds,
            createdAtEpochMs = nowEpochMs,
            expiresAtEpochMs = null,
            attemptCount = 0,
            nextRetryAtEpochMs = nextRetryAtEpochMs,
            lastError = lastError,
        )
    }
}

/**
 * Data Access Object managing offline persistence and retry scheduling for both
 * `playing_now` notifications and completed listen scrobbles.
 */
@Dao
interface ScrobbleDao {

    // -------------------------------------------------------------------------
    // playing_now Offline Queue Operations
    // -------------------------------------------------------------------------

    /**
     * Deletes all queued `playing_now` records. Only the latest active track should ever
     * be queued as `playing_now`.
     */
    @Query("DELETE FROM scrobble_queue WHERE listen_type = :playingNowType")
    suspend fun clearPlayingNowQueue(
        playingNowType: QueuedListenType = QueuedListenType.PLAYING_NOW,
    ): Int

    /**
     * Deletes any queued `playing_now` records whose TTL window [QueuedScrobbleEntity.expiresAtEpochMs]
     * has elapsed.
     */
    @Query(
        """
        DELETE FROM scrobble_queue
        WHERE listen_type = :playingNowType
          AND expires_at_ms IS NOT NULL
          AND expires_at_ms <= :nowEpochMs
        """,
    )
    suspend fun pruneExpiredPlayingNow(
        nowEpochMs: Long,
        playingNowType: QueuedListenType = QueuedListenType.PLAYING_NOW,
    ): Int

    /**
     * Inserts a queue entity, replacing on conflict.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(entity: QueuedScrobbleEntity): Long

    /**
     * Atomically clears any previous `playing_now` entry and enqueues [entity] as the sole
     * pending `playing_now` item.
     */
    @Transaction
    suspend fun replacePlayingNow(entity: QueuedScrobbleEntity): Long {
        require(entity.listenType == QueuedListenType.PLAYING_NOW) {
            "replacePlayingNow requires listenType == PLAYING_NOW"
        }
        clearPlayingNowQueue()
        return insertOrReplace(entity)
    }

    /**
     * Returns the currently queued `playing_now` entity if it has not expired and is eligible
     * for retry at [nowEpochMs].
     */
    @Query(
        """
        SELECT * FROM scrobble_queue
        WHERE listen_type = :playingNowType
          AND (expires_at_ms IS NULL OR expires_at_ms > :nowEpochMs)
          AND next_retry_at_ms <= :nowEpochMs
        ORDER BY created_at_ms DESC
        LIMIT 1
        """,
    )
    suspend fun getReadyPlayingNow(
        nowEpochMs: Long,
        playingNowType: QueuedListenType = QueuedListenType.PLAYING_NOW,
    ): QueuedScrobbleEntity?

    /**
     * Returns the latest queued `playing_now` entity regardless of its retry backoff timestamp.
     */
    @Query(
        """
        SELECT * FROM scrobble_queue
        WHERE listen_type = :playingNowType
        ORDER BY created_at_ms DESC
        LIMIT 1
        """,
    )
    suspend fun peekPlayingNow(
        playingNowType: QueuedListenType = QueuedListenType.PLAYING_NOW,
    ): QueuedScrobbleEntity?

    // -------------------------------------------------------------------------
    // Completed Listens (single / import) Offline Queue Operations
    // -------------------------------------------------------------------------

    /**
     * Enqueues a completed listen into the offline Room table.
     * Uses [OnConflictStrategy.IGNORE] on the unique `(video_id, listened_at)` index
     * so duplicate submissions of the same playback session are ignored idempotently.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCompletedListen(entity: QueuedScrobbleEntity): Long

    /**
     * Fetches up to [limit] completed listens whose backoff timer (`next_retry_at_ms`) has elapsed,
     * ordered chronologically by `listened_at ASC`.
     */
    @Query(
        """
        SELECT * FROM scrobble_queue
        WHERE listen_type = :completedType
          AND next_retry_at_ms <= :nowEpochMs
        ORDER BY listened_at ASC, id ASC
        LIMIT :limit
        """,
    )
    suspend fun getReadyCompletedListens(
        nowEpochMs: Long,
        limit: Int = DEFAULT_BATCH_LIMIT,
        completedType: QueuedListenType = QueuedListenType.COMPLETED,
    ): List<QueuedScrobbleEntity>

    /**
     * Returns all queued completed listens regardless of backoff timer, ordered oldest first.
     */
    @Query(
        """
        SELECT * FROM scrobble_queue
        WHERE listen_type = :completedType
        ORDER BY listened_at ASC, id ASC
        """,
    )
    suspend fun getAllCompletedListens(
        completedType: QueuedListenType = QueuedListenType.COMPLETED,
    ): List<QueuedScrobbleEntity>

    // -------------------------------------------------------------------------
    // Shared Queue Lifecycle, Backoff & Pruning Operations
    // -------------------------------------------------------------------------

    /**
     * Deletes a single queued item by its primary key [id].
     */
    @Query("DELETE FROM scrobble_queue WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    /**
     * Deletes a batch of successfully submitted items by their primary key [ids].
     */
    @Query("DELETE FROM scrobble_queue WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>): Int

    /**
     * Increments `attempt_count` and schedules the next retry timestamp for the given [ids].
     */
    @Query(
        """
        UPDATE scrobble_queue
        SET attempt_count = attempt_count + 1,
            last_attempt_at_ms = :attemptedAtMs,
            next_retry_at_ms = :nextRetryAtMs,
            last_error = :errorMessage
        WHERE id IN (:ids)
        """,
    )
    suspend fun recordFailedAttempt(
        ids: List<Long>,
        attemptedAtMs: Long,
        nextRetryAtMs: Long,
        errorMessage: String?,
    ): Int

    /**
     * Applies a global HTTP 429 `X-RateLimit-Reset-In` cooldown deadline across all queued items
     * whose `next_retry_at_ms` is earlier than [rateLimitResetAtEpochMs].
     */
    @Query(
        """
        UPDATE scrobble_queue
        SET next_retry_at_ms = :rateLimitResetAtEpochMs,
            last_error = :reason
        WHERE next_retry_at_ms < :rateLimitResetAtEpochMs
        """,
    )
    suspend fun applyGlobalRateLimitBackoff(
        rateLimitResetAtEpochMs: Long,
        reason: String,
    ): Int

    /**
     * Removes dead-letter entries that have exceeded [maxAttempts] retries.
     */
    @Query("DELETE FROM scrobble_queue WHERE attempt_count >= :maxAttempts")
    suspend fun pruneDeadLetters(maxAttempts: Int = MAX_RETRY_ATTEMPTS): Int

    /**
     * Returns the earliest `next_retry_at_ms` across all queued entries, or `null` if the queue is empty.
     */
    @Query("SELECT MIN(next_retry_at_ms) FROM scrobble_queue")
    suspend fun getEarliestNextRetryAtMs(): Long?

    /**
     * Reactive stream of the total number of queued items (`playing_now` + `completed`).
     */
    @Query("SELECT COUNT(*) FROM scrobble_queue")
    fun observeTotalQueuedCount(): Flow<Int>

    /**
     * Reactive stream of the number of pending completed scrobbles in the queue.
     */
    @Query("SELECT COUNT(*) FROM scrobble_queue WHERE listen_type = :completedType")
    fun observePendingCompletedCount(
        completedType: QueuedListenType = QueuedListenType.COMPLETED,
    ): Flow<Int>

    /**
     * Reactive stream of all pending completed listens in the offline queue, ordered newest first for UI display.
     */
    @Query(
        """
        SELECT * FROM scrobble_queue
        WHERE listen_type = :completedType
        ORDER BY listened_at DESC, id DESC
        """,
    )
    fun observePendingCompletedListens(
        completedType: QueuedListenType = QueuedListenType.COMPLETED,
    ): Flow<List<QueuedScrobbleEntity>>

    /**
     * Clears all pending completed listens from the offline queue.
     */
    @Query("DELETE FROM scrobble_queue WHERE listen_type = :completedType")
    suspend fun clearCompletedQueue(
        completedType: QueuedListenType = QueuedListenType.COMPLETED,
    ): Int

    /**
     * Reactive stream of the currently queued `playing_now` item, if any.
     */
    @Query(
        """
        SELECT * FROM scrobble_queue
        WHERE listen_type = :playingNowType
        ORDER BY created_at_ms DESC
        LIMIT 1
        """,
    )
    fun observeQueuedPlayingNow(
        playingNowType: QueuedListenType = QueuedListenType.PLAYING_NOW,
    ): Flow<QueuedScrobbleEntity?>

    companion object {
        const val DEFAULT_BATCH_LIMIT = 50
        const val MAX_RETRY_ATTEMPTS = 12
    }
}

/**
 * Room database housing the offline ListenBrainz scrobble queue.
 */
@Database(
    entities = [QueuedScrobbleEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(ScrobbleTypeConverters::class)
abstract class ScrobbleDatabase : RoomDatabase() {
    abstract fun scrobbleDao(): ScrobbleDao

    companion object {
        const val DATABASE_NAME = "listentube_scrobble.db"
    }
}
