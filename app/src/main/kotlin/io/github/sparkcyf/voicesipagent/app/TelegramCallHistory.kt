package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local, bounded history for Telegram-originated SIM calls; no carrier billing is inferred. */
internal class TelegramCallHistory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("telegram_call_history", Context.MODE_PRIVATE)

    @Synchronized
    fun add(record: TelegramCallRecord) {
        val records = read().filterNot { it.id == record.id }.toMutableList()
        records.add(0, record)
        save(records.take(MAX_RECORDS))
    }

    @Synchronized
    fun list(limit: Int = MAX_RECORDS): List<TelegramCallRecord> = read().take(limit.coerceIn(1, MAX_RECORDS))

    @Synchronized
    fun delete(id: String): Boolean {
        if (!id.matches(Regex("[a-fA-F0-9-]{4,36}"))) return false
        val records = read()
        val changed = records.removeAll { it.id.equals(id, ignoreCase = true) }
        if (changed) save(records)
        return changed
    }

    private fun read(): MutableList<TelegramCallRecord> {
        val array = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val id = obj.optString("id").takeIf { it.matches(Regex("[a-fA-F0-9-]{4,36}")) } ?: return@mapNotNull null
            val number = obj.optString("number").takeIf { it.matches(Regex("[+0-9*#]{3,32}")) } ?: return@mapNotNull null
            val started = obj.optLong("startedAt", -1).takeIf { it > 0 } ?: return@mapNotNull null
            val ended = obj.optLong("endedAt", -1).takeIf { it >= started } ?: return@mapNotNull null
            val answered = obj.optLong("answeredAt", -1).takeIf { it > 0 }
            val result = obj.optString("result").take(80)
            TelegramCallRecord(
                id, number, started, answered, ended, result,
                obj.optString("audioStatus", "غير مسجل").take(240),
                obj.optString("batteryStatus", "غير متاح").take(100),
            )
        }.toMutableList()
    }

    private fun save(records: List<TelegramCallRecord>) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(JSONObject()
                .put("id", record.id)
                .put("number", record.number)
                .put("startedAt", record.startedAtMs)
                .put("answeredAt", record.answeredAtMs ?: JSONObject.NULL)
                .put("endedAt", record.endedAtMs)
                .put("result", record.result.take(80))
                .put("audioStatus", record.audioStatus.take(240))
                .put("batteryStatus", record.batteryStatus.take(100)))
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "records"
        const val MAX_RECORDS = 100
    }
}
