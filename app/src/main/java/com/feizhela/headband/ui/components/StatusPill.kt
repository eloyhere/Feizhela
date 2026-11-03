package com.feizhela.headband.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.feizhela.headband.ui.model.QualityLevel
import com.feizhela.headband.ui.model.SignalCheckStatus

/** Semantic tone for pills. Text and icon always carry the meaning, colour is secondary. */
enum class StatusTone { Neutral, Info, Success, Warning, Danger }

private data class ToneColors(val container: Color, val content: Color)

@Composable
private fun toneColors(tone: StatusTone): ToneColors {
    val scheme = MaterialTheme.colorScheme
    return when (tone) {
        StatusTone.Neutral -> ToneColors(scheme.surfaceVariant, scheme.onSurfaceVariant)
        StatusTone.Info -> ToneColors(scheme.secondaryContainer, scheme.onSecondaryContainer)
        StatusTone.Success -> ToneColors(scheme.primaryContainer, scheme.onPrimaryContainer)
        StatusTone.Warning -> ToneColors(scheme.tertiaryContainer, scheme.onTertiaryContainer)
        StatusTone.Danger -> ToneColors(scheme.errorContainer, scheme.onErrorContainer)
    }
}

private fun defaultIcon(tone: StatusTone): ImageVector = when (tone) {
    StatusTone.Success -> Icons.Filled.Check
    StatusTone.Warning, StatusTone.Danger -> Icons.Filled.Warning
    StatusTone.Info, StatusTone.Neutral -> Icons.Filled.Info
}

/**
 * Small label + icon used for phases, statuses and quality levels.
 *
 * The text label is required, so colour is never the only information channel.
 */
@Composable
fun StatusPill(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accessibilityLabel: String? = null,
) {
    val colors = toneColors(tone)
    Surface(
        shape = CircleShape,
        color = colors.container,
        contentColor = colors.content,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .semantics(mergeDescendants = true) {
                    accessibilityLabel?.let { this.contentDescription = it }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon ?: defaultIcon(tone),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(text = text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun qualityTone(level: QualityLevel): StatusTone = when (level) {
    QualityLevel.Good -> StatusTone.Success
    QualityLevel.Fair -> StatusTone.Warning
    QualityLevel.Poor -> StatusTone.Danger
    QualityLevel.Rejected -> StatusTone.Danger
    QualityLevel.Unknown -> StatusTone.Neutral
}

@Composable
fun checkTone(status: SignalCheckStatus): StatusTone = when (status) {
    SignalCheckStatus.Pass -> StatusTone.Success
    SignalCheckStatus.Attention -> StatusTone.Warning
    SignalCheckStatus.Fail -> StatusTone.Danger
    SignalCheckStatus.Unknown -> StatusTone.Neutral
}
