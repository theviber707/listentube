// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube.data.innertube.cipher

import com.listentube.core.result.AppResult
import com.listentube.core.result.DomainError
import com.listentube.core.result.appRunCatching
import com.listentube.core.result.flatMap
import com.listentube.core.result.map
import com.listentube.data.innertube.model.AdaptiveFormat
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Primitive array operations used by YouTube's `base.js` signature deciphering algorithm.
 */
sealed interface CipherOp {
    /**
     * `a.reverse()` — reverses the signature character array in place.
     */
    data object Reverse : CipherOp

    /**
     * `a.splice(0, count)` — removes the first [count] characters from the signature.
     */
    data class Splice(val count: Int) : CipherOp

    /**
     * `var c = a[0]; a[0] = a[index % a.length]; a[index % a.length] = c` — swaps index `0`
     * with `index % a.length`.
     */
    data class Swap(val index: Int) : CipherOp
}

/**
 * Compiled cipher transformation plan extracted from a specific YouTube `base.js` script.
 */
data class PlayerCipherPlan(
    val playerJsUrl: String,
    val signatureTimestamp: Int?,
    val operations: List<CipherOp>,
)

/**
 * Parsed components of an `AdaptiveFormat.signatureCipher` query string (`s=...&sp=sig&url=...`).
 */
data class ParsedSignatureCipher(
    val encryptedSignature: String,
    val signatureParamName: String,
    val baseStreamUrl: String,
)

/**
 * Extracts YouTube `base.js` signature cipher plans and decrypts `signatureCipher` stream URLs.
 *
 * All network, regex extraction, query parsing, and bounds errors are explicitly surfaced upward
 * via [AppResult.Failure] without swallowing exceptions.
 */
