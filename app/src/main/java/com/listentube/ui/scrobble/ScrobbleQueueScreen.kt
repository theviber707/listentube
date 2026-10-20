// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube.ui.scrobble

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.listentube.data.listenbrainz.local.QueuedScrobbleEntity
import com.listentube.data.listenbrainz.model.RateLimitInfo
import com.listentube.domain.scrobble.ScrobbleSessionState
import com.listentube.ui.i18n.LocalAppLocale
import com.listentube.ui.i18n.LocalAppStrings
import com.listentube.ui.player.PlayerUiState
import com.listentube.ui.player.formatDurationMs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screen displaying live ListenBrainz session & rate-limit telemetry, user token configuration
 * with online validation, and the persistent Room offline queue (`playing_now` + `completed` listens).
 */
@Composable
fun ScrobbleQueueScreen(
    uiState: PlayerUiState,
    onTokenInputChanged: (String) -> Unit,
    onValidateAndSaveToken: () -> Unit,
    onToggleScrobblingEnabled: (Boolean) -> Unit,
    onFlushQueueNow: () -> Unit,
    onRemoveQueuedItem: (Long) -> Unit,
    onClearQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "live_status_card") {
            LiveListenBrainzStatusCard(
                sessionState = uiState.scrobbleSession,
                rateLimitInfo = uiState.rateLimitInfo,
                scrobblingEnabled = uiState.scrobblingEnabled,
                isFlushingQueue = uiState.isFlushingQueue,
                totalQueuedCount = uiState.totalQueuedCount,
                onToggleScrobblingEnabled = onToggleScrobblingEnabled,
                onFlushQueueNow = onFlushQueueNow,
            )
        }

        item(key = "token_config_card") {
            ListenBrainzTokenConfigCard(
                tokenInput = uiState.listenBrainzTokenInput,
                configuredTokenMasked = uiState.configuredTokenMasked,
                validatedUserName = uiState.validatedUserName,
                isTokenValid = uiState.isTokenValid,
                isValidating = uiState.isValidatingToken,
                onTokenInputChanged = onTokenInputChanged,
                onValidateAndSave = onValidateAndSaveToken,
            )
        }

        val queuedPlayingNow = uiState.queuedPlayingNow
        if (queuedPlayingNow != null) {
            item(key = "queued_playing_now_header") {
                Text(
                    text = strings.queuedPlayingNowHeader,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            item(key = "queued_playing_now_${queuedPlayingNow.id}") {
                QueuedScrobbleItemCard(
                    entity = queuedPlayingNow,
                    isPlayingNow = true,
                    onRemove = { onRemoveQueuedItem(queuedPlayingNow.id) },
                )
            }
        }

        item(key = "completed_queue_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strings.pendingOfflineScrobblesHeader(uiState.pendingCompletedCount),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = strings.pendingOfflineScrobblesSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (uiState.totalQueuedCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(onClick = onClearQueue) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.actionClearAll)
                    }
                }
            }
        }

        if (uiState.pendingCompletedScrobbles.isEmpty()) {
            item(key = "empty_queue_state") {
                EmptyScrobbleQueueCard()
            }
        } else {
            items(
                items = uiState.pendingCompletedScrobbles,
                key = { it.id },
            ) { entity ->
                QueuedScrobbleItemCard(
                    entity = entity,
                    isPlayingNow = false,
                    onRemove = { onRemoveQueuedItem(entity.id) },
                )
            }
        }
    }
}

