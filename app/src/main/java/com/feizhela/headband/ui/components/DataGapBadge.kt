package com.feizhela.headband.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.feizhela.headband.R
import com.feizhela.headband.ui.util.durationLabel

/**
 * One consistent gap badge for lists, detail headers and the timeline legend.
 * A count of zero is rendered as a neutral “no gaps” pill, never hidden silently.
 */
@Composable
fun DataGapBadge(
    count: Int,
    durationMillis: Long? = null,
    modifier: Modifier = Modifier,
) {
    if (count <= 0) {
        StatusPill(
            text = stringResource(R.string.data_gap_badge_none),
            tone = StatusTone.Neutral,
            modifier = modifier,
        )
    } else {
        val text = if (durationMillis != null) {
            stringResource(R.string.data_gap_badge_with_duration, count, durationLabel(durationMillis))
        } else {
            stringResource(R.string.data_gap_badge_count, count)
        }
        StatusPill(
            text = text,
            tone = StatusTone.Warning,
            modifier = modifier,
            icon = Icons.Filled.Warning,
        )
    }
}
