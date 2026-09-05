package com.syyun.meetinginterpreter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class MeetingEntry(
    val id: Long,
    val time: String,
    val original: String,
    val translation: String
)

data class CompanyEntry(val company: String, val people: String)

object MeetingStore {
    private const val PREFS = "meeting_interpreter"
    private const val ENTRIES = "entries"

    @Synchronized
    fun entries(context: Context): MutableList<MeetingEntry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ENTRIES, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            MutableList(array.length()) { index ->
                val item = array.getJSONObject(index)
                MeetingEntry(
                    item.optLong("id"), item.optString("time"),
                    item.optString("original"), item.optString("translation")
                )
            }
        }.getOrDefault(mutableListOf())
    }

    @Synchronized
    fun append(context: Context, entry: MeetingEntry) {
        val items = entries(context)
        items.add(entry)
        writeEntries(context, items)
    }

    @Synchronized
    fun updateTranslation(context: Context, id: Long, translation: String) {
        val items = entries(context)
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) items[index] = items[index].copy(translation = translation)
        writeEntries(context, items)
    }

    private fun writeEntries(context: Context, items: List<MeetingEntry>) {
        val array = JSONArray()
        items.forEach { entry ->
            array.put(JSONObject().apply {
                put("id", entry.id); put("time", entry.time)
                put("original", entry.original); put("translation", entry.translation)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(ENTRIES, array.toString()).apply()
    }

    fun saveMetadata(
        context: Context,
        name: String,
        place: String,
        time: String,
        languageTag: String,
        companies: List<CompanyEntry>,
        keyPoints: String = "",
        decisions: String = "",
        actions: String = ""
    ) {
        val companyArray = JSONArray()
        companies.forEach { companyArray.put(JSONObject().put("company", it.company).put("people", it.people)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("meeting_name", name)
            .putString("meeting_place", place)
            .putString("meeting_time", time)
            .putString("language_tag", languageTag)
            .putString("companies", companyArray.toString())
            .putString("key_points", keyPoints)
            .putString("decisions", decisions)
            .putString("actions", actions)
            .apply()
    }

    fun metadata(context: Context): Map<String, String> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return mapOf(
            "name" to (p.getString("meeting_name", "") ?: ""),
            "place" to (p.getString("meeting_place", "") ?: ""),
            "time" to (p.getString("meeting_time", "") ?: ""),
            "language" to (p.getString("language_tag", "en-US") ?: "en-US"),
            "companies" to (p.getString("companies", "[]") ?: "[]"),
            "keyPoints" to (p.getString("key_points", "") ?: ""),
            "decisions" to (p.getString("decisions", "") ?: ""),
            "actions" to (p.getString("actions", "") ?: "")
        )
    }

    fun companies(context: Context): List<CompanyEntry> {
        val raw = metadata(context)["companies"] ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                CompanyEntry(item.optString("company"), item.optString("people"))
            }
        }.getOrDefault(emptyList())
    }

    fun setSession(context: Context, state: String, elapsed: Long = 0L) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("session_state", state).putLong("elapsed", elapsed).apply()
    }

    fun sessionState(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("session_state", "idle") ?: "idle"

    fun elapsed(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("elapsed", 0L)

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
