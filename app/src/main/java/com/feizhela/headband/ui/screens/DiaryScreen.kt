package com.feizhela.headband.ui.screens

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.feizhela.headband.R
import com.feizhela.headband.ui.components.DemoDataNotice
import com.feizhela.headband.ui.components.EmptyState
import com.feizhela.headband.ui.components.MetricRow
import com.feizhela.headband.ui.components.SectionCard
import com.feizhela.headband.ui.components.StatusPill
import com.feizhela.headband.ui.components.StatusTone
import com.feizhela.headband.ui.model.DiaryEntry
import com.feizhela.headband.ui.model.DiaryFeeling
import com.feizhela.headband.ui.model.SessionSummary
import com.feizhela.headband.ui.util.countLabel
import com.feizhela.headband.ui.util.durationLabel
import com.feizhela.headband.ui.util.feelingLabel
import com.feizhela.headband.ui.util.formatClock
import com.feizhela.headband.ui.util.formatDateTime
import com.feizhela.headband.ui.util.formatDay
import com.feizhela.headband.ui.util.percentLabel
import com.feizhela.headband.ui.util.sessionStatusLabel
import java.util.Calendar

private enum class DiaryError { InvalidStart, InvalidWake, InvalidAwake, InvalidAwakenings, AwakeExceedsWindow }

/**
 * Diary entry (self-reported) shown side by side with, but never merged into, device observation.
 *
 * The screen computes only self-reported in-bed and self-reported sleep duration; those are
 * labelled as such and are never called “objective sleep duration”. Values persist in the app's
 * UI state (in-memory for this demo); the source is always `self_reported`.
 */
