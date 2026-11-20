package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import io.github.sparkcyf.voicesipagent.voice.HangupCause
import org.json.JSONArray
import org.json.JSONObject

/** Persisted call summary; never contains SIP URIs, credentials, or a full caller number. */
data class CallHistoryEntry(
    val sessionId: Int,
    val startedAtMs: Long,
    val destinationLabel: String,
    val maskedCaller: String,
    val answered: Boolean,
    val endReason: String,
    val sipResponseCode: Int?,
    val attemptSummary: String,
    val durationSeconds: Long,
)

class CallHistoryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("clinic_call_history", Context.MODE_PRIVATE)

    @Synchronized fun list(): List<CallHistoryEntry> = runCatching {
        val array = JSONArray(prefs.getString(KEY_HISTORY, "[]") ?: "[]")
        buildList {
            for (i in 0 until array.length()) array.optJSONObject(i)?.let { item ->
                add(
                    CallHistoryEntry(
                        sessionId = item.optInt("id"),
                        startedAtMs = item.optLong("started"),
                        destinationLabel = item.optString("destination", "غير معروف"),
                        maskedCaller = item.optString("caller", "**"),
                        answered = item.optBoolean("answered"),
                        endReason = item.optString("reason", "UNKNOWN"),
                        sipResponseCode = if (item.isNull("sipCode")) null else item.optInt("sipCode"),
                        attemptSummary = item.optString("attempts", ""),
                        durationSeconds = item.optLong("duration").coerceAtLeast(0),
                    ),
                )
            }
        }.sortedByDescending { it.startedAtMs }
    }.getOrDefault(emptyList())

    @Synchronized fun add(entry: CallHistoryEntry) {
        val entries = (list() + entry)
            .sortedByDescending { it.startedAtMs }.take(MAX_ENTRIES)
        val array = JSONArray()
        entries.forEach { e ->
            array.put(JSONObject().apply {
                put("id", e.sessionId)
                put("started", e.startedAtMs)
                put("destination", e.destinationLabel.take(80))
                put("caller", e.maskedCaller.take(8))
                put("answered", e.answered)
                put("reason", e.endReason.take(40))
                if (e.sipResponseCode == null) put("sipCode", JSONObject.NULL) else put("sipCode", e.sipResponseCode)
                put("attempts", e.attemptSummary.take(500))
                put("duration", e.durationSeconds.coerceAtLeast(0))
            })
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    companion object {
        private const val KEY_HISTORY = "entries"
        private const val MAX_ENTRIES = 100

        fun reason(cause: HangupCause?, callerLegCause: HangupCause?, answered: Boolean): String = when {
            callerLegCause == HangupCause.CANCELLED -> "CALLER_ENDED"
            cause == null -> "UNKNOWN"
            answered && cause == HangupCause.NORMAL -> "NORMAL"
            cause == HangupCause.BUSY -> "BUSY"
            cause == HangupCause.NO_ANSWER -> "NO_ANSWER"
            cause == HangupCause.UNAVAILABLE -> "UNAVAILABLE"
            cause == HangupCause.REJECTED -> "REJECTED"
            cause == HangupCause.NETWORK_ERROR -> "NETWORK_ERROR"
            cause == HangupCause.NOT_FOUND -> "NOT_FOUND"
            cause == HangupCause.FORBIDDEN -> "FORBIDDEN"
            cause == HangupCause.INTERNAL_ERROR -> "INTERNAL_ERROR"
            else -> "UNKNOWN"
        }
    }
}