@Composable
private fun LiveListenBrainzStatusCard(
    sessionState: ScrobbleSessionState,
    rateLimitInfo: RateLimitInfo,
    scrobblingEnabled: Boolean,
    isFlushingQueue: Boolean,
    totalQueuedCount: Int,
    onToggleScrobblingEnabled: (Boolean) -> Unit,
    onFlushQueueNow: () -> Unit,
) {
    val strings = LocalAppStrings.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = if (scrobblingEnabled) Icons.Default.CloudSync else Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = if (scrobblingEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Column {
                        Text(
                            text = strings.lbScrobblerTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = if (scrobblingEnabled) {
                                strings.lbScrobblerSubtitleActive
                            } else {
                                strings.lbScrobblerSubtitleDisabled
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Switch(
                    checked = scrobblingEnabled,
                    onCheckedChange = onToggleScrobblingEnabled,
                )
            }

            HorizontalDivider()

            // Active Playback Session Telemetry
            val activeTrack = sessionState.currentTrack
            if (activeTrack != null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = strings.lbCurrentSession(activeTrack.trackName, activeTrack.artistName),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        val playingNowText = if (sessionState.playingNowDispatched) {
                            strings.lbPlayingNowSent
                        } else {
                            strings.lbPlayingNowPending
                        }
                        Text(
                            text = playingNowText,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        if (sessionState.scrobbleCompleted || (sessionState.isEligibleForScrobble && sessionState.thresholdMs > 0L)) {
                            val thresholdStatus = if (sessionState.scrobbleCompleted) {
                                strings.lbCompletedListenRecorded
                            } else {
                                "${formatDurationMs(sessionState.accumulatedPlayedMs)} / ${formatDurationMs(sessionState.thresholdMs)}"
                            }
                            Text(
                                text = thresholdStatus,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = if (sessionState.scrobbleCompleted) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }

                    if (sessionState.isEligibleForScrobble && sessionState.thresholdMs > 0L) {
                        LinearProgressIndicator(
                            progress = { sessionState.progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                        )
                    }
                }
            } else {
                Text(
                    text = strings.lbNoActiveSession,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // HTTP 429 X-RateLimit-* Telemetry Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    val quotaText = if (rateLimitInfo.remaining != null && rateLimitInfo.limit != null) {
                        strings.lbApiQuotaRemaining(rateLimitInfo.remaining, rateLimitInfo.limit)
                    } else {
                        strings.lbApiQuotaReady
                    }
                    Text(
                        text = quotaText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (rateLimitInfo.resetInSeconds != null) {
                        Text(
                            text = strings.lbRateLimitResetIn(rateLimitInfo.resetInSeconds),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Button(
                    onClick = onFlushQueueNow,
                    enabled = !isFlushingQueue && totalQueuedCount > 0,
                ) {
                    if (isFlushingQueue) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.actionFlushQueue(totalQueuedCount))
                }
            }
        }
    }
}

@Composable
private fun ListenBrainzTokenConfigCard(
    tokenInput: String,
    configuredTokenMasked: String?,
    validatedUserName: String?,
    isTokenValid: Boolean?,
    isValidating: Boolean,
    onTokenInputChanged: (String) -> Unit,
    onValidateAndSave: () -> Unit,
) {
    val strings = LocalAppStrings.current
    var showRawToken by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = strings.lbTokenSectionTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (isTokenValid == true) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        val badgeText = buildString {
                            if (!validatedUserName.isNullOrBlank()) {
                                append(strings.lbTokenVerifiedWithUser(validatedUserName))
                            } else {
                                append(strings.lbTokenVerifiedBase)
                            }
                            if (!configuredTokenMasked.isNullOrBlank()) {
                                append(" ($configuredTokenMasked)")
                            }
                        }
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            } else if (isTokenValid == false) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            text = strings.lbTokenValidationFailed,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            OutlinedTextField(
                value = tokenInput,
                onValueChange = onTokenInputChanged,
                label = { Text(strings.lbTokenInputLabel) },
                singleLine = true,
                visualTransformation = if (showRawToken) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { showRawToken = !showRawToken }) {
                        Icon(
                            imageVector = if (showRawToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showRawToken) strings.cdHideToken else strings.cdShowToken,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = onValidateAndSave,
                enabled = !isValidating && tokenInput.isNotBlank(),
                modifier = Modifier.align(Alignment.End),
            ) {
                if (isValidating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(strings.actionValidateAndSaveToken)
            }
        }
    }
}

@Composable
private fun QueuedScrobbleItemCard(
    entity: QueuedScrobbleEntity,
    isPlayingNow: Boolean,
    onRemove: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val locale = LocalAppLocale.current.javaLocale

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isPlayingNow) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = entity.trackName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = buildString {
                        append(entity.artistName)
                        if (!entity.releaseName.isNullOrBlank()) {
                            append(" • ")
                            append(entity.releaseName)
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val timestampLabel = if (isPlayingNow) {
                        val expiresText = entity.expiresAtEpochMs
                            ?.let { formatEpochMs(it, locale) }
                            ?: strings.notAvailableShort
                        strings.queuedPlayingNowMeta(expiresText)
                    } else {
                        val listenedText = entity.listenedAtEpochSeconds
                            ?.let { formatEpochMs(it * 1_000L, locale) }
                            ?: formatEpochMs(entity.createdAtEpochMs, locale)
                        strings.queuedCompletedMeta(listenedText, entity.attemptCount)
                    }
                    Text(
                        text = timestampLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (!entity.lastError.isNullOrBlank()) {
                    Text(
                        text = strings.queuedLastStatus(entity.lastError),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = strings.cdRemoveQueuedScrobble,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyScrobbleQueueCard() {
    val strings = LocalAppStrings.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.CloudDone,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = strings.emptyQueueTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = strings.emptyQueueSubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatEpochMs(epochMs: Long, locale: Locale = Locale.US): String {
    val formatter = SimpleDateFormat("MMM d, HH:mm:ss", locale)
    return formatter.format(Date(epochMs))
}
