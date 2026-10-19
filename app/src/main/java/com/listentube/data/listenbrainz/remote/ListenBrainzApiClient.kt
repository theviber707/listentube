// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube.data.listenbrainz.remote

import android.os.SystemClock
import com.listentube.core.result.AppResult
import com.listentube.core.result.DomainError
import com.listentube.core.result.appRunCatching
import com.listentube.data.listenbrainz.model.ListenBrainzErrorResponse
import com.listentube.data.listenbrainz.model.ListenType
import com.listentube.data.listenbrainz.model.RateLimitInfo
import com.listentube.data.listenbrainz.model.ScrobbleTrack
import com.listentube.data.listenbrainz.model.SubmitListensRequest
import com.listentube.data.listenbrainz.model.SubmitListensResponse
import com.listentube.data.listenbrainz.model.ValidateTokenResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the ListenBrainz Core API (`https://api.listenbrainz.org/1/`).
 *
 * Implements:
 * - `POST /1/submit-listens` for `playing_now`, `single`, and `import` payloads.
 * - `GET /1/validate-token` for verifying user tokens.
 * - Reactive & proactive rate-limit tracking using ListenBrainz's `X-RateLimit-Reset-In`,
 *   `X-RateLimit-Remaining`, and `X-RateLimit-Limit` headers.
 */