@Composable
fun DiaryScreen(
    diary: DiaryEntry?,
    observed: SessionSummary?,
    onSave: (DiaryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val savedAt = diary?.updatedAtMillis
    var startText by rememberSaveable(savedAt) {
        mutableStateOf(diary?.sleepStartMillis?.let { formatClock(it) }.orEmpty())
    }
    var wakeText by rememberSaveable(savedAt) {
        mutableStateOf(diary?.finalWakeMillis?.let { formatClock(it) }.orEmpty())
    }
    var awakeningsText by rememberSaveable(savedAt) {
        mutableStateOf(diary?.awakenings?.toString() ?: "")
    }
    var awakeMinutesText by rememberSaveable(savedAt) {
        mutableStateOf(diary?.awakeMinutes?.toString() ?: "")
    }
    var feelingName by rememberSaveable(savedAt) {
        mutableStateOf(diary?.feeling?.name.orEmpty())
    }
    var note by rememberSaveable(savedAt) { mutableStateOf(diary?.note ?: "") }
    var errorName by rememberSaveable { mutableStateOf<String?>(null) }
    val error = errorName?.let { runCatching { DiaryError.valueOf(it) }.getOrNull() }
    val now = System.currentTimeMillis()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("diary_screen"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("intro") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.diary_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item("form") {
            SectionCard(
                title = stringResource(R.string.diary_self_report_title),
                subtitle = stringResource(
                    R.string.diary_date_label,
                    formatDay(diary?.sleepStartMillis ?: (now - 24L * 60L * 60L * 1000L)),
                ),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = startText,
                        onValueChange = { startText = it },
                        label = { Text(stringResource(R.string.diary_sleep_start)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        isError = error == DiaryError.InvalidStart,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("diary_sleep_start"),
                    )
                    OutlinedTextField(
                        value = wakeText,
                        onValueChange = { wakeText = it },
                        label = { Text(stringResource(R.string.diary_final_wake)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        isError = error == DiaryError.InvalidWake,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("diary_final_wake"),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = awakeningsText,
                        onValueChange = { awakeningsText = it },
                        isError = error == DiaryError.InvalidAwakenings,
                        label = { Text(stringResource(R.string.diary_awakenings)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("diary_awakenings"),
                    )
                    OutlinedTextField(
                        value = awakeMinutesText,
                        onValueChange = { awakeMinutesText = it },
                        label = { Text(stringResource(R.string.diary_awake_minutes)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = error == DiaryError.InvalidAwake || error == DiaryError.AwakeExceedsWindow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("diary_awake_minutes"),
                    )
                }
                Text(
                    text = stringResource(R.string.diary_feeling),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    DiaryFeeling.entries.forEach { feeling ->
                        FilterChip(
                            selected = feelingName == feeling.name,
                            onClick = { feelingName = feeling.name },
                            label = { Text(feelingLabel(feeling)) },
                            modifier = Modifier.testTag("diary_feeling_${feeling.name.lowercase()}"),
                        )
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.diary_note)) },
                    placeholder = { Text(stringResource(R.string.diary_note_hint)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(
                        text = when (it) {
                            DiaryError.InvalidStart -> stringResource(R.string.diary_invalid_time) + "（" +
                                stringResource(R.string.diary_sleep_start) + "）"

                            DiaryError.InvalidWake -> stringResource(R.string.diary_invalid_time) + "（" +
                                stringResource(R.string.diary_final_wake) + "）"

                            DiaryError.InvalidAwakenings -> stringResource(R.string.diary_invalid_awakenings)
                            DiaryError.InvalidAwake -> stringResource(R.string.diary_invalid_awake)
                            DiaryError.AwakeExceedsWindow -> stringResource(R.string.diary_awake_exceeds_window)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = {
                        val start = parseHhMm(startText)
                        val wake = parseHhMm(wakeText)
                        val awakeMinutes = if (awakeMinutesText.isBlank()) null else awakeMinutesText.toIntOrNull()
                        val awakenings = awakeningsText.toIntOrNull()
                        errorName = when {
                            start == null -> DiaryError.InvalidStart.name
                            wake == null -> DiaryError.InvalidWake.name
                            awakeMinutesText.isNotBlank() && (awakeMinutes == null || awakeMinutes < 0) -> DiaryError.InvalidAwake.name
                            awakeningsText.isNotBlank() && (awakenings == null || awakenings < 0) -> DiaryError.InvalidAwakenings.name
                            else -> null
                        }
                        if (start != null && wake != null) {
                            val startMillis = diary?.sleepStartMillis?.let { existing ->
                                Calendar.getInstance().apply {
                                    timeInMillis = existing
                                    set(Calendar.HOUR_OF_DAY, start.first)
                                    set(Calendar.MINUTE, start.second)
                                    set(Calendar.SECOND, 0)
                                    set(Calendar.MILLISECOND, 0)
                                }.timeInMillis
                            } ?: combineLocalTime(now, start.first, start.second)
                            val wakeMillis = combineWakeTime(startMillis, wake.first, wake.second)
                            val inBedMinutes = ((wakeMillis - startMillis) / 60_000L).toInt()
                            if (awakeMinutes != null && awakeMinutes > inBedMinutes) {
                                errorName = DiaryError.AwakeExceedsWindow.name
                            } else if (errorName == null) {
                                onSave(
                                    DiaryEntry(
                                        sleepStartMillis = startMillis,
                                        finalWakeMillis = wakeMillis,
                                        awakenings = awakenings,
                                        awakeMinutes = awakeMinutes,
                                        feeling = runCatching { DiaryFeeling.valueOf(feelingName) }
                                            .getOrDefault(DiaryFeeling.Fair),
                                        note = note.trim(),
                                        updatedAtMillis = System.currentTimeMillis(),
                                    ),
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("save_diary"),
                ) {
                    Text(
                        stringResource(
                            if (diary == null) R.string.diary_save else R.string.diary_update,
                        ),
                    )
                }
                Text(
                    text = stringResource(R.string.diary_demo_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (diary != null) {
            item("saved") {
                SectionCard(
                    title = stringResource(R.string.diary_self_report_title),
                    action = {
                        StatusPill(
                            text = stringResource(R.string.diary_source_self_reported),
                            tone = StatusTone.Info,
                        )
                    },
                ) {
                    Text(
                        text = stringResource(R.string.diary_saved_at, formatDateTime(diary.updatedAtMillis)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MetricRow(
                        label = stringResource(R.string.diary_sleep_start),
                        value = diary.sleepStartMillis?.let { formatClock(it) }
                            ?: stringResource(R.string.diary_awake_time_unknown),
                    )
                    MetricRow(
                        label = stringResource(R.string.diary_final_wake),
                        value = diary.finalWakeMillis?.let { formatClock(it) }
                            ?: stringResource(R.string.diary_awake_time_unknown),
                    )
                    MetricRow(
                        label = stringResource(R.string.diary_awakenings),
                        value = diary.awakenings?.let {
                            "$it " + stringResource(R.string.diary_awakenings_unit)
                        } ?: stringResource(R.string.diary_awake_time_unknown),
                    )
                    MetricRow(
                        label = stringResource(R.string.diary_awake_minutes),
                        value = diary.awakeMinutes?.let {
                            "$it " + stringResource(R.string.diary_minutes_unit)
                        } ?: stringResource(R.string.diary_awake_time_unknown),
                    )
                    MetricRow(
                        label = stringResource(R.string.diary_feeling),
                        value = diary.feeling?.let { feelingLabel(it) }
                            ?: stringResource(R.string.diary_awake_time_unknown),
                    )
                    MetricRow(
                        label = stringResource(R.string.diary_window_label),
                        value = durationLabel(diary.timeInBedMillis),
                    )
                    MetricRow(
                        label = stringResource(R.string.diary_sleep_estimate_label),
                        value = durationLabel(diary.selfReportedSleepMillis),
                    )
                    Text(
                        text = stringResource(R.string.diary_estimate_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (diary.note.isNotBlank()) {
                        Text(
                            text = diary.note,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        } else {
            item("empty") {
                EmptyState(
                    title = stringResource(R.string.diary_empty_title),
                    description = stringResource(R.string.diary_empty_body),
                )
            }
        }

        item("observed") {
            SectionCard(
                title = stringResource(R.string.diary_observed_title),
                subtitle = stringResource(R.string.diary_observed_note),
            ) {
                if (observed == null) {
                    EmptyState(
                        title = stringResource(R.string.diary_observed_empty),
                        description = stringResource(R.string.diary_observed_note),
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(
                            text = sessionStatusLabel(observed.status),
                            tone = StatusTone.Neutral,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = formatDateTime(observed.startedAtMillis),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    MetricRow(
                        label = stringResource(R.string.diary_observed_span),
                        value = durationLabel(observed.metrics.recordedSpanMillis),
                    )
                    MetricRow(
                        label = stringResource(R.string.diary_observed_coverage),
                        value = percentLabel(observed.metrics.coveragePercent),
                    )
                    MetricRow(
                        label = stringResource(R.string.diary_observed_candidates),
                        value = countLabel(observed.metrics.candidateWindowCount),
                    )
                }
            }
        }

        item("boundary") {
            Text(
                text = stringResource(R.string.diary_boundary_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun parseHhMm(text: String): Pair<Int, Int>? {
    val parts = text.trim().split(":")
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour to minute
}

/** Local date/time for the bedtime; a future time means “last night”. */
private fun combineLocalTime(referenceNowMillis: Long, hour: Int, minute: Int): Long {
    val calendar = Calendar.getInstance().apply {
        timeInMillis = referenceNowMillis
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    if (calendar.timeInMillis > referenceNowMillis) {
        calendar.add(Calendar.DAY_OF_YEAR, -1)
    }
    return calendar.timeInMillis
}

/** Wake time on the same night; if it is not after bedtime, it belongs to the next day. */
private fun combineWakeTime(sleepStartMillis: Long, hour: Int, minute: Int): Long {
    val calendar = Calendar.getInstance().apply {
        timeInMillis = sleepStartMillis
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    if (calendar.timeInMillis <= sleepStartMillis) {
        calendar.add(Calendar.DAY_OF_YEAR, 1)
    }
    return calendar.timeInMillis
}
