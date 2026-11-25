package io.github.sparkcyf.voicesipagent.app

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Direct Gemini Live API transport. The API key is supplied only by the caller and never logged. */
internal class GeminiLiveClient(
    private val apiKey: String,
    private val model: String,
    private val instructions: String,
    private val onAudio24k: (ByteArray) -> Unit,
    private val onInputTranscript: (String) -> Unit,
    private val onOutputTranscript: (String) -> Unit,
    private val onReady: () -> Unit,
    private val onClosed: (String) -> Unit,
) {
    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false

    fun connect() {
        require(apiKey.isNotBlank()) { "Gemini API key is not configured" }
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(url).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val setup = JSONObject()
                    .put("setup", JSONObject()
                        .put("model", "models/$model")
                        .put("responseModalities", org.json.JSONArray().put("AUDIO"))
                        .put("systemInstruction", JSONObject().put("parts", org.json.JSONArray()
                            .put(JSONObject().put("text", instructions))))
                        .put("inputAudioTranscription", JSONObject())
                        .put("outputAudioTranscription", JSONObject()))
                webSocket.send(setup.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { handle(JSONObject(text)) }
                    .onFailure { onClosed("Gemini message error: ${it.javaClass.simpleName}") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!closed) onClosed("Gemini connection failed: ${t.javaClass.simpleName}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closed) onClosed("Gemini connection closed: $code")
            }
        })
    }

    private fun handle(message: JSONObject) {
        if (message.has("setupComplete")) { onReady(); return }
        val content = message.optJSONObject("serverContent") ?: return
        content.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let(onInputTranscript)
        content.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let(onOutputTranscript)
        val parts = content.optJSONObject("modelTurn")?.optJSONArray("parts") ?: return
        for (i in 0 until parts.length()) {
            val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
            val data = inline.optString("data")
            if (data.isNotBlank()) onAudio24k(Base64.decode(data, Base64.DEFAULT))
        }
    }

    /** Sends one 20 ms, 8 kHz PCM frame after simple 8->16 kHz duplication. */
    fun sendPcm8(frame: ShortArray) {
        val bytes = ByteArray(frame.size * 4)
        var p = 0
        for (sample in frame) repeat(2) {
            bytes[p++] = sample.toByte()
            bytes[p++] = (sample.toInt() shr 8).toByte()
        }
        val audio = JSONObject()
            .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("mimeType", "audio/pcm;rate=16000")
        socket?.send(JSONObject().put("realtimeInput", JSONObject().put("audio", audio)).toString())
    }

    fun close(reason: String = "stopped") {
        closed = true
        socket?.close(1000, reason.take(120))
        socket = null
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
}
