package com.feizhela.headband.ui.state

import android.content.Context
import com.feizhela.headband.ui.model.DiaryEntry
import com.feizhela.headband.ui.model.DiaryFeeling
import org.json.JSONObject

/** Small durable facade for the self-reported diary; missing keys remain unknown. */
internal class DiaryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun read(): DiaryEntry? {
        val raw = preferences.getString(KEY_ENTRY, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            DiaryEntry(
                sleepStartMillis = json.optLongOrNull("sleepStartMillis"),
                finalWakeMillis = json.optLongOrNull("finalWakeMillis"),
                awakenings = json.optIntOrNull("awakenings"),
                awakeMinutes = json.optIntOrNull("awakeMinutes"),
                feeling = json.optString("feeling").takeIf { it.isNotBlank() }?.let { DiaryFeeling.valueOf(it) },
                note = json.optString("note"),
                updatedAtMillis = json.optLong("updatedAtMillis"),
            )
        }.getOrNull()
    }

    fun write(entry: DiaryEntry) {
        val json = JSONObject().apply {
            putNullable("sleepStartMillis", entry.sleepStartMillis)
            putNullable("finalWakeMillis", entry.finalWakeMillis)
            putNullable("awakenings", entry.awakenings)
            putNullable("awakeMinutes", entry.awakeMinutes)
            putNullable("feeling", entry.feeling?.name)
            put("note", entry.note)
            put("updatedAtMillis", entry.updatedAtMillis)
        }
        preferences.edit().putString(KEY_ENTRY, json.toString()).apply()
    }

    private companion object { const val PREFERENCES = "diary"; const val KEY_ENTRY = "entry" }
}

private fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null
private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
private fun JSONObject.putNullable(key: String, value: Any?) { if (value == null) put(key, JSONObject.NULL) else put(key, value) }
