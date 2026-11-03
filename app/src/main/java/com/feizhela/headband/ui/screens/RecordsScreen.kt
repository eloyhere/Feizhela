package com.feizhela.headband.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.feizhela.headband.R
import com.feizhela.headband.ui.components.DataGapBadge
import com.feizhela.headband.ui.components.EmptyState
import com.feizhela.headband.ui.components.StatusPill
import com.feizhela.headband.ui.components.StatusTone
import com.feizhela.headband.ui.model.SessionDateFilter
import com.feizhela.headband.ui.model.SessionStatus
import com.feizhela.headband.ui.model.SessionStatusFilter
import com.feizhela.headband.ui.model.SessionSummary
import com.feizhela.headband.ui.model.matchesFilter
import com.feizhela.headband.ui.util.countLabel
import com.feizhela.headband.ui.util.durationLabel
import com.feizhela.headband.ui.util.formatClock
import com.feizhela.headband.ui.util.formatDateTime
import com.feizhela.headband.ui.util.percentLabel
import com.feizhela.headband.ui.util.sessionStatusLabel

/**
 * Records list with status/date filters and an empty state. On phones a tap navigates to
 * `records/{sessionId}`; the two-pane tablet layout reuses [SessionCard] and the same detail
 * screen, so the data model and routes stay identical across form factors.
 */
@Composable
fun RecordsScreen(
    sessions: List<SessionSummary>,
    statusFilter: SessionStatusFilter,
    dateFilter: SessionDateFilter,
    onStatusFilterChange: (SessionStatusFilter) -> Unit,
    onDateFilterChange: (SessionDateFilter) -> Unit,
    onOpenSession: (String) -> Unit,
    modifier: Modifier = Modifier,
    selectedSessionId: String? = null,
    nowMillis: Long = System.currentTimeMillis(),
) {
    val filtered = sessions.filter { it.matchesFilter(statusFilter, dateFilter, nowMillis) }
    val hasFilters = statusFilter != SessionStatusFilter.All || dateFilter != SessionDateFilter.All

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("records_screen"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("header") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.records_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item("filters") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.records_filter_status),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    SessionStatusFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = statusFilter == filter,
                            onClick = { onStatusFilterChange(filter) },
                            label = { Text(sessionStatusFilterLabel(filter)) },
                            modifier = Modifier.testTag("status_filter_${filter.name.lowercase()}"),
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.records_filter_date),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    SessionDateFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = dateFilter == filter,
                            onClick = { onDateFilterChange(filter) },
                            label = { Text(sessionDateFilterLabel(filter)) },
                            modifier = Modifier.testTag("date_filter_${filter.name.lowercase()}"),
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.records_count, filtered.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (filtered.isEmpty()) {
            item("empty") {
                EmptyState(
                    title = stringResource(R.string.records_empty_title),
                    description = stringResource(R.string.records_empty_body),
                    actionLabel = if (hasFilters) stringResource(R.string.records_clear_filters) else null,
                    onAction = if (hasFilters) {
                        {
                            onStatusFilterChange(SessionStatusFilter.All)
                            onDateFilterChange(SessionDateFilter.All)
                        }
                    } else {
                        null
                    },
                )
            }
        } else {
            items(filtered, key = { it.sessionId }) { session ->
                SessionCard(
                    summary = session,
                    selected = session.sessionId == selectedSessionId,
                    onClick = { onOpenSession(session.sessionId) },
                )
            }
        }
    }
}

@Composable
private fun SessionCard(
    summary: SessionSummary,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val openLabel = stringResource(R.string.records_open_detail_cd, formatDateTime(summary.startedAtMillis))
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("record_item_${summary.sessionId}")
            .semantics { this.selected = selected }
            .clickable(onClickLabel = openLabel, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) scheme.primaryContainer else scheme.surface,
            contentColor = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
        ),
        border = BorderStroke(
            1.dp,
            if (selected) scheme.primary else scheme.outlineVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = formatDateTime(summary.startedAtMillis),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = summary.deviceName ?: stringResource(R.string.device_name_unknown),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) scheme.onPrimaryContainer.copy(alpha = 0.8f) else scheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                StatusPill(
                    text = sessionStatusLabel(summary.status),
                    tone = when (summary.status) {
                        SessionStatus.Completed -> StatusTone.Success
                        SessionStatus.Interrupted -> StatusTone.Warning
                        SessionStatus.Failed -> StatusTone.Danger
                        SessionStatus.Active -> StatusTone.Info
                        SessionStatus.Unknown -> StatusTone.Neutral
                    },
                )
            }
            Text(
                text = stringResource(
                    R.string.records_time_range,
                    formatClock(summary.startedAtMillis),
                    summary.endedAtMillis?.let { formatClock(it) }
                        ?: stringResource(R.string.value_unknown),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) scheme.onPrimaryContainer.copy(alpha = 0.8f) else scheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactMetric(
                    label = stringResource(R.string.records_item_duration),
                    value = durationLabel(summary.durationMillis),
                    modifier = Modifier.fillMaxWidth(),
                )
                CompactMetric(
                    label = stringResource(R.string.records_item_coverage),
                    value = percentLabel(summary.metrics.coveragePercent),
                    modifier = Modifier.fillMaxWidth(),
                )
                CompactMetric(
                    label = stringResource(R.string.records_item_candidates),
                    value = countLabel(summary.metrics.candidateWindowCount),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DataGapBadge(
                    count = summary.gapCount,
                    durationMillis = summary.metrics.disconnectedMillis,
                )
            }
        }
    }
}

@Composable
private fun CompactMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun sessionStatusFilterLabel(filter: SessionStatusFilter): String = stringResource(
    when (filter) {
        SessionStatusFilter.All -> R.string.records_filter_all
        SessionStatusFilter.Completed -> R.string.records_filter_completed
        SessionStatusFilter.Interrupted -> R.string.records_filter_interrupted
        SessionStatusFilter.Failed -> R.string.records_filter_failed
    },
)

@Composable
private fun sessionDateFilterLabel(filter: SessionDateFilter): String = stringResource(
    when (filter) {
        SessionDateFilter.All -> R.string.records_date_all
        SessionDateFilter.Last24Hours -> R.string.records_date_24h
        SessionDateFilter.Last7Days -> R.string.records_date_7d
        SessionDateFilter.Older -> R.string.records_date_older
    },
)
