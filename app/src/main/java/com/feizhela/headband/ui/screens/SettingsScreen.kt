package com.feizhela.headband.ui.screens

import android.os.Build
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import com.feizhela.headband.BuildConfig
import com.feizhela.headband.R
import com.feizhela.headband.ui.ThemeMode
import com.feizhela.headband.ui.components.EvidenceNotice
import com.feizhela.headband.ui.components.MetricRow
import com.feizhela.headband.ui.components.SectionCard

/**
 * Settings: appearance (light/dark/system + Android 12 dynamic colour), data export/delete,
 * privacy/licences, algorithm/evidence boundary, version and diagnostics.
 *
 * Destructive and export actions show their consequences in a dialog before the placeholder
 * callback fires. This screen has no direct database or file access.
 */
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    sessionCount: Int,
    onExportAll: () -> Unit,
    onDeleteAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showExportDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    val supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val buildLabel = if (BuildConfig.DEBUG) {
        stringResource(R.string.settings_build_debug)
    } else {
        stringResource(R.string.settings_build_release)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_screen"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("intro") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.settings_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item("appearance") {
            SectionCard(title = stringResource(R.string.settings_appearance_title)) {
                Text(
                    text = stringResource(R.string.settings_theme_mode),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = themeMode == mode,
                            onClick = { onThemeModeChange(mode) },
                            label = { Text(themeModeLabel(mode)) },
                            modifier = Modifier.testTag("theme_mode_${mode.name.lowercase()}"),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_dynamic_color),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.settings_dynamic_color_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = dynamicColor && supportsDynamicColor,
                        enabled = supportsDynamicColor,
                        onCheckedChange = onDynamicColorChange,
                        modifier = Modifier.testTag("dynamic_color_switch"),
                    )
                }
                if (!supportsDynamicColor) {
                    Text(
                        text = stringResource(R.string.settings_dynamic_color_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.settings_theme_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item("data") {
            SectionCard(title = stringResource(R.string.settings_data_title)) {
                Text(
                    text = stringResource(R.string.records_count, sessionCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.settings_export_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.settings_export_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { showExportDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("export_all"),
                ) {
                    Text(stringResource(R.string.action_export))
                }
                Text(
                    text = stringResource(R.string.settings_delete_all_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.settings_delete_all_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("delete_all"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(stringResource(R.string.action_delete))
                }
                Text(
                    text = stringResource(R.string.settings_demo_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item("privacy") {
            SectionCard(title = stringResource(R.string.settings_privacy_title)) {
                Text(
                    text = stringResource(R.string.settings_privacy_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        item("license") {
            SectionCard(title = stringResource(R.string.settings_license_title)) {
                Text(
                    text = stringResource(R.string.settings_license_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        item("algorithm") {
            SectionCard(title = stringResource(R.string.settings_about_algorithm_title)) {
                Text(
                    text = stringResource(R.string.settings_about_algorithm_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                EvidenceNotice()
                Text(
                    text = stringResource(R.string.settings_model_unavailable_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = stringResource(R.string.settings_model_unavailable_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item("diagnostics") {
            SectionCard(title = stringResource(R.string.settings_diagnostic_title)) {
                MetricRow(
                    label = stringResource(R.string.settings_app_version),
                    value = stringResource(
                        R.string.settings_version_value,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE,
                    ),
                )
                MetricRow(
                    label = stringResource(R.string.settings_build_type),
                    value = buildLabel,
                )
                MetricRow(
                    label = stringResource(R.string.settings_model_version),
                    value = stringResource(R.string.value_unknown),
                )
                MetricRow(
                    label = stringResource(R.string.settings_rules_version),
                    value = stringResource(R.string.value_unknown),
                )
                MetricRow(
                    label = stringResource(R.string.settings_diag_source),
                    value = stringResource(R.string.settings_diag_demo),
                )
                MetricRow(
                    label = stringResource(R.string.settings_storage),
                    value = stringResource(R.string.settings_diag_real),
                    hint = stringResource(R.string.settings_storage_body),
                )
                Text(
                    text = stringResource(R.string.settings_about_line),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showExportDialog) {
        SettingsConfirmDialog(
            title = stringResource(R.string.settings_confirm_export_title),
            body = stringResource(R.string.settings_confirm_export_body),
            confirmLabel = stringResource(R.string.action_export_placeholder),
            destructive = false,
            onConfirm = {
                showExportDialog = false
                onExportAll()
            },
            onDismiss = { showExportDialog = false },
        )
    }
    if (showDeleteDialog) {
        SettingsConfirmDialog(
            title = stringResource(R.string.settings_confirm_delete_title),
            body = stringResource(R.string.settings_confirm_delete_body),
            confirmLabel = stringResource(R.string.action_confirm_delete),
            destructive = true,
            onConfirm = {
                showDeleteDialog = false
                onDeleteAll()
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

@Composable
private fun themeModeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.System -> R.string.settings_theme_system
        ThemeMode.Light -> R.string.settings_theme_light
        ThemeMode.Dark -> R.string.settings_theme_dark
    },
)

@Composable
private fun SettingsConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
