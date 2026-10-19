// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube.core.result

import android.database.sqlite.SQLiteException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Typed domain error hierarchy representing all recoverable and non-recoverable
 * failures across networking, persistence, validation, and media extraction layers.
 */
sealed interface DomainError {
    val message: String
    val cause: Throwable?
        get() = null

    /**
     * Whether the operation that produced this error is transient and eligible
     * for automatic retry with backoff.
     */
    val isRetryable: Boolean
        get() = false

    /**
     * Whether a scrobble or listen submission that failed with this error should
     * be persisted in the local Room offline queue for later delivery.
     */
    val shouldQueueOffline: Boolean
        get() = isRetryable

    /**
     * Network and HTTP transport errors.
     */
    sealed interface Network : DomainError {

        /**
         * Device has no network connectivity or DNS resolution failed.
         */
        data class Offline(
            override val message: String = "No network connection available",
            override val cause: Throwable? = null,
        ) : Network {
            override val isRetryable: Boolean = true
            override val shouldQueueOffline: Boolean = true
        }

        /**
         * Socket connect, read, or write timeout expired.
         */
        data class Timeout(
            override val message: String = "Network request timed out",
            override val cause: Throwable? = null,
        ) : Network {
            override val isRetryable: Boolean = true
            override val shouldQueueOffline: Boolean = true
        }

        /**
         * HTTP 429 Too Many Requests or proactive `X-RateLimit-Remaining == 0` throttle.
         *
         * @property resetInSeconds Value parsed from ListenBrainz's `X-RateLimit-Reset-In` header.
         * @property limit Optional value parsed from `X-RateLimit-Limit`.
         * @property remaining Optional value parsed from `X-RateLimit-Remaining`.
         */
        data class RateLimited(
            val resetInSeconds: Long,
            val limit: Int? = null,
            val remaining: Int? = 0,
            override val message: String = "Rate limited by ListenBrainz; reset in ${resetInSeconds}s",
            override val cause: Throwable? = null,
        ) : Network {
            override val isRetryable: Boolean = true
            override val shouldQueueOffline: Boolean = true

            val resetInMillis: Long
                get() = resetInSeconds.coerceAtLeast(1L) * 1_000L
        }

        /**
         * HTTP 401 Unauthorized — user token is missing, revoked, or invalid.
         * Completed listens are still queued offline so the user does not lose listen history
         * before re-authenticating, while automatic retry is paused until token changes.
         */
        data class Unauthorized(
            override val message: String = "Invalid or unauthorized ListenBrainz user token",
            override val cause: Throwable? = null,
        ) : Network {
            override val isRetryable: Boolean = false
            override val shouldQueueOffline: Boolean = true
        }

        /**
         * HTTP 400 Bad Request — payload was rejected as malformed by ListenBrainz.
         * Per ListenBrainz API guidelines, malformed submissions must NOT be retried infinitely.
         */
        data class BadRequest(
            val httpCode: Int = 400,
            val apiErrorCode: Int? = null,
            override val message: String = "ListenBrainz rejected malformed submission payload",
            override val cause: Throwable? = null,
        ) : Network {
            override val isRetryable: Boolean = false
            override val shouldQueueOffline: Boolean = false
        }

        /**
         * HTTP 5xx Server Error (500, 502, 503, 504) — upstream ListenBrainz outage.
         */
        data class ServerError(
            val httpCode: Int,
            override val message: String = "ListenBrainz upstream server error (HTTP $httpCode)",
            override val cause: Throwable? = null,
        ) : Network {
            override val isRetryable: Boolean = true
            override val shouldQueueOffline: Boolean = true
        }

        /**
         * Any other non-2xx HTTP status code.
         */
        data class HttpError(
            val httpCode: Int,
            override val message: String = "HTTP error $httpCode",
            override val cause: Throwable? = null,
        ) : Network {
            override val isRetryable: Boolean = httpCode == 408 || httpCode in 500..599
            override val shouldQueueOffline: Boolean = isRetryable
        }

        /**
         * JSON serialization or deserialization failure.
         */
        data class Serialization(
            override val message: String = "Failed to serialize or deserialize network payload",
            override val cause: Throwable? = null,
        ) : Network {
            override val isRetryable: Boolean = false
            override val shouldQueueOffline: Boolean = false
        }

        /**
         * Unclassified transport error.
         */
        data class Unknown(
            override val message: String = "Unexpected network transport failure",
            override val cause: Throwable? = null,
        ) : Network {
            override val isRetryable: Boolean = true
            override val shouldQueueOffline: Boolean = true
        }
    }

    /**
     * Local database (Room / SQLite) and DataStore errors.
     */
    sealed interface Storage : DomainError {
        data class DatabaseError(
            override val message: String = "Local database operation failed",
            override val cause: Throwable? = null,
        ) : Storage

        data class DiskFull(
            override val message: String = "Device storage is full",
            override val cause: Throwable? = null,
        ) : Storage
    }

