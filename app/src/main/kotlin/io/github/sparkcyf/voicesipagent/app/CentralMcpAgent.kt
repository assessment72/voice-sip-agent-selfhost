package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class CentralMcpConfig(
    val endpoint: String = "",
    val gatewayId: String = "",
    val token: String = "",
) {
    val enabled: Boolean get() = endpoint.isNotBlank() && gatewayId.isNotBlank() && token.isNotBlank()
}

/** Device token is encrypted with a non-exportable Android Keystore AES-GCM key. */
class CentralMcpConfigStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("central_mcp", Context.MODE_PRIVATE)

    fun load(): CentralMcpConfig {
        val id = prefs.getString("gateway_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("gateway_id", it).apply()
        }
        return CentralMcpConfig(
            endpoint = prefs.getString("endpoint", "").orEmpty(),
            gatewayId = id,
            token = prefs.getString("token_sealed", null)?.let(::unseal).orEmpty(),
        )
    }

    fun save(endpoint: String, gatewayId: String, token: String) {
        require(endpoint.startsWith("https://", true) || endpoint.startsWith("wss://", true)) {
            "Central MCP endpoint must use HTTPS/WSS"
        }
        require(gatewayId.matches(Regex("^[A-Za-z0-9._-]{1,64}$"))) { "Invalid gateway ID" }
        require(token.length >= 24) { "Device token is too short" }
        prefs.edit()
            .putString("endpoint", endpoint.trim().trimEnd('/'))
            .putString("gateway_id", gatewayId.trim())
            .putString("token_sealed", seal(token))
            .apply()
    }

    fun clear() {
        prefs.edit().remove("endpoint").remove("token_sealed").apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun seal(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun unseal(value: String): String? = runCatching {
        val raw = Base64.decode(value, Base64.NO_WRAP)
        require(raw.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }.getOrElse {
        AppLog.w("central MCP device token could not be decrypted; reconnect configuration is required", it)
        null
    }

    companion object {
        private const val KEY_ALIAS = "central-mcp-device-token"
    }
}

/** Outbound-only WSS agent; this phone remains authoritative for its SIM/SIP and call approvals. */
object CentralMcpAgent {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var socket: WebSocket? = null
    private val _connectionState = MutableStateFlow("DISCONNECTED")
    val connectionState: StateFlow<String> = _connectionState

    @Synchronized
    fun start(context: Context, runtime: GatewayRuntime) {
        stop()
        val config = CentralMcpConfigStore(context).load()
        if (!config.enabled) {
            _connectionState.value = "DISABLED"
            return
        }
        _connectionState.value = "CONNECTING"
        val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = agentScope
        agentScope.launch {
            var backoffMs = 2_000L
            while (isActive) {
                val opened = CompletableDeferred<WebSocket>()
                val closed = CompletableDeferred<Unit>()
                val request = runCatching { buildRequest(config) }.getOrNull()
                if (request == null) {
                    _connectionState.value = "CONFIG_ERROR"
                    AppLog.w("central MCP endpoint configuration is invalid")
                    delay(60_000)
                    continue
                }
                val listener = object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (socket !== webSocket) {
                            webSocket.close(1000, "superseded connection")
                            closed.complete(Unit)
                            return
                        }
                        _connectionState.value = "CONNECTED"
                        opened.complete(webSocket)
                        AppLog.i("central MCP connected (" + config.gatewayId + ")")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (webSocket !== socket) return
                        runCatching { handleCommand(runtime, webSocket, JSONObject(text)) }
                            .onFailure { AppLog.w("central MCP command rejected", it) }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (socket === webSocket) socket = null
                        _connectionState.value = "RECONNECTING"
                        closed.complete(Unit)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (socket === webSocket) socket = null
                        _connectionState.value = "RECONNECTING"
                        if (!opened.isCompleted) opened.completeExceptionally(t)
                        closed.complete(Unit)
                        AppLog.w("central MCP connection interrupted", t)
                    }
                }
                val ws = client.newWebSocket(request, listener)
                socket = ws
                val connected = withTimeoutOrNull(15_000) { runCatching { opened.await() }.getOrNull() }
                if (connected != null) {
                    backoffMs = 2_000L
                    while (isActive && !closed.isCompleted) {
                        val metadata = heartbeat(runtime, config.gatewayId)
                        connected.send(JSONObject().put("type", "heartbeat").put("metadata", metadata).toString())
                        delay(20_000)
                    }
                } else {
                    ws.cancel()
                    _connectionState.value = "RECONNECTING"
                }
                if (socket === ws) socket = null
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
            }
        }
    }

    @Synchronized
    fun stop() {
        socket?.close(1000, "gateway service stopped")
        socket = null
        scope?.cancel()
        scope = null
        _connectionState.value = "DISCONNECTED"
    }

    @Synchronized
    fun reconfigure(context: Context, runtime: GatewayRuntime) = start(context, runtime)

    private fun buildRequest(config: CentralMcpConfig): Request {
        val base = config.endpoint.trimEnd('/')
        val wsUrl = when {
            base.startsWith("https://", true) -> "wss://" + base.substringAfter("://") + "/v1/agent"
            base.startsWith("wss://", true) -> base + "/v1/agent"
            else -> error("Only HTTPS/WSS central endpoints are supported")
        }
        return Request.Builder()
            .url(wsUrl)
            .header("Authorization", "Bearer " + config.token)
            .header("X-Gateway-Id", config.gatewayId)
            .build()
    }

    private fun heartbeat(runtime: GatewayRuntime, gatewayId: String): JSONObject {
        val health = runtime.health.value
        val settings = runtime.settings
        return JSONObject()
            .put("gateway_id", gatewayId)
            .put("manufacturer", Build.MANUFACTURER.take(40))
            .put("model", Build.MODEL.take(80))
            .put("android_release", Build.VERSION.RELEASE)
            .put("app_version", "0.2.1-selfhost1")
            .put("takeover", health.takeover)
            .put("sip_ready", health.sip.ready)
            .put("sip_detail", health.sip.detail.take(120))
            .put("server_mode", settings.serverMode)
            .put("active_call", health.session != null || health.calls.owned > 0)
            .put("call_summary", health.calls.summary.take(120))
            .put("reported_at", System.currentTimeMillis())
    }

    private fun handleCommand(runtime: GatewayRuntime, ws: WebSocket, message: JSONObject) {
        if (message.optString("type") != "command") return
        val id = message.optString("id")
        if (id.isBlank() || id.length > 96) return
        val name = message.optString("name")
        val args = message.optJSONObject("arguments") ?: JSONObject()
        val result = runCatching { execute(runtime, name, args) }
        val reply = JSONObject().put("type", "command_result").put("id", id)
        if (result.isSuccess) reply.put("result", result.getOrThrow())
        else reply.put("error", result.exceptionOrNull()?.message ?: "command_failed")
        ws.send(reply.toString())
    }

    private fun execute(runtime: GatewayRuntime, name: String, args: JSONObject): JSONObject = when (name) {
        "sip_gateway_status" -> {
            val health = runtime.health.value
            JSONObject()
                .put("ok", true)
                .put("takeover", health.takeover)
                .put("sip_ready", health.sip.ready)
                .put("sip_detail", health.sip.detail)
                .put("call_active", health.session != null || health.calls.owned > 0)
                .put("call_summary", health.calls.summary)
                .put("server_mode", runtime.settings.serverMode)
        }
        "sip_calls_list" -> {
            fun callJson(call: io.github.sparkcyf.voicesipagent.voice.ManagedCall) = JSONObject()
                .put("id", call.id)
                .put("kind", call.kind)
                .put("direction", call.direction.name)
                .put("remote_number", call.remoteNumber ?: JSONObject.NULL)
                .put("display_name", call.displayName ?: JSONObject.NULL)
                .put("target", call.target ?: JSONObject.NULL)
                .put("state", call.state.name)
                .put("end_cause", call.endCause?.name ?: JSONObject.NULL)
                .put("observed_at", call.observedAt.toString())
            JSONObject()
                .put("active", JSONArray(runtime.callControl.activeCalls().map(::callJson)))
                .put("history", JSONArray(runtime.callControl.callHistory(args.optInt("limit", 100).coerceIn(1, 500)).map(::callJson)))
        }
        "sip_pending_call_approvals" -> JSONObject().put(
            "approvals",
            JSONArray(runtime.pendingCallApprovals.value.map { approval ->
                JSONObject()
                    .put("id", approval.id)
                    .put("action", approval.spec.action.name)
                    .put("call_id", approval.spec.callId ?: JSONObject.NULL)
                    .put("target", approval.spec.target ?: JSONObject.NULL)
                    .put("route", approval.spec.route)
                    .put("digit", approval.spec.dtmf?.toString() ?: JSONObject.NULL)
                    .put("state", approval.state.name)
                    .put("expires_at", approval.expiresAt.toString())
            }),
        )
        "sip_request_call_action" -> {
            val action = io.github.sparkcyf.voicesipagent.voice.CallAction.valueOf(args.getString("action"))
            val spec = io.github.sparkcyf.voicesipagent.voice.CallActionSpec(
                action = action,
                callId = args.optString("call_id").takeIf { it.isNotBlank() && it != "null" },
                target = args.optString("target").takeIf { it.isNotBlank() && it != "null" },
                route = args.optString("route", "auto"),
                dtmf = args.optString("digit").singleOrNull(),
            )
            val approval = runtime.requestCallApproval(spec)
            JSONObject()
                .put("ok", true)
                .put("approval_id", approval.id)
                .put("state", approval.state.name)
                .put("expires_at", approval.expiresAt.toString())
                .put("requires_local_approval", true)
        }
        "sip_execute_approved_action" -> {
            val approvalId = args.getString("approval_id")
            val result = runtime.callControl.execute(approvalId)
            JSONObject()
                .put("ok", result.executed)
                .put("detail", result.detail)
                .put("call_id", result.call?.id ?: JSONObject.NULL)
        }
        "sip_call_audit" -> JSONObject().put(
            "entries",
            JSONArray(runtime.callControl.approvals.auditLog(args.optInt("limit", 100).coerceIn(1, 500)).map { entry ->
                JSONObject()
                    .put("approval_id", entry.approvalId)
                    .put("action", entry.action.name)
                    .put("outcome", entry.outcome.name)
                    .put("timestamp", entry.timestamp.toString())
                    .put("detail", entry.detail)
            }),
        )
        else -> error("unsupported_gateway_command")
    }
}