class CipherDecryptor(
    private val httpClient: HttpClient,
    private val youtubeOriginUrl: String = DEFAULT_YOUTUBE_ORIGIN,
) {
    private val cacheMutex = Mutex()
    private var cachedPlayerJsUrl: String? = null
    private val compiledPlansByUrl = LinkedHashMap<String, PlayerCipherPlan>()

    /**
     * Resolves a playable URL from [format], decrypting `signatureCipher` if required.
     */
    suspend fun resolveFormatUrl(
        videoId: String,
        format: AdaptiveFormat,
        explicitPlayerJsUrl: String? = null,
    ): AppResult<String> {
        val directUrl = format.url?.trim()
        if (!directUrl.isNullOrEmpty() && format.rawCipher.isNullOrBlank()) {
            return validateAndNormalizeStreamUrl(videoId = videoId, rawUrl = directUrl)
        }

        val rawCipher = format.rawCipher
            ?: return AppResult.Failure(
                DomainError.Playback.CipherDecryptionFailed(
                    videoId = videoId,
                    reason = "Format itag=${format.itag} contains neither 'url' nor 'signatureCipher'",
                ),
            )

        return parseSignatureCipher(videoId = videoId, rawCipher = rawCipher)
            .flatMap { parsedCipher ->
                getOrFetchCipherPlan(explicitPlayerJsUrl).flatMap { plan ->
                    applyCipherPlan(
                        videoId = videoId,
                        encryptedSig = parsedCipher.encryptedSignature,
                        operations = plan.operations,
                    ).flatMap { decryptedSig ->
                        buildDecryptedStreamUrl(
                            videoId = videoId,
                            baseStreamUrl = parsedCipher.baseStreamUrl,
                            sigParamName = parsedCipher.signatureParamName,
                            decryptedSignature = decryptedSig,
                        )
                    }
                }
            }
    }

    /**
     * Returns the cached `signatureTimestamp` (`sts`) or fetches and compiles the latest `base.js`
     * to obtain it.
     */
    suspend fun getSignatureTimestamp(explicitPlayerJsUrl: String? = null): AppResult<Int> {
        return getOrFetchCipherPlan(explicitPlayerJsUrl).flatMap { plan ->
            val sts = plan.signatureTimestamp
            if (sts != null && sts > 0) {
                AppResult.Success(sts)
            } else {
                AppResult.Failure(
                    DomainError.Playback.SignatureFunctionNotFound(
                        playerJsUrl = plan.playerJsUrl,
                        detail = "signatureTimestamp (sts) not found in player script",
                    ),
                )
            }
        }
    }

    /**
     * Parses a URL-encoded `signatureCipher` string (`s=...&sp=sig&url=...`) into [ParsedSignatureCipher].
     */
    fun parseSignatureCipher(
        videoId: String? = null,
        rawCipher: String,
    ): AppResult<ParsedSignatureCipher> {
        if (rawCipher.isBlank()) {
            return AppResult.Failure(
                DomainError.Playback.CipherDecryptionFailed(
                    videoId = videoId,
                    reason = "signatureCipher string is blank",
                ),
            )
        }

        return appRunCatching(
            errorMapper = { throwable ->
                DomainError.Playback.CipherDecryptionFailed(
                    videoId = videoId,
                    reason = "Malformed URL encoding in signatureCipher: ${throwable.message}",
                    cause = throwable,
                )
            },
        ) {
            val params = mutableMapOf<String, String>()
            for (pair in rawCipher.split('&')) {
                if (pair.isBlank()) continue
                val eqIdx = pair.indexOf('=')
                if (eqIdx <= 0) continue
                val key = URLDecoder.decode(pair.substring(0, eqIdx), StandardCharsets.UTF_8.name())
                val value = URLDecoder.decode(pair.substring(eqIdx + 1), StandardCharsets.UTF_8.name())
                params[key] = value
            }
            params
        }.flatMap { params ->
            val encryptedSig = params["s"]?.takeIf { it.isNotEmpty() }
                ?: return@flatMap AppResult.Failure(
                    DomainError.Playback.CipherDecryptionFailed(
                        videoId = videoId,
                        reason = "Missing encrypted signature parameter 's' in signatureCipher",
                    ),
                )

            val baseUrl = params["url"]?.takeIf { it.isNotBlank() }
                ?: return@flatMap AppResult.Failure(
                    DomainError.Playback.CipherDecryptionFailed(
                        videoId = videoId,
                        reason = "Missing stream 'url' parameter in signatureCipher",
                    ),
                )

            val sigParam = params["sp"]?.takeIf { it.isNotBlank() } ?: DEFAULT_SIGNATURE_PARAM
            AppResult.Success(
                ParsedSignatureCipher(
                    encryptedSignature = encryptedSig,
                    signatureParamName = sigParam,
                    baseStreamUrl = baseUrl,
                ),
            )
        }
    }

    /**
     * Parses a YouTube `base.js` script into a [PlayerCipherPlan] containing `signatureTimestamp`
     * and the ordered list of [CipherOp] transformations.
     */
    fun extractCipherPlanFromJs(
        playerJsUrl: String,
        jsSource: String,
    ): AppResult<PlayerCipherPlan> {
        if (jsSource.isBlank()) {
            return AppResult.Failure(
                DomainError.Playback.SignatureFunctionNotFound(
                    playerJsUrl = playerJsUrl,
                    detail = "Player JavaScript source is empty",
                ),
            )
        }

        val signatureTimestamp = STS_REGEX.find(jsSource)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val decipherBody = DECIPHER_FUNCTION_PATTERNS.firstNotNullOfOrNull { regex ->
            regex.find(jsSource)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        } ?: return AppResult.Failure(
            DomainError.Playback.SignatureFunctionNotFound(
                playerJsUrl = playerJsUrl,
                detail = "Could not locate decipher entry function in base.js",
            ),
        )

        val statements = decipherBody.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("a=a.split") && !it.startsWith("return a.join") }

        if (statements.isEmpty()) {
            return AppResult.Failure(
                DomainError.Playback.SignatureFunctionNotFound(
                    playerJsUrl = playerJsUrl,
                    detail = "Decipher function body contained no transformation statements",
                ),
            )
        }

        val firstCallMatch = STEP_CALL_REGEX.find(statements.first())
            ?: return AppResult.Failure(
                DomainError.Playback.SignatureFunctionNotFound(
                    playerJsUrl = playerJsUrl,
                    detail = "Unrecognized cipher step statement: '${statements.first()}'",
                ),
            )
        val helperObjectName = firstCallMatch.groupValues[1]

        val helperObjectRegex = Regex(
            """(?:var\s+)?${Regex.escape(helperObjectName)}\s*=\s*\{([\s\S]*?)\}\s*;""",
        )
        val helperObjectBody = helperObjectRegex.find(jsSource)?.groupValues?.getOrNull(1)
            ?: return AppResult.Failure(
                DomainError.Playback.SignatureFunctionNotFound(
                    playerJsUrl = playerJsUrl,
                    detail = "Could not locate cipher helper object '$helperObjectName' in base.js",
                ),
            )

        val methodKindByName = parseHelperMethods(helperObjectBody)
        if (methodKindByName.isEmpty()) {
            return AppResult.Failure(
                DomainError.Playback.SignatureFunctionNotFound(
                    playerJsUrl = playerJsUrl,
                    detail = "Helper object '$helperObjectName' contained no recognizable cipher methods",
                ),
            )
        }

        val operations = mutableListOf<CipherOp>()
        for (statement in statements) {
            val match = STEP_CALL_REGEX.find(statement)
                ?: return AppResult.Failure(
                    DomainError.Playback.SignatureFunctionNotFound(
                        playerJsUrl = playerJsUrl,
                        detail = "Failed to parse cipher call statement '$statement'",
                    ),
                )

            val methodName = match.groupValues[2].ifEmpty { match.groupValues[3] }
            val argValue = match.groupValues.getOrNull(4)?.toIntOrNull()
            val kind = methodKindByName[methodName]
                ?: return AppResult.Failure(
                    DomainError.Playback.SignatureFunctionNotFound(
                        playerJsUrl = playerJsUrl,
                        detail = "Unknown cipher helper method '$helperObjectName.$methodName'",
                    ),
                )

            val op = when (kind) {
                HelperMethodKind.REVERSE -> CipherOp.Reverse
                HelperMethodKind.SPLICE -> {
                    if (argValue == null || argValue < 0) {
                        return AppResult.Failure(
                            DomainError.Playback.SignatureFunctionNotFound(
                                playerJsUrl = playerJsUrl,
                                detail = "Invalid splice count argument in '$statement'",
                            ),
                        )
                    }
                    CipherOp.Splice(argValue)
                }

                HelperMethodKind.SWAP -> {
                    if (argValue == null || argValue < 0) {
                        return AppResult.Failure(
                            DomainError.Playback.SignatureFunctionNotFound(
                                playerJsUrl = playerJsUrl,
                                detail = "Invalid swap index argument in '$statement'",
                            ),
                        )
                    }
                    CipherOp.Swap(argValue)
                }
            }
            operations += op
        }

        return AppResult.Success(
            PlayerCipherPlan(
                playerJsUrl = playerJsUrl,
                signatureTimestamp = signatureTimestamp,
                operations = operations,
            ),
        )
    }

    /**
     * Executes [operations] sequentially on [encryptedSig] to produce the decrypted signature string.
     */
    fun applyCipherPlan(
        videoId: String? = null,
        encryptedSig: String,
        operations: List<CipherOp>,
    ): AppResult<String> {
        if (encryptedSig.isEmpty()) {
            return AppResult.Failure(
                DomainError.Playback.CipherDecryptionFailed(
                    videoId = videoId,
                    reason = "Cannot decrypt an empty signature string",
                ),
            )
        }
        if (operations.isEmpty()) {
            return AppResult.Failure(
                DomainError.Playback.CipherDecryptionFailed(
                    videoId = videoId,
                    reason = "Cipher transformation plan contains zero operations",
                ),
            )
        }

        val buffer = encryptedSig.toMutableList()
        for (op in operations) {
            when (op) {
                is CipherOp.Reverse -> buffer.reverse()
                is CipherOp.Splice -> {
                    if (op.count > buffer.size) {
                        return AppResult.Failure(
                            DomainError.Playback.CipherDecryptionFailed(
                                videoId = videoId,
                                reason = "Splice count (${op.count}) exceeds current signature length (${buffer.size})",
                            ),
                        )
                    }
                    if (op.count > 0) {
                        buffer.subList(0, op.count).clear()
                    }
                }

                is CipherOp.Swap -> {
                    if (buffer.isEmpty()) {
                        return AppResult.Failure(
                            DomainError.Playback.CipherDecryptionFailed(
                                videoId = videoId,
                                reason = "Signature buffer became empty before swap(${op.index})",
                            ),
                        )
                    }
                    val targetIdx = op.index % buffer.size
                    val firstChar = buffer[0]
                    buffer[0] = buffer[targetIdx]
                    buffer[targetIdx] = firstChar
                }
            }
        }

        if (buffer.isEmpty()) {
            return AppResult.Failure(
                DomainError.Playback.CipherDecryptionFailed(
                    videoId = videoId,
                    reason = "Decrypted signature is empty after applying ${operations.size} cipher operations",
                ),
            )
        }

        return AppResult.Success(buffer.joinToString(separator = ""))
    }

    /**
     * Retrieves a cached [PlayerCipherPlan] or downloads and parses the player script.
     */
    private suspend fun getOrFetchCipherPlan(explicitPlayerJsUrl: String?): AppResult<PlayerCipherPlan> {
        cacheMutex.withLock {
            val targetUrl = explicitPlayerJsUrl ?: cachedPlayerJsUrl
            if (targetUrl != null) {
                compiledPlansByUrl[targetUrl]?.let { return AppResult.Success(it) }
            }
        }

        val resolvedUrlResult = if (!explicitPlayerJsUrl.isNullOrBlank()) {
            AppResult.Success(normalizePlayerJsUrl(explicitPlayerJsUrl))
        } else {
            discoverPlayerJsUrl()
        }

        return resolvedUrlResult.flatMap { playerJsUrl ->
            cacheMutex.withLock {
                compiledPlansByUrl[playerJsUrl]?.let { return@flatMap AppResult.Success(it) }
            }

            fetchPlayerJsSource(playerJsUrl).flatMap { jsSource ->
                extractCipherPlanFromJs(playerJsUrl = playerJsUrl, jsSource = jsSource).map { plan ->
                    cacheMutex.withLock {
                        cachedPlayerJsUrl = playerJsUrl
                        if (compiledPlansByUrl.size >= MAX_CACHED_PLANS) {
                            val eldestKey = compiledPlansByUrl.keys.firstOrNull()
                            if (eldestKey != null) compiledPlansByUrl.remove(eldestKey)
                        }
                        compiledPlansByUrl[playerJsUrl] = plan
                    }
                    plan
                }
            }
        }
    }

    /**
     * Discovers the active `/s/player/<id>/.../base.js` URL from YouTube's `iframe_api` or embed page.
     */
    private suspend fun discoverPlayerJsUrl(): AppResult<String> {
        val iframeUrl = "${youtubeOriginUrl.trimEnd('/')}/iframe_api"
        return appRunCatching {
            val response = httpClient.get(iframeUrl)
            if (!response.status.isSuccess()) {
                return@appRunCatching AppResult.Failure(
                    DomainError.Playback.PlayerJsFetchFailed(
                        playerJsUrl = iframeUrl,
                        message = "Failed to fetch YouTube iframe_api (HTTP ${response.status.value})",
                    ),
                )
            }
            val body = response.bodyAsText()
            val playerVersion = IFRAME_PLAYER_VERSION_REGEX.find(body)?.groupValues?.getOrNull(1)
            val baseJsPath = BASE_JS_PATH_REGEX.find(body)?.groupValues?.getOrNull(1)

            val discoveredUrl = when {
                !baseJsPath.isNullOrBlank() -> normalizePlayerJsUrl(baseJsPath)
                !playerVersion.isNullOrBlank() ->
                    "${youtubeOriginUrl.trimEnd('/')}/s/player/$playerVersion/player_ias.vflset/en_US/base.js"
                else -> null
            }

            if (discoveredUrl != null) {
                AppResult.Success(discoveredUrl)
            } else {
                AppResult.Failure(
                    DomainError.Playback.PlayerJsFetchFailed(
                        playerJsUrl = iframeUrl,
                        message = "Unable to locate player base.js URL in iframe_api response",
                    ),
                )
            }
        }.let { outer ->
            when (outer) {
                is AppResult.Success -> outer.data
                is AppResult.Failure -> outer
            }
        }
    }

    /**
     * Downloads the raw JavaScript contents of `base.js` at [playerJsUrl].
     */
    private suspend fun fetchPlayerJsSource(playerJsUrl: String): AppResult<String> {
        return appRunCatching {
            val response = httpClient.get(playerJsUrl)
            if (!response.status.isSuccess()) {
                return@appRunCatching AppResult.Failure(
                    DomainError.Playback.PlayerJsFetchFailed(
                        playerJsUrl = playerJsUrl,
                        message = "HTTP ${response.status.value} when downloading player script '$playerJsUrl'",
                    ),
                )
            }
            val text = response.bodyAsText()
            if (text.isBlank()) {
                AppResult.Failure(
                    DomainError.Playback.PlayerJsFetchFailed(
                        playerJsUrl = playerJsUrl,
                        message = "Downloaded player script '$playerJsUrl' was empty",
                    ),
                )
            } else {
                AppResult.Success(text)
            }
        }.let { outer ->
            when (outer) {
                is AppResult.Success -> outer.data
                is AppResult.Failure -> outer
            }
        }
    }

    private fun buildDecryptedStreamUrl(
        videoId: String,
        baseStreamUrl: String,
        sigParamName: String,
        decryptedSignature: String,
    ): AppResult<String> {
        return appRunCatching(
            errorMapper = { throwable ->
                DomainError.Playback.CipherDecryptionFailed(
                    videoId = videoId,
                    reason = "Failed to construct decrypted stream URL: ${throwable.message}",
                    cause = throwable,
                )
            },
        ) {
            val encodedParam = URLEncoder.encode(sigParamName, StandardCharsets.UTF_8.name())
            val encodedSig = URLEncoder.encode(decryptedSignature, StandardCharsets.UTF_8.name())
            val separator = if ('?' in baseStreamUrl) '&' else '?'
            val withSig = "$baseStreamUrl$separator$encodedParam=$encodedSig"
            ensureRateBypassParam(withSig)
        }.flatMap { finalUrl ->
            validateAndNormalizeStreamUrl(videoId = videoId, rawUrl = finalUrl)
        }
    }

    private fun validateAndNormalizeStreamUrl(
        videoId: String,
        rawUrl: String,
    ): AppResult<String> {
        val trimmed = rawUrl.trim()
        if (!trimmed.startsWith("https://", ignoreCase = true) &&
            !trimmed.startsWith("http://", ignoreCase = true)
        ) {
            return AppResult.Failure(
                DomainError.Playback.CipherDecryptionFailed(
                    videoId = videoId,
                    reason = "Resolved stream URL has invalid scheme: '$trimmed'",
                ),
            )
        }
        return AppResult.Success(ensureRateBypassParam(trimmed))
    }

    private fun ensureRateBypassParam(url: String): String {
        if (url.contains("ratebypass=", ignoreCase = true)) return url
        val separator = if ('?' in url) '&' else '?'
        return "${url}${separator}ratebypass=yes"
    }

    private fun normalizePlayerJsUrl(rawPathOrUrl: String): String {
        val trimmed = rawPathOrUrl.trim()
        return when {
            trimmed.startsWith("https://") || trimmed.startsWith("http://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "${youtubeOriginUrl.trimEnd('/')}$trimmed"
            else -> "${youtubeOriginUrl.trimEnd('/')}/$trimmed"
        }
    }

    private enum class HelperMethodKind {
        REVERSE,
        SPLICE,
        SWAP,
    }

    private fun parseHelperMethods(helperObjectBody: String): Map<String, HelperMethodKind> {
        val result = mutableMapOf<String, HelperMethodKind>()
        for (match in HELPER_METHOD_REGEX.findAll(helperObjectBody)) {
            val methodName = match.groupValues[1]
            val methodBody = match.groupValues[2]
            val kind = when {
                methodBody.contains(".reverse(") -> HelperMethodKind.REVERSE
                methodBody.contains(".splice(") -> HelperMethodKind.SPLICE
                methodBody.contains("%a.length") || methodBody.contains("% a.length") -> HelperMethodKind.SWAP
                else -> null
            }
            if (kind != null) {
                result[methodName] = kind
            }
        }
        return result
    }

    companion object {
        const val DEFAULT_YOUTUBE_ORIGIN = "https://www.youtube.com"
        const val DEFAULT_SIGNATURE_PARAM = "sig"
        private const val MAX_CACHED_PLANS = 4

        private val STS_REGEX = Regex("""(?:signatureTimestamp|sts)\s*:\s*(\d{5,})""")

        private val IFRAME_PLAYER_VERSION_REGEX =
            Regex("""\\?/s\\?/player\\?/([a-zA-Z0-9_-]{8,})\\?/""")

        private val BASE_JS_PATH_REGEX =
            Regex("""(/s/player/[a-zA-Z0-9_-]+/[a-zA-Z0-9_.-]+/[a-zA-Z0-9_-]+/base\.js)""")

        private val DECIPHER_FUNCTION_PATTERNS = listOf(
            Regex(
                """[a-zA-Z0-9_$]+\s*=\s*function\(\s*a\s*\)\s*\{\s*a\s*=\s*a\.split\(\s*""\s*\)\s*;([a-zA-Z0-9_$]+\.[^}]+);\s*return\s+a\.join\(\s*""\s*\)\s*\}""",
            ),
            Regex(
                """function\(\s*a\s*\)\s*\{\s*a\s*=\s*a\.split\(\s*""\s*\)\s*;(\s*[a-zA-Z0-9_$]+(?:\.[a-zA-Z0-9_$]+|\["[^"]+"\])\(\s*a\s*,\s*\d+\s*\)[^}]+);\s*return\s+a\.join\(\s*""\s*\)\s*\}""",
            ),
        )

        private val STEP_CALL_REGEX = Regex(
            """([a-zA-Z0-9_$]+)(?:\.([a-zA-Z0-9_$]+)|\["([^"]+)"\])\(\s*a(?:\s*,\s*(\d+))?\s*\)""",
        )

        private val HELPER_METHOD_REGEX = Regex(
            """([a-zA-Z0-9_$]+)\s*:\s*function\s*\([^)]*\)\s*\{([^}]*)\}""",
        )
    }
}
