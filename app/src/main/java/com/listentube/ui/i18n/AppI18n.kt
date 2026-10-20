// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.os.ConfigurationCompat
import com.listentube.core.result.DomainError
import java.util.Locale

/**
 * Supported application locales with BCP-47 tags, native display names,
 * and corresponding YouTube Music Innertube `hl` (host language) / `gl` (geolocation) codes.
 */
enum class AppLocale(
    val languageTag: String,
    val nativeDisplayName: String,
    val innertubeHl: String,
    val innertubeGl: String,
    val javaLocale: Locale,
) {
    EN_US(
        languageTag = "en-US",
        nativeDisplayName = "English (US)",
        innertubeHl = "en",
        innertubeGl = "US",
        javaLocale = Locale.forLanguageTag("en-US"),
    ),
    PT_BR(
        languageTag = "pt-BR",
        nativeDisplayName = "Português (Brasil)",
        innertubeHl = "pt-BR",
        innertubeGl = "BR",
        javaLocale = Locale.forLanguageTag("pt-BR"),
    ),
    ES_MX(
        languageTag = "es-MX",
        nativeDisplayName = "Español (México)",
        innertubeHl = "es-419",
        innertubeGl = "MX",
        javaLocale = Locale.forLanguageTag("es-MX"),
    ),
    RU_RU(
        languageTag = "ru-RU",
        nativeDisplayName = "Русский",
        innertubeHl = "ru",
        innertubeGl = "RU",
        javaLocale = Locale.forLanguageTag("ru-RU"),
    ),
    ;

    companion object {
        /**
         * Resolves an [AppLocale] from a BCP-47 language tag (e.g., `"pt-BR"`, `"es-MX"`, `"ru"`, `"en-US"`),
         * falling back to the current JVM default locale or [EN_US].
         */
        fun fromLanguageTag(tag: String?): AppLocale {
            if (tag.isNullOrBlank()) return fromLocale(Locale.getDefault())
            val normalized = tag.trim().replace('_', '-').lowercase(Locale.ROOT)
            return when {
                normalized.startsWith("pt") -> PT_BR
                normalized.startsWith("es") -> ES_MX
                normalized.startsWith("ru") -> RU_RU
                normalized.startsWith("en") -> EN_US
                else -> fromLocale(Locale.forLanguageTag(tag))
            }
        }

        /**
         * Resolves an [AppLocale] from a [Locale] instance, defaulting to [EN_US] for unsupported languages.
         */
        fun fromLocale(locale: Locale = Locale.getDefault()): AppLocale {
            return when (locale.language.lowercase(Locale.ROOT)) {
                "pt" -> PT_BR
                "es" -> ES_MX
                "ru" -> RU_RU
                else -> EN_US
            }
        }
    }
}

/**
 * Strongly-typed localized string catalog for ListenTube across American English (`en-US`),
 * Brazilian Portuguese (`pt-BR`), Mexican Spanish (`es-MX`), and Russian (`ru-RU`).
 */
@Immutable
data class AppStrings(
    val locale: AppLocale,

    // App Chrome & Navigation
    val appName: String,
    val badgeScrobbled: String,
    val cdScrobbleQueue: String,
    val navYoutubeMusic: String,
    val navLocalAudio: String,
    val navListenBrainz: String,

    // Banners & Status Messages
    val cdDismissError: String,
    val cdDismissMessage: String,
    val statusVerifiedUserFallback: String,
    val statusScrobblingEnabled: String,
    val statusScrobblingPaused: String,
    val statusQueueUpToDate: String,
    val statusClearedOfflineQueue: String,
    val statusAddedToQueue: (title: String) -> String,
    val statusConnectedToListenBrainz: (userName: String) -> String,
    val statusFlushedQueueCount: (count: Int) -> String,

    // YouTube Music Explore Tab
    val searchYtmPlaceholder: String,
    val cdClearSearch: String,
    val actionSearch: String,
    val cdSearch: String,
    val cdBack: String,
    val cdPlayAll: String,
    val sectionSongs: String,
    val sectionAlbums: String,
    val sectionPlaylists: String,
    val playlistEmptyTracks: String,
    val emptyYtmSearchPrompt: String,
    val actionLoadMore: String,

    // Local Library Tab
    val filterLocalPlaceholder: String,
    val cdRefreshLocalLibrary: String,
    val emptyLocalLibrary: String,
    val cdAddToQueue: String,
    val unknownArtist: String,
    val actionFilterFolders: String,
    val foldersDialogTitle: String,
    val foldersDialogSubtitle: String,
    val actionSelectAll: String,
    val actionDeselectAll: String,
    val folderTrackCount: (count: Int) -> String,

    // Now Playing Sheet & Transport Controls
    val cdCollapsePlayer: String,
    val renderModeAudioArt: String,
    val renderModeVideoSurface: String,
    val videoSurfaceReady: (title: String) -> String,
    val cdToggleShuffle: String,
    val cdPreviousTrack: String,
    val cdPlay: String,
    val cdPause: String,
    val cdNextTrack: String,
    val cdCycleRepeat: String,
    val upNextHeader: (count: Int) -> String,

    // ListenBrainz Scrobble Queue & Settings Screen
    val lbScrobblerTitle: String,
    val lbScrobblerSubtitleActive: String,
    val lbScrobblerSubtitleDisabled: String,
    val lbCurrentSession: (trackName: String, artistName: String) -> String,
    val lbPlayingNowSent: String,
    val lbPlayingNowPending: String,
    val lbCompletedListenRecorded: String,
    val lbIneligibleShortTrack: String,
    val lbNoActiveSession: String,
    val lbApiQuotaRemaining: (remaining: Int, limit: Int) -> String,
    val lbApiQuotaReady: String,
    val lbRateLimitResetIn: (seconds: Long) -> String,
    val actionFlushQueue: (count: Int) -> String,
    val lbTokenSectionTitle: String,
    val lbTokenVerifiedBase: String,
    val lbTokenVerifiedWithUser: (userName: String) -> String,
    val lbTokenValidationFailed: String,
    val lbTokenInputLabel: String,
    val cdShowToken: String,
    val cdHideToken: String,
    val actionValidateAndSaveToken: String,
    val queuedPlayingNowHeader: String,
    val pendingOfflineScrobblesHeader: (count: Int) -> String,
    val pendingOfflineScrobblesSubtitle: String,
    val actionClearAll: String,
    val notAvailableShort: String,
    val queuedPlayingNowMeta: (expiresText: String) -> String,
    val queuedCompletedMeta: (listenedText: String, attemptCount: Int) -> String,
    val queuedLastStatus: (error: String) -> String,
    val cdRemoveQueuedScrobble: String,
    val emptyQueueTitle: String,
    val emptyQueueSubtitle: String,

    // Localized DomainError Formatters
    val errorNetworkOffline: String,
    val errorNetworkTimeout: String,
    val errorNetworkRateLimited: (resetInSeconds: Long) -> String,
    val errorNetworkUnauthorized: String,
    val errorNetworkBadRequest: String,
    val errorNetworkServerError: (httpCode: Int) -> String,
    val errorNetworkHttpError: (httpCode: Int) -> String,
    val errorNetworkSerialization: String,
    val errorNetworkUnknown: String,
    val errorStorageDatabase: String,
    val errorStorageDiskFull: String,
    val errorValidationMissingToken: String,
    val errorValidationInvalidMetadata: (field: String) -> String,
    val errorValidationTrackTooShort: (durationMs: Long, minDurationMs: Long) -> String,
    val errorPlaybackStreamExtractionFailed: (videoId: String) -> String,
    val errorPlaybackUnplayableVideo: (videoId: String, status: String, reason: String?) -> String,
    val errorPlaybackNoAudioStream: (videoId: String) -> String,
    val errorPlaybackPlayerJsFetchFailed: (url: String) -> String,
    val errorPlaybackSignatureFunctionNotFound: (detail: String) -> String,
    val errorPlaybackCipherDecryptionFailed: (videoId: String?, reason: String) -> String,
    val errorPlaybackPermissionDenied: (permission: String) -> String,
    val errorPlaybackLocalMediaQueryFailed: String,
    val errorPlaybackPlayerError: String,
    val errorUnexpected: String,
)

