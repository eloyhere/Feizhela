package com.feizhela.headband.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.feizhela.headband.R

/**
 * Product-boundary notice. It must stay visible on the main flow (not only in About): the app is
 * a research/observation prototype and the EOG model is not validated on this headband or PSG.
 */
@Composable
fun EvidenceNotice(
    modifier: Modifier = Modifier,
    modelAvailable: Boolean = true,
    body: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = scheme.secondaryContainer.copy(alpha = 0.55f),
        contentColor = scheme.onSecondaryContainer,
    ) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .semantics(mergeDescendants = true) { },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.evidence_title),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(
                text = body ?: stringResource(R.string.evidence_body),
                style = MaterialTheme.typography.bodySmall,
            )
            if (!modelAvailable) {
                Row {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.evidence_model_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/** Compact demo-data disclosure used above charts and lists fed by the demo bundle. */
@Composable
fun DemoDataNotice(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusPill(
            text = stringResource(R.string.demo_badge),
            tone = StatusTone.Info,
            accessibilityLabel = stringResource(R.string.evidence_demo),
        )
        Text(
            text = stringResource(R.string.evidence_demo),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
