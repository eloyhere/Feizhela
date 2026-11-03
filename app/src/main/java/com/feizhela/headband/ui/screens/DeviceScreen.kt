package com.feizhela.headband.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.feizhela.headband.R
import com.feizhela.headband.ui.components.DemoDataNotice
import com.feizhela.headband.ui.components.EmptyState
import com.feizhela.headband.ui.components.MetricRow
import com.feizhela.headband.ui.components.SectionCard
import com.feizhela.headband.ui.components.StatusPill
import com.feizhela.headband.ui.components.StatusTone
import com.feizhela.headband.ui.model.ConnectionUiStatus
import com.feizhela.headband.ui.model.DeviceUi
import com.feizhela.headband.ui.model.DeviceUiState
import com.feizhela.headband.ui.util.connectionStatusLabel

/**
 * Device screen: scan/connect/disconnect, permission explanation (only after the user taps scan),
 * connection facts (firmware/battery are “unknown” unless a protocol provides them), raw preview
 * placeholder, calibration notes and a disabled motor-test entry.
 *
 * It never scans on its own, never requests a permission automatically and never fabricates
 * connection or battery values.
 */
@Composable
fun DeviceScreen(
    state: DeviceUiState,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (DeviceUi) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var permissionExplained by rememberSaveable { mutableStateOf(false) }
    var showPermissionDialog by rememberSaveable { mutableStateOf(false) }
    val connection = state.connection
    val connectedAddress = connection.deviceAddress

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("device_screen"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("intro") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.device_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item("connection") {
            SectionCard(title = stringResource(R.string.device_connected_title)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(
                        text = connectionStatusLabel(connection.status),
                        tone = when (connection.status) {
                            ConnectionUiStatus.Connected -> StatusTone.Success
                            ConnectionUiStatus.Connecting, ConnectionUiStatus.Scanning -> StatusTone.Info
                            ConnectionUiStatus.Error, ConnectionUiStatus.Unavailable -> StatusTone.Danger
                            ConnectionUiStatus.Disconnected -> StatusTone.Neutral
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = connection.deviceName ?: stringResource(R.string.device_none_connected),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (connection.isConnected) {
                        OutlinedButton(
                            onClick = onDisconnect,
                            modifier = Modifier.testTag("disconnect_device"),
                        ) {
                            Text(stringResource(R.string.action_disconnect))
                        }
                    }
                }
                MetricRow(
                    label = stringResource(R.string.device_address),
                    value = connection.deviceAddress ?: stringResource(R.string.value_unknown),
                )
                MetricRow(
                    label = stringResource(R.string.device_services),
                    value = if (state.servicesDiscovered) {
                        stringResource(R.string.device_services_done)
                    } else {
                        stringResource(R.string.device_services_pending)
                    },
                )
                MetricRow(
                    label = stringResource(R.string.device_firmware),
                    value = state.firmwareVersion ?: stringResource(R.string.device_unknown),
                )
                MetricRow(
                    label = stringResource(R.string.device_battery),
                    value = state.batteryPercent?.let { "$it%" } ?: stringResource(R.string.device_unknown),
                )
                val error = state.lastError ?: connection.lastError
                if (error != null) {
                    Text(
                        text = stringResource(R.string.device_error_prefix, error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = stringResource(R.string.device_linked_record_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item("scan") {
            SectionCard(
                title = stringResource(R.string.device_scan_title),
                subtitle = stringResource(R.string.device_scan_hint),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.scanning) {
                        OutlinedButton(
                            onClick = onStopScan,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("scan_button"),
                        ) {
                            Text(stringResource(R.string.action_stop_scan))
                        }
                    } else {
                        Button(
                            onClick = {
                                if (permissionExplained) {
                                    onScan()
                                } else {
                                    showPermissionDialog = true
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("scan_button"),
                        ) {
                            Text(stringResource(R.string.action_scan))
                        }
                    }
                }
                if (state.scanning) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = stringResource(R.string.device_scanning_label),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (state.devices.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.device_empty_title),
                        description = stringResource(R.string.device_empty_body),
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.devices.forEach { device ->
                            DeviceRow(
                                device = device,
                                connected = connection.isConnected && device.address == connectedAddress,
                                connecting = connection.status == ConnectionUiStatus.Connecting &&
                                    device.address == connectedAddress,
                                onConnect = { onConnect(device) },
                                canConnect = !connection.isConnected && !connection.isTransitioning,
                            )
                        }
                    }
                }
            }
        }

        item("raw") {
            SectionCard(
                title = stringResource(R.string.device_raw_title),
                subtitle = stringResource(R.string.device_raw_body),
            ) {
                EmptyState(
                    title = stringResource(R.string.device_raw_placeholder),
                    description = stringResource(R.string.chart_canvas_note),
                )
            }
        }

        item("calibration") {
            SectionCard(title = stringResource(R.string.device_calibration_title)) {
                Text(
                    text = stringResource(R.string.device_calibration_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        item("diagnostics") {
            SectionCard(title = stringResource(R.string.device_diagnostics_title)) {
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.device_test_tone))
                }
                Text(
                    text = stringResource(R.string.device_test_tone_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item("demo") {
            Text(
                text = stringResource(R.string.device_demo_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text(stringResource(R.string.device_permission_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.device_permission_body))
                    Text(
                        text = stringResource(R.string.device_permission_demo_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        permissionExplained = true
                        showPermissionDialog = false
                        onScan()
                    },
                ) {
                    Text(stringResource(R.string.device_permission_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun DeviceRow(
    device: DeviceUi,
    connected: Boolean,
    connecting: Boolean,
    onConnect: () -> Unit,
    canConnect: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("device_item_${device.address}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant.copy(alpha = 0.45f)),
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.6f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = device.name ?: stringResource(R.string.device_name_unknown),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                Text(
                    text = device.rssi?.let { stringResource(R.string.unit_dbm, it) }
                        ?: stringResource(R.string.device_rssi) + " " + stringResource(R.string.value_unknown),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            when {
                connected -> StatusPill(
                    text = stringResource(R.string.status_connected),
                    tone = StatusTone.Success,
                )

                connecting -> StatusPill(
                    text = stringResource(R.string.status_connecting),
                    tone = StatusTone.Info,
                )

                else -> Button(
                    onClick = onConnect,
                    enabled = canConnect,
                    modifier = Modifier.testTag("connect_${device.address}"),
                ) {
                    Text(stringResource(R.string.action_connect))
                }
            }
        }
    }
}
