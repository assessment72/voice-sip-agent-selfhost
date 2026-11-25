package io.github.sparkcyf.voicesipagent.app

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
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
    private val onTurnComplete: () -> Unit = {},
    private val onInterrupted: () -> Unit = {},
) {
    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val setupTimer = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "GeminiLiveSetupTimeout").apply { isDaemon = true }
    }
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var setupComplete = false
    @Volatile private var setupQueued = false
    @Volatile private var setupTimeout: ScheduledFuture<*>? = null

    fun connect() {
        require(apiKey.isNotBlank()) { "Gemini API key is not configured" }
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(url).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                AppLog.i("Gemini Live WebSocket opened (HTTP ${response.code}); sending setup for $model")
                setupTimeout = setupTimer.schedule({
                    if (!setupComplete && !closed) {
                        onClosed("Gemini setup timeout: setupQueued=$setupQueued, no setupComplete received within 20 seconds")
                    }
                }, 20, TimeUnit.SECONDS)
                val setup = JSONObject()
                    .put("setup", JSONObject()
                        .put("model", "models/$model")
                        .put("generationConfig", JSONObject()
                            .put("responseModalities", org.json.JSONArray().put("AUDIO")))
                        .put("realtimeInputConfig", JSONObject()
                            .put("automaticActivityDetection", JSONObject()
                                .put("disabled", false)
                                .put("startOfSpeechSensitivity", "START_SENSITIVITY_LOW")
                                .put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                                .put("prefixPaddingMs", 80)
                                .put("silenceDurationMs", 420)))
                        .put("systemInstruction", JSONObject().put("parts", org.json.JSONArray()
                            .put(JSONObject().put("text", instructions))))
                        .put("inputAudioTranscription", JSONObject())
                        .put("outputAudioTranscription", JSONObject()))
                setupQueued = webSocket.send(setup.toString())
                AppLog.i("Gemini Live setup queued=$setupQueued")
                if (!setupQueued) onClosed("Gemini setup could not be queued on WebSocket")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val message = JSONObject(text)
                    AppLog.i("Gemini Live server message fields=${message.keys().asSequence().toList().sorted().joinToString(",")}")
                    val error = message.optJSONObject("error")
                    if (error != null) {
                        onClosed(formatServerError(error))
                    } else {
                        handle(message)
                    }
                }.onFailure {
                    AppLog.w("Gemini Live response could not be parsed (${it.javaClass.simpleName})")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                AppLog.i("Gemini Live received binary WebSocket frame (${bytes.size} bytes)")
                onMessage(webSocket, bytes.utf8())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!closed) {
                    val detail = response?.let(::formatHttpFailure)
                        ?: "WebSocket transport failed (${t.javaClass.simpleName})"
                    AppLog.w("Gemini Live connection failed: $detail")
                    onClosed(detail)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closed) onClosed("Gemini WebSocket closed (code $code)")
            }
        })
    }

    private fun handle(message: JSONObject) {
        if (message.has("setupComplete")) {
            setupComplete = true
            setupTimeout?.cancel(false)
            AppLog.i("Gemini Live setup completed")
            onReady()
            return
        }
        val content = message.optJSONObject("serverContent") ?: return
        if (content.optBoolean("interrupted", false)) onInterrupted()
        content.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let(onInputTranscript)
        content.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let(onOutputTranscript)
        if (content.optBoolean("turnComplete", false)) onTurnComplete()
        val parts = content.optJSONObject("modelTurn")?.optJSONArray("parts") ?: return
        for (i in 0 until parts.length()) {
            val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
            val data = inline.optString("data")
            if (data.isNotBlank()) onAudio24k(Base64.decode(data, Base64.DEFAULT))
        }
    }

    private fun formatHttpFailure(response: Response): String {
        val error = runCatching { JSONObject(response.body.string()).optJSONObject("error") }.getOrNull()
        val status = error?.optString("status")?.takeIf { it.isNotBlank() }
        val message = error?.optString("message")?.takeIf { it.isNotBlank() }?.let(::redactSecrets)
        return buildString {
            append("HTTP ${response.code}")
            if (status != null) append(" $status")
            if (message != null) append(": ${message.take(240)}")
        }
    }

    private fun formatServerError(error: JSONObject): String {
        val code = error.optInt("code", 0)
        val status = error.optString("status").takeIf { it.isNotBlank() }
        val message = error.optString("message").takeIf { it.isNotBlank() }?.let(::redactSecrets)
        return buildString {
            append("Gemini API error")
            if (code > 0) append(" $code")
            if (status != null) append(" $status")
            if (message != null) append(": ${message.take(240)}")
        }
    }

    private fun redactSecrets(value: String): String = value
        .replace(apiKey, "[REDACTED]", ignoreCase = true)
        .replace(Regex("(?i)AQ\\.[A-Za-z0-9_-]{12,}"), "[REDACTED_API_KEY]")
        .replace(Regex("(?i)AIza[A-Za-z0-9_-]{20,}"), "[REDACTED_API_KEY]")
        .replace(Regex("https?://\\S+"), "[URL]")

    /** Starts the first spoken turn; setupComplete alone does not ask the model to respond. */
    fun sendClientText(text: String): Boolean {
        val message = JSONObject().put("clientContent", JSONObject()
            .put("turns", org.json.JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", org.json.JSONArray().put(JSONObject().put("text", text)))))
            .put("turnComplete", true))
        val queued = socket?.send(message.toString()) == true
        AppLog.i("Gemini Live opening clientContent queued=$queued")
        return queued
    }

    /** Sends one 20 ms, 8 kHz PCM frame interpolated to Gemini's 16 kHz input rate. */
    fun sendPcm8(frame: ShortArray): Boolean {
        val bytes = ByteArray(frame.size * 4)
        var p = 0
        for (i in frame.indices) {
            val current = frame[i].toInt()
            val next = if (i + 1 < frame.size) frame[i + 1].toInt() else current
            bytes[p++] = current.toByte()
            bytes[p++] = (current shr 8).toByte()
            val interpolated = (current + next) / 2
            bytes[p++] = interpolated.toByte()
            bytes[p++] = (interpolated shr 8).toByte()
        }
        val audio = JSONObject()
            .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("mimeType", "audio/pcm;rate=16000")
        val queued = socket?.send(JSONObject().put("realtimeInput", JSONObject().put("audio", audio)).toString()) == true
        if (!queued) AppLog.w("Gemini Live rejected an outgoing audio frame")
        return queued
    }

    /** Hybrid VAD: finalize the current audio turn without disabling server-side VAD. */
    fun sendAudioStreamEnd(): Boolean {
        val message = JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true))
        val queued = socket?.send(message.toString()) == true
        AppLog.i("Gemini Live audio stream end queued=$queued")
        return queued
    }

    fun close(reason: String = "stopped") {
        closed = true
        setupTimeout?.cancel(false)
        setupTimer.shutdownNow()
        socket?.close(1000, reason.take(120))
        socket = null
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
}