/**
 * American English (`en-US`) string catalog.
 */
val EnUsStrings = AppStrings(
    locale = AppLocale.EN_US,
    appName = "ListenTube",
    badgeScrobbled = "Scrobbled",
    cdScrobbleQueue = "ListenBrainz Scrobble Queue",
    navYoutubeMusic = "YouTube Music",
    navLocalAudio = "Local Audio",
    navListenBrainz = "ListenBrainz",
    cdDismissError = "Dismiss error",
    cdDismissMessage = "Dismiss message",
    statusVerifiedUserFallback = "verified user",
    statusScrobblingEnabled = "ListenBrainz scrobbling enabled",
    statusScrobblingPaused = "ListenBrainz scrobbling paused",
    statusQueueUpToDate = "Scrobble queue is up to date",
    statusClearedOfflineQueue = "Cleared offline scrobble queue",
    statusAddedToQueue = { title -> "Added \"$title\" to queue" },
    statusConnectedToListenBrainz = { userName -> "Connected to ListenBrainz as $userName" },
    statusFlushedQueueCount = { count -> "Submitted $count queued listen(s) to ListenBrainz" },
    searchYtmPlaceholder = "Search YouTube Music songs, artists, albums...",
    cdClearSearch = "Clear search",
    actionSearch = "Search",
    cdSearch = "Search",
    cdBack = "Back",
    cdPlayAll = "Play all",
    sectionSongs = "Songs",
    sectionAlbums = "Albums",
    sectionPlaylists = "Playlists",
    playlistEmptyTracks = "No tracks found in this playlist",
    emptyYtmSearchPrompt = "Search YouTube Music to stream and scrobble tracks",
    actionLoadMore = "Load More",
    filterLocalPlaceholder = "Filter local audio files...",
    cdRefreshLocalLibrary = "Refresh local audio library",
    emptyLocalLibrary = "No local audio tracks found on device",
    cdAddToQueue = "Add to queue",
    unknownArtist = "Unknown Artist",
    actionFilterFolders = "Folders",
    foldersDialogTitle = "Audio Folders",
    foldersDialogSubtitle = "Choose which folders the app scans",
    actionSelectAll = "Select all",
    actionDeselectAll = "Deselect all",
    folderTrackCount = { count -> "$count track(s)" },
    cdCollapsePlayer = "Collapse player",
    renderModeAudioArt = "Audio Art",
    renderModeVideoSurface = "Video Surface",
    videoSurfaceReady = { title -> "Video surface ready for \"$title\"" },
    cdToggleShuffle = "Toggle shuffle",
    cdPreviousTrack = "Previous track",
    cdPlay = "Play",
    cdPause = "Pause",
    cdNextTrack = "Next track",
    cdCycleRepeat = "Cycle repeat mode",
    upNextHeader = { count -> "Up Next ($count)" },
    lbScrobblerTitle = "ListenBrainz Scrobbler",
    lbScrobblerSubtitleActive = "Active • 50% or 4:00 threshold",
    lbScrobblerSubtitleDisabled = "Scrobbling disabled",
    lbCurrentSession = { trackName, artistName -> "Current Session: $trackName — $artistName" },
    lbPlayingNowSent = "playing_now: Sent",
    lbPlayingNowPending = "playing_now: Pending/Queued",
    lbCompletedListenRecorded = "Completed Listen Recorded",
    lbIneligibleShortTrack = "Ineligible (< 30s)",
    lbNoActiveSession = "No active track playback session",
    lbApiQuotaRemaining = { remaining, limit -> "API Quota: $remaining / $limit remaining" },
    lbApiQuotaReady = "API Quota: Ready",
    lbRateLimitResetIn = { seconds -> "X-RateLimit-Reset-In: ${seconds}s" },
    actionFlushQueue = { count -> "Flush Queue ($count)" },
    lbTokenSectionTitle = "ListenBrainz User Token",
    lbTokenVerifiedBase = "Token verified",
    lbTokenVerifiedWithUser = { userName -> "Token verified as @$userName" },
    lbTokenValidationFailed = "Token validation failed. Please check your ListenBrainz user token.",
    lbTokenInputLabel = "User Token (from listenbrainz.org/settings)",
    cdShowToken = "Show token",
    cdHideToken = "Hide token",
    actionValidateAndSaveToken = "Validate & Save Token",
    queuedPlayingNowHeader = "Queued Offline: Playing Now",
    pendingOfflineScrobblesHeader = { count -> "Pending Offline Scrobbles ($count)" },
    pendingOfflineScrobblesSubtitle = "Persisted in Room • Submitted via single/import when online",
    actionClearAll = "Clear All",
    notAvailableShort = "N/A",
    queuedPlayingNowMeta = { expiresText -> "playing_now • Expires $expiresText" },
    queuedCompletedMeta = { listenedText, attemptCount -> "Listened at $listenedText • Attempts: $attemptCount" },
    queuedLastStatus = { error -> "Last status: $error" },
    cdRemoveQueuedScrobble = "Remove queued scrobble",
    emptyQueueTitle = "All Scrobbles Synced",
    emptyQueueSubtitle = "Any tracks played while offline or during an HTTP 429 rate-limit window will be queued here automatically.",
    errorNetworkOffline = "No network connection available",
    errorNetworkTimeout = "Network request timed out",
    errorNetworkRateLimited = { resetInSeconds -> "Rate limited by ListenBrainz; reset in ${resetInSeconds}s" },
    errorNetworkUnauthorized = "Invalid or unauthorized ListenBrainz user token",
    errorNetworkBadRequest = "ListenBrainz rejected malformed submission payload",
    errorNetworkServerError = { httpCode -> "ListenBrainz upstream server error (HTTP $httpCode)" },
    errorNetworkHttpError = { httpCode -> "HTTP error $httpCode" },
    errorNetworkSerialization = "Failed to serialize or deserialize network payload",
    errorNetworkUnknown = "Unexpected network transport failure",
    errorStorageDatabase = "Local database operation failed",
    errorStorageDiskFull = "Device storage is full",
    errorValidationMissingToken = "ListenBrainz user token is not configured",
    errorValidationInvalidMetadata = { field -> "Required track metadata field '$field' is blank or invalid" },
    errorValidationTrackTooShort = { durationMs, minDurationMs ->
        "Track duration (${durationMs}ms) is shorter than minimum scrobble threshold (${minDurationMs}ms)"
    },
    errorPlaybackStreamExtractionFailed = { videoId -> "Failed to extract audio stream for video '$videoId'" },
    errorPlaybackUnplayableVideo = { videoId, status, reason ->
        if (reason.isNullOrBlank()) {
            "Video '$videoId' is not playable (status=$status)"
        } else {
            "Video '$videoId' is not playable (status=$status, reason=$reason)"
        }
    },
    errorPlaybackNoAudioStream = { videoId -> "No compatible audio stream found for '$videoId'" },
    errorPlaybackPlayerJsFetchFailed = { url -> "Failed to fetch YouTube player script from '$url'" },
    errorPlaybackSignatureFunctionNotFound = { detail -> "Failed to parse cipher function: $detail" },
    errorPlaybackCipherDecryptionFailed = { videoId, reason ->
        if (videoId.isNullOrBlank()) {
            "Cipher decryption failed: $reason"
        } else {
            "Cipher decryption failed for video '$videoId': $reason"
        }
    },
    errorPlaybackPermissionDenied = { permission -> "Missing required media permission: $permission" },
    errorPlaybackLocalMediaQueryFailed = "Failed to query local MediaStore audio library",
    errorPlaybackPlayerError = "Media playback engine encountered an error",
    errorUnexpected = "An unexpected error occurred",
)

