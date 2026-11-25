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
import kotlinx.coroutines.CancellationException
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
    private var telegramSettingsJob: Job? = null
    private var telegramCommandJob: Job? = null
    private var telegramClient: TelegramBotClient? = null
    private var telegramConfigToken: String? = null
    private var telegramConfigUserId: Long = 0L
    private var telegramUpdateOffset: Long = 0L
    private var bridgeHeartbeatJob: Job? = null
    private var bridgeCommandJob: Job? = null
    private var foreground = false
    private val salesAdmin by lazy { SalesAdminController(SalesAdminStore(this)) }
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
        telegramUpdateOffset = SettingsStore(this).loadTelegramUpdateOffset()
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
        telegramSettingsJob = scope.launch {
            runtime.settingsFlow.map { it.telegramBotToken to it.telegramAllowedUserId }
                .distinctUntilChanged()
                .collect { (token, allowedUserId) -> syncTelegramControl(token, allowedUserId) }
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
        syncTelegramControl(runtime.settings.telegramBotToken, runtime.settings.telegramAllowedUserId)
        if (statusJob == null) {
            val nm = getSystemService(NotificationManager::class.java)
            statusJob = scope.launch {
                runtime.health.map {
                    Triple(it.needsForegroundService, summary(it), it.takeover || it.session != null || it.calls.owned > 0)
                }.distinctUntilChanged().collect { (needed, text, keepCpuAwake) ->
                    if (keepCpuAwake) acquireWakeLock() else releaseWakeLock()
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
        if (health.takeover || health.session != null || health.calls.owned > 0) acquireWakeLock()
        else releaseWakeLock()
    }

    private fun stopGateway(startId: Int? = null) {
        statusJob?.cancel()
        statusJob = null
        bridgeHeartbeatJob?.cancel()
        bridgeHeartbeatJob = null
        bridgeCommandJob?.cancel()
        bridgeCommandJob = null
        // Interrupt blocking getUpdates before cancelling its coroutine. HttpURLConnection
        // is not cancellation-aware while running on Dispatchers.IO.
        telegramClient?.cancelPoll()
        telegramCommandJob?.cancel()
        telegramCommandJob = null
        telegramClient = null
        telegramConfigToken = null
        telegramConfigUserId = 0L
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
        telegramSettingsJob?.cancel()
        bridgeHeartbeatJob?.cancel()
        bridgeCommandJob?.cancel()
        telegramClient?.cancelPoll()
        telegramCommandJob?.cancel()
        telegramClient = null
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        releaseWakeLock()
        super.onDestroy()
    }

    /**
     * Keeps the authenticated control-plane heartbeat and bounded command poller active.
     * Only service.status/start/stop are accepted by executeControlCommand; no SIP call action
     * or arbitrary command is exposed through this bridge.
     */
    private fun syncControlBridge(settings: GatewaySettings) {
        val runtime = GatewayRuntime.get(this)
        bridgeHeartbeatJob?.cancel()
        bridgeHeartbeatJob = null
        bridgeCommandJob?.cancel()
        bridgeCommandJob = null
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
                    AppLog.w("Voicx heartbeat failed: ${e.javaClass.simpleName}")
                }
                delay(30_000)
            }
        }
        bridgeCommandJob = scope.launch {
            while (isActive && foreground) {
                try {
                    val commands = withContext(Dispatchers.IO) { client.pollCommands() }
                    for (command in commands) {
                        val commandId = command.optString("id")
                        if (commandId.isBlank()) continue
                        val execution = try {
                            withContext(Dispatchers.IO) { client.reportResult(commandId, "running") }
                            val result = executeControlCommand(runtime, command)
                            "succeeded" to result
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            "failed" to org.json.JSONObject().put("error", e.javaClass.simpleName)
                        }
                        withContext(Dispatchers.IO) {
                            client.reportResult(
                                commandId,
                                execution.first,
                                result = execution.second,
                                error = if (execution.first == "failed") execution.second.optString("error") else null,
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.w("Voicx command poll failed")
                }
                delay(8_000)
            }
        }
    }

    /**
     * Direct, single-device Telegram polling. The bot token is stored encrypted by SettingsStore.
     * Only the configured numeric user ID in a private chat can execute commands.
     */
    private fun syncTelegramControl(token: String, allowedUserId: Long) {
        if (!foreground || token.isBlank() || allowedUserId <= 0L) {
            telegramClient?.cancelPoll()
            telegramCommandJob?.cancel()
            telegramCommandJob = null
            telegramClient = null
            telegramConfigToken = null
            telegramConfigUserId = 0L
            return
        }
        if (telegramConfigToken == token && telegramConfigUserId == allowedUserId && telegramCommandJob?.isActive == true) return
        // getUpdates is a long poll. Coroutine cancellation alone cannot interrupt blocking I/O.
        telegramClient?.cancelPoll()
        telegramCommandJob?.cancel()
        telegramCommandJob = null
        telegramClient = null
        val client = runCatching { TelegramBotClient(token) }.getOrElse {
            AppLog.w("Telegram configuration invalid: ${it.javaClass.simpleName}")
            telegramConfigToken = null
            telegramConfigUserId = 0L
            return
        }
        telegramClient = client
        telegramConfigToken = token
        telegramConfigUserId = allowedUserId
        val runtime = GatewayRuntime.get(this)
        runtime.onAiReport = { messages ->
            scope.launch(Dispatchers.IO) {
                messages.forEach { message ->
                    if (isActive) runCatching { client.sendMessage(allowedUserId, message) }
                }
            }
        }
        telegramCommandJob = scope.launch {
            while (isActive && foreground) {
                try {
                    val updates = withContext(Dispatchers.IO) { client.poll(telegramUpdateOffset) }
                    for (update in updates) {
                        telegramUpdateOffset = maxOf(telegramUpdateOffset, update.updateId + 1L)
                        SettingsStore(this@GatewayService).saveTelegramUpdateOffset(telegramUpdateOffset)
                        if (!update.privateChat || update.senderId != allowedUserId) continue
                        if (update.callbackQueryId != null) {
                            withContext(Dispatchers.IO) { runCatching { client.answerCallbackQuery(update.callbackQueryId) } }
                            val response = withContext(Dispatchers.IO) { salesAdmin.handleCallback(update.callbackData.orEmpty(), update.senderId) }
                            withContext(Dispatchers.IO) { client.sendMessage(update.chatId, response.text, response.buttons) }
                            continue
                        }
                        val adminReply = withContext(Dispatchers.IO) { salesAdmin.handleText(update.text, update.senderId) }
                        if (adminReply != null) {
                            withContext(Dispatchers.IO) { client.sendMessage(update.chatId, adminReply.text, adminReply.buttons) }
                            continue
                        }
                        val commandParts = update.text.trim().split(Regex("\\s+"), limit = 2)
                        val command = commandParts.firstOrNull().orEmpty().substringBefore('@').lowercase()
                        val argument = commandParts.getOrNull(1).orEmpty().trim()
                        val reply = try {
                            when (command) {
                                "/start", "/help" ->
                                    "Voicx control جاهز. الأوامر:\n/status - حالة البوابة\n/sip - حالة SIP\n/calls - حالة المكالمات\n/simcall <رقم> - اتصال SIM وربطه بـ SIP\n/call رقم | اسم | وظيفة | شركة - اتصال SIM مع Gemini\n/admin - إعدادات أسعار وتغطية الديزل\n/ai_status - اختبار اتصال Gemini Live API\n/ai_errors - آخر تشخيصات Gemini\n/auto_answer_on - الرد الآلي بواسطة Gemini\n/auto_answer_off - إيقاف الرد الآلي\n/ai_stop - إيقاف مكالمة Gemini\n/simcall_cancel - إنهاء الاتصال الجاري\n/account - سجل المكالمات والصوت والبطارية\n/simcall_delete <id> - حذف سجل\n/service_on - تشغيل البوابة\n/service_off - إيقاف التحويل"
                                "/status" -> {
                                    val h = GatewayRuntime.get(this@GatewayService).health.value
                                    "Gateway: ${if (h.takeover) "ON" else "OFF"}\nSIP: ${if (h.sip.ready) "ready" else "not ready"}\nActive call: ${h.session != null || h.calls.owned > 0}"
                                }
                                "/sip" -> {
                                    val h = GatewayRuntime.get(this@GatewayService).health.value
                                    "SIP: ${if (h.sip.ready) "ready" else "not ready"}\n${h.sip.detail.take(500)}"
                                }
                                "/calls" -> GatewayRuntime.get(this@GatewayService).health.value.calls.summary.take(1000)
                                "/simcall" -> {
                                    if (argument.isBlank()) "الاستخدام: /simcall <رقم>"
                                    else {
                                        val chatId = update.chatId
                                        GatewayRuntime.get(this@GatewayService).startTelegramSimCall(argument) { event ->
                                            scope.launch(Dispatchers.IO) { runCatching { client.sendMessage(chatId, event) } }
                                        }
                                        "تم استلام الطلب. قد تُحتسب رسوم اتصال من المشغّل. سأرسل تحديثات الرنين والرد والانتهاء هنا."
                                    }
                                }
                                "/simcall_cancel" -> if (GatewayRuntime.get(this@GatewayService).cancelTelegramSimCall()) {
                                    "أُرسل طلب إنهاء مكالمة SIM الحالية."
                                } else "لا توجد مكالمة SIM نشطة."
                                "/simcall_delete" -> if (argument.isBlank()) {
                                    "الاستخدام: /simcall_delete <id> — اعرض السجلات أولاً عبر /account."
                                } else if (GatewayRuntime.get(this@GatewayService).deleteTelegramCallRecord(argument)) {
                                    "تم حذف سجل المكالمة #$argument."
                                } else "لم أعثر على سجل بالمعرّف المحدد."
                                "/account" -> GatewayRuntime.get(this@GatewayService).telegramAccountReport()
                                "/ai_status" -> {
                                    val chatId = update.chatId
                                    runtime.checkAiConnection { result ->
                                        scope.launch(Dispatchers.IO) {
                                            runCatching { client.sendMessage(chatId, result) }
                                        }
                                    }
                                    "جارٍ اختبار اتصال Gemini Live API؛ سأرسل النتيجة حالما يكتمل الفحص."
                                }
                                "/ai_errors" -> {
                                    val diagnostics = AppLog.tail.value
                                        .filter { it.contains("Gemini Live", ignoreCase = true) }
                                        .takeLast(10)
                                    if (diagnostics.isEmpty()) "لا توجد تشخيصات Gemini مسجلة منذ تشغيل التطبيق."
                                    else "آخر تشخيصات Gemini Live (لا تتضمن المفتاح أو الصوت):\n${diagnostics.joinToString("\n")}".take(3_800)
                                }
                                "/auto_answer_on" -> {
                                    runtime.setAiAutoAnswer(true) { result ->
                                        scope.launch(Dispatchers.IO) { runCatching { client.sendMessage(update.chatId, result) } }
                                    }
                                    "جارٍ تفعيل الرد الآلي بواسطة Gemini."
                                }
                                "/auto_answer_off" -> {
                                    runtime.setAiAutoAnswer(false) { result ->
                                        scope.launch(Dispatchers.IO) { runCatching { client.sendMessage(update.chatId, result) } }
                                    }
                                    "جارٍ إيقاف الرد الآلي بواسطة Gemini."
                                }
                                "/ai_stop" -> { runtime.stopAiCall(); "تم قبول طلب إيقاف مكالمة Gemini." }
                                "/call" -> {
                                    val fields = argument.split('|').map { it.trim() }
                                    if (fields.size < 4 || fields[0].isBlank()) "الاستخدام: /call الرقم | الاسم | الوظيفة | الشركة"
                                    else {
                                        runtime.startAiCall(fields[0], fields[1], fields[2], fields[3])
                                        "تم قبول طلب الاتصال. سيرد Gemini على المكالمة وسيُرسل التقرير بعد انتهائها."
                                    }
                                }
                                "/service_on" -> {
                                    GatewayRuntime.get(this@GatewayService).setTakeover(true)
                                    "Enable request accepted. Check /status in a moment."
                                }
                                "/service_off" -> {
                                    GatewayRuntime.get(this@GatewayService).setTakeover(false)
                                    "Disable request accepted. An active call will finish first."
                                }
                                else -> "Unknown command. Send /help."
                            }
                        } catch (e: Exception) {
                            "Command failed: ${e.javaClass.simpleName}"
                        }
                        withContext(Dispatchers.IO) { client.sendMessage(update.chatId, reply) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A deliberate poll disconnect during service/config shutdown is not
                    // a Telegram outage and must not be reported as an IllegalStateException.
                    if (!isActive || !foreground) break
                    AppLog.w("Telegram polling failed: ${e.javaClass.simpleName}: ${e.message?.take(180) ?: "no detail"}")
                    delay(5_000)
                }
            }
        }
    }

    private suspend fun executeControlCommand(
        runtime: GatewayRuntime,
        command: org.json.JSONObject,
    ): org.json.JSONObject {
        return when (command.optString("type")) {
            "service.status" -> {
                val h = runtime.health.value
                org.json.JSONObject()
                    .put("serviceState", if (h.takeover) "running" else "stopped")
                    .put("gatewayHost", "running")
                    .put("sipReady", h.sip.ready)
                    .put("sipDetail", h.sip.detail.take(160))
                    .put("activeCall", h.session != null || h.calls.owned > 0)
                    .put("takeoverPending", h.takeoverOffPending || h.takeoverOnPending)
            }
            "service.start" -> {
                runtime.setTakeover(true)
                delay(1_200)
                val h = runtime.health.value
                if (!h.takeover && !h.takeoverOnPending) error("gateway_start_not_applied")
                org.json.JSONObject().put("serviceState", "running")
                    .put("takeoverPending", h.takeoverOnPending)
            }
            "service.stop" -> {
                runtime.setTakeover(false)
                delay(1_200)
                val h = runtime.health.value
                if (h.takeover && !h.takeoverOffPending) error("gateway_stop_not_applied")
                org.json.JSONObject().put("serviceState", if (h.takeoverOffPending) "stopping_after_call" else "stopped")
                    .put("takeoverPending", h.takeoverOffPending)
                    .put("gatewayHost", "running")
            }
            else -> error("unsupported_command")
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
