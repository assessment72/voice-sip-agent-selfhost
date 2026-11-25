package io.github.sparkcyf.voicesipagent.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Direct Telegram Bot API long-poll transport. Call only from Dispatchers.IO.
 * The bot token is never included in logs or exception messages.
 */
internal class TelegramBotClient(
    token: String,
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 35_000,
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    private val endpoint: String
    @Volatile private var activePoll: HttpURLConnection? = null

    init {
        require(token.length >= 30 && Regex("^[0-9]+:[A-Za-z0-9_-]+$").matches(token)) {
            "Invalid Telegram bot token format"
        }
        require(connectTimeoutMs in 1..30_000 && readTimeoutMs in 1..60_000)
        endpoint = "https://api.telegram.org/bot$token"
    }

    data class Incoming(
        val updateId: Long,
        val senderId: Long,
        val chatId: Long,
        val privateChat: Boolean,
        val text: String,
        val callbackQueryId: String? = null,
        val callbackData: String? = null,
    )

    /** Validates the token with Telegram without starting long polling or exposing the token. */
    fun testConnection(): String {
        val result = request("GET", "/getMe").optJSONObject("result")
            ?: throw IllegalStateException("Telegram returned no bot information")
        return result.optString("username").takeIf { it.isNotBlank() }?.let { "@$it" }
            ?: throw IllegalStateException("Telegram account is not a bot")
    }

    fun poll(offset: Long): List<Incoming> {
        val query = "timeout=20&offset=$offset&allowed_updates=%5B%22message%22%2C%22callback_query%22%5D"
        val json = request("GET", "/getUpdates?$query", isPoll = true)
        val updates = json.optJSONArray("result") ?: JSONArray()
        return (0 until updates.length()).mapNotNull { index ->
            val update = updates.optJSONObject(index) ?: return@mapNotNull null
            val callback = update.optJSONObject("callback_query")
            val message = if (callback == null) update.optJSONObject("message") else callback.optJSONObject("message")
                ?: return@mapNotNull null
            val from = (callback?.optJSONObject("from") ?: message.optJSONObject("from")) ?: return@mapNotNull null
            val chat = message.optJSONObject("chat") ?: return@mapNotNull null
            val text = if (callback == null) message.optString("text").trim() else ""
            val callbackData = callback?.optString("data")?.takeIf(String::isNotBlank)
            if (text.isBlank() && callbackData == null) return@mapNotNull null
            Incoming(
                updateId = update.optLong("update_id", -1L),
                senderId = from.optLong("id", -1L),
                chatId = chat.optLong("id", -1L),
                privateChat = chat.optString("type") == "private",
                text = text,
                callbackQueryId = callback?.optString("id")?.takeIf(String::isNotBlank),
                callbackData = callbackData,
            ).takeIf { it.updateId >= 0 && it.senderId >= 0 && it.chatId >= 0 }
        }
    }

    fun sendMessage(chatId: Long, text: String, buttons: List<List<AdminButton>> = emptyList()) {
        require(text.length <= 3_900)
        val body = JSONObject().put("chat_id", chatId).put("text", text)
        if (buttons.isNotEmpty()) {
            val rows = JSONArray()
            buttons.forEach { row ->
                val cells = JSONArray()
                row.forEach { button ->
                    cells.put(JSONObject().put("text", button.title).put("callback_data", button.callbackData))
                }
                rows.put(cells)
            }
            body.put("reply_markup", JSONObject().put("inline_keyboard", rows))
        }
        request("POST", "/sendMessage", body)
    }

    fun answerCallbackQuery(callbackQueryId: String) {
        request("POST", "/answerCallbackQuery", JSONObject().put("callback_query_id", callbackQueryId))
    }

    /** Interrupts an outstanding long-poll before its owning coroutine is replaced. */
    fun cancelPoll() {
        activePoll?.disconnect()
    }

    private fun request(method: String, path: String, body: JSONObject? = null, isPoll: Boolean = false): JSONObject {
        val connection = connectionFactory(URL(endpoint + path)).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        if (isPoll) activePoll = connection
        try {
            if (body != null) connection.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val response = runCatching { JSONObject(responseText) }.getOrNull()
            if (code !in 200..299) {
                val errorCode = response?.optInt("error_code", code) ?: code
                val description = response?.optString("description")?.takeIf { it.isNotBlank() }?.take(180)
                throw IllegalStateException(
                    "Telegram API HTTP $code (error $errorCode)${description?.let { ": $it" } ?: ""}",
                )
            }
            val parsed = response ?: throw IllegalStateException("Telegram API returned invalid JSON")
            if (!parsed.optBoolean("ok")) {
                val errorCode = parsed.optInt("error_code", 0)
                throw IllegalStateException("Telegram API rejected request ($errorCode)")
            }
            return parsed
        } finally {
            if (isPoll && activePoll === connection) activePoll = null
            connection.disconnect()
        }
    }
}