/**
 * Brazilian Portuguese (`pt-BR`) string catalog.
 */
val PtBrStrings = AppStrings(
    locale = AppLocale.PT_BR,
    appName = "ListenTube",
    badgeScrobbled = "Scrobblado",
    cdScrobbleQueue = "Fila de Scrobbles do ListenBrainz",
    navYoutubeMusic = "YouTube Music",
    navLocalAudio = "Áudio Local",
    navListenBrainz = "ListenBrainz",
    cdDismissError = "Dispensar erro",
    cdDismissMessage = "Dispensar mensagem",
    statusVerifiedUserFallback = "usuário verificado",
    statusScrobblingEnabled = "Scrobbling do ListenBrainz ativado",
    statusScrobblingPaused = "Scrobbling do ListenBrainz pausado",
    statusQueueUpToDate = "A fila de scrobbles está atualizada",
    statusClearedOfflineQueue = "Fila de scrobbles offline limpa",
    statusAddedToQueue = { title -> "\"$title\" adicionada à fila" },
    statusConnectedToListenBrainz = { userName -> "Conectado ao ListenBrainz como $userName" },
    statusFlushedQueueCount = { count -> "$count reprodução(ões) na fila enviada(s) ao ListenBrainz" },
    searchYtmPlaceholder = "Buscar músicas, artistas e álbuns no YouTube Music...",
    cdClearSearch = "Limpar busca",
    actionSearch = "Buscar",
    cdSearch = "Buscar",
    cdBack = "Voltar",
    cdPlayAll = "Reproduzir tudo",
    sectionSongs = "Músicas",
    sectionAlbums = "Álbuns",
    sectionPlaylists = "Playlists",
    playlistEmptyTracks = "Nenhuma faixa encontrada nesta playlist",
    emptyYtmSearchPrompt = "Busque no YouTube Music para ouvir e fazer scrobble de faixas",
    actionLoadMore = "Carregar mais",
    filterLocalPlaceholder = "Filtrar arquivos de áudio locais...",
    cdRefreshLocalLibrary = "Atualizar biblioteca de áudio local",
    emptyLocalLibrary = "Nenhuma faixa de áudio local encontrada no dispositivo",
    cdAddToQueue = "Adicionar à fila",
    unknownArtist = "Artista desconhecido",
    actionFilterFolders = "Pastas",
    foldersDialogTitle = "Pastas de áudio",
    foldersDialogSubtitle = "Escolha quais pastas o app identifica",
    actionSelectAll = "Selecionar tudo",
    actionDeselectAll = "Desmarcar tudo",
    folderTrackCount = { count -> "$count faixa(s)" },
    cdCollapsePlayer = "Recolher reprodutor",
    renderModeAudioArt = "Capa do Álbum",
    renderModeVideoSurface = "Vídeo",
    videoSurfaceReady = { title -> "Superfície de vídeo pronta para \"$title\"" },
    cdToggleShuffle = "Alternar modo aleatório",
    cdPreviousTrack = "Faixa anterior",
    cdPlay = "Reproduzir",
    cdPause = "Pausar",
    cdNextTrack = "Próxima faixa",
    cdCycleRepeat = "Alternar modo de repetição",
    upNextHeader = { count -> "A Seguir ($count)" },
    lbScrobblerTitle = "Scrobbler do ListenBrainz",
    lbScrobblerSubtitleActive = "Ativo • Limite de 50% ou 4:00",
    lbScrobblerSubtitleDisabled = "Scrobbling desativado",
    lbCurrentSession = { trackName, artistName -> "Sessão Atual: $trackName — $artistName" },
    lbPlayingNowSent = "playing_now: Enviado",
    lbPlayingNowPending = "playing_now: Pendente/Na fila",
    lbCompletedListenRecorded = "Reprodução Concluída Registrada",
    lbIneligibleShortTrack = "Inelegível (< 30s)",
    lbNoActiveSession = "Nenhuma sessão de reprodução ativa",
    lbApiQuotaRemaining = { remaining, limit -> "Cota da API: $remaining / $limit restantes" },
    lbApiQuotaReady = "Cota da API: Pronta",
    lbRateLimitResetIn = { seconds -> "X-RateLimit-Reset-In: ${seconds}s" },
    actionFlushQueue = { count -> "Enviar Fila ($count)" },
    lbTokenSectionTitle = "Token de Usuário do ListenBrainz",
    lbTokenVerifiedBase = "Token verificado",
    lbTokenVerifiedWithUser = { userName -> "Token verificado como @$userName" },
    lbTokenValidationFailed = "Falha na validação do token. Verifique seu token de usuário do ListenBrainz.",
    lbTokenInputLabel = "Token de Usuário (em listenbrainz.org/settings)",
    cdShowToken = "Mostrar token",
    cdHideToken = "Ocultar token",
    actionValidateAndSaveToken = "Validar e Salvar Token",
    queuedPlayingNowHeader = "Na Fila Offline: Ouvindo Agora",
    pendingOfflineScrobblesHeader = { count -> "Scrobbles Offline Pendentes ($count)" },
    pendingOfflineScrobblesSubtitle = "Salvos no Room • Enviados via single/import quando online",
    actionClearAll = "Limpar Tudo",
    notAvailableShort = "N/D",
    queuedPlayingNowMeta = { expiresText -> "playing_now • Expira em $expiresText" },
    queuedCompletedMeta = { listenedText, attemptCount -> "Ouvido em $listenedText • Tentativas: $attemptCount" },
    queuedLastStatus = { error -> "Último status: $error" },
    cdRemoveQueuedScrobble = "Remover scrobble da fila",
    emptyQueueTitle = "Todos os Scrobbles Sincronizados",
    emptyQueueSubtitle = "Qualquer faixa reproduzida offline ou durante uma janela de limite HTTP 429 será colocada na fila automaticamente.",
    errorNetworkOffline = "Nenhuma conexão de rede disponível",
    errorNetworkTimeout = "O tempo limite da requisição de rede expirou",
    errorNetworkRateLimited = { resetInSeconds -> "Limite de taxa do ListenBrainz atingido; reinício em ${resetInSeconds}s" },
    errorNetworkUnauthorized = "Token de usuário do ListenBrainz inválido ou não autorizado",
    errorNetworkBadRequest = "O ListenBrainz rejeitou a carga de envio malformada",
    errorNetworkServerError = { httpCode -> "Erro no servidor do ListenBrainz (HTTP $httpCode)" },
    errorNetworkHttpError = { httpCode -> "Erro HTTP $httpCode" },
    errorNetworkSerialization = "Falha ao serializar ou desserializar dados de rede",
    errorNetworkUnknown = "Falha inesperada no transporte de rede",
    errorStorageDatabase = "Falha na operação do banco de dados local",
    errorStorageDiskFull = "O armazenamento do dispositivo está cheio",
    errorValidationMissingToken = "O token de usuário do ListenBrainz não está configurado",
    errorValidationInvalidMetadata = { field -> "O campo obrigatório de metadados '$field' está vazio ou inválido" },
    errorValidationTrackTooShort = { durationMs, minDurationMs ->
        "A duração da faixa (${durationMs}ms) é menor que o limite mínimo de scrobble (${minDurationMs}ms)"
    },
    errorPlaybackStreamExtractionFailed = { videoId -> "Falha ao extrair fluxo de áudio para o vídeo '$videoId'" },
    errorPlaybackUnplayableVideo = { videoId, status, reason ->
        if (reason.isNullOrBlank()) {
            "O vídeo '$videoId' não pode ser reproduzido (status=$status)"
        } else {
            "O vídeo '$videoId' não pode ser reproduzido (status=$status, motivo=$reason)"
        }
    },
    errorPlaybackNoAudioStream = { videoId -> "Nenhum fluxo de áudio compatível encontrado para '$videoId'" },
    errorPlaybackPlayerJsFetchFailed = { url -> "Falha ao baixar o script do player do YouTube de '$url'" },
    errorPlaybackSignatureFunctionNotFound = { detail -> "Falha ao analisar a função de cifra: $detail" },
    errorPlaybackCipherDecryptionFailed = { videoId, reason ->
        if (videoId.isNullOrBlank()) {
            "Falha na descriptografia da cifra: $reason"
        } else {
            "Falha na descriptografia da cifra para o vídeo '$videoId': $reason"
        }
    },
    errorPlaybackPermissionDenied = { permission -> "Permissão de mídia obrigatória ausente: $permission" },
    errorPlaybackLocalMediaQueryFailed = "Falha ao consultar a biblioteca de áudio local no MediaStore",
    errorPlaybackPlayerError = "O mecanismo de reprodução de mídia encontrou um erro",
    errorUnexpected = "Ocorreu um erro inesperado",
)

