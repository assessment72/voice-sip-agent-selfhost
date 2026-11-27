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
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    private val panelMessageIds = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private val panelSessions = java.util.concurrent.ConcurrentHashMap<Long, PanelSession>()
    private var bridgeHeartbeatJob: Job? = null
    private var bridgeCommandJob: Job? = null
    private var foreground = false
    private val campaignTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private fun campaignTime(value: String): Long? = runCatching {
        LocalDateTime.parse(value.trim().replace('T', ' '), campaignTimeFormatter).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()
    private val salesAdmin by lazy { SalesAdminController(SalesAdminStore(this)) }
    private data class PanelSession(
        val flow: String,
        val values: MutableList<String> = mutableListOf(),
        var confirmationToken: String? = null,
        var action: PanelAction? = null,
    )
    private sealed interface PanelAction {
        data class AddCustomer(val phone: String, val name: String, val role: String, val company: String, val instructions: String) : PanelAction
        data class GeminiCall(val phone: String, val name: String, val role: String, val company: String) : PanelAction
        data class SimCall(val phone: String) : PanelAction
        data class SendSms(val phone: String, val text: String) : PanelAction
        data class ScheduleCall(val phone: String, val at: Long, val note: String) : PanelAction
        data class ScheduleSms(val phone: String, val at: Long, val text: String) : PanelAction
        data class CreateCampaign(val name: String, val at: Long, val group: String, val channel: String, val template: String) : PanelAction
        data class DeleteCall(val id: String) : PanelAction
        data class ToggleGateway(val enabled: Boolean) : PanelAction
        data class ReconcileSchedule(val type: ScheduleType, val id: String, val action: ScheduleReconciliationPolicy.Action, val evidence: String) : PanelAction
    }
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
                            val response = withContext(Dispatchers.IO) {
                                if (update.callbackData.orEmpty().startsWith("voicx:")) handleVoicxPanel(update.callbackData.orEmpty(), runtime, update.chatId, client)
                                else salesAdmin.handleCallback(update.callbackData.orEmpty(), update.senderId)
                            }
                            withContext(Dispatchers.IO) { renderPanel(update.chatId, client, response, update.messageId) }
                            continue
                        }
                        if (update.text.trim().equals("/start", true) || update.text.trim().equals("/panel", true) || update.text.trim().equals("/db", true)) {
                            panelSessions.remove(update.chatId)
                            val response = when (update.text.trim().lowercase()) {
                                "/db" -> handleVoicxPanel("voicx:db", runtime, update.chatId, client)
                                else -> handleVoicxPanel("voicx:home", runtime, update.chatId, client)
                            }
                            withContext(Dispatchers.IO) { renderPanel(update.chatId, client, response) }
                            continue
                        }
                        val panelReply = handlePanelText(update.chatId, update.text, runtime, client)
                        if (panelReply != null) {
                            withContext(Dispatchers.IO) { renderPanel(update.chatId, client, panelReply) }
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
                                    "Voicx Control جاهز. افتح اللوحة التفاعلية عبر /panel.\n\nأوامر سريعة:\n/status - حالة البوابة\n/sip - حالة SIP\n/calls - حالة المكالمات\n/db أو /db_status - ملخص SQLite المحلية\n/db_customers - قراءة العملاء\n/db_schedules - قراءة الجدولة\n/db_export - يذكّرك بالتصدير من التطبيق (لا تُرسل نسخة قاعدة البيانات تلقائياً)\n/customer_search <نص> - البحث عن عميل\n/customer_add رقم | اسم | وظيفة | شركة | تعليمات - إضافة عميل\n/schedule_add customerId | رقم | وقت Unix ms | ملاحظة - جدولة اتصال\n/schedule_list - عرض مواعيد الاتصالات\n/sms_schedule_list - عرض رسائل SMS المجدولة\n/sms_sequence رقم | YYYY-MM-DD HH:mm - إنشاء 3 مسودات Gemini مخصصة\n/sms_sequence_list - عرض تسلسلات العملاء\n/sms_sequence_confirm ID - تفعيل التسلسل بعد المراجعة\n/sms_sequence_edit ID | المرحلة | YYYY-MM-DD HH:mm | النص - تعديل رسالة وموعد\n/campaign_add ... - إنشاء حملة\n/admin - إعدادات المبيعات\n/account - سجل المكالمات\n/service_on أو /service_off - تشغيل أو إيقاف البوابة"
                                "/status" -> {
                                    val h = GatewayRuntime.get(this@GatewayService).health.value
                                    "Gateway: ${if (h.takeover) "ON" else "OFF"}\nSIP: ${if (h.sip.ready) "ready" else "not ready"}\nActive call: ${h.session != null || h.calls.owned > 0}"
                                }
                                "/sip" -> {
                                    val h = GatewayRuntime.get(this@GatewayService).health.value
                                    "SIP: ${if (h.sip.ready) "ready" else "not ready"}\n${h.sip.detail.take(500)}"
                                }
                                "/calls" -> GatewayRuntime.get(this@GatewayService).health.value.calls.summary.take(1000)
                                "/db_status", "/db" -> runtime.localDatabaseSummary()
                                "/db_customers" -> runtime.searchCustomers("") { result -> scope.launch(Dispatchers.IO) { runCatching { client.sendMessage(update.chatId, "العملاء:\n${result.take(3800)}") } } }.let { "جارٍ قراءة العملاء من SQLite المحلية." }
                                "/db_schedules" -> { runtime.listCallSchedules { result -> scope.launch(Dispatchers.IO) { runCatching { client.sendMessage(update.chatId, "جدولة الاتصالات:\n${result.take(1800)}") } } }; runtime.listSmsSchedules { result -> scope.launch(Dispatchers.IO) { runCatching { client.sendMessage(update.chatId, "جدولة SMS:\n${result.take(1800)}") } } }; "جارٍ قراءة الجداول من SQLite المحلية." }
                                "/db_export" -> "التصدير متاح من التطبيق عبر قسم قاعدة البيانات المحلية. لا أرسل ملف SQLite تلقائياً عبر Telegram لحماية بيانات العملاء؛ استخدم زر التصدير ثم شارك الملف يدوياً عند الحاجة."
                                "/customer_search" -> {
                                    if (argument.isBlank()) "الاستخدام: /customer_search <نص>"
                                    else { runtime.searchCustomers(argument) { result -> scope.launch(Dispatchers.IO) { runCatching { client.sendMessage(update.chatId, result.take(3900)) } } }; "جارٍ البحث عن العملاء وإرسال النتائج." }
                                }
                                "/customer_add" -> {
                                    val fields = argument.split('|').map { it.trim() }
                                    if (fields.size < 5 || fields[0].isBlank() || fields[1].isBlank()) "الاستخدام: /customer_add رقم | اسم | وظيفة | شركة | تعليمات"
                                    else { runtime.saveCustomer(VoicxCustomer(phone = fields[0], name = fields[1], role = fields[2], company = fields[3], instructions = fields.drop(4).joinToString(" | "))) { result -> scope.launch(Dispatchers.IO) { runCatching { client.sendMessage(update.chatId, "نتيجة حفظ العميل: $result") } } }; "تم استلام بيانات العميل للحفظ." }
                                }
                                "/schedule_add" -> {
                                    val fields = argument.split('|').map { it.trim() }; val at = fields.getOrNull(2)?.toLongOrNull()
                                    if (fields.size < 3 || fields[0].isBlank() || fields[1].isBlank() || at == null || at <= System.currentTimeMillis()) "الاستخدام: /schedule_add customerId | رقم | وقت Unix ms | ملاحظة"
                                    else { runtime.saveCallSchedule(CallSchedule(customerId = fields[0], phone = fields[1], runAt = at, note = fields.getOrElse(3) { "" })) { result -> scope.launch(Dispatchers.IO) { runCatching { client.sendMessage(update.chatId, "نتيجة الجدولة: $result") } } }; "تم استلام موعد الاتصال." }
                                }
                                "/schedule_list" -> { runtime.listCallSchedules { result -> scope.launch(Dispatchers.IO) { runCatching { client.sendMessage(update.chatId, result.take(3900)) } } }; "جارٍ تحميل مواعيد الاتصالات." }
                                "/sms_schedule_add" -> {
                                    val fields = argument.split('|').map { it.trim() }; val at = fields.getOrNull(2)?.toLongOrNull()
                                    if (fields.size < 4 || fields[0].isBlank() || fields[1].isBlank() || at == null || at <= System.currentTimeMillis() || fields[3].isBlank()) "الاستخدام: /sms_schedule_add customerId | رقم | وقت Unix ms | رسالة | ملاحظة"
                                    else { runtime.saveSmsSchedule(SmsSchedule(customerId = fields[0], phone = fields[1], runAt = at ?: 0L, message = fields[3], note = fields.getOrElse(4) { "" })) { result -> scope.launch(Dispatchers.IO) { runCatching { client.sendMessage(update.chatId, "نتيجة جدولة SMS: $result") } } }; "تم استلام موعد SMS." }
                                }
                                "/sms_schedule_list" -> { runtime.listSmsSchedules { result -> scope.launch(Dispatchers.IO) { runCatching { client.sendMessage(update.chatId, result.take(3900)) } } }; "جارٍ تحميل رسائل SMS المجدولة." }
                                "/campaign_add" -> {
                                    val fields = argument.split('|').map { it.trim() }
                                    val callAt = fields.getOrNull(2)?.toLongOrNull(); val smsAt = fields.getOrNull(3)?.toLongOrNull()
                                    if (fields.size < 5 || fields[0].isBlank() || fields[1].isBlank() || callAt == null || smsAt == null || callAt <= System.currentTimeMillis() || smsAt <= System.currentTimeMillis() || fields[4].isBlank()) "الاستخدام: /campaign_add customerId | رقم | وقت الاتصال Unix ms | وقت SMS Unix ms | الرسالة | ملاحظة"
                                    else {
                                        val campaignId = "campaign-${System.currentTimeMillis()}-${fields[0]}"
                                        runtime.saveCallSchedule(CallSchedule(customerId = fields[0], phone = fields[1], runAt = callAt, note = fields.getOrElse(5) { "" }, campaignId = campaignId)) { callId ->
                                            runtime.saveSmsSchedule(SmsSchedule(customerId = fields[0], phone = fields[1], runAt = smsAt ?: 0L, message = fields[4], note = fields.getOrElse(5) { "" }, campaignId = campaignId)) { smsId ->
                                                scope.launch(Dispatchers.IO) { runCatching { client.sendMessage(update.chatId, "تم إنشاء الحملة $campaignId\nالمكالمة: $callId\nSMS: $smsId") } }
                                            }
                                        }
                                        "تم استلام حملة الاتصال وSMS للجدولة."
                                    }
                                }
                                "/campaign_start" -> {
                                    val fields = argument.split('|').map { it.trim() }
                                    val startAt = fields.getOrNull(1)?.let(::campaignTime)
                                    val channel = fields.getOrNull(3).orEmpty().ifBlank { "call_sms" }
                                    if (fields.size < 4 || fields[0].isBlank() || startAt == null || startAt <= System.currentTimeMillis() || channel !in setOf("call", "sms", "call_sms"))
                                        "الاستخدام: /campaign_start اسم الحملة | 2026-12-01 09:00 | المجموعة أو all | call أو sms أو call_sms | قالب SMS"
                                    else {
                                        runtime.createCampaign(Campaign(name = fields[0], startAt = startAt, targetGroup = fields[2], channel = channel, smsTemplate = fields.getOrElse(4) { "" })) { result ->
                                            scope.launch(Dispatchers.IO) { runCatching { client.sendMessage(update.chatId, "تم إنشاء الحملة: $result") } }
                                        }
                                        "تم استلام طلب إنشاء الحملة."
                                    }
                                }
                                "/followup" -> {
                                    val fields = argument.split('|').map { it.trim() }
                                    val callAt = fields.getOrNull(1)?.let(::campaignTime); val smsAt = fields.getOrNull(2)?.takeIf { it.isNotBlank() }?.let(::campaignTime)
                                    if (fields.size < 2 || fields[0].isBlank() || callAt == null || callAt <= System.currentTimeMillis()) "الاستخدام: /followup رقم الهاتف | 2026-12-03 09:00 | 2026-12-02 17:00 | ملاحظة"
                                    else { runtime.followUp(fields[0], callAt, smsAt, fields.getOrElse(3) { "" }) { result -> scope.launch(Dispatchers.IO) { runCatching { client.sendMessage(update.chatId, result) } } }; "تم استلام قرار المتابعة." }
                                }
                                "/mark_interested" -> {
                                    val fields = argument.split('|', limit = 2).map { it.trim() }
                                    if (fields.isEmpty() || fields[0].isBlank()) "الاستخدام: /mark_interested رقم الهاتف | ملاحظة الإدارة"
                                    else { runtime.markInterested(fields[0], fields.getOrElse(1) { "" }) { result -> scope.launch(Dispatchers.IO) { runCatching { client.sendMessage(update.chatId, result) } } }; "تم استلام تحويل العميل للإدارة." }
                                }
                                "/pause_customer" -> {
                                    val fields = argument.split('|', limit = 2).map { it.trim() }
                                    if (fields.isEmpty() || fields[0].isBlank()) "الاستخدام: /pause_customer رقم الهاتف | السبب"
                                    else { runtime.pauseCustomer(fields[0], fields.getOrElse(1) { "" }) { result -> scope.launch(Dispatchers.IO) { runCatching { client.sendMessage(update.chatId, result) } } }; "تم استلام طلب الإيقاف." }
                                }
                                "/resume_customer" -> {
                                    val fields = argument.split('|').map { it.trim() }; val callAt = fields.getOrNull(1)?.let(::campaignTime); val smsAt = fields.getOrNull(2)?.takeIf { it.isNotBlank() }?.let(::campaignTime)
                                    if (fields.size < 2 || fields[0].isBlank() || callAt == null || callAt <= System.currentTimeMillis()) "الاستخدام: /resume_customer رقم الهاتف | 2026-12-03 09:00 | 2026-12-02 17:00 | ملاحظة"
                                    else { runtime.resumeCustomer(fields[0], callAt, smsAt, fields.getOrElse(3) { "" }) { result -> scope.launch(Dispatchers.IO) { runCatching { client.sendMessage(update.chatId, result) } } }; "تم استلام طلب الاستئناف." }
                                }
                                "/sms" -> {
                                    val fields = argument.split('|', limit = 2).map { it.trim() }
                                    if (fields.size < 2) "الاستخدام: /sms رقم | رسالة"
                                    else { runtime.sendSms(fields[0], fields[1]) { result -> scope.launch(Dispatchers.IO) { runCatching { client.sendMessage(update.chatId, result) } } }; "تم استلام طلب SMS." }
                                }

                                "/sms_sequence" -> {
                                    val fields = argument.split('|').map { it.trim() }
                                    val firstAt = fields.getOrNull(1)?.let(::campaignTime)
                                    if (fields.size < 2 || fields[0].isBlank() || firstAt == null || firstAt <= System.currentTimeMillis()) {
                                        "الاستخدام: /sms_sequence رقم | 2026-12-01 09:00\nينشئ Gemini ثلاث مسودات مخصصة. لن تُجدول قبل تأكيدك."
                                    } else {
                                        runtime.prepareCustomerSmsSequence(fields[0], firstAt) { result ->
                                            scope.launch(Dispatchers.IO) { runCatching { client.sendMessage(update.chatId, result) } }
                                        }
                                        "جارٍ إنشاء مسودات الرسائل الثلاث بواسطة Gemini. لن تُرسل أي رسالة قبل أمر التأكيد."
                                    }
                                }
                                "/sms_sequence_list" -> {
                                    runtime.listCustomerSmsSequences { result ->
                                        scope.launch(Dispatchers.IO) { runCatching { client.sendMessage(update.chatId, result.take(3800)) } }
                                    }
                                    "جارٍ تحميل تسلسلات رسائل العملاء."
                                }
                                "/sms_sequence_confirm" -> {
                                    if (argument.isBlank()) "الاستخدام: /sms_sequence_confirm معرّف_التسلسل"
                                    else {
                                        runtime.confirmCustomerSmsSequence(argument.trim()) { result ->
                                            scope.launch(Dispatchers.IO) { runCatching { client.sendMessage(update.chatId, result) } }
                                        }
                                        "جارٍ التحقق من المسودة وتفعيل الجدولة."
                                    }
                                }
                                "/sms_sequence_edit" -> {
                                    val fields = argument.split('|', limit = 4).map { it.trim() }
                                    val stage = fields.getOrNull(1)?.toIntOrNull()
                                    val at = fields.getOrNull(2)?.let(::campaignTime)
                                    if (fields.size < 4 || fields[0].isBlank() || stage == null || stage !in 1..3 || at == null || at <= System.currentTimeMillis() || fields[3].isBlank()) {
                                        "الاستخدام: /sms_sequence_edit ID | 1 أو 2 أو 3 | 2026-12-08 09:00 | نص الرسالة"
                                    } else {
                                        runtime.editCustomerSmsSequence(fields[0], stage!!, at, fields[3]) { result ->
                                            scope.launch(Dispatchers.IO) { runCatching { client.sendMessage(update.chatId, result) } }
                                        }
                                        "جارٍ حفظ تعديل الرسالة وموعدها."
                                    }
                                }
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

    private fun handleVoicxPanel(action: String, runtime: GatewayRuntime, chatId: Long, client: TelegramBotClient): AdminReply {
        if (action == "voicx:home") panelSessions.remove(chatId)
        val home = listOf(
            listOf(AdminButton("📊 لوحة المعلومات", "voicx:status"), AdminButton("📞 الاتصالات", "voicx:calls_menu")),
            listOf(AdminButton("🧠 Gemini Live", "voicx:gemini"), AdminButton("👥 إدارة العملاء", "voicx:customers_menu")),
            listOf(AdminButton("💬 الرسائل SMS", "voicx:sms_menu"), AdminButton("📅 إدارة المواعيد", "voicx:schedules_menu")),
            listOf(AdminButton("📢 الحملات", "voicx:campaigns"), AdminButton("📈 التقارير والسجلات", "voicx:reports")),
            listOf(AdminButton("⛽ إدارة الديزل", "voicx:diesel"), AdminButton("⚙️ إدارة النظام", "voicx:system")),
            listOf(AdminButton("🔄 تحديث", "voicx:home")),
        )
        val back = listOf(listOf(AdminButton("⬅️ رجوع", "voicx:home")))
        if (action == "voicx:cancel_flow") {
            panelSessions.remove(chatId)
            return AdminReply("تم الإلغاء. لم يُنفذ أي إجراء.", home)
        }
        if (action.startsWith("voicx:pick_date:")) {
            val session = panelSessions[chatId]
                ?: return AdminReply("انتهت جلسة اختيار الموعد. ابدأ الجدولة من جديد.", home)
            if (session.flow !in setOf("schedule_call", "sms_schedule", "campaign") || session.values.size != 1) {
                return AdminReply("اختيار التاريخ لم يعد صالحاً. ابدأ الجدولة من جديد.", home)
            }
            val selectedDate = runCatching { java.time.LocalDate.parse(action.substringAfterLast(':')) }.getOrNull()
                ?: return scheduleDatePickerReply(session.flow, "التاريخ المحدد غير صالح.")
            val today = java.time.LocalDate.now(ZoneId.systemDefault())
            if (selectedDate.isBefore(today) || selectedDate.isAfter(today.plusDays(30))) {
                return scheduleDatePickerReply(session.flow, "اختر تاريخاً من اليوم وحتى الثلاثين يوماً القادمة.")
            }
            session.values += selectedDate.toString()
            return scheduleTimePickerReply(selectedDate.toString())
        }
        if (action == "voicx:pick_back") {
            val session = panelSessions[chatId]
                ?: return AdminReply("انتهت جلسة اختيار الموعد.", home)
            if (session.flow !in setOf("schedule_call", "sms_schedule", "campaign") || session.values.size != 2) {
                return AdminReply("لا يمكن الرجوع إلى اختيار التاريخ الآن.", home)
            }
            session.values.removeAt(1)
            return scheduleDatePickerReply(session.flow)
        }
        if (action.startsWith("voicx:pick_time:")) {
            val session = panelSessions[chatId]
                ?: return AdminReply("انتهت جلسة اختيار الموعد. ابدأ الجدولة من جديد.", home)
            if (session.flow !in setOf("schedule_call", "sms_schedule", "campaign") || session.values.size != 2) {
                return AdminReply("اختيار الوقت لم يعد صالحاً. اختر التاريخ من جديد.", home)
            }
            val date = session.values[1]
            val timeToken = action.substringAfterLast(':')
            if (!timeToken.matches(Regex("\\d{4}"))) return scheduleTimePickerReply(date, "صيغة الوقت غير صالحة.")
            val time = timeToken.substring(0, 2) + ":" + timeToken.substring(2, 4)
            val at = campaignTime("$date $time")
            if (at == null || at <= System.currentTimeMillis()) {
                return scheduleTimePickerReply(date, "هذا الوقت مضى بالفعل. اختر وقتاً لاحقاً.")
            }
            session.values[1] = "$date $time"
            val nextPrompt = when (session.flow) {
                "schedule_call" -> "تم اختيار الموعد $date الساعة $time. أرسل ملاحظة الاتصال أو اكتب تخطي."
                "sms_schedule" -> "تم اختيار موعد الإرسال $date الساعة $time. أرسل نص الرسالة."
                else -> "تم اختيار بداية الحملة $date الساعة $time. أرسل مجموعة العملاء (all للجميع)."
            }
            return AdminReply(nextPrompt, listOf(listOf(AdminButton("إلغاء", "voicx:cancel_flow"))))
        }
        if (action.startsWith("voicx:rc:")) {
            val parts = action.split(":")
            if (parts.size != 5) return AdminReply("طلب مصالحة غير صالح.", home)
            val decision = when (parts[2]) { "done" -> ScheduleReconciliationPolicy.Action.MARK_COMPLETED; "fail" -> ScheduleReconciliationPolicy.Action.MARK_FAILED; else -> return AdminReply("هذا الإجراء غير متاح من Telegram.", home) }
            val type = runCatching { ScheduleType.valueOf(parts[3]) }.getOrNull() ?: return AdminReply("نوع المهمة غير صالح.", home)
            val id = parts[4].takeIf { it.isNotBlank() } ?: return AdminReply("معرّف المهمة غير صالح.", home)
            panelSessions[chatId] = PanelSession("reconcile_evidence", mutableListOf(type.name, id, decision.name))
            return AdminReply("مراجعة المهمة $id (${if (type == ScheduleType.CALL) "اتصال" else "SMS"}). أرسل مرجع الدليل أو وصف التحقق (إيصال المزود، سجل المشغل أو سجل الهاتف). لن تُرسل أي رسالة أو تُجرى مكالمة.", listOf(listOf(AdminButton("إلغاء", "voicx:cancel_flow"))))
        }
        if (action.startsWith("voicx:confirm:")) {
            val session = panelSessions[chatId]
            val token = action.substringAfterLast(':')
            if (session?.confirmationToken != token || session.action == null) return AdminReply("انتهت صلاحية التأكيد أو نُفذ مسبقاً. لم يُكرر الإجراء.", home)
            val confirmed = session.action!!
            panelSessions.remove(chatId)
            return executePanelAction(confirmed, runtime, chatId, client, home)
        }
        return when (action) {
            "voicx:home" -> {
                val h = runtime.health.value
                val keyStatus = if (runtime.settings.geminiApiKey.isBlank()) "مفتاح Gemini غير مضبوط" else "المفتاح مضبوط؛ حالة Live تحتاج فحصاً"
                val db = runtime.localDatabaseSummary().lineSequence().take(3).joinToString("\n")
                val last = AppLog.tail.value.lastOrNull()?.take(150) ?: "لا يوجد نشاط مسجل"
                AdminReply("Voicx Control\n\nالبوابة: ${if (h.takeover) "🟢 مفعلة" else "🔴 متوقفة"}\nSIP: ${if (h.sip.ready) "🟢 جاهز" else "🔴 غير جاهز"}\nGemini Live: $keyStatus\nFirebase: ${runtime.firebaseStatus().take(180)}\nقاعدة البيانات المحلية: SQLite\nالرد الآلي: ${if (runtime.settings.aiAutoAnswer) "مفعل" else "متوقف"}\nالمكالمات: ${h.calls.summary.take(180)}\nآخر نشاط: $last\n\nاختر قسماً:", home)
            }
            "voicx:status" -> {
                val h = runtime.health.value
                AdminReply("📊 لوحة المعلومات\nالبوابة: ${if (h.takeover) "مفعلة" else "متوقفة"}\nSIP: ${if (h.sip.ready) "جاهز" else "غير جاهز"} — ${h.sip.detail.take(240)}\nGemini: ${if (runtime.settings.geminiApiKey.isBlank()) "مفتاح غير مضبوط" else "مفتاح مضبوط؛ اضغط فحص الاتصال للتأكد من Live"}\nFirebase: ${runtime.firebaseStatus().take(220)}\nقاعدة البيانات المحلية: SQLite\nالرد الآلي: ${if (runtime.settings.aiAutoAnswer) "مفعل" else "متوقف"}\nالمكالمات الجارية: ${h.calls.summary.take(500)}\nآخر نشاط: ${AppLog.tail.value.lastOrNull()?.take(180) ?: "لا يوجد"}", listOf(listOf(AdminButton("فحص Gemini Live", "voicx:ai_status"), AdminButton("SIP", "voicx:sip")), listOf(AdminButton("قاعدة البيانات", "voicx:db")), back.first()))
            }
            "voicx:calls_menu" -> AdminReply("📞 <b>الاتصالات</b>\nاختر نوع العملية. سيطلب البوت البيانات ثم يعرض ملخصاً وزر تأكيد قبل الاتصال.", listOf(listOf(AdminButton("اتصال Gemini", "voicx:flow:gemini")), listOf(AdminButton("اتصال SIM مع SIP", "voicx:flow:sim")), listOf(AdminButton("إنهاء SIM", "voicx:cancel_sim"), AdminButton("إيقاف Gemini", "voicx:stop_ai")), back.first()))
            "voicx:gemini" -> AdminReply("🧠 <b>Gemini Live</b>\nالحالة الظاهرة هنا هي حالة الإعداد، وليست تأكيداً لاتصال حي. استخدم الفحص للتحقق من الخدمة.", listOf(listOf(AdminButton("اختبار اتصال Live", "voicx:ai_status"), AdminButton("آخر التشخيصات", "voicx:ai_errors")), listOf(AdminButton("تشغيل الرد الآلي", "voicx:auto_on"), AdminButton("إيقاف الرد الآلي", "voicx:auto_off")), back.first()))
            "voicx:customers_menu" -> AdminReply("👥 <b>إدارة العملاء</b>", listOf(listOf(AdminButton("البحث عن عميل", "voicx:flow:search"), AdminButton("إضافة عميل", "voicx:flow:add_customer")), listOf(AdminButton("عرض العملاء", "voicx:customers")), back.first()))
            "voicx:sms_menu" -> AdminReply("💬 <b>الرسائل النصية</b>\nالإرسال الفوري أو المجدول يتطلب مراجعة البيانات ثم تأكيداً صريحاً.", listOf(listOf(AdminButton("إرسال SMS", "voicx:flow:sms"), AdminButton("جدولة SMS", "voicx:flow:sms_schedule")), listOf(AdminButton("الرسائل المجدولة", "voicx:sms_list")), back.first()))
            "voicx:schedules_menu" -> AdminReply("📅 <b>إدارة المواعيد</b>", listOf(listOf(AdminButton("جدولة اتصال", "voicx:flow:schedule_call"), AdminButton("عرض المواعيد", "voicx:schedule_list")), listOf(AdminButton("جدولة SMS", "voicx:flow:sms_schedule"), AdminButton("رسائل SMS المجدولة", "voicx:sms_list")), listOf(AdminButton("⚠️ مهام تحتاج مراجعة", "voicx:reconciliation")), back.first()))
            "voicx:campaigns" -> AdminReply("📢 <b>إنشاء حملة</b>\nسيطلب البوت البيانات على مراحل، ثم يعرض معاينة قبل حفظ الحملة وجدولتها.", listOf(listOf(AdminButton("إنشاء حملة جديدة", "voicx:flow:campaign")), listOf(AdminButton("عرض الجداول", "voicx:schedules")), back.first()))
            "voicx:reports" -> AdminReply("📈 <b>التقارير والسجلات</b>\n${runtime.telegramAccountReport().take(2800)}", listOf(listOf(AdminButton("حذف سجل مكالمة", "voicx:flow:delete_call")), back.first()))
            "voicx:diesel" -> salesAdmin.handleText("/admin", runtime.settings.telegramAllowedUserId) ?: AdminReply("تعذر فتح إعدادات المبيعات.", back)
            "voicx:system" -> AdminReply("⚙️ <b>إدارة النظام</b>\nالتغييرات لا تنفذ إلا بعد اختيار التأكيد.", listOf(listOf(AdminButton("تشغيل البوابة", "voicx:confirm_gateway_on"), AdminButton("إيقاف البوابة", "voicx:confirm_gateway_off")), back.first()))
            "voicx:confirm_gateway_on" -> askConfirmation(chatId, "تشغيل البوابة الآن؟", PanelAction.ToggleGateway(true))
            "voicx:confirm_gateway_off" -> askConfirmation(chatId, "إيقاف التحويل؟ لن يُقطع اتصال نشط؛ سيسري الإيقاف وفق منطق التطبيق الحالي.", PanelAction.ToggleGateway(false))
            "voicx:db" -> AdminReply("قاعدة البيانات المحلية SQLite\n${runtime.localDatabaseSummary()}\n\nحالة Firebase: ${runtime.firebaseStatus()}\nالأسرار خارج ملف SQLite.", listOf(listOf(AdminButton("العملاء", "voicx:customers"), AdminButton("الجدولة", "voicx:schedules")), back.first()))
            "voicx:customers" -> AdminReply("👥 <b>العملاء</b>\n${runtime.localDatabaseCustomers().take(2600)}", listOf(listOf(AdminButton("بحث", "voicx:flow:search"), AdminButton("إضافة", "voicx:flow:add_customer")), back.first()))
            "voicx:schedules" -> AdminReply("📅 <b>الجدولة</b>\n${runtime.localDatabaseSchedules().take(2700)}", listOf(listOf(AdminButton("إضافة اتصال", "voicx:flow:schedule_call"), AdminButton("إضافة SMS", "voicx:flow:sms_schedule")), back.first()))
            "voicx:sip" -> { val sip = runtime.health.value.sip; AdminReply("📡 <b>حالة SIP</b>\n${if (sip.ready) "جاهز" else "غير جاهز"}\n${sip.detail.take(700)}", back) }
            "voicx:calls" -> AdminReply("📞 <b>المكالمات</b>\n${runtime.health.value.calls.summary.take(1200)}", back)
            "voicx:ai_status" -> { runtime.checkAiConnection { asyncPanelReply(chatId, client, it) }; AdminReply("جارٍ اختبار اتصال Gemini Live الحقيقي…", back) }
            "voicx:ai_errors" -> { val logs = AppLog.tail.value.filter { it.contains("Gemini", true) }.takeLast(12); AdminReply("🧠 آخر تشخيصات Gemini:\n${logs.joinToString("\n").ifBlank { "لا توجد تشخيصات مسجلة." }.take(2800)}", back) }
            "voicx:auto_on", "voicx:auto_off" -> { val enabled = action.endsWith("on"); runtime.setAiAutoAnswer(enabled) { asyncPanelReply(chatId, client, it) }; AdminReply(if (enabled) "جارٍ تشغيل الرد الآلي…" else "جارٍ إيقاف الرد الآلي…", back) }
            "voicx:cancel_sim" -> AdminReply(if (runtime.cancelTelegramSimCall()) "أُرسل طلب إنهاء مكالمة SIM." else "لا توجد مكالمة SIM نشطة.", back)
            "voicx:stop_ai" -> { runtime.stopAiCall(); AdminReply("تم إرسال طلب إيقاف Gemini.", back) }
            "voicx:reconciliation" -> {
                val items = runtime.scheduleReconciliationItems(8)
                val itemButtons = items.flatMap { item ->
                    val kind = if (item.type == ScheduleType.CALL) "اتصال" else "SMS"
                    val masked = TelegramSimCall.maskNumber(item.phone)
                    listOf(listOf(
                        AdminButton("✓ تمت · $kind $masked", "voicx:rc:done:${item.type.name}:${item.id}"),
                        AdminButton("✕ فشلت · $kind $masked", "voicx:rc:fail:${item.type.name}:${item.id}"),
                    ))
                }
                AdminReply(runtime.scheduleReconciliationReport(), listOf(listOf(AdminButton("🔄 تحديث القائمة", "voicx:reconciliation"))) + itemButtons + back)
            }
            "voicx:schedule_list" -> { runtime.listCallSchedules { asyncPanelReply(chatId, client, "📅 المواعيد:\n${it}") }; AdminReply("جارٍ تحميل المواعيد…", back) }
            "voicx:sms_list" -> { runtime.listSmsSchedules { asyncPanelReply(chatId, client, "💬 رسائل SMS المجدولة:\n${it}") }; AdminReply("جارٍ تحميل رسائل SMS…", back) }
            "voicx:flow:search" -> beginPanelFlow(chatId, "search")
            "voicx:flow:add_customer" -> beginPanelFlow(chatId, "add_customer")
            "voicx:flow:gemini" -> beginPanelFlow(chatId, "gemini")
            "voicx:flow:sim" -> beginPanelFlow(chatId, "sim")
            "voicx:flow:sms" -> beginPanelFlow(chatId, "sms")
            "voicx:flow:schedule_call" -> beginPanelFlow(chatId, "schedule_call")
            "voicx:flow:sms_schedule" -> beginPanelFlow(chatId, "sms_schedule")
            "voicx:flow:campaign" -> beginPanelFlow(chatId, "campaign")
            "voicx:flow:delete_call" -> beginPanelFlow(chatId, "delete_call")
            else -> AdminReply("إجراء غير معروف أو انتهت صلاحيته.", home)
        }
    }

    private fun renderPanel(chatId: Long, client: TelegramBotClient, reply: AdminReply, messageId: Long? = null) {
        val safeText = reply.text.replace(Regex("</?b>", RegexOption.IGNORE_CASE), "")
        val target = messageId ?: panelMessageIds[chatId]
        if (target != null) {
            val edited = runCatching { client.editMessageText(chatId, target, safeText, reply.buttons) }
            if (edited.isSuccess || edited.exceptionOrNull()?.message?.contains("message is not modified", true) == true) {
                panelMessageIds[chatId] = target
                return
            }
            val reason = edited.exceptionOrNull()?.message.orEmpty()
            val staleMessage = reason.contains("message to edit not found", true) || reason.contains("message can't be edited", true) || reason.contains("message_id_invalid", true)
            if (!staleMessage) {
                AppLog.w("Telegram panel edit failed; suppressing fallback send to avoid duplicate panel: ${reason.take(120)}")
                return
            }
        }
        runCatching { client.sendMessage(chatId, safeText, reply.buttons) }.onSuccess { sent ->
            if (sent != null) panelMessageIds[chatId] = sent
        }.onFailure { AppLog.w("could not render Telegram panel: ${it.javaClass.simpleName}") }
    }

    private fun asyncPanelReply(chatId: Long, client: TelegramBotClient, text: String) {
        scope.launch(Dispatchers.IO) { renderPanel(chatId, client, AdminReply(text, listOf(listOf(AdminButton("⬅️ القائمة الرئيسية", "voicx:home"))))) }
    }

    private fun beginPanelFlow(chatId: Long, flow: String): AdminReply {
        panelSessions[chatId] = PanelSession(flow)
        val prompts = mapOf(
            "search" to "أرسل جزءاً من الاسم أو رقم الهاتف للبحث في العملاء.",
            "add_customer" to "إضافة عميل (1/5): أرسل رقم الجوال اليمني.",
            "gemini" to "اتصال Gemini (1/4): أرسل رقم الجوال اليمني.",
            "sim" to "اتصال SIM مع SIP (1/1): أرسل رقم الجوال اليمني.",
            "sms" to "إرسال SMS (1/2): أرسل رقم الجوال اليمني.",
            "schedule_call" to "جدولة اتصال (1/3): أرسل رقم الجوال اليمني.",
            "sms_schedule" to "جدولة SMS (1/3): أرسل رقم الجوال اليمني.",
            "campaign" to "إنشاء حملة (1/4): أرسل اسم الحملة.",
            "delete_call" to "حذف سجل (1/1): أرسل معرّف السجل الظاهر في تقرير المكالمات.",
        )
        return AdminReply("${prompts[flow] ?: "أدخل البيانات المطلوبة."}\nأرسل /cancel للإلغاء.", listOf(listOf(AdminButton("إلغاء", "voicx:cancel_flow")), listOf(AdminButton("القائمة الرئيسية", "voicx:home"))))
    }

    private fun askConfirmation(chatId: Long, summary: String, action: PanelAction): AdminReply {
        val session = panelSessions[chatId] ?: PanelSession("confirm").also { panelSessions[chatId] = it }
        val token = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        session.confirmationToken = token
        session.action = action
        return AdminReply("معاينة قبل التنفيذ:\n$summary\n\nهل تؤكد؟", listOf(listOf(AdminButton("تأكيد التنفيذ", "voicx:confirm:$token"), AdminButton("إلغاء", "voicx:cancel_flow"))))
    }

    private fun handlePanelText(chatId: Long, rawText: String, runtime: GatewayRuntime, client: TelegramBotClient): AdminReply? {
        val session = panelSessions[chatId] ?: return null
        val text = rawText.trim()
        if (text.equals("/cancel", true) || text.equals("إلغاء", true)) {
            panelSessions.remove(chatId)
            return AdminReply("تم إلغاء النموذج. لم يُنفذ أي إجراء.", listOf(listOf(AdminButton("القائمة الرئيسية", "voicx:home"))))
        }
        if (text.startsWith("/")) return null
        if (text.isBlank()) return AdminReply("لم يصل نص. أرسل البيانات المطلوبة أو /cancel للإلغاء.")
        if (session.flow == "reconcile_evidence") {
            if (text.length < 4) return AdminReply("الدليل مطلوب (4 أحرف على الأقل). أرسل مرجعاً أو وصف تحقق، أو /cancel للإلغاء.")
            val type = runCatching { ScheduleType.valueOf(session.values.getOrNull(0).orEmpty()) }.getOrNull()
                ?: return AdminReply("انتهت جلسة المراجعة. افتح القائمة من جديد.")
            val id = session.values.getOrNull(1).orEmpty()
            val action = runCatching { ScheduleReconciliationPolicy.Action.valueOf(session.values.getOrNull(2).orEmpty()) }.getOrNull()
                ?: return AdminReply("إجراء المراجعة غير صالح.")
            if (action == ScheduleReconciliationPolicy.Action.RETRY) return AdminReply("إعادة المحاولة غير متاحة من هذه الواجهة.")
            val evidence = text.take(2000)
            val pending = PanelAction.ReconcileSchedule(type, id, action, evidence)
            val outcome = if (action == ScheduleReconciliationPolicy.Action.MARK_COMPLETED) "اعتماد المهمة كمكتملة" else "اعتماد المهمة كفاشلة"
            return askConfirmation(chatId, "$outcome\nالمعرّف: $id\nالدليل: $evidence\n\nلن يتم إرسال SMS أو إجراء مكالمة.", pending)
        }
        if (session.flow == "search") {
            panelSessions.remove(chatId)
            runtime.searchCustomers(text) { asyncPanelReply(chatId, client, "نتائج البحث:\n${it.take(3400)}") }
            return AdminReply("جارٍ البحث في قاعدة SQLite المحلية…")
        }
        var value = if (text.equals("تخطي", true)) "" else text
        val phoneStep = when (session.flow) {
            "add_customer", "gemini", "sim", "sms", "schedule_call", "sms_schedule" -> session.values.isEmpty()
            else -> false
        }
        if (phoneStep) {
            value = runCatching { PhoneNumberNormalizer.normalizeYemen(value) }.getOrElse { return AdminReply("رقم غير صالح. أدخل جوالاً يمنياً مثل 730501035 أو +967730501035.") }
        }
        session.values += value
        val fields = session.values
        val expected = when (session.flow) {
            "add_customer" -> 5
            "gemini" -> 4
            "sim", "delete_call" -> 1
            "sms" -> 2
            "sms_schedule" -> 3
            "schedule_call" -> 3
            "campaign" -> if (fields.size >= 4 && fields[3].lowercase() in setOf("اتصال", "call")) 4 else 5
            else -> fields.size
        }
        if (fields.size < expected) {
            if (fields.size == 1 && session.flow in setOf("schedule_call", "sms_schedule", "campaign")) {
                return scheduleDatePickerReply(session.flow, "اختر التاريخ من الأزرار، أو اكتب الموعد كاملاً بصيغة YYYY-MM-DD HH:mm.")
            }
            val prompt = when (session.flow) {
                "add_customer" -> listOf("رقم الجوال", "اسم العميل", "الوظيفة أو تخطي", "الشركة أو تخطي", "تعليمات/ملاحظات أو تخطي")[fields.size]
                "gemini" -> listOf("رقم الجوال", "الاسم أو تخطي", "الوظيفة أو تخطي", "الشركة أو تخطي")[fields.size]
                "sms" -> listOf("رقم الجوال", "نص الرسالة")[fields.size]
                "sms_schedule" -> listOf("رقم الجوال", "موعد الإرسال YYYY-MM-DD HH:mm", "نص الرسالة")[fields.size]
                "schedule_call" -> listOf("رقم الجوال", "موعد الاتصال YYYY-MM-DD HH:mm", "ملاحظة أو تخطي")[fields.size]
                "campaign" -> listOf("اسم الحملة", "موعد البدء YYYY-MM-DD HH:mm", "مجموعة العملاء أو all", "نوع الحملة: call أو sms أو call_sms", "نص SMS أو تخطي")[fields.size]
                else -> "البيانات المطلوبة"
            }
            return AdminReply("تم استلام ${fields.size}/$expected. $prompt\nأرسل /cancel للإلغاء.", listOf(listOf(AdminButton("إلغاء", "voicx:cancel_flow"))))
        }

        val action = when (session.flow) {
            "add_customer" -> PanelAction.AddCustomer(fields[0], fields[1], fields[2], fields[3], fields[4])
            "gemini" -> PanelAction.GeminiCall(fields[0], fields[1], fields[2], fields[3])
            "sim" -> PanelAction.SimCall(fields[0])
            "sms" -> PanelAction.SendSms(fields[0], fields[1])
            "schedule_call" -> {
                val at = campaignTime(fields[1]) ?: return retryPanelStep(session, "صيغة الموعد غير صحيحة. استخدم YYYY-MM-DD HH:mm (مثال 2026-12-01 09:30).")
                if (at <= System.currentTimeMillis()) return retryPanelStep(session, "يجب أن يكون الموعد في المستقبل. أعد إدخاله بصيغة YYYY-MM-DD HH:mm.")
                PanelAction.ScheduleCall(fields[0], at, fields[2])
            }
            "sms_schedule" -> {
                val at = campaignTime(fields[1]) ?: return retryPanelStep(session, "صيغة الموعد غير صحيحة. استخدم YYYY-MM-DD HH:mm.")
                if (at <= System.currentTimeMillis()) return retryPanelStep(session, "موعد الإرسال يجب أن يكون في المستقبل.")
                PanelAction.ScheduleSms(fields[0], at, fields[2])
            }
            "campaign" -> {
                if (fields[0].isBlank()) return retryPanelStep(session, "اسم الحملة مطلوب.")
                val at = campaignTime(fields[1]) ?: return retryPanelStep(session, "صيغة الموعد غير صحيحة. استخدم YYYY-MM-DD HH:mm.")
                if (at <= System.currentTimeMillis()) return retryPanelStep(session, "موعد بدء الحملة يجب أن يكون في المستقبل.")
                val channel = when (fields[3].lowercase()) { "اتصال", "call" -> "call"; "sms", "رسائل" -> "sms"; "call_sms", "كلاهما", "اتصال ورسائل" -> "call_sms"; else -> return retryPanelStep(session, "نوع الحملة غير معروف. اكتب call أو sms أو call_sms.") }
                PanelAction.CreateCampaign(fields[0], at, fields[2].ifBlank { "all" }, channel, fields.getOrElse(4) { "" })
            }
            "delete_call" -> PanelAction.DeleteCall(fields[0])
            else -> { panelSessions.remove(chatId); return AdminReply("انتهى النموذج؛ ابدأ من القائمة الرئيسية.") }
        }
        val summary = when (action) {
            is PanelAction.AddCustomer -> "إضافة العميل ${action.name} — ${action.phone}"
            is PanelAction.GeminiCall -> "اتصال Gemini إلى ${action.phone} (${action.name.ifBlank { "بلا اسم" }})"
            is PanelAction.SimCall -> "اتصال SIM مع ربط SIP إلى ${action.phone}. قد تُحتسب رسوم المشغّل."
            is PanelAction.SendSms -> "إرسال SMS إلى ${action.phone}:\n${action.text}"
            is PanelAction.ScheduleCall -> "جدولة اتصال إلى ${action.phone} في ${java.time.Instant.ofEpochMilli(action.at).atZone(ZoneId.systemDefault()).toLocalDateTime()}"
            is PanelAction.ScheduleSms -> "جدولة SMS إلى ${action.phone} في ${java.time.Instant.ofEpochMilli(action.at).atZone(ZoneId.systemDefault()).toLocalDateTime()}:\n${action.text}"
            is PanelAction.CreateCampaign -> "إنشاء حملة ${action.name}؛ المجموعة=${action.group}؛ القناة=${action.channel}؛ البدء=${java.time.Instant.ofEpochMilli(action.at).atZone(ZoneId.systemDefault()).toLocalDateTime()}"
            is PanelAction.DeleteCall -> "حذف سجل المكالمة بالمعرّف ${action.id} نهائياً من سجل Telegram المحلي."
            is PanelAction.ToggleGateway -> if (action.enabled) "تشغيل البوابة" else "إيقاف التحويل"
            is PanelAction.ReconcileSchedule -> "مصالحة ${action.type.name} ${action.id}: ${if (action.action == ScheduleReconciliationPolicy.Action.MARK_COMPLETED) "مكتملة" else "فاشلة"}"
        }
        session.flow.let { }
        return askConfirmation(chatId, summary, action)
    }

    private fun scheduleDatePickerReply(flow: String, message: String = ""): AdminReply {
        val today = java.time.LocalDate.now(ZoneId.systemDefault())
        val dateButtons = (0..13).map { offset ->
            val date = today.plusDays(offset.toLong())
            AdminButton(date.format(DateTimeFormatter.ofPattern("EEE dd/MM")), "voicx:pick_date:$date")
        }.chunked(4).map { row -> row }
        val title = when (flow) {
            "schedule_call" -> "اختر تاريخ الاتصال"
            "sms_schedule" -> "اختر تاريخ إرسال الرسالة"
            else -> "اختر تاريخ بدء الحملة"
        }
        return AdminReply(
            listOf(message, "$title (اليوم وحتى 14 يوماً). يمكنك أيضاً كتابة التاريخ YYYY-MM-DD.").filter { it.isNotBlank() }.joinToString("\n"),
            dateButtons + listOf(listOf(AdminButton("إلغاء", "voicx:cancel_flow"))),
        )
    }

    private fun scheduleTimePickerReply(date: String, message: String = ""): AdminReply {
        val times = (8 * 2..20 * 2).map { halfHour ->
            val hour = halfHour / 2
            val minute = if (halfHour % 2 == 0) "00" else "30"
            "%02d:%s".format(hour, minute)
        }
        val timeButtons = times.map { time -> AdminButton(time, "voicx:pick_time:" + time.replace(":", "")) }
            .chunked(4).map { row -> row }
        return AdminReply(
            listOf(message, "التاريخ: $date\nاختر الوقت (بتوقيت الهاتف المحلي).").filter { it.isNotBlank() }.joinToString("\n"),
            timeButtons + listOf(listOf(AdminButton("رجوع للتاريخ", "voicx:pick_back"), AdminButton("إلغاء", "voicx:cancel_flow"))),
        )
    }

    private fun retryPanelStep(session: PanelSession, message: String): AdminReply {
        if (session.values.isNotEmpty()) session.values.removeAt(session.values.lastIndex)
        val next = when (session.flow) {
            "schedule_call" -> "أعد إدخال الموعد YYYY-MM-DD HH:mm."
            "sms_schedule" -> "أعد إدخال موعد الإرسال YYYY-MM-DD HH:mm."
            "campaign" -> "أعد إدخال البيانات الصحيحة للخطوة الحالية."
            else -> "أعد إدخال القيمة المطلوبة."
        }
        return AdminReply("$message\n$next", listOf(listOf(AdminButton("إلغاء", "voicx:cancel_flow"))))
    }

    private fun executePanelAction(action: PanelAction, runtime: GatewayRuntime, chatId: Long, client: TelegramBotClient, home: List<List<AdminButton>>): AdminReply {
        when (action) {
            is PanelAction.AddCustomer -> runtime.saveCustomer(VoicxCustomer(phone = action.phone, name = action.name, role = action.role, company = action.company, instructions = action.instructions)) { asyncPanelReply(chatId, client, "حفظ العميل: $it") }
            is PanelAction.GeminiCall -> runtime.startAiCall(action.phone, action.name, action.role, action.company)
            is PanelAction.SimCall -> runtime.startTelegramSimCall(action.phone) { asyncPanelReply(chatId, client, it) }
            is PanelAction.SendSms -> runtime.sendSms(action.phone, action.text) { asyncPanelReply(chatId, client, "نتيجة SMS: $it") }
            is PanelAction.ScheduleCall -> runtime.saveCallSchedule(CallSchedule(customerId = "", phone = action.phone, runAt = action.at, note = action.note)) { asyncPanelReply(chatId, client, "نتيجة جدولة الاتصال: $it") }
            is PanelAction.ScheduleSms -> runtime.saveSmsSchedule(SmsSchedule(phone = action.phone, runAt = action.at, message = action.text)) { asyncPanelReply(chatId, client, "نتيجة جدولة SMS: $it") }
            is PanelAction.CreateCampaign -> runtime.createCampaign(Campaign(name = action.name, startAt = action.at, targetGroup = action.group, channel = action.channel, smsTemplate = action.template)) { asyncPanelReply(chatId, client, "نتيجة إنشاء الحملة: $it") }
            is PanelAction.DeleteCall -> return AdminReply(if (runtime.deleteTelegramCallRecord(action.id)) "تم حذف السجل المحدد." else "لم يوجد سجل بهذا المعرّف؛ لم يُحذف شيء.", home)
            is PanelAction.ToggleGateway -> runtime.setTakeover(action.enabled)
            is PanelAction.ReconcileSchedule -> runtime.resolveScheduleReconciliation(action.type, action.id, ScheduleReconciliationPolicy.Decision(action.action, "telegram:${runtime.settings.telegramAllowedUserId}", action.evidence)) { asyncPanelReply(chatId, client, it) }
        }
        return AdminReply("تم قبول الإجراء المؤكد. ستظهر النتيجة هنا عند اكتمال الحفظ أو الفحص.", home)
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