class ListenBrainzApiClient(
    private val httpClient: HttpClient = createDefaultHttpClient(),
    private val json: Json = createDefaultJson(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val elapsedRealtimeMsProvider: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val _rateLimitState = MutableStateFlow(RateLimitInfo())

    /**
     * Observable snapshot of the latest ListenBrainz rate-limit state and active cooldown window.
     */
    val rateLimitState: StateFlow<RateLimitInfo> = _rateLimitState.asStateFlow()

    /**
     * Submits a `playing_now` notification for [track].
     * Ensures `listened_at` is omitted (`null`) as required by the ListenBrainz specification.
     */
    suspend fun submitPlayingNow(
        userToken: String,
        track: ScrobbleTrack,
    ): AppResult<SubmitListensResponse> {
        when (val validation = track.validateMetadata()) {
            is AppResult.Failure -> return validation
            is AppResult.Success -> Unit
        }
        val request = SubmitListensRequest(
            listenType = ListenType.PLAYING_NOW,
            payload = listOf(track.toPlayingNowPayload()),
        )
        return submitListens(userToken = userToken, request = request)
    }

    /**
     * Submits a single completed listen (`listen_type = "single"`) with [listenedAtEpochSeconds].
     */
    suspend fun submitSingleListen(
        userToken: String,
        track: ScrobbleTrack,
        listenedAtEpochSeconds: Long,
    ): AppResult<SubmitListensResponse> {
        when (val validation = track.validateMetadata()) {
            is AppResult.Failure -> return validation
            is AppResult.Success -> Unit
        }
        if (listenedAtEpochSeconds <= 0L) {
            return AppResult.Failure(
                DomainError.Validation.InvalidMetadata("listened_at must be a positive epoch timestamp"),
            )
        }
        val request = SubmitListensRequest(
            listenType = ListenType.SINGLE,
            payload = listOf(track.toCompletedPayload(listenedAtEpochSeconds)),
        )
        return submitListens(userToken = userToken, request = request)
    }

    /**
     * Submits a batch of completed listens (`listen_type = "import"` or `"single"` when size == 1).
     */
    suspend fun submitImportBatch(
        userToken: String,
        completedListens: List<Pair<ScrobbleTrack, Long>>,
    ): AppResult<SubmitListensResponse> {
        if (completedListens.isEmpty()) {
            return AppResult.Success(SubmitListensResponse(status = "ok"))
        }
        if (completedListens.size == 1) {
            val (track, listenedAt) = completedListens.first()
            return submitSingleListen(userToken, track, listenedAt)
        }

        val payloads = completedListens.map { (track, listenedAtEpochSeconds) ->
            when (val validation = track.validateMetadata()) {
                is AppResult.Failure -> return validation
                is AppResult.Success -> Unit
            }
            if (listenedAtEpochSeconds <= 0L) {
                return AppResult.Failure(
                    DomainError.Validation.InvalidMetadata("listened_at must be a positive epoch timestamp"),
                )
            }
            track.toCompletedPayload(listenedAtEpochSeconds)
        }

        val request = SubmitListensRequest(
            listenType = ListenType.IMPORT,
            payload = payloads,
        )
        return submitListens(userToken = userToken, request = request)
    }

    /**
     * Executes `POST /1/submit-listens` with full rate-limit gatekeeping and error mapping.
     */
    suspend fun submitListens(
        userToken: String,
        request: SubmitListensRequest,
    ): AppResult<SubmitListensResponse> {
        val sanitizedToken = userToken.trim()
        if (sanitizedToken.isEmpty()) {
            return AppResult.Failure(DomainError.Validation.MissingToken())
        }

        val gateError = checkActiveRateLimitGate()
        if (gateError != null) {
            return AppResult.Failure(gateError)
        }

        return appRunCatching {
            val response = httpClient.post("${baseUrl.trimEnd('/')}$ENDPOINT_SUBMIT_LISTENS") {
                header(HttpHeaders.Authorization, "Token $sanitizedToken")
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                setBody(request)
            }
            handleHttpResponse<SubmitListensResponse>(response)
        }.let { outer ->
            when (outer) {
                is AppResult.Success -> outer.data
                is AppResult.Failure -> outer
            }
        }
    }

    /**
     * Executes `GET /1/validate-token` to verify whether [userToken] is valid on ListenBrainz.
     */
    suspend fun validateToken(userToken: String): AppResult<ValidateTokenResponse> {
        val sanitizedToken = userToken.trim()
        if (sanitizedToken.isEmpty()) {
            return AppResult.Failure(DomainError.Validation.MissingToken())
        }

        val gateError = checkActiveRateLimitGate()
        if (gateError != null) {
            return AppResult.Failure(gateError)
        }

        return appRunCatching {
            val response = httpClient.get("${baseUrl.trimEnd('/')}$ENDPOINT_VALIDATE_TOKEN") {
                header(HttpHeaders.Authorization, "Token $sanitizedToken")
                accept(ContentType.Application.Json)
            }
            handleHttpResponse<ValidateTokenResponse>(response)
        }.let { outer ->
            when (outer) {
                is AppResult.Success -> outer.data
                is AppResult.Failure -> outer
            }
        }
    }

    /**
     * Checks whether the client is currently inside an active rate-limit backoff window.
     */
    private fun checkActiveRateLimitGate(): DomainError.Network.RateLimited? {
        val nowElapsedMs = elapsedRealtimeMsProvider()
        val snapshot = _rateLimitState.value
        if (snapshot.isCoolingDown(nowElapsedMs)) {
            val remainingSeconds = snapshot.remainingCooldownSeconds(nowElapsedMs)
            return DomainError.Network.RateLimited(
                resetInSeconds = remainingSeconds,
                limit = snapshot.limit,
                remaining = snapshot.remaining ?: 0,
                message = "ListenBrainz rate limit active; waiting ${remainingSeconds}s before next request",
            )
        }
        return null
    }

    /**
     * Inspects rate-limit headers, status code, and body to produce a typed [AppResult].
     */
    private suspend inline fun <reified T> handleHttpResponse(response: HttpResponse): AppResult<T> {
        val nowElapsedMs = elapsedRealtimeMsProvider()
        val limit = response.headers[HEADER_RATE_LIMIT]?.toIntOrNull()
        val remaining = response.headers[HEADER_RATE_REMAINING]?.toIntOrNull()
        val resetInHeaderSeconds = response.headers[HEADER_RATE_RESET_IN]?.toLongOrNull()
            ?: response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()

        val status = response.status
        val isRateLimited = status == HttpStatusCode.TooManyRequests
        val isWindowExhausted = remaining != null && remaining <= 0

        val effectiveResetSeconds = when {
            isRateLimited -> (resetInHeaderSeconds ?: DEFAULT_RATE_LIMIT_RESET_SECONDS)
                .coerceIn(MIN_RATE_LIMIT_RESET_SECONDS, MAX_RATE_LIMIT_RESET_SECONDS)

            isWindowExhausted && resetInHeaderSeconds != null ->
                resetInHeaderSeconds.coerceIn(MIN_RATE_LIMIT_RESET_SECONDS, MAX_RATE_LIMIT_RESET_SECONDS)

            else -> resetInHeaderSeconds
        }

        val cooldownDeadlineMs = if (isRateLimited || (isWindowExhausted && effectiveResetSeconds != null)) {
            nowElapsedMs + (effectiveResetSeconds ?: DEFAULT_RATE_LIMIT_RESET_SECONDS) * 1_000L
        } else {
            0L
        }

        _rateLimitState.update {
            RateLimitInfo(
                limit = limit ?: it.limit,
                remaining = remaining,
                resetInSeconds = effectiveResetSeconds,
                resetAtElapsedMs = maxOf(it.resetAtElapsedMs, cooldownDeadlineMs),
            )
        }

        val rawBody = response.bodyAsText()

        if (status.isSuccess()) {
            return try {
                AppResult.Success(json.decodeFromString<T>(rawBody))
            } catch (e: Exception) {
                AppResult.Failure(
                    DomainError.Network.Serialization(
                        message = "Failed to decode ListenBrainz response: ${e.message}",
                        cause = e,
                    ),
                )
            }
        }

        val parsedError = runCatching {
            json.decodeFromString<ListenBrainzErrorResponse>(rawBody)
        }.getOrNull()
        val serverMessage = parsedError?.error?.takeIf { it.isNotBlank() }
            ?: rawBody.take(256).ifBlank { status.description }

        return when (status) {
            HttpStatusCode.TooManyRequests -> {
                val backoffSeconds = effectiveResetSeconds ?: DEFAULT_RATE_LIMIT_RESET_SECONDS
                AppResult.Failure(
                    DomainError.Network.RateLimited(
                        resetInSeconds = backoffSeconds,
                        limit = limit,
                        remaining = remaining ?: 0,
                        message = "HTTP 429 Too Many Requests ($serverMessage); X-RateLimit-Reset-In=${backoffSeconds}s",
                    ),
                )
            }

            HttpStatusCode.Unauthorized -> AppResult.Failure(
                DomainError.Network.Unauthorized(
                    message = serverMessage,
                ),
            )

            HttpStatusCode.BadRequest -> AppResult.Failure(
                DomainError.Network.BadRequest(
                    httpCode = status.value,
                    apiErrorCode = parsedError?.code,
                    message = serverMessage,
                ),
            )

            HttpStatusCode.InternalServerError,
            HttpStatusCode.BadGateway,
            HttpStatusCode.ServiceUnavailable,
            HttpStatusCode.GatewayTimeout,
            -> AppResult.Failure(
                DomainError.Network.ServerError(
                    httpCode = status.value,
                    message = serverMessage,
                ),
            )

            else -> AppResult.Failure(
                DomainError.Network.HttpError(
                    httpCode = status.value,
                    message = serverMessage,
                ),
            )
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.listenbrainz.org"
        const val ENDPOINT_SUBMIT_LISTENS = "/1/submit-listens"
        const val ENDPOINT_VALIDATE_TOKEN = "/1/validate-token"

        const val HEADER_RATE_LIMIT = "X-RateLimit-Limit"
        const val HEADER_RATE_REMAINING = "X-RateLimit-Remaining"
        const val HEADER_RATE_RESET_IN = "X-RateLimit-Reset-In"

        const val DEFAULT_RATE_LIMIT_RESET_SECONDS = 15L
        const val MIN_RATE_LIMIT_RESET_SECONDS = 1L
        const val MAX_RATE_LIMIT_RESET_SECONDS = 3_600L

        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val REQUEST_TIMEOUT_MS = 30_000L
        private const val SOCKET_TIMEOUT_MS = 30_000L

        @OptIn(ExperimentalSerializationApi::class)
        fun createDefaultJson(): Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
            explicitNulls = false
        }

        fun createDefaultHttpClient(json: Json = createDefaultJson()): HttpClient = HttpClient(OkHttp) {
            expectSuccess = false

            install(ContentNegotiation) {
                json(json)
            }

            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
            }

            defaultRequest {
                header(HttpHeaders.UserAgent, "ListenTube/1.0 (https://github.com/theviber707/listentube)")
            }

            engine {
                config {
                    connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    readTimeout(SOCKET_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    writeTimeout(SOCKET_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    retryOnConnectionFailure(true)
                }
            }
        }
    }
}