/**
 * Mexican Spanish (`es-MX`) string catalog.
 */
val EsMxStrings = AppStrings(
    locale = AppLocale.ES_MX,
    appName = "ListenTube",
    badgeScrobbled = "Scrobbleado",
    cdScrobbleQueue = "Cola de Scrobbles de ListenBrainz",
    navYoutubeMusic = "YouTube Music",
    navLocalAudio = "Audio Local",
    navListenBrainz = "ListenBrainz",
    cdDismissError = "Descartar error",
    cdDismissMessage = "Descartar mensaje",
    statusVerifiedUserFallback = "usuario verificado",
    statusScrobblingEnabled = "Scrobbling de ListenBrainz activado",
    statusScrobblingPaused = "Scrobbling de ListenBrainz en pausa",
    statusQueueUpToDate = "La cola de scrobbles está al día",
    statusClearedOfflineQueue = "Se vació la cola de scrobbles sin conexión",
    statusAddedToQueue = { title -> "Se agregó \"$title\" a la cola" },
    statusConnectedToListenBrainz = { userName -> "Conectado a ListenBrainz como $userName" },
    statusFlushedQueueCount = { count -> "Se enviaron $count escucha(s) en cola a ListenBrainz" },
    searchYtmPlaceholder = "Buscar canciones, artistas y álbumes en YouTube Music...",
    cdClearSearch = "Borrar búsqueda",
    actionSearch = "Buscar",
    cdSearch = "Buscar",
    cdBack = "Atrás",
    cdPlayAll = "Reproducir todo",
    sectionSongs = "Canciones",
    sectionAlbums = "Álbumes",
    sectionPlaylists = "Listas de reproducción",
    playlistEmptyTracks = "No se encontraron pistas en esta lista",
    emptyYtmSearchPrompt = "Busca en YouTube Music para transmitir y hacer scrobble de pistas",
    actionLoadMore = "Cargar más",
    filterLocalPlaceholder = "Filtrar archivos de audio locales...",
    cdRefreshLocalLibrary = "Actualizar biblioteca de audio local",
    emptyLocalLibrary = "No se encontraron pistas de audio locales en el dispositivo",
    cdAddToQueue = "Agregar a la cola",
    unknownArtist = "Artista desconocido",
    actionFilterFolders = "Carpetas",
    foldersDialogTitle = "Carpetas de audio",
    foldersDialogSubtitle = "Elige qué carpetas escanea la app",
    actionSelectAll = "Seleccionar todo",
    actionDeselectAll = "Deseleccionar todo",
    folderTrackCount = { count -> "$count pista(s)" },
    cdCollapsePlayer = "Contraer reproductor",
    renderModeAudioArt = "Portada",
    renderModeVideoSurface = "Video",
    videoSurfaceReady = { title -> "Superficie de video lista para \"$title\"" },
    cdToggleShuffle = "Alternar reproducción aleatoria",
    cdPreviousTrack = "Pista anterior",
    cdPlay = "Reproducir",
    cdPause = "Pausar",
    cdNextTrack = "Siguiente pista",
    cdCycleRepeat = "Cambiar modo de repetición",
    upNextHeader = { count -> "A Continuación ($count)" },
    lbScrobblerTitle = "Scrobbler de ListenBrainz",
    lbScrobblerSubtitleActive = "Activo • Umbral de 50% o 4:00",
    lbScrobblerSubtitleDisabled = "Scrobbling desactivado",
    lbCurrentSession = { trackName, artistName -> "Sesión Actual: $trackName — $artistName" },
    lbPlayingNowSent = "playing_now: Enviado",
    lbPlayingNowPending = "playing_now: Pendiente/En cola",
    lbCompletedListenRecorded = "Escucha Completada Registrada",
    lbIneligibleShortTrack = "No elegible (< 30s)",
    lbNoActiveSession = "No hay sesión de reproducción activa",
    lbApiQuotaRemaining = { remaining, limit -> "Cuota de API: $remaining / $limit restantes" },
    lbApiQuotaReady = "Cuota de API: Lista",
    lbRateLimitResetIn = { seconds -> "X-RateLimit-Reset-In: ${seconds}s" },
    actionFlushQueue = { count -> "Enviar Cola ($count)" },
    lbTokenSectionTitle = "Token de Usuario de ListenBrainz",
    lbTokenVerifiedBase = "Token verificado",
    lbTokenVerifiedWithUser = { userName -> "Token verificado como @$userName" },
    lbTokenValidationFailed = "Falló la validación del token. Verifica tu token de usuario de ListenBrainz.",
    lbTokenInputLabel = "Token de Usuario (de listenbrainz.org/settings)",
    cdShowToken = "Mostrar token",
    cdHideToken = "Ocultar token",
    actionValidateAndSaveToken = "Validar y Guardar Token",
    queuedPlayingNowHeader = "En Cola Sin Conexión: Reproduciendo Ahora",
    pendingOfflineScrobblesHeader = { count -> "Scrobbles Pendientes Sin Conexión ($count)" },
    pendingOfflineScrobblesSubtitle = "Guardados en Room • Se envían vía single/import al conectar",
    actionClearAll = "Vaciar Todo",
    notAvailableShort = "N/D",
    queuedPlayingNowMeta = { expiresText -> "playing_now • Expira $expiresText" },
    queuedCompletedMeta = { listenedText, attemptCount -> "Escuchada $listenedText • Intentos: $attemptCount" },
    queuedLastStatus = { error -> "Último estado: $error" },
    cdRemoveQueuedScrobble = "Eliminar scrobble en cola",
    emptyQueueTitle = "Todos los Scrobbles Sincronizados",
    emptyQueueSubtitle = "Cualquier pista reproducida sin conexión o durante un límite HTTP 429 se guardará en esta cola automáticamente.",
    errorNetworkOffline = "No hay conexión de red disponible",
    errorNetworkTimeout = "Se agotó el tiempo de espera de la solicitud de red",
    errorNetworkRateLimited = { resetInSeconds -> "Límite de peticiones de ListenBrainz alcanzado; reinicio en ${resetInSeconds}s" },
    errorNetworkUnauthorized = "Token de usuario de ListenBrainz inválido o no autorizado",
    errorNetworkBadRequest = "ListenBrainz rechazó la carga de envío por formato inválido",
    errorNetworkServerError = { httpCode -> "Error en el servidor de ListenBrainz (HTTP $httpCode)" },
    errorNetworkHttpError = { httpCode -> "Error HTTP $httpCode" },
    errorNetworkSerialization = "Error al serializar o deserializar los datos de red",
    errorNetworkUnknown = "Fallo inesperado en el transporte de red",
    errorStorageDatabase = "Falló la operación de la base de datos local",
    errorStorageDiskFull = "El almacenamiento del dispositivo está lleno",
    errorValidationMissingToken = "El token de usuario de ListenBrainz no está configurado",
    errorValidationInvalidMetadata = { field -> "El campo obligatorio de metadatos '$field' está vacío o es inválido" },
    errorValidationTrackTooShort = { durationMs, minDurationMs ->
        "La duración de la pista (${durationMs}ms) es menor que el umbral mínimo de scrobble (${minDurationMs}ms)"
    },
    errorPlaybackStreamExtractionFailed = { videoId -> "No se pudo extraer el flujo de audio para el video '$videoId'" },
    errorPlaybackUnplayableVideo = { videoId, status, reason ->
        if (reason.isNullOrBlank()) {
            "El video '$videoId' no se puede reproducir (estado=$status)"
        } else {
            "El video '$videoId' no se puede reproducir (estado=$status, motivo=$reason)"
        }
    },
    errorPlaybackNoAudioStream = { videoId -> "No se encontró ningún flujo de audio compatible para '$videoId'" },
    errorPlaybackPlayerJsFetchFailed = { url -> "No se pudo descargar el script del reproductor de YouTube desde '$url'" },
    errorPlaybackSignatureFunctionNotFound = { detail -> "No se pudo analizar la función de cifrado: $detail" },
    errorPlaybackCipherDecryptionFailed = { videoId, reason ->
        if (videoId.isNullOrBlank()) {
            "Falló el descifrado de firma: $reason"
        } else {
            "Falló el descifrado de firma para el video '$videoId': $reason"
        }
    },
    errorPlaybackPermissionDenied = { permission -> "Falta el permiso de medios requerido: $permission" },
    errorPlaybackLocalMediaQueryFailed = "Error al consultar la biblioteca de audio local en MediaStore",
    errorPlaybackPlayerError = "El motor de reproducción multimedia encontró un error",
    errorUnexpected = "Ocurrió un error inesperado",
)

