package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A single persisted hosted-call state transition. Contains no bearer credentials or phone data. */
internal data class HostedStateUpdate(
    val id: String,
    val callId: String,
    val state: String,
    val reason: String?,
)

/**
 * Small ordered durable outbox backed by synchronous SharedPreferences commits.
 *
 * The API is intentionally at-least-once: if the process dies after the server applies an update
 * but before remove() commits, the same state may be sent again. State updates must therefore
 * remain safe to replay on the server.
 */
internal class HostedStateUpdateQueue(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun enqueue(callId: String, state: String, reason: String?): Boolean {
        if (callId.isBlank() || state.isBlank()) return false
        val items = readItems() ?: return false
        // Avoid adding an identical pending transition twice after a retry or lifecycle callback.
        if (items.any { it.callId == callId && it.state == state && it.reason == reason }) return true
        if (items.size >= MAX_ITEMS) return false

        val item = HostedStateUpdate(
            id = UUID.randomUUID().toString(),
            callId = callId,
            state = state,
            reason = reason,
        )
        val updated = JSONArray().apply {
            items.forEach { put(it.toJson()) }
            put(item.toJson())
        }
        return preferences.edit().putString(KEY_ITEMS, updated.toString()).commit()
    }

    @Synchronized
    fun peek(): HostedStateUpdate? = readItems()?.firstOrNull()

    @Synchronized
    fun remove(id: String): Boolean {
        val items = readItems() ?: return false
        if (items.none { it.id == id }) return true
        val updated = JSONArray().apply {
            items.filterNot { it.id == id }.forEach { put(it.toJson()) }
        }
        return preferences.edit().putString(KEY_ITEMS, updated.toString()).commit()
    }

    @Synchronized
    fun size(): Int = readItems()?.size ?: 0

    private fun readItems(): List<HostedStateUpdate>? = try {
        val raw = preferences.getString(KEY_ITEMS, null) ?: return emptyList()
        val json = JSONArray(raw)
        buildList(json.length()) {
            for (index in 0 until json.length()) {
                val item = json.getJSONObject(index)
                add(
                    HostedStateUpdate(
                        id = item.getString("id"),
                        callId = item.getString("callId"),
                        state = item.getString("state"),
                        reason = item.optString("reason").takeIf { it.isNotEmpty() },
                    ),
                )
            }
        }
    } catch (_: Exception) {
        // Do not overwrite a corrupt queue: retain evidence for diagnostics rather than silently
        // deleting potentially important call state transitions.
        null
    }

    private fun HostedStateUpdate.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("callId", callId)
        .put("state", state)
        .put("reason", reason ?: JSONObject.NULL)

    private companion object {
        const val PREFS_NAME = "hosted_state_outbox"
        const val KEY_ITEMS = "pending_updates"
        const val MAX_ITEMS = 500
    }
}
