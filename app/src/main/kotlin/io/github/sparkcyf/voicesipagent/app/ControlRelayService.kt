package io.github.sparkcyf.voicesipagent.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Independent control-plane service. It deliberately survives SIP takeover being switched off,
 * so Telegram can still request that the gateway be enabled again.
 */
class ControlRelayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
    private var connectionJob: Job? = null
    private var socket: WebSocket? = null
    private var deviceId: String = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val settings = SettingsStore(this).load()
        if (!validSettings(settings)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        deviceId = settings.relayDeviceId
        promote()
        if (connectionJob?.isActive != true) {
            connectionJob = scope.launch { connectionLoop() }
        }
        return START_STICKY
    }

    private fun validSettings(s: GatewaySettings): Boolean {
        val urlOk = s.relayUrl.startsWith("ws://", true) || s.relayUrl.startsWith("wss://", true)
        return urlOk && s.relayDeviceId.isNotBlank() && s.relayDeviceToken.length >= 32
    }

    private fun promote() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Voicx remote control", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_gateway)
            .setContentTitle("Voicx SIP Control")
            .setContentText("قناة التحكم عن بُعد تعمل · $deviceId")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun connectionLoop() {
        var backoffSeconds = 2L
        while (scope.isActive) {
            val settings = SettingsStore(this).load()
            if (!validSettings(settings)) return
            val opened = CompletableDeferred<Unit>()
            val closed = CompletableDeferred<Unit>()
            val request = Request.Builder()
                .url(deviceWebSocketUrl(settings.relayUrl, settings.relayDeviceId))
                .header("Authorization", "Bearer ${settings.relayDeviceToken}")
                .build()
            socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    backoffSeconds = 2L
                    sendHeartbeat(webSocket)
                    opened.complete(Unit)
                    AppLog.i("local control relay connected")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.length <= 16_384) handlePacket(webSocket, text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!opened.isCompleted) opened.completeExceptionally(IllegalStateException("relay closed before handshake"))
                    closed.complete(Unit)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!opened.isCompleted) opened.completeExceptionally(t)
                    closed.complete(Unit)
                    AppLog.w("local control relay connection failed", t)
                }
            })
            try {
                opened.await()
                val heartbeatJob = scope.launch {
                    while (isActive && !closed.isCompleted) {
                        delay(25_000)
                        socket?.let(::sendHeartbeat)
                    }
                }
                closed.await()
                heartbeatJob.cancel()
            } catch (_: Exception) {
                // Retry below; credentials and URL are deliberately not logged.
            } finally {
                socket?.close(1000, "reconnecting")
                socket = null
            }
            delay(backoffSeconds * 1_000)
            backoffSeconds = min(backoffSeconds * 2, 60L)
        }
    }

    private fun deviceWebSocketUrl(base: String, id: String): String =
        base.trimEnd('/') + "/ws/device/" + id

    private fun sendHeartbeat(ws: WebSocket) {
        val health = GatewayRuntime.get(this).health.value
        val settings = GatewayRuntime.get(this).settings
        val battery = (getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
        val status = JSONObject()
            .put("gateway", if (health.takeover) "enabled" else "disabled")
            .put("sip", health.sip.detail.take(120))
            .put("active_call", health.session != null || health.calls.owned > 0)
            .put("battery", battery ?: JSONObject.NULL)
            .put("device_model", Build.MODEL.take(80))
        ws.send(JSONObject().put("type", "heartbeat").put("status", status).toString())
    }

    private fun handlePacket(ws: WebSocket, raw: String) {
        try {
            val packet = JSONObject(raw)
            if (packet.optString("type") != "command") return
            val id = packet.optString("id")
            val action = packet.optString("action")
            val issuedAt = packet.optLong("issued_at", 0L)
            val now = System.currentTimeMillis() / 1_000L
            val result = when {
                id.isBlank() || id.length > 80 -> JSONObject().put("ok", false).put("error", "invalid_command_id")
                issuedAt <= 0L || kotlin.math.abs(now - issuedAt) > 120L ->
                    JSONObject().put("ok", false).put("error", "stale_command")
                action == "status" -> statusResult()
                action == "gateway_start" -> {
                    GatewayRuntime.get(this).setTakeover(true)
                    GatewayService.start(this)
                    JSONObject().put("ok", true).put("message", "gateway_start_requested")
                }
                action == "gateway_stop" -> {
                    // The control relay service remains alive; only the SIP/cellular gateway is disabled.
                    GatewayRuntime.get(this).setTakeover(false)
                    JSONObject().put("ok", true).put("message", "gateway_stop_requested")
                }
                else -> JSONObject().put("ok", false).put("error", "action_not_allowed")
            }
            ws.send(JSONObject().put("type", "result").put("id", id).put("result", result).toString())
        } catch (e: Exception) {
            AppLog.w("invalid local relay command", e)
        }
    }

    private fun statusResult(): JSONObject {
        val runtime = GatewayRuntime.get(this)
        val health = runtime.health.value
        return JSONObject()
            .put("ok", true)
            .put("device_id", deviceId)
            .put("gateway_enabled", health.takeover)
            .put("sip_ready", health.sip.ready)
            .put("sip_detail", health.sip.detail.take(160))
            .put("active_call", health.session != null || health.calls.owned > 0)
            .put("privileged_ready", health.privilegedReady)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        connectionJob?.cancel()
        socket?.close(1000, "service stopped")
        socket = null
        scope.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "voicx_control_relay"
        private const val NOTIFICATION_ID = 27

        fun sync(context: Context, settings: GatewaySettings = SettingsStore(context).load()) {
            val enabled = (settings.relayUrl.startsWith("ws://", true) || settings.relayUrl.startsWith("wss://", true)) &&
                settings.relayDeviceId.isNotBlank() && settings.relayDeviceToken.length >= 32
            val intent = Intent(context, ControlRelayService::class.java)
            if (enabled) {
                try {
                    ContextCompat.startForegroundService(context, intent)
                } catch (e: Exception) {
                    AppLog.w("could not start local control relay service", e)
                }
            } else {
                context.stopService(intent)
            }
        }
    }
}