/**
 * Russian (`ru-RU`) string catalog.
 */
val RuRuStrings = AppStrings(
    locale = AppLocale.RU_RU,
    appName = "ListenTube",
    badgeScrobbled = "Заскробблено",
    cdScrobbleQueue = "Очередь скробблов ListenBrainz",
    navYoutubeMusic = "YouTube Music",
    navLocalAudio = "Локальное аудио",
    navListenBrainz = "ListenBrainz",
    cdDismissError = "Скрыть ошибку",
    cdDismissMessage = "Скрыть сообщение",
    statusVerifiedUserFallback = "проверенный пользователь",
    statusScrobblingEnabled = "Скробблинг ListenBrainz включён",
    statusScrobblingPaused = "Скробблинг ListenBrainz приостановлен",
    statusQueueUpToDate = "Очередь скробблов актуальна",
    statusClearedOfflineQueue = "Офлайн-очередь скробблов очищена",
    statusAddedToQueue = { title -> "Трек «$title» добавлен в очередь" },
    statusConnectedToListenBrainz = { userName -> "Подключено к ListenBrainz как $userName" },
    statusFlushedQueueCount = { count -> "Отправлено прослушиваний из очереди в ListenBrainz: $count" },
    searchYtmPlaceholder = "Поиск песен, исполнителей и альбомов в YouTube Music...",
    cdClearSearch = "Очистить поиск",
    actionSearch = "Найти",
    cdSearch = "Поиск",
    cdBack = "Назад",
    cdPlayAll = "Воспроизвести всё",
    sectionSongs = "Песни",
    sectionAlbums = "Альбомы",
    sectionPlaylists = "Плейлисты",
    playlistEmptyTracks = "В этом плейлисте нет треков",
    emptyYtmSearchPrompt = "Ищите в YouTube Music для прослушивания и скробблинга треков",
    actionLoadMore = "Загрузить ещё",
    filterLocalPlaceholder = "Фильтр локальных аудиофайлов...",
    cdRefreshLocalLibrary = "Обновить локальную фонотеку",
    emptyLocalLibrary = "На устройстве не найдено локальных аудиотреков",
    cdAddToQueue = "Добавить в очередь",
    unknownArtist = "Неизвестный исполнитель",
    actionFilterFolders = "Папки",
    foldersDialogTitle = "Папки с аудио",
    foldersDialogSubtitle = "Выберите, какие папки сканировать",
    actionSelectAll = "Выбрать все",
    actionDeselectAll = "Снять выбор",
    folderTrackCount = { count -> "$count трек(ов)" },
    cdCollapsePlayer = "Свернуть плеер",
    renderModeAudioArt = "Обложка",
    renderModeVideoSurface = "Видео",
    videoSurfaceReady = { title -> "Видеоповерхность готова для «$title»" },
    cdToggleShuffle = "Переключить случайный порядок",
    cdPreviousTrack = "Предыдущий трек",
    cdPlay = "Воспроизвести",
    cdPause = "Пауза",
    cdNextTrack = "Следующий трек",
    cdCycleRepeat = "Переключить режим повтора",
    upNextHeader = { count -> "Далее в очереди ($count)" },
    lbScrobblerTitle = "Скробблер ListenBrainz",
    lbScrobblerSubtitleActive = "Активен • Порог 50% или 4:00",
    lbScrobblerSubtitleDisabled = "Скробблинг отключён",
    lbCurrentSession = { trackName, artistName -> "Текущая сессия: $trackName — $artistName" },
    lbPlayingNowSent = "playing_now: Отправлено",
    lbPlayingNowPending = "playing_now: Ожидает / В очереди",
    lbCompletedListenRecorded = "Прослушивание засчитано",
    lbIneligibleShortTrack = "Не подходит (< 30 с)",
    lbNoActiveSession = "Нет активной сессии воспроизведения",
    lbApiQuotaRemaining = { remaining, limit -> "Квота API: осталось $remaining из $limit" },
    lbApiQuotaReady = "Квота API: Доступна",
    lbRateLimitResetIn = { seconds -> "X-RateLimit-Reset-In: ${seconds} с" },
    actionFlushQueue = { count -> "Отправить очередь ($count)" },
    lbTokenSectionTitle = "Токен пользователя ListenBrainz",
    lbTokenVerifiedBase = "Токен подтверждён",
    lbTokenVerifiedWithUser = { userName -> "Токен подтверждён для @$userName" },
    lbTokenValidationFailed = "Не удалось проверить токен. Проверьте ваш пользовательский токен ListenBrainz.",
    lbTokenInputLabel = "Токен пользователя (с сайта listenbrainz.org/settings)",
    cdShowToken = "Показать токен",
    cdHideToken = "Скрыть токен",
    actionValidateAndSaveToken = "Проверить и сохранить токен",
    queuedPlayingNowHeader = "Офлайн-очередь: Сейчас играет",
    pendingOfflineScrobblesHeader = { count -> "Ожидающие офлайн-скробблы ($count)" },
    pendingOfflineScrobblesSubtitle = "Сохранено в Room • Отправка через single/import при появлении сети",
    actionClearAll = "Очистить всё",
    notAvailableShort = "Н/Д",
    queuedPlayingNowMeta = { expiresText -> "playing_now • Истекает: $expiresText" },
    queuedCompletedMeta = { listenedText, attemptCount -> "Прослушано: $listenedText • Попыток: $attemptCount" },
    queuedLastStatus = { error -> "Последний статус: $error" },
    cdRemoveQueuedScrobble = "Удалить скроббл из очереди",
    emptyQueueTitle = "Все скробблы синхронизированы",
    emptyQueueSubtitle = "Любые треки, прослушанные офлайн или во время ограничения скорости HTTP 429, будут автоматически добавлены в эту очередь.",
    errorNetworkOffline = "Отсутствует подключение к сети",
    errorNetworkTimeout = "Превышено время ожидания сетевого запроса",
    errorNetworkRateLimited = { resetInSeconds -> "Превышен лимит запросов ListenBrainz; сброс через ${resetInSeconds} с" },
    errorNetworkUnauthorized = "Недействительный или неавторизованный токен пользователя ListenBrainz",
    errorNetworkBadRequest = "Сервер ListenBrainz отклонил некорректные данные запроса",
    errorNetworkServerError = { httpCode -> "Ошибка сервера ListenBrainz (HTTP $httpCode)" },
    errorNetworkHttpError = { httpCode -> "Ошибка HTTP $httpCode" },
    errorNetworkSerialization = "Ошибка сериализации или десериализации сетевых данных",
    errorNetworkUnknown = "Непредвиденная ошибка сетевого транспорта",
    errorStorageDatabase = "Ошибка операции локальной базы данных",
    errorStorageDiskFull = "Хранилище устройства заполнено",
    errorValidationMissingToken = "Токен пользователя ListenBrainz не настроен",
    errorValidationInvalidMetadata = { field -> "Обязательное поле метаданных трека «$field» пусто или некорректно" },
    errorValidationTrackTooShort = { durationMs, minDurationMs ->
        "Длительность трека (${durationMs} мс) меньше минимального порога скробблинга (${minDurationMs} мс)"
    },
    errorPlaybackStreamExtractionFailed = { videoId -> "Не удалось извлечь аудиопоток для видео «$videoId»" },
    errorPlaybackUnplayableVideo = { videoId, status, reason ->
        if (reason.isNullOrBlank()) {
            "Видео «$videoId» недоступно для воспроизведения (статус=$status)"
        } else {
            "Видео «$videoId» недоступно для воспроизведения (статус=$status, причина=$reason)"
        }
    },
    errorPlaybackNoAudioStream = { videoId -> "Для «$videoId» не найден совместимый аудиопоток" },
    errorPlaybackPlayerJsFetchFailed = { url -> "Не удалось загрузить скрипт плеера YouTube из «$url»" },
    errorPlaybackSignatureFunctionNotFound = { detail -> "Не удалось разобрать функцию шифра: $detail" },
    errorPlaybackCipherDecryptionFailed = { videoId, reason ->
        if (videoId.isNullOrBlank()) {
            "Ошибка расшифровки подписи: $reason"
        } else {
            "Ошибка расшифровки подписи для видео «$videoId»: $reason"
        }
    },
    errorPlaybackPermissionDenied = { permission -> "Отсутствует необходимое разрешение для медиа: $permission" },
    errorPlaybackLocalMediaQueryFailed = "Не удалось запросить локальную фонотеку MediaStore",
    errorPlaybackPlayerError = "Произошла ошибка движка воспроизведения медиа",
    errorUnexpected = "Произошла непредвиденная ошибка",
)

