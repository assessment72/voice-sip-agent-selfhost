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
) {
    private val endpoint: String

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
    )

    fun poll(offset: Long): List<Incoming> {
        val query = "timeout=20&offset=$offset&allowed_updates=%5B%22message%22%5D"
        val json = request("GET", "/getUpdates?$query")
        val updates = json.optJSONArray("result") ?: JSONArray()
        return (0 until updates.length()).mapNotNull { index ->
            val update = updates.optJSONObject(index) ?: return@mapNotNull null
            val message = update.optJSONObject("message") ?: return@mapNotNull null
            val from = message.optJSONObject("from") ?: return@mapNotNull null
            val chat = message.optJSONObject("chat") ?: return@mapNotNull null
            val text = message.optString("text").trim()
            if (text.isBlank()) return@mapNotNull null
            Incoming(
                updateId = update.optLong("update_id", -1L),
                senderId = from.optLong("id", -1L),
                chatId = chat.optLong("id", -1L),
                privateChat = chat.optString("type") == "private",
                text = text,
            ).takeIf { it.updateId >= 0 && it.senderId >= 0 && it.chatId >= 0 }
        }
    }

    fun sendMessage(chatId: Long, text: String) {
        require(text.length <= 3_900)
        val body = JSONObject().put("chat_id", chatId).put("text", text)
        request("POST", "/sendMessage", body)
    }

    private fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val connection = (URL(endpoint + path).openConnection() as HttpURLConnection).apply {
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
        try {
            if (body != null) connection.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("Telegram API HTTP $code")
            val response = JSONObject(responseText)
            if (!response.optBoolean("ok")) {
                val errorCode = response.optInt("error_code", 0)
                throw IllegalStateException("Telegram API rejected request ($errorCode)")
            }
            return response
        } finally {
            connection.disconnect()
        }
    }
}