    /**
     * Domain-level input and invariant validation errors.
     */
    sealed interface Validation : DomainError {
        data class MissingToken(
            override val message: String = "ListenBrainz user token is not configured",
        ) : Validation {
            override val isRetryable: Boolean = false
            override val shouldQueueOffline: Boolean = true
        }

        data class InvalidMetadata(
            val field: String,
            override val message: String = "Required track metadata field '$field' is blank or invalid",
        ) : Validation {
            override val isRetryable: Boolean = false
            override val shouldQueueOffline: Boolean = false
        }

        data class TrackTooShort(
            val durationMs: Long,
            val minimumDurationMs: Long,
            override val message: String =
                "Track duration (${durationMs}ms) is shorter than minimum scrobble threshold (${minimumDurationMs}ms)",
        ) : Validation {
            override val isRetryable: Boolean = false
            override val shouldQueueOffline: Boolean = false
        }
    }

    /**
     * Media playback, YouTube Music Innertube extraction, cipher decryption, and local MediaStore errors.
     */
    sealed interface Playback : DomainError {
        data class StreamExtractionFailed(
            val videoId: String,
            override val message: String = "Failed to extract audio stream for video '$videoId'",
            override val cause: Throwable? = null,
        ) : Playback {
            override val isRetryable: Boolean = true
        }

        data class UnplayableVideo(
            val videoId: String,
            val playabilityStatus: String,
            val reason: String? = null,
            override val message: String = buildString {
                append("Video '$videoId' is not playable (status=$playabilityStatus")
                if (!reason.isNullOrBlank()) append(", reason=$reason")
                append(")")
            },
        ) : Playback {
            override val isRetryable: Boolean = false
        }

        data class NoAudioStreamFound(
            val videoId: String,
            override val message: String = "No compatible audio stream found for '$videoId'",
        ) : Playback {
            override val isRetryable: Boolean = false
        }

        data class PlayerJsFetchFailed(
            val playerJsUrl: String,
            override val message: String = "Failed to fetch YouTube player script from '$playerJsUrl'",
            override val cause: Throwable? = null,
        ) : Playback {
            override val isRetryable: Boolean = true
        }

        data class SignatureFunctionNotFound(
            val playerJsUrl: String,
            val detail: String,
            override val message: String = "Failed to parse cipher function from '$playerJsUrl': $detail",
            override val cause: Throwable? = null,
        ) : Playback {
            override val isRetryable: Boolean = false
        }

        data class CipherDecryptionFailed(
            val videoId: String? = null,
            val reason: String,
            override val message: String = buildString {
                append("Cipher decryption failed")
                if (!videoId.isNullOrBlank()) append(" for video '$videoId'")
                append(": $reason")
            },
            override val cause: Throwable? = null,
        ) : Playback {
            override val isRetryable: Boolean = false
        }

        data class PermissionDenied(
            val permission: String,
            override val message: String = "Missing required media permission: $permission",
            override val cause: Throwable? = null,
        ) : Playback {
            override val isRetryable: Boolean = false
        }

        data class LocalMediaQueryFailed(
            override val message: String = "Failed to query local MediaStore audio library",
            override val cause: Throwable? = null,
        ) : Playback {
            override val isRetryable: Boolean = false
        }

        data class PlayerError(
            override val message: String = "Media playback engine encountered an error",
            override val cause: Throwable? = null,
        ) : Playback
    }

    /**
     * Fallback for unexpected runtime exceptions.
     */
    data class Unexpected(
        override val message: String = "An unexpected error occurred",
        override val cause: Throwable? = null,
    ) : DomainError
}

/**
 * Maps arbitrary [Throwable] instances into structured [DomainError] values while
 * preserving structured concurrency by always rethrowing [CancellationException].
 */
fun Throwable.toDomainError(): DomainError {
    if (this is CancellationException) throw this
    return when (this) {
        is UnknownHostException,
        is ConnectException,
        is NoRouteToHostException,
        -> DomainError.Network.Offline(
            message = localizedMessage ?: "Unable to reach ListenBrainz server",
            cause = this,
        )

        is SocketTimeoutException,
        is InterruptedIOException,
        -> DomainError.Network.Timeout(
            message = localizedMessage ?: "Connection timed out",
            cause = this,
        )

        is SerializationException -> DomainError.Network.Serialization(
            message = localizedMessage ?: "Malformed JSON payload",
            cause = this,
        )

        is SQLiteException -> DomainError.Storage.DatabaseError(
            message = localizedMessage ?: "SQLite database failure",
            cause = this,
        )

        is IOException -> DomainError.Network.Offline(
            message = localizedMessage ?: "Network I/O failure",
            cause = this,
        )

        else -> DomainError.Unexpected(
            message = localizedMessage ?: this::class.simpleName ?: "Unknown error",
            cause = this,
        )
    }
}
