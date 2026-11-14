package io.github.sparkcyf.voicesipagent.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Telegram Bot API transport for private voice messages and WebRTC room invitation callbacks.
 * Invoke from Dispatchers.IO. It does not modify SIP/RTP or the Shizuku audio pipeline.
 */
class TelegramBotClient(
    private val token: String,
    private val allowedPrivateChatId: String,
) {
    init {
        require(token.matches(Regex("[0-9]{5,}:[A-Za-z0-9_-]{20,}"))) { "Invalid Telegram bot token format" }
        require(allowedPrivateChatId.matches(Regex("-?[0-9]{1,20}"))) { "A numeric private chat ID is required" }
    }

    data class VoiceUpdate(
        val updateId: Long,
        val chatId: String,
        val fileId: String,
        val durationSeconds: Int,
        val fileSize: Long?,
    )

    data class JoinInvite(val token: String, val callbackQueryId: String)

    data class PollResult(val voices: List<VoiceUpdate>, val joinInvites: List<JoinInvite>, val nextOffset: Long)

    fun getBotUsername(): String {
        val response = apiJson("getMe")
        check(response.optBoolean("ok")) { "Telegram getMe failed" }
        return response.getJSONObject("result").optString("username")
    }

    /** Long-polls voice messages and WebRTC invitation callbacks from the configured private chat. */
    fun pollVoice(offset: Long, timeoutSeconds: Int = 20): PollResult {
        require(timeoutSeconds in 1..30)
        val body = JSONObject()
            .put("offset", offset)
            .put("timeout", timeoutSeconds)
            .put("allowed_updates", JSONArray().put("message").put("callback_query"))
        val response = apiJson("getUpdates", body, 10_000, (timeoutSeconds + 10) * 1_000)
        check(response.optBoolean("ok")) { "Telegram getUpdates failed" }
        val updates = response.getJSONArray("result")
        val voices = mutableListOf<VoiceUpdate>()
        val joinInvites = mutableListOf<JoinInvite>()
        var next = offset
        for (i in 0 until updates.length()) {
            val update = updates.getJSONObject(i)
            val updateId = update.optLong("update_id", -1L)
            if (updateId >= 0) next = maxOf(next, updateId + 1)
            val callback = update.optJSONObject("callback_query")
            if (callback != null) {
                val message = callback.optJSONObject("message")
                val chat = message?.optJSONObject("chat")
                val chatId = chat?.opt("id")?.toString()
                val senderId = callback.optJSONObject("from")?.opt("id")?.toString()
                val data = callback.optString("data")
                val match = Regex("^vsa_join:([A-Za-z0-9_-]{32,100})$").matchEntire(data)
                if (chat?.optString("type") == "private" && chatId == allowedPrivateChatId &&
                    senderId == allowedPrivateChatId && match != null) {
                    joinInvites += JoinInvite(match.groupValues[1], callback.optString("id"))
                }
                continue
            }
            val message = update.optJSONObject("message") ?: continue
            val chat = message.optJSONObject("chat") ?: continue
            val chatId = chat.opt("id")?.toString() ?: continue
            if (chat.optString("type") != "private" || chatId != allowedPrivateChatId) continue
            val voice = message.optJSONObject("voice") ?: continue
            val fileId = voice.optString("file_id").takeIf { it.isNotBlank() } ?: continue
            voices += VoiceUpdate(
                updateId = updateId,
                chatId = chatId,
                fileId = fileId,
                durationSeconds = voice.optInt("duration", 0),
                fileSize = voice.optLong("file_size").takeIf { it > 0 },
            )
        }
        return PollResult(voices, joinInvites, next)
    }

    fun answerCallbackQuery(callbackQueryId: String) {
        require(callbackQueryId.isNotBlank())
        val response = apiJson("answerCallbackQuery", JSONObject().put("callback_query_id", callbackQueryId))
        check(response.optBoolean("ok")) { "Telegram callback acknowledgement failed" }
    }

    /** Downloads a received voice message into app-private storage. */
    fun downloadVoice(update: VoiceUpdate, inbox: File): File {
        require(update.chatId == allowedPrivateChatId) { "Voice message is not from the configured private chat" }
        require(update.fileSize == null || update.fileSize <= MAX_VOICE_BYTES) { "Voice message exceeds size limit" }
        val info = apiJson("getFile", JSONObject().put("file_id", update.fileId))
        check(info.optBoolean("ok")) { "Telegram getFile failed" }
        val path = info.getJSONObject("result").getString("file_path")
        val connection = (URL("https://api.telegram.org/file/bot" + token + "/" + path).openConnection() as HttpURLConnection)
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            check(connection.responseCode in 200..299) { "Telegram file download failed (" + connection.responseCode + ")" }
            inbox.mkdirs()
            val target = File(inbox, "voice_" + update.updateId + ".ogg")
            val temp = File(inbox, "." + target.name + ".part")
            BufferedInputStream(connection.inputStream).use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_VOICE_BYTES) { "Telegram voice message exceeds size limit" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            check(temp.renameTo(target)) { "Could not finalize downloaded voice message" }
            return target
        } finally {
            connection.disconnect()
        }
    }

    /** Sends an OGG, MP3, or M4A recording as a Telegram voice message to the allowlisted private chat. */
    fun sendVoice(file: File, caption: String? = null) {
        require(file.isFile && file.length() in 1L..MAX_VOICE_BYTES) { "Audio file is missing or exceeds size limit" }
        val (filename, contentType) = when (file.extension.lowercase()) {
            "ogg" -> "voice.ogg" to "audio/ogg"
            "mp3" -> "voice.mp3" to "audio/mpeg"
            "m4a" -> "voice.m4a" to "audio/mp4"
            else -> throw IllegalArgumentException("Voice recording must be OGG, MP3, or M4A")
        }
        val boundary = "----VsaTelegram" + System.nanoTime()
        val connection = (URL("https://api.telegram.org/bot" + token + "/sendVoice").openConnection() as HttpURLConnection)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary)
        try {
            connection.outputStream.buffered().use { out ->
                fun field(name: String, value: String) {
                    out.write(("--" + boundary + "\r\n").toByteArray())
                    out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").toByteArray())
                    out.write(value.toByteArray(Charsets.UTF_8))
                    out.write("\r\n".toByteArray())
                }
                field("chat_id", allowedPrivateChatId)
                caption?.takeIf { it.isNotBlank() }?.let { field("caption", it.take(900)) }
                out.write(("--" + boundary + "\r\n").toByteArray())
                out.write(("Content-Disposition: form-data; name=\"voice\"; filename=\"$filename\"\r\n").toByteArray())
                out.write(("Content-Type: $contentType\r\n\r\n").toByteArray())
                FileInputStream(file).use { it.copyTo(out) }
                out.write(("\r\n--" + boundary + "--\r\n").toByteArray())
            }
            val response = readResponse(connection)
            check(response.optBoolean("ok")) { "Telegram sendVoice failed" }
        } finally {
            connection.disconnect()
        }
    }

    private fun apiJson(
        method: String,
        body: JSONObject? = null,
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 30_000,
    ): JSONObject {
        val connection = (URL("https://api.telegram.org/bot" + token + "/" + method).openConnection() as HttpURLConnection)
        connection.requestMethod = if (body == null) "GET" else "POST"
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            return readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun readResponse(connection: HttpURLConnection): JSONObject {
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }

    companion object {
        const val MAX_VOICE_BYTES = 20L * 1024L * 1024L
    }
}
