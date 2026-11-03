package com.feizhela.headband.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.feizhela.headband.R
import com.feizhela.headband.ui.model.ConnectionUiState
import com.feizhela.headband.ui.model.ConnectionUiStatus
import com.feizhela.headband.ui.util.connectionStatusLabel
import com.feizhela.headband.ui.util.formatClock

/**
 * Cross-screen connection and acquisition banner.
 *
 * Shows device identity, phase and last-sample age; on a broken link it exposes the reconnect
 * action instead of simply colouring the screen red. A disconnected session is never presented as
 * “recording in progress”.
 */
@Composable
fun ConnectionStatusBar(
    connection: ConnectionUiState,
    modifier: Modifier = Modifier,
    phaseLabel: String? = null,
    onReconnect: (() -> Unit)? = null,
    onOpenDevice: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val tone = when (connection.status) {
        ConnectionUiStatus.Connected -> StatusTone.Success
        ConnectionUiStatus.Connecting, ConnectionUiStatus.Scanning -> StatusTone.Info
        ConnectionUiStatus.Error, ConnectionUiStatus.Unavailable -> StatusTone.Danger
        ConnectionUiStatus.Disconnected -> StatusTone.Neutral
    }
    val icon = when (connection.status) {
        ConnectionUiStatus.Connected -> Icons.Filled.Check
        ConnectionUiStatus.Connecting -> Icons.Filled.Refresh
        ConnectionUiStatus.Scanning -> Icons.Filled.Search
        ConnectionUiStatus.Error, ConnectionUiStatus.Unavailable -> Icons.Filled.Warning
        ConnectionUiStatus.Disconnected -> Icons.Filled.Info
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        border = BorderStroke(1.dp, scheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill(
                    text = connectionStatusLabel(connection.status),
                    tone = tone,
                    icon = icon,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = connection.deviceName ?: stringResource(R.string.connection_no_device),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (onOpenDevice != null) {
                    TextButton(onClick = onOpenDevice) {
                        Text(stringResource(R.string.nav_device))
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (connection.lastPacketAtMillis != null) {
                    Text(
                        text = stringResource(
                            R.string.connection_last_packet,
                            formatClock(connection.lastPacketAtMillis),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = connection.deviceAddress ?: stringResource(R.string.value_unknown),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                if (phaseLabel != null) {
                    Text(
                        text = phaseLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            if (connection.status == ConnectionUiStatus.Error && connection.lastError != null) {
                Text(
                    text = stringResource(R.string.connection_error_detail, connection.lastError),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.error,
                )
            }
            if (connection.status == ConnectionUiStatus.Disconnected && onReconnect != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.tonight_hint_keep_foreground),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = onReconnect) {
                        Text(stringResource(R.string.action_reconnect))
                    }
                }
            }
        }
    }
}

/**
 * Very small connection dot for the top app bar (text is available through the content
 * description and on the screens themselves, so the dot alone is not the only cue).
 */
@Composable
fun ConnectionIndicator(
    connection: ConnectionUiState,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val color = when (connection.status) {
        ConnectionUiStatus.Connected -> scheme.primary
        ConnectionUiStatus.Connecting, ConnectionUiStatus.Scanning -> scheme.tertiary
        ConnectionUiStatus.Error, ConnectionUiStatus.Unavailable -> scheme.error
        ConnectionUiStatus.Disconnected -> scheme.outline
    }
    Surface(
        modifier = modifier.size(18.dp),
        shape = CircleShape,
        color = color,
        contentColor = scheme.onPrimary,
    ) {
        Spacer(Modifier.size(18.dp))
    }
}
