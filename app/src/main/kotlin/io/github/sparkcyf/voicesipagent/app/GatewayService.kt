package io.github.sparkcyf.voicesipagent.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.os.BatteryManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Build
import android.os.PowerManager
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Persistent foreground host for the gateway. The service remains alive independently of the
 * takeover switch; SIP/call bridging itself remains gated by GatewayRuntime.gatewayEnabled.
 * Android force-stop and OEM task killers can still terminate any application.
 */
class GatewayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var statusJob: Job? = null
    private var bridgeSettingsJob: Job? = null
    private var bridgeHeartbeatJob: Job? = null
    private var foreground = false
    // Held while the gateway runs in the foreground, so Doze on an aggressive OEM (ZTE/Nubia,
    // Xiaomi, Transsion…) cannot suspend the SIP reader thread.  Without it the socket backs up
    // and the softphone's registration silently drops until the phone next wakes — the battery
    // allowlist does not help, because it lifts network/alarm limits but does not keep the CPU
    // scheduled.  The gateway sits on a charger in this role, so keeping the CPU awake is the
    // intended trade-off.
    private var wakeLock: PowerManager.WakeLock? = null
    private val runtimeToken = Any()
    private val roleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            GatewayRuntime.get(this@GatewayService).refreshCallAccess()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val runtime = GatewayRuntime.get(this)
        runtime.start()
        runtime.onServiceCreated(runtimeToken)
        ContextCompat.registerReceiver(this, roleReceiver,
            IntentFilter(TelecomManager.ACTION_DEFAULT_DIALER_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        runtime.refreshCallAccess()
        bridgeSettingsJob = scope.launch {
            runtime.settingsFlow.map { Triple(it.controlBridgeUrl, it.controlBridgeDeviceId, it.controlBridgeToken) }
                .distinctUntilChanged()
                .collect { syncControlBridge(runtime.settings) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runtime = GatewayRuntime.get(this)
        if (!runtime.health.value.needsForegroundService) {
            stopGateway(startId)
            return START_NOT_STICKY
        }
        // Recheck at each start, including sticky restarts and rapid off/on transitions.
        if (!foreground) {
            try {
                promote(runtime.health.value)
            } catch (e: Exception) {
                AppLog.w("could not promote gateway service", e)
                stopGateway(startId)
                return START_NOT_STICKY
            }
        }
        runtime.onForegroundStarted(runtimeToken)
        syncControlBridge(runtime.settings)
        if (statusJob == null) {
            val nm = getSystemService(NotificationManager::class.java)
            statusJob = scope.launch {
                runtime.health.map { it.needsForegroundService to summary(it) }.distinctUntilChanged().collect { (needed, text) ->
                    if (needed) nm.notify(NOTIFICATION_ID, build(text)) else stopGateway()
                }
            }
        }
        return START_STICKY
    }

    private fun promote(health: HealthState) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Gateway status", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, build(summary(health)), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, build(summary(health)))
        }
        foreground = true
        acquireWakeLock()
    }

    private fun stopGateway(startId: Int? = null) {
        statusJob?.cancel()
        statusJob = null
        bridgeHeartbeatJob?.cancel()
        bridgeHeartbeatJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        releaseWakeLock()
        if (startId == null) stopSelf() else stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        GatewayRuntime.get(this).onForegroundStopped(runtimeToken)
        unregisterReceiver(roleReceiver)
        bridgeSettingsJob?.cancel()
        bridgeHeartbeatJob?.cancel()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        releaseWakeLock()
        super.onDestroy()
    }

    /**
     * Reports telemetry only. Command polling/execution is deliberately not connected until
     * a reviewed Android runtime adapter exists for every allow-listed command.
     */
    private fun syncControlBridge(settings: GatewaySettings) {
        bridgeHeartbeatJob?.cancel()
        bridgeHeartbeatJob = null
        if (!foreground || settings.controlBridgeUrl.isBlank()) return
        val client = runCatching {
            ControlBridgeClient(settings.controlBridgeUrl, settings.controlBridgeDeviceId, settings.controlBridgeToken)
        }.getOrElse {
            AppLog.w("Voicx bridge configuration invalid: ${it.javaClass.simpleName}")
            return
        }
        bridgeHeartbeatJob = scope.launch {
            while (isActive && foreground) {
                try {
                    withContext(Dispatchers.IO) {
                        val batteryManager = getSystemService(BatteryManager::class.java)
                        val battery = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                            .takeIf { it in 0..100 }
                        val charging = batteryManager.isCharging
                        client.heartbeat(
                            battery = battery,
                            charging = charging,
                            serviceState = "running",
                            appVersion = BuildConfig.VERSION_NAME,
                        )
                    }
                } catch (e: Exception) {
                    // Do not log URLs, credentials, or response bodies.
                    AppLog.w("Voicx heartbeat failed: ${e.javaClass.simpleName}")
                }
                delay(30_000)
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voicesipagent:gateway").apply {
            setReferenceCounted(false)
            acquire()
        }
        AppLog.i("gateway wakelock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun summary(h: HealthState): String = when {
        h.session != null -> "In call · ${h.session}"
        !h.takeover && h.holdsDialer -> "Takeover off. Still your default phone app; change it in Settings."
        !h.takeover -> "Call takeover off"
        h.loopbackTest -> "Loopback test mode"
        !h.privilegedReady -> "Setup needed · Shizuku ${h.shizuku.name.lowercase()}"
        !h.sip.ready -> "SIP: ${h.sip.detail}"
        else -> "Ready · ${h.sip.detail}"
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_gateway)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL = "gateway"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val runtime = GatewayRuntime.get(context)
            runtime.start()
            sync(context, runtime.health.value)
        }

        /** Used for both explicit launches and runtime switch/role changes. */
        internal fun sync(context: Context, health: HealthState): Boolean {
            if (!health.needsForegroundService) {
                context.stopService(Intent(context, GatewayService::class.java))
                return true
            }
            try {
                context.startForegroundService(Intent(context, GatewayService::class.java))
                return true
            } catch (e: Exception) {
                // Background-start restrictions; the activity or boot receiver will retry.
                AppLog.w("could not start foreground service", e)
                return false
            }
        }
    }
}