/**
 * Returns the [AppStrings] catalog corresponding to [locale].
 */
fun stringsForLocale(locale: AppLocale): AppStrings = when (locale) {
    AppLocale.EN_US -> EnUsStrings
    AppLocale.PT_BR -> PtBrStrings
    AppLocale.ES_MX -> EsMxStrings
    AppLocale.RU_RU -> RuRuStrings
}

/**
 * Formats this [DomainError] into a user-facing localized message using [strings].
 */
fun DomainError.localizedMessage(strings: AppStrings): String = when (this) {
    is DomainError.Network.Offline -> strings.errorNetworkOffline
    is DomainError.Network.Timeout -> strings.errorNetworkTimeout
    is DomainError.Network.RateLimited -> strings.errorNetworkRateLimited(resetInSeconds)
    is DomainError.Network.Unauthorized -> strings.errorNetworkUnauthorized
    is DomainError.Network.BadRequest -> strings.errorNetworkBadRequest
    is DomainError.Network.ServerError -> strings.errorNetworkServerError(httpCode)
    is DomainError.Network.HttpError -> strings.errorNetworkHttpError(httpCode)
    is DomainError.Network.Serialization -> strings.errorNetworkSerialization
    is DomainError.Network.Unknown -> strings.errorNetworkUnknown
    is DomainError.Storage.DatabaseError -> strings.errorStorageDatabase
    is DomainError.Storage.DiskFull -> strings.errorStorageDiskFull
    is DomainError.Validation.MissingToken -> strings.errorValidationMissingToken
    is DomainError.Validation.InvalidMetadata -> strings.errorValidationInvalidMetadata(field)
    is DomainError.Validation.TrackTooShort -> strings.errorValidationTrackTooShort(durationMs, minimumDurationMs)
    is DomainError.Playback.StreamExtractionFailed -> strings.errorPlaybackStreamExtractionFailed(videoId)
    is DomainError.Playback.UnplayableVideo -> strings.errorPlaybackUnplayableVideo(videoId, playabilityStatus, reason)
    is DomainError.Playback.NoAudioStreamFound -> strings.errorPlaybackNoAudioStream(videoId)
    is DomainError.Playback.PlayerJsFetchFailed -> strings.errorPlaybackPlayerJsFetchFailed(playerJsUrl)
    is DomainError.Playback.SignatureFunctionNotFound -> strings.errorPlaybackSignatureFunctionNotFound(detail)
    is DomainError.Playback.CipherDecryptionFailed -> strings.errorPlaybackCipherDecryptionFailed(videoId, reason)
    is DomainError.Playback.PermissionDenied -> strings.errorPlaybackPermissionDenied(permission)
    is DomainError.Playback.LocalMediaQueryFailed -> strings.errorPlaybackLocalMediaQueryFailed
    is DomainError.Playback.PlayerError -> strings.errorPlaybackPlayerError
    is DomainError.Unexpected -> strings.errorUnexpected
}

val LocalAppLocale = staticCompositionLocalOf { AppLocale.fromLocale() }

val LocalAppStrings = staticCompositionLocalOf { stringsForLocale(AppLocale.fromLocale()) }

/**
 * Resolves the active [AppLocale] from the Android system configuration (which reflects both
 * system-wide language settings and Android 13+ per-app language preferences).
 */
@Composable
fun rememberSystemAppLocale(): AppLocale {
    val configuration = LocalConfiguration.current
    val primaryLocale = ConfigurationCompat.getLocales(configuration)[0] ?: Locale.getDefault()
    return AppLocale.fromLocale(primaryLocale)
}

/**
 * Provides [LocalAppLocale] and [LocalAppStrings] to the Compose hierarchy for [locale].
 */
@Composable
fun ProvideAppStrings(
    locale: AppLocale = rememberSystemAppLocale(),
    content: @Composable () -> Unit,
) {
    val strings = stringsForLocale(locale)
    CompositionLocalProvider(
        LocalAppLocale provides locale,
        LocalAppStrings provides strings,
        content = content,
    )
}
