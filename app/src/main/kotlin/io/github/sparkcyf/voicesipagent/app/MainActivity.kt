package io.github.sparkcyf.voicesipagent.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import io.github.sparkcyf.voicesipagent.gateway.BusyPolicy
import io.github.sparkcyf.voicesipagent.gateway.CallCenterEmployee
import io.github.sparkcyf.voicesipagent.gateway.CallFallbackPolicy
import io.github.sparkcyf.voicesipagent.gateway.EmployeeDestination
import io.github.sparkcyf.voicesipagent.gateway.FinalCallAction
import io.github.sparkcyf.voicesipagent.privileged.HealthKeys
import io.github.sparkcyf.voicesipagent.privileged.AudioMethod
import io.github.sparkcyf.voicesipagent.privileged.CallAccess
import io.github.sparkcyf.voicesipagent.voice.CallerIdMode
import io.github.sparkcyf.voicesipagent.privileged.ShizukuConnector

class MainActivity : DialerRoleActivity() {
    private val smsPermissionRequest = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.READ_PHONE_STATE] == true) {
            GatewayRuntime.get(this).refreshCallAccess()
        }
        if (granted[Manifest.permission.SEND_SMS] == true) {
            GatewayRuntime.get(this).showDialerMessage("SMS sending enabled.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = GatewayRuntime.get(this)
        setContent {
            MaterialTheme(colorScheme = appColorScheme(isSystemInDarkTheme())) {
                val message by roleMessage.collectAsState()
                Scaffold { pad -> Screen(runtime, Modifier.padding(pad), ::requestDialer, ::switchDialer,
                    ::chooseDialerInSettings, ::cancelDialerChange, ::setTakeoverFromUi, message) }
            }
        }
        // Let Compose draw the first screen before Android displays permission sheets. Request
        // related permissions through one launcher instead of launching two sheets concurrently.
        window.decorView.postDelayed({
            val missing = arrayOf(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.SEND_SMS,
            ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) smsPermissionRequest.launch(missing.toTypedArray())
        }, 500L)
    }
}

private fun appColorScheme(dark: Boolean) = if (dark) {
    darkColorScheme(primary = Color(0xFF9CCBFF), secondary = Color(0xFFB6C8E8), tertiary = Color(0xFFB6DCCB))
} else {
    lightColorScheme(
        primary = Color(0xFF125A9E),
        secondary = Color(0xFF4D6280),
        tertiary = Color(0xFF276A50),
        background = Color(0xFFF5F7FB),
        surfaceVariant = Color(0xFFE8EEF6),
    )
}

@Composable
private fun Screen(rt: GatewayRuntime, modifier: Modifier, requestDialer: () -> Unit, switchDialer: () -> Unit,
    chooseDialer: () -> Unit, cancelDialer: () -> Unit, setTakeover: (Boolean) -> Unit, roleMessage: String) {
    val h by rt.health.collectAsState()
    val net by rt.networkMetrics.collectAsState()
    val context = LocalContext.current
    val log by AppLog.tail.collectAsState()
    var logActionMessage by remember { mutableStateOf("") }
    val cur by rt.settingsFlow.collectAsState()
    currentUiLang = UiLang.of(cur.uiLanguage)
    CompositionLocalProvider(LocalLayoutDirection provides
        if (currentUiLang == UiLang.AR) LayoutDirection.Rtl else LayoutDirection.Ltr) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Voice SIP Agent", style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text(t("بوابة المكالمات الخلوية وSIP", "Cellular and SIP call gateway"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val lang = UiLang.of(cur.uiLanguage)
                    TextButton(onClick = { rt.setUiLanguage("ru") }, enabled = lang != UiLang.RU) { Text("РУ", color = MaterialTheme.colorScheme.onPrimary) }
                    TextButton(onClick = { rt.setUiLanguage("en") }, enabled = lang != UiLang.EN) { Text("EN", color = MaterialTheme.colorScheme.onPrimary) }
                    TextButton(onClick = { rt.setUiLanguage("ar") }, enabled = lang != UiLang.AR) { Text("عربي", color = MaterialTheme.colorScheme.onPrimary) }
                    InfoButton("language")
                }
            }
        }
        val ready = h.cellularReady && h.callAccessReady && h.sip.ready
        StatusBanner(
            ready = ready,
            title = if (ready) t("البوابة جاهزة للعمل", "Gateway ready") else t("تحتاج البوابة إلى إعداد", "Setup required"),
            detail = if (ready) t("المكالمات الخلوية وSIP جاهزة للتحويل.", "Cellular and SIP calls are ready to bridge.") else h.readinessDetail,
        )
        Section(t("Перехват", "Takeover"), info = "takeover") {
            Toggle(t("Перехватывать звонки", "Take over calls"), cur.takeover, setTakeover) {
                when {
                    h.takeoverOffPending -> t("Выключится после текущего звонка", "Turning off after the current call")
                    h.takeoverOnPending -> t("Включится после завершения звонков", "Turning on after current calls end")
                    h.dialerChangeRunning -> t("Меняется звонилка по умолчанию", "Changing the default dialer")
                    h.dialerChangePending -> if (h.dialerChangeAcquire) t("Ожидание роли звонилки по умолчанию", "Waiting to become the default dialer") else t("Ожидание возврата прежней звонилки", "Waiting to restore your previous dialer")
                    cur.takeover -> if (h.cellularReady) t("Звонки переводятся на софтфон", "Calls are bridged to the PBX") else h.readinessDetail
                    h.holdsDialer && cur.keepDefaultDialer -> t("Шлюз выключен; роль звонилки сохранена", "Gateway off; keeping the default-dialer role")
                    h.holdsDialer -> t("Шлюз выключен; нужно вернуть звонилку", "Gateway off; dialer restoration needs attention")
                    else -> t("Шлюз выключен; звонки у вашей звонилки", "Gateway off; calls stay with your dialer")
                }
            }
            if (h.effectiveCallAccess == CallAccess.DIALER) {
                Text(t("Включение запросит роль звонилки по умолчанию. Выключение вернёт прежнюю, если не включено сохранение роли в Advanced.",
                        "Turning on requests the default-dialer role. Turning off restores your previous dialer unless you keep the role in Advanced settings."),
                    style = MaterialTheme.typography.bodySmall)
            }
            if (h.settingsPending) Text(t("Настройки применятся после завершения звонков", "Settings will apply after current calls end"))
        }
        Section(t("Состояние", "Health")) {
            val p = h.privileged
            Check("Shizuku", h.shizuku == ShizukuConnector.State.CONNECTED, "${h.shizuku} ${h.shizukuDetail}")
            Check(t("Доступ к звонкам", "Call access"), h.callAccessReady,
                if (h.effectiveCallAccess == CallAccess.DIALER) t("Звонилка по умолчанию", "Default dialer") else t("Рядом со звонилкой", "Alongside your dialer"))
            Check(t("Доступ к звуку вызова", "Call audio access"), h.audioReady)
            Check(t("Исключён из экономии батареи", "Doze allowlisted"), p?.get(HealthKeys.DOZE_ALLOWLISTED) == true)
            if (p?.get(HealthKeys.DOZE_ALLOWLISTED) != true) {
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                }) { Text(if (currentUiLang == UiLang.AR) "إعدادات البطارية" else "Open battery settings") }
            }
            Check(t("Регистрация SIP", "SIP registration"), h.sip.ready, h.sip.detail)
            Text(h.readinessDetail, style = MaterialTheme.typography.bodySmall)
            if (h.effectiveCallAccess == CallAccess.DIALER && !h.holdsDialer) {
                Text(t("Режим звонилки по умолчанию мостит звонки. Для ручного набора верни обычную звонилку.",
                    "Default-dialer mode bridges calls through the PBX. For manual calling, switch back to your usual dialer."))
                if (cur.takeover && !h.dialerChangePending) {
                    Button(onClick = { setTakeover(true) }, enabled = h.dialerRoleAvailable && !h.dialerChangeRunning) { Text(t("Включить перехват", "Enable takeover")) }
                }
                if (!h.dialerRoleAvailable) Text(t("Выбор звонилки по умолчанию недоступен на этом устройстве.", "Default dialer selection is unavailable on this device."))
            }
            if (roleMessage.isNotEmpty()) Text(roleMessage)
            if (h.dialerChangeFailed || (h.holdsDialer && !cur.takeover && !cur.keepDefaultDialer)) {
                if (h.holdsDialer) OutlinedButton(onClick = switchDialer, enabled = !h.dialerChangeRunning) { Text(t("Вернуть прежнюю звонилку", "Restore previous dialer")) }
                OutlinedButton(onClick = chooseDialer, enabled = !h.dialerChangeRunning) { Text(t("Выбрать звонилку в настройках", "Choose dialer in Settings")) }
            }
            if (h.dialerChangePending) OutlinedButton(onClick = cancelDialer) { Text(t("Отменить смену звонилки", "Cancel dialer change")) }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (h.shizuku == ShizukuConnector.State.PERMISSION_REQUIRED || h.shizuku == ShizukuConnector.State.PERMISSION_DENIED) {
                    Button(onClick = { rt.shizuku.requestPermission() }) { Text(t("Выдать Shizuku", "Grant Shizuku")) }
                }
                OutlinedButton(onClick = { rt.runSetup() }) { Text(t("Перенастроить", "Re-run setup")) }
                OutlinedButton(onClick = { rt.restartSip() }) { Text(t("Перерегистрация", "Re-register")) }
            }
        }
        Section(if (currentUiLang == UiLang.AR) "الشبكة والإنترنت" else "Network and internet") {
            Check(
                if (currentUiLang == UiLang.AR) "SIM 2" else "SIM 2",
                net.sim2Active,
                "${net.sim2Network} · ${if (net.sim2Dbm != null) "${net.sim2Dbm} dBm" else "signal ${net.sim2SignalLevel}/4"}",
            )
            Text(if (currentUiLang == UiLang.AR) {
                "الإشارة: ${net.sim2SignalLevel.coerceAtLeast(0)}/4${net.sim2Dbm?.let { " · $it dBm" } ?: ""} · الشبكة: ${net.sim2Network}"
            } else {
                "SIM 2 signal: ${net.sim2SignalLevel.coerceAtLeast(0)}/4${net.sim2Dbm?.let { " · $it dBm" } ?: ""} · radio: ${net.sim2Network}"
            })
            Text(if (currentUiLang == UiLang.AR) {
                "الإنترنت: ${if (net.internetConnected) "متصل" else "غير متصل"} · ${net.internetTransport}"
            } else {
                "Internet: ${if (net.internetConnected) "connected" else "unavailable"} · ${net.internetTransport}"
            })
            Text(if (currentUiLang == UiLang.AR) {
                "تقدير الرابط: تنزيل ${net.downstreamKbps / 1000.0} Mbps · رفع ${net.upstreamKbps / 1000.0} Mbps"
            } else {
                "Link estimate: ${net.downstreamKbps / 1000.0} Mbps down · ${net.upstreamKbps / 1000.0} Mbps up"
            })
            Text(if (currentUiLang == UiLang.AR) {
                "القياس محلي من Android فقط؛ لا يتم الاتصال بخادم اختبار سرعة أو خدمة تتبع."
            } else {
                "Measured locally from Android only; no speed-test or tracking server is contacted."
            }, style = MaterialTheme.typography.bodySmall)
            Text(if (currentUiLang == UiLang.AR) "تعمل المراقبة أثناء تشغيل الخدمة الأمامية." else "Monitoring runs while the foreground gateway service is active.",
                style = MaterialTheme.typography.bodySmall)
        }
        Section(t("Звонок", "Call")) {
            Text(h.calls.summary)
            Text(h.session ?: t("Нет активной сессии", "No active session"))
        }
        ClinicRoutingSection(rt)
        ClinicEmployeeStatusSection(rt)
        IvrSettingsSection(rt)
        CallHistorySection(rt)
        SettingsSection(rt)
        Section(t("Журнал", "Log")) {
            val sharedLog = remember(log) { LogShareSanitizer.sanitize(log.takeLast(200).joinToString("\n")) }
            Text(t("تُخفى كلمات المرور وبيانات اعتماد SIP المعروفة تلقائيًا؛ راجع المحتوى قبل مشاركته.",
                "Known passwords and SIP credentials are masked automatically; review before sharing."),
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val copied = runCatching {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("Voice SIP Agent log", sharedLog))
                    }.isSuccess
                    logActionMessage = if (copied) t("تم نسخ السجل المنقّى", "Sanitized log copied")
                        else t("تعذر نسخ السجل", "Could not copy log")
                }) { Text(t("نسخ السجل", "Copy log")) }
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, t("سجل Voice SIP Agent", "Voice SIP Agent log"))
                        putExtra(Intent.EXTRA_TEXT, sharedLog)
                    }
                    runCatching { context.startActivity(Intent.createChooser(send, t("مشاركة السجل", "Share log"))) }
                        .onFailure { logActionMessage = t("تعذر فتح المشاركة", "Could not open sharing") }
                }) { Text(t("مشاركة السجل", "Share log")) }
            }
            if (logActionMessage.isNotBlank()) Text(logActionMessage, style = MaterialTheme.typography.bodySmall)
            Text(log.takeLast(60).reversed().joinToString("\n"), fontFamily = FontFamily.Monospace,
                fontSize = 11.sp, lineHeight = 14.sp)
        }
        var advanced by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { advanced = !advanced }) { Text(if (advanced) t("Скрыть доп. настройки", "Hide advanced settings") else t("Дополнительные настройки", "Advanced settings")) }
        if (advanced) {
            Section(t("Интеграция с системой", "Platform integration")) {
                Choice(t("Доступ к звонкам", "Call access"), listOf(CallAccess.AUTO, CallAccess.COMPANION, CallAccess.DIALER), cur.callAccess,
                    { when (it) { CallAccess.AUTO -> t("Авто", "Automatic"); CallAccess.COMPANION -> t("Рядом", "Alongside dialer"); else -> t("Звонилка", "Default dialer") } },
                    info = "call_access") {
                    rt.applySettings(cur.copy(callAccess = it))
                }
                Toggle(t("Сохранять роль звонилки при выключении", "Keep default-dialer role when gateway is off"), cur.keepDefaultDialer,
                    { rt.setKeepDefaultDialer(it) }, info = "keep_dialer") {
                    t("Применится при следующем выключении перехвата. Прежнюю звонилку можно вернуть кнопкой ниже.",
                        "Applies the next time takeover is switched off. Restore manually below when needed.")
                }
                if (h.holdsDialer) {
                    OutlinedButton(onClick = switchDialer, enabled = !h.dialerChangeRunning) { Text(t("Вернуть прежнюю звонилку", "Restore previous dialer")) }
                } else if (h.dialerRoleAvailable) {
                    OutlinedButton(onClick = requestDialer, enabled = !h.dialerChangeRunning) { Text(t("Стать звонилкой по умолчанию", "Become default dialer")) }
                }
                OutlinedButton(onClick = chooseDialer, enabled = !h.dialerChangeRunning) { Text(t("Выбрать звонилку в настройках", "Choose dialer in Settings")) }
                Choice(t("Способ захвата звука", "Cellular audio"), listOf(AudioMethod.AUTO, AudioMethod.LEGACY, AudioMethod.OFFICIAL), cur.audioMethod,
                    { when (it) { AudioMethod.AUTO -> t("Авто", "Automatic"); AudioMethod.LEGACY -> t("Прямой тракт", "Direct telephony"); else -> t("Переадресация", "Call redirection") } },
                    enabled = { it != AudioMethod.OFFICIAL || Build.VERSION.SDK_INT >= 33 }, info = "audio_method") {
                    rt.applySettings(cur.copy(audioMethod = it))
                }
                Text(t("إذا اتصلت المكالمة ولم يسمع المتصل القائمة، جرّب طريقة صوت أخرى عند عدم وجود مكالمة، ثم راجع سجل IVR وCall audio بعد إنهاء الاختبار.",
                        "If the call connects but the caller cannot hear the menu, try another audio method while idle, then inspect IVR and Call audio logs after the test call."),
                    style = MaterialTheme.typography.bodySmall)
                Toggle(t("Глушить динамик шлюза", "Mute local call output"), cur.muteEarpiece, { rt.setMuteEarpiece(it) }, info = "mute_earpiece") {
                    val backendAvailable = when (cur.audioMethod) {
                        AudioMethod.LEGACY -> h.privileged?.get(HealthKeys.DIRECT_AUDIO) == true
                        AudioMethod.OFFICIAL -> h.privileged?.get(HealthKeys.REDIRECT_AUDIO) == true
                        else -> true
                    }
                    if (!cur.muteEarpiece)
                        t("Оставляет звук вызова в динамике; микрофон глушится. С следующего звонка.",
                            "Keeps normal call output for local listening; microphone stays muted. Applies from next call.")
                    else if (h.privileged?.get(HealthKeys.LOCAL_SUPPRESSION) == true && backendAvailable)
                        t("Глушит звук вызова, если поддерживается; с следующего звонка", "Mutes call output when supported; applies from next call")
                    else t("Недоступно с текущей платформой или методом звука", "Unavailable with the current platform or audio method")
                }
                Toggle(t("Эхо-тест (зацикливание входящих)", "Loopback test (echo inbound calls)"), h.loopbackTest, { rt.setLoopbackTest(it) }, info = "loopback") { t("Возвращает входящие эхом вместо соединения с софтфоном", "Echoes incoming calls instead of connecting the PBX") }
                Toggle(t("Подробный журнал", "Debug log"), cur.sipDebugLog, { rt.setSipDebugLog(it) }, info = "debug_log") { t("Подробные логи, номера маскируются", "Info-level logs with masked numbers") }
            }
            Section(t("Диагностика", "Diagnostics")) {
                Text("Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}")
                Text("requestedCallAccess=${cur.callAccess} effective=${h.effectiveCallAccess} audio=${cur.audioMethod}")
                Text(h.privileged?.entries?.joinToString("\n") { "${it.key}=${it.value}" } ?: t("Ожидание помощника", "Waiting for helper"))
                Text(h.privilegedDetail)
                Text(h.lastSetupLog)
                Text(t("Счётчики звука не доказывают доставку. Проверяй обе стороны на слух.",
                    "Audio access and frame counters do not verify remote delivery. Verify both directions on this device."))
            }
        }
        FaqSection()
        DonateFooter()
    }
    }
}

@Composable
private fun IvrSettingsSection(rt: GatewayRuntime) {
    val cur by rt.settingsFlow.collectAsState()
    val health by rt.health.collectAsState()
    val callActive = health.session != null || health.calls.owned > 0
    val context = LocalContext.current
    var welcome by remember(cur) { mutableStateOf(cur.ivrWelcome) }
    var doctor by remember(cur) { mutableStateOf(cur.ivrDoctor) }
    var reception by remember(cur) { mutableStateOf(cur.ivrReception) }
    var invalid by remember(cur) { mutableStateOf(cur.ivrInvalid) }
    var timeout by remember(cur) { mutableStateOf(cur.ivrTimeout) }
    var source by remember(cur) { mutableStateOf(cur.ivrSource) }
    var rate by remember(cur) { mutableStateOf(cur.ivrSpeechRate) }
    var pitch by remember(cur) { mutableStateOf(cur.ivrPitch) }
    var audioName by remember(cur) { mutableStateOf(cur.ivrAudioFileName) }
    var selectedVoice by remember(cur) { mutableStateOf(cur.ivrTtsVoice) }
    var message by remember { mutableStateOf("") }
    var synthesizing by remember { mutableStateOf(false) }
    var ttsReady by remember { mutableStateOf(false) }
    val tts = remember(context) { TextToSpeech(context.applicationContext) { status ->
        ttsReady = status == TextToSpeech.SUCCESS
    } }
    DisposableEffect(tts) { onDispose { tts.stop(); tts.shutdown() } }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?: uri.lastPathSegment?.substringAfterLast('/') ?: "ivr-audio"
            val ext = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            require(ext == "wav" || ext == "ogg") { "اختر ملف WAV أو OGG فقط." }
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    total += n
                    require(total <= 10 * 1024 * 1024) { "الحد الأقصى لحجم ملف IVR هو 10 ميغابايت." }
                    out.write(chunk, 0, n)
                }
                out.toByteArray()
            } ?: error("تعذر قراءة الملف المحدد.")
            require(bytes.size >= 44) { "الملف الصوتي أقصر من الحد الأدنى أو تالف." }
            val wav = ext == "wav" && isSupportedPcmWav(bytes)
            val ogg = ext == "ogg" && bytes.copyOfRange(0, 4).decodeToString() == "OggS"
            require(wav || ogg) { "امتداد الملف لا يطابق ترويسة WAV/OGG صالحة." }
            sequenceOf("wav", "ogg").filter { it != ext }.forEach { File(context.filesDir, "ivr_custom.$it").delete() }
            val privateFile = File(context.filesDir, "ivr_custom.$ext")
            FileOutputStream(privateFile).use { it.write(bytes) }
            audioName = displayName.take(120)
            source = "file"
            message = "تم نسخ الملف إلى مساحة التطبيق الخاصة."
        }.onFailure { message = it.message ?: "تعذر استيراد الملف." }
    }
    Section("صوت قائمة الرد الآلي IVR") {
        Text("رسائل القائمة الحالية محفوظة محلياً. لا تُسجّل هذه الإعدادات صوت المكالمات.",
            style = MaterialTheme.typography.bodySmall)
        @Composable fun ivrField(label: String, value: String, update: (String) -> Unit) {
            OutlinedTextField(value = value, onValueChange = update, label = { Text(label) },
                modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4)
        }
        ivrField("رسالة الترحيب", welcome) { welcome = it }
        ivrField("نص الخيار 1 — الطبيب", doctor) { doctor = it }
        ivrField("نص الخيار 2 — الاستقبال", reception) { reception = it }
        ivrField("رسالة الاختيار غير الصحيح", invalid) { invalid = it }
        ivrField("رسالة انتهاء المهلة", timeout) { timeout = it }
        Choice("مصدر الصوت", listOf("tts", "file", "default"), source,
            { when (it) { "tts" -> "تحويل النص إلى كلام TTS"; "file" -> "ملف صوتي مخصص"; else -> "الإعداد الافتراضي" } }) {
            source = it
        }
        Text("سرعة الكلام: ${"%.1f".format(Locale.ROOT, rate)}")
        androidx.compose.material3.Slider(value = rate, onValueChange = { rate = it }, valueRange = 0.5f..2f)
        Text("درجة الصوت: ${"%.1f".format(Locale.ROOT, pitch)}")
        androidx.compose.material3.Slider(value = pitch, onValueChange = { pitch = it }, valueRange = 0.5f..2f)
        val availableVoiceNames = if (ttsReady) tts.voices.orEmpty().filter { it.locale.language == "ar" }
            .map { it.name }.sorted() else emptyList()
        if (availableVoiceNames.isNotEmpty()) {
            Choice("الصوت العربي المتاح على الجهاز", availableVoiceNames, selectedVoice.takeIf { it in availableVoiceNames }
                ?: availableVoiceNames.first(), { it }) { selectedVoice = it }
        } else {
            Text("يتم التحقق من أصوات TTS العربية المتاحة على الجهاز…", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = {
            if (!ttsReady) {
                message = "محرك TTS لم يصبح جاهزاً بعد."
            } else if (tts.isLanguageAvailable(Locale("ar")) < TextToSpeech.LANG_AVAILABLE) {
                message = "محرك TTS أو بيانات اللغة العربية غير متوفرة على هذا الجهاز."
            } else {
                tts.setLanguage(Locale("ar"))
                availableVoiceNames.firstOrNull { it == selectedVoice }?.let { name ->
                    tts.voices?.firstOrNull { it.name == name }?.let(tts::setVoice)
                }
                tts.setSpeechRate(rate)
                tts.setPitch(pitch)
                tts.speak(welcome, TextToSpeech.QUEUE_FLUSH, null, "ivr-local-preview")
                message = "بدأت معاينة TTS محلياً؛ هذا لا يثبت وصول الصوت إلى المتصل."
            }
        }) { Text("اختبار الصوت محلياً") }
        if (source == "file") {
            Text(if (audioName.isBlank()) "لم يتم اختيار ملف" else "الملف: $audioName")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickAudio.launch(arrayOf("audio/wav", "audio/ogg", "application/ogg", "application/octet-stream")) }, enabled = !callActive) {
                    Text("اختيار ملف")
                }
                OutlinedButton(onClick = {
                    val file = sequenceOf("ivr_custom.wav", "ivr_custom.ogg").map { File(context.filesDir, it) }.firstOrNull { it.exists() }
                    if (file == null) message = "اختر ملفاً أولاً."
                    else runCatching {
                        MediaPlayer().apply {
                            setDataSource(file.absolutePath)
                            setOnCompletionListener { it.release() }
                            setOnPreparedListener { it.start() }
                            prepareAsync()
                        }
                        message = "بدأ التشغيل التجريبي المحلي؛ لا يؤكد ذلك وصول الصوت للمتصل."
                    }.onFailure { message = "تعذر تشغيل الملف: ${it.message}" }
                }) { Text("تشغيل تجريبي") }
                TextButton(onClick = {
                    sequenceOf("ivr_custom.wav", "ivr_custom.ogg").map { File(context.filesDir, it) }.forEach { it.delete() }
                    audioName = ""; source = "tts"; message = "حُذف الملف المحلي."
                }, enabled = !callActive) { Text("حذف الملف") }
            }
            OutlinedButton(onClick = { source = "tts" }) { Text("العودة إلى TTS") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val newSettings = cur.copy(ivrWelcome = welcome, ivrDoctor = doctor,
                    ivrReception = reception, ivrInvalid = invalid, ivrTimeout = timeout,
                    ivrSource = source, ivrSpeechRate = rate, ivrPitch = pitch,
                    ivrAudioFileName = audioName, ivrTtsVoice = selectedVoice)
                if (source != "tts") {
                    rt.applySettings(newSettings)
                    message = "تم حفظ الإعدادات؛ ستُطبّق من المكالمة التالية."
                } else if (!ttsReady || tts.isLanguageAvailable(Locale("ar")) < TextToSpeech.LANG_AVAILABLE) {
                    message = "تعذر توليد TTS: تحقق من جاهزية المحرك وتوفر بيانات اللغة العربية."
                } else {
                    synthesizing = true
                    tts.setLanguage(Locale("ar"))
                    tts.voices?.firstOrNull { it.name == selectedVoice }?.let(tts::setVoice)
                    tts.setSpeechRate(rate)
                    tts.setPitch(pitch)
                    val prompts = listOf("welcome" to welcome, "doctor" to doctor, "reception" to reception,
                        "invalid" to invalid, "timeout" to timeout)
                    lateinit var generateNext: (Int) -> Unit
                    generateNext = { index ->
                        val (kind, text) = prompts[index]
                        val output = File(context.filesDir, "ivr_tts_new_$kind.wav")
                        val utteranceId = "ivr-save-$kind-${System.currentTimeMillis()}"
                        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) = Unit
                            override fun onDone(utteranceId: String?) {
                                Handler(Looper.getMainLooper()).post {
                                    if (!output.isFile || output.length() <= 44) {
                                        synthesizing = false
                                        message = "لم ينتج TTS ملف WAV صالحاً لرسالة $kind."
                                    } else if (index + 1 < prompts.size) generateNext(index + 1)
                                    else {
                                        val installed = prompts.all { (promptKind, _) ->
                                            val staged = File(context.filesDir, "ivr_tts_new_$promptKind.wav")
                                            val target = File(context.filesDir, "ivr_tts_$promptKind.wav")
                                            staged.isFile && staged.length() > 44 && staged.renameTo(target)
                                        }
                                        synthesizing = false
                                        if (installed) {
                                            rt.applySettings(newSettings)
                                            message = "تم توليد رسائل IVR محلياً؛ ستُطبّق من المكالمة التالية."
                                        } else message = "تعذر تثبيت ملفات TTS؛ أعد المحاولة."
                                    }
                                }
                            }
                            override fun onError(utteranceId: String?) {
                                Handler(Looper.getMainLooper()).post {
                                    synthesizing = false
                                    prompts.forEach { (pendingKind, _) -> File(context.filesDir, "ivr_tts_new_$pendingKind.wav").delete() }
                                    message = "فشل توليد رسالة TTS: $kind."
                                }
                            }
                        })
                        val result = tts.synthesizeToFile(text, Bundle(), output, utteranceId)
                        if (result != TextToSpeech.SUCCESS) {
                            synthesizing = false
                            prompts.forEach { (pendingKind, _) -> File(context.filesDir, "ivr_tts_new_$pendingKind.wav").delete() }
                            message = "تعذر بدء توليد رسالة TTS: $kind."
                        }
                    }
                    generateNext(0)
                    message = "جارٍ توليد رسائل IVR محلياً…"
                }
            }, enabled = !callActive && !synthesizing) { Text("حفظ وتطبيق") }
            OutlinedButton(onClick = {
                welcome = "مرحباً بك. اضغط 1 للتحدث مع الطبيب، أو اضغط 2 للتحدث مع الاستقبال."
                doctor = "يتم تحويلك الآن إلى الطبيب."
                reception = "يتم تحويلك الآن إلى الاستقبال."
                invalid = "الاختيار غير صحيح. حاول مرة أخرى."
                timeout = "لم يتم اختيار أي خيار."
                sequenceOf("ivr_custom.wav", "ivr_custom.ogg").map { File(context.filesDir, it) }.forEach { it.delete() }
                audioName = ""; source = "default"; rate = 1f; pitch = 1f; selectedVoice = ""
                rt.applySettings(cur.copy(
                    ivrWelcome = welcome, ivrDoctor = doctor, ivrReception = reception,
                    ivrInvalid = invalid, ivrTimeout = timeout, ivrSource = "default",
                    ivrSpeechRate = 1f, ivrPitch = 1f, ivrAudioFileName = "", ivrTtsVoice = "",
                ))
                message = "تمت استعادة الإعدادات الافتراضية للصوت المدمج."
            }, enabled = !callActive) { Text("استعادة الإعدادات الافتراضية") }
        }
        if (callActive) Text("لا يمكن تطبيق تغييرات IVR أو استبدال ملفاته أثناء مكالمة نشطة؛ انتظر انتهاء المكالمة.",
            style = MaterialTheme.typography.bodySmall)
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
    }
}

private fun isSupportedPcmWav(data: ByteArray): Boolean {
    if (data.size < 44 || data.asciiAt(0, 4) != "RIFF" || data.asciiAt(8, 4) != "WAVE") return false
    var offset = 12
    var pcm = false
    var channels = 0
    var sampleRate = 0
    var bits = 0
    var audioBytes = 0
    while (offset + 8 <= data.size) {
        val chunk = data.asciiAt(offset, 4)
        val size = data.le32(offset + 4).toLong() and 0xffffffffL
        val content = offset + 8
        if (size > data.size - content) return false
        if (chunk == "fmt " && size >= 16) {
            pcm = data.le16(content) == 1
            channels = data.le16(content + 2)
            sampleRate = data.le32(content + 4)
            bits = data.le16(content + 14)
        } else if (chunk == "data") audioBytes = size.toInt()
        offset = content + size.toInt() + (size.toInt() and 1)
    }
    return pcm && channels in 1..2 && sampleRate in 8000..48000 && bits == 16 && audioBytes >= 2
}

private fun ByteArray.asciiAt(offset: Int, count: Int) = copyOfRange(offset, offset + count).decodeToString()
private fun ByteArray.le16(offset: Int) = (this[offset].toInt() and 255) or ((this[offset + 1].toInt() and 255) shl 8)
private fun ByteArray.le32(offset: Int) = le16(offset) or (le16(offset + 2) shl 16)

/**
 * Optional, voluntary donation link (DonationAlerts). The app stays free (GPL-2.0-or-later);
 * this changes nothing.
 */
private const val DONATE_DA_URL = "https://www.donationalerts.com/r/hinducoder"

@Composable
private fun DonateFooter() {
    val uri = LocalUriHandler.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            t("♡ Поддержать разработку (добровольно)", "♡ Support development (optional)"),
            modifier = Modifier.clickable { runCatching { uri.openUri(DONATE_DA_URL) } },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
        )
    }
}

@Composable
private fun FaqSection() {
    var open by remember { mutableStateOf(false) }
    Section(t("Справка и частые вопросы", "Help & FAQ")) {
        OutlinedButton(onClick = { open = !open }) {
            Text(if (open) t("Скрыть", "Hide") else t("Показать справку / рекомендации клиентов", "Show help / recommended clients"))
        }
        if (open) {
            val lang = currentUiLang
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                faqEntries(lang).forEach { (q, a) ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(q, style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        Text(a, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(rt: GatewayRuntime) {
    // Re-seed the form whenever settings change elsewhere (e.g. adb provisioning).
    val cur by rt.settingsFlow.collectAsState()
    var serverMode by remember(cur) { mutableStateOf(cur.serverMode) }
    var localPort by remember(cur) { mutableStateOf(cur.localSipPort.toString()) }
    var server by remember(cur) { mutableStateOf(cur.server) }
    var port by remember(cur) { mutableStateOf(cur.port.toString()) }
    var user by remember(cur) { mutableStateOf(cur.username) }
    var password by remember(cur) { mutableStateOf(cur.password) }
    var prefixes by remember(cur) { mutableStateOf(cur.allowedPrefixes) }
    var domain by remember(cur) { mutableStateOf(cur.domain) }
    var stack by remember(cur) { mutableStateOf(cur.sipStack) }
    var transport by remember(cur) { mutableStateOf(SipTransport.of(cur.sipTransport) ?: SipTransport.UDP) }
    var srtp by remember(cur) { mutableStateOf(SrtpPolicy.of(cur.srtp) ?: SrtpPolicy.OPTIONAL) }
    var busy by remember(cur) { mutableStateOf(cur.busyPolicy) }
    var fallbackExtensions by remember(cur) { mutableStateOf(cur.fallbackExtensionsCsv) }
    var maxCallAttempts by remember(cur) { mutableStateOf(cur.maxCallAttempts.toString()) }
    var finalCallAction by remember(cur) { mutableStateOf(cur.finalCallAction) }
    var lease by remember(cur) { mutableStateOf(cur.registrarLeaseSec.toString()) }
    var regPassword by remember(cur) { mutableStateOf(cur.registrarPassword) }
    var callerIdMode by remember(cur) { mutableStateOf(cur.callerIdMode) }
    var sipCallerId by remember(cur) { mutableStateOf(cur.sipCallerId) }
    var settingsMessage by remember { mutableStateOf("") }
    val builtin = stack == SipStack.BUILTIN
    Section(t("Настройки", "Settings")) {
        // Core mode: this phone is its own SIP server, or it registers to an external PBX.
        Toggle(t("Режим «устройство в себе» (встроенный SIP-сервер)", "Device-in-itself mode (built-in SIP server)"), serverMode, { serverMode = it }, info = "server_mode") {
            if (serverMode) t("Этот телефон сам — SIP-сервер; софтфон (baresip) регистрируется прямо на него. Внешний сервер не нужен, поля PBX ниже необязательны.",
                "This phone hosts the SIP registrar; your softphone (e.g. baresip) registers straight to it. No external PBX is needed, so the PBX fields below are optional.")
            else t("Выкл: шлюз регистрируется на внешнюю SIP-АТС. Заполни PBX host, user и password ниже.",
                "Off: this gateway registers to an external PBX / SIP server. Fill in PBX host, user and password below.")
        }
        Field(t("Порт регистратора", "Local SIP port (registrar listens here)"), localPort, KeyboardType.Number, info = "local_port") { localPort = it }
        if (serverMode) Text(
            t("Укажи в софтфоне  <IP этого телефона>:\$localPort  по UDP.", "Point your softphone at  <this phone's IP>:\$localPort  over UDP.")
                .replace("\$localPort", localPort),
            style = MaterialTheme.typography.bodySmall,
        )
        if (serverMode) Text(
            if (currentUiLang == UiLang.AR) {
                "للوصول من خارج الشبكة: حوّل UDP ${localPort.ifBlank { "5062" }} إلى عنوان الهاتف، وحوّل UDP 10000–20000 للصوت RTP. لا يستطيع التطبيق إجبار الراوتر أو شركة الاتصالات على فتحها."
            } else {
                "For internet access: forward UDP ${localPort.ifBlank { "5062" }} to this phone and UDP 10000–20000 for RTP. The app cannot force a router or carrier to open ports."
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Field(t("Аренда регистрации (сек)", "Registration lease (sec)"), lease, KeyboardType.Number, info = "lease") { lease = it }
        if (serverMode) {
            OutlinedTextField(
                value = regPassword, onValueChange = { regPassword = it },
                label = { Text(t("Пароль для софтфона (защита регистратора)", "Softphone password (registrar auth)")) },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Text(t("Пароль обязателен для исходящих SIP-звонков через SIM и для доступа из интернета. Введи тот же пароль в baresip. Без пароля шлюз отклоняет исходящие INVITE.",
                "This password is required for SIP-to-SIM outgoing calls and for internet access. Enter the same password in baresip. Without it, the gateway rejects outgoing INVITEs."),
                style = MaterialTheme.typography.bodySmall)
        }
        Choice(t("SIP-стек", "SIP stack"), SipStack.entries, stack, { it.id }, info = "sip_stack") {
            stack = it
            // The built-in stack is UDP only.
            if (it == SipStack.BUILTIN) transport = SipTransport.UDP
        }
        Choice(t("Транспорт", "Transport"), SipTransport.entries, transport, { it.id }, enabled = { !builtin || it == SipTransport.UDP }, info = "transport") {
            // Follow the default port when it was still the previous transport's default.
            if (port == transport.defaultPort.toString()) port = it.defaultPort.toString()
            transport = it
        }
        Choice(t("SRTP (только TLS)", "SRTP (TLS only)"), SrtpPolicy.entries, srtp, { it.id }, enabled = { !builtin && transport == SipTransport.TLS }, info = "srtp") {
            srtp = it
        }
        LabelWithInfo(if (serverMode) t("Внешняя АТС (необязательно в режиме «устройство в себе»)", "External PBX (optional in device-in-itself mode)") else t("Внешняя АТС", "External PBX"),
            "pbx", style = MaterialTheme.typography.bodySmall)
        Field(t("Адрес АТС (PBX host)", "PBX host"), server) { server = it }
        Field(t("Порт АТС", "PBX port"), port, KeyboardType.Number) { port = it }
        Field(t("SIP-домен (пусто = адрес АТС)", "SIP domain (empty = PBX host)"), domain) { domain = it }
        Field(t("Логин / extension", "Extension / user"), user) { user = it }
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text(t("Пароль", "Password")) },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Text(
            t("مكالمات SIM الواردة تستخدم القائمة المحلية: 1 للطبيب و2 للاستقبال.", "Incoming SIM calls use the local IVR: 1 = doctor, 2 = reception."),
            style = MaterialTheme.typography.bodySmall,
        )
        Field(t("Префиксы для исходящих (через запятую, пусто = любой)", "Allowed dial-out prefixes (comma separated, empty = any)"), prefixes, info = "prefixes") { prefixes = it }
        Choice(
            if (currentUiLang == UiLang.AR) "هوية المتصل للمكالمات الصادرة" else t("Идентификатор вызывающего", "Outgoing caller ID"),
            CallerIdMode.entries,
            callerIdMode,
            {
                if (currentUiLang == UiLang.AR) when (it) {
                    CallerIdMode.NORMAL -> "إظهار الرقم العادي"
                    CallerIdMode.PRIVATE -> "رقم خاص"
                    CallerIdMode.UNKNOWN -> "رقم مجهول"
                    CallerIdMode.HIDDEN -> "بدون رقم"
                } else when (it) {
                    CallerIdMode.NORMAL -> t("Показывать номер", "Show normal number")
                    CallerIdMode.PRIVATE -> "Private Number"
                    CallerIdMode.UNKNOWN -> "Unknown"
                    CallerIdMode.HIDDEN -> "No Caller ID"
                }
            },
            info = "caller_id",
        ) { callerIdMode = it }
        if (!serverMode) {
            Field("رقم Caller ID المعتمد من مزود SIP (E.164، مثال +9677XXXXXXX)", sipCallerId, KeyboardType.Phone) {
                sipCallerId = it.filter { ch -> ch.isDigit() || ch == '+' }.take(16)
            }
            Text("اتركه فارغاً لاستخدام هوية حساب SIP. لا تستخدم إلا رقماً معتمداً لديك لدى مزود الخدمة.",
                style = MaterialTheme.typography.bodySmall)
        }
        Text(
            if (currentUiLang == UiLang.AR)
                "تُرسل خيارات الإخفاء كطلب CLIR عبر SIM وPrivacy عبر SIP. ظهور Private أو Unknown أو No Caller ID يعتمد على دعم شركة الاتصالات ومزود SIP."
            else
                "Privacy choices request CLIR on SIM and SIP Privacy signalling. The carrier/PBX decides whether the receiver sees Private, Unknown, or No Caller ID.",
            style = MaterialTheme.typography.bodySmall,
        )
        Choice(t("Второй звонок при занятости", "Second call while busy"), BusyPolicy.entries, busy,
            { when (it) { BusyPolicy.REJECT -> t("Отклонять", "Reject"); BusyPolicy.IGNORE -> t("Отдать звонилке", "Leave to phone") } }, info = "busy") {
            busy = it
        }
        Field(t("ترتيب تحويلات البدائل (تحويلات مفصولة بفواصل)", "Fallback extension order (comma separated)"), fallbackExtensions) {
            fallbackExtensions = it
        }
        Field(t("الحد الأقصى لمحاولات الموظفين (1–4)", "Maximum employee attempts (1–4)"), maxCallAttempts, KeyboardType.Number) {
            maxCallAttempts = it
        }
        Choice(t("الإجراء النهائي بعد فشل البدائل", "Final action after all employee fallbacks fail"), FinalCallAction.entries, finalCallAction,
            { if (it == FinalCallAction.END_CALL) t("إنهاء المكالمة", "End call") else t("إعادة قائمة IVR مرة واحدة ثم الإنهاء", "Replay IVR once, then end") }) {
            finalCallAction = it
        }
        Button(onClick = {
            val attempts = maxCallAttempts.toIntOrNull()?.takeIf { it in 1..4 }
            val fallback = runCatching {
                CallFallbackPolicy(fallbackExtensions.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                    attempts ?: error("invalid attempts"), finalCallAction)
            }.exceptionOrNull()
            if (fallback != null) {
                settingsMessage = t("تحقق من ترتيب التحويلات وحد المحاولات (1–4).", "Check fallback extensions and attempt count (1–4).")
                return@Button
            }
            if (sipCallerId.isNotBlank() && !sipCallerId.matches(Regex("""^\\+[1-9][0-9]{6,14}$"""))) {
                settingsMessage = "رقم Caller ID يجب أن يكون بصيغة دولية صحيحة مثل +9677XXXXXXX، أو اتركه فارغاً."
                return@Button
            }
            settingsMessage = ""
            rt.applySettings(
                cur.copy(
                    serverMode = serverMode,
                    localSipPort = localPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: cur.localSipPort,
                    registrarLeaseSec = lease.toIntOrNull()?.takeIf { it in 30..86400 } ?: cur.registrarLeaseSec,
                    registrarPassword = regPassword,
                    server = server, port = port.toIntOrNull() ?: transport.defaultPort, username = user, password = password,
                    allowedPrefixes = prefixes, callerIdMode = callerIdMode, domain = domain,
                    sipCallerId = sipCallerId.trim(),
                    sipStack = stack, sipTransport = transport.id, srtp = srtp.id, busyPolicy = busy,
                    fallbackExtensionsCsv = fallbackExtensions,
                    maxCallAttempts = attempts ?: cur.maxCallAttempts,
                    finalCallAction = finalCallAction,
                ),
            )
        }) { Text(t("Сохранить и применить", "Save & apply")) }
        if (settingsMessage.isNotBlank()) Text(settingsMessage, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ClinicRoutingSection(rt: GatewayRuntime) {
    val store = rt.clinicEmployeeStore
    var employees by remember { mutableStateOf(store.list()) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var extension by remember { mutableStateOf("1") }
    var ringSeconds by remember { mutableStateOf("20") }
    var kind by remember { mutableStateOf("sip") }
    var destination by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    fun clearForm() {
        editingId = null
        name = ""
        extension = if (employees.none { it.extension == "1" }) "1" else "2"
        ringSeconds = "20"
        kind = "sip"
        destination = ""
        message = ""
    }

    Section(t("تحويلات الموظفين", "Employee routing")) {
        Text(t("المتصل يضغط 1 للطبيب أو 2 للاستقبال. احفظ وجهة SIP لكل تحويلة.",
            "Caller presses 1 for the doctor or 2 for reception. Save a SIP destination for each."),
            style = MaterialTheme.typography.bodySmall)
        Choice(t("التحويلة", "Extension"), listOf("1", "2"), extension,
            { if (it == "1") t("1 — الطبيب", "1 — Doctor") else t("2 — الاستقبال", "2 — Reception") }) {
            extension = it
        }
        Choice(t("نوع الوجهة", "Destination type"), listOf("sip", "mobile"), kind,
            { if (it == "sip") "SIP" else t("رقم جوال عبر SIP", "Mobile via SIP trunk") }) { kind = it }
        Field(t("اسم الموظف", "Employee name"), name) { name = it }
        Field(t("مدة الرنين قبل البديل (5–120 ثانية)", "Ring time before fallback (5–120 seconds)"), ringSeconds, KeyboardType.Number) {
            ringSeconds = it
        }
        Field(
            if (kind == "sip") t("وجهة SIP (مثال: sip:doctor@host)", "SIP destination (e.g. sip:doctor@host)")
            else t("رقم دولي بصيغة +966…", "International number in +966… format"),
            destination,
            if (kind == "mobile") KeyboardType.Phone else KeyboardType.Uri,
        ) { destination = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val wasEditing = editingId != null
                    try {
                        store.upsert(name, kind, destination, priority = extension.toInt(),
                            extension = extension, id = editingId,
                            ringSeconds = ringSeconds.toIntOrNull()?.takeIf { it in 5..120 } ?: error("invalid ring time"))
                        employees = store.list()
                        rt.refreshEmployeeStatuses()
                        message = if (wasEditing) t("تم تحديث الموظف", "Employee updated") else t("تمت إضافة الموظف", "Employee added")
                        editingId = null
                        name = ""
                        destination = ""
                        ringSeconds = "20"
                        extension = if (employees.none { it.extension == "1" }) "1" else "2"
                    } catch (e: Exception) {
                        message = if (e.message?.contains("duplicate extension") == true)
                            t("هذه التحويلة مستخدمة بالفعل؛ اختر التحويلة الأخرى.", "That extension is already in use; choose the other one.")
                        else t("تعذر الحفظ. تحقق من الاسم ووجهة SIP أو رقم الجوال.", "Could not save. Check the name and SIP destination or mobile number.")
                    }
                },
                enabled = name.isNotBlank() && destination.isNotBlank(),
            ) { Text(if (editingId == null) t("إضافة الموظف", "Add employee") else t("حفظ التعديل", "Save changes")) }
            if (editingId != null) OutlinedButton(onClick = ::clearForm) { Text(t("إلغاء", "Cancel")) }
        }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        if (employees.isEmpty()) Text(t("لم تُضف وجهات بعد. أضف الطبيب على 1 والاستقبال على 2.",
            "No destinations yet. Add the doctor on 1 and reception on 2."))
        employees.sortedBy { it.extension }.forEach { employee ->
            EmployeeRouteRow(employee, onEdit = {
                editingId = employee.id
                name = employee.displayName
                extension = employee.extension.ifBlank { "1" }
                kind = when (employee.destination) {
                    is EmployeeDestination.Sip -> "sip"
                    is EmployeeDestination.Mobile -> "mobile"
                }
                destination = when (val d = employee.destination) {
                    is EmployeeDestination.Sip -> d.uri
                    is EmployeeDestination.Mobile -> d.e164
                }
                ringSeconds = employee.ringSeconds.toString()
                message = ""
            }, onDelete = {
                store.delete(employee.id)
                employees = store.list()
                rt.refreshEmployeeStatuses()
                if (editingId == employee.id) clearForm()
            })
        }
    }
}

@Composable
private fun EmployeeRouteRow(employee: CallCenterEmployee, onEdit: () -> Unit, onDelete: () -> Unit) {
    val role = when (employee.extension) {
        "1" -> t("الطبيب (1)", "Doctor (1)")
        "2" -> t("الاستقبال (2)", "Reception (2)")
        else -> t("تحويلة ${employee.extension}", "Extension ${employee.extension}")
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("$role — ${employee.displayName}", style = MaterialTheme.typography.titleSmall)
        Text(t("الرنين ${employee.ringSeconds} ث قبل البديل", "Ring ${employee.ringSeconds}s before fallback"),
            style = MaterialTheme.typography.bodySmall)
        val target = when (val d = employee.destination) {
            is EmployeeDestination.Sip -> d.uri
            is EmployeeDestination.Mobile -> d.e164
        }
        Text(target, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onEdit) { Text(t("تعديل", "Edit")) }
            TextButton(onClick = onDelete) { Text(t("حذف", "Delete")) }
        }
    }
}

@Composable
private fun ClinicEmployeeStatusSection(rt: GatewayRuntime) {
    val snapshot by rt.employeeStatuses.collectAsState()
    Section(t("حالات الموظفين", "Employee status")) {
        OutlinedButton(onClick = { rt.refreshEmployeeStatuses() }) {
            Text(t("تحديث الحالات", "Refresh status"))
        }
        val updated = snapshot.updatedAtMs?.let {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(it))
        } ?: t("لم يُحدّث بعد", "Not checked yet")
        Text(t("آخر تحديث: $updated", "Last updated: $updated"), style = MaterialTheme.typography.bodySmall)
        snapshot.items.forEach { item ->
            val state = when (item.status) {
                EmployeeCallStatus.REGISTERED -> t("مسجّل", "Registered")
                EmployeeCallStatus.BUSY -> t("مشغول", "Busy")
                EmployeeCallStatus.OFFLINE -> t("غير متصل", "Offline")
                EmployeeCallStatus.UNKNOWN -> t("غير قابل للتحقق", "Not verifiable")
            }
            Text("${item.employee.displayName} · ${item.employee.extension}: $state")
        }
        snapshot.error?.let {
            Text(t("معلومة: لا يمكن لهذا الجهاز التحقق من تسجيل وجهات SIP الخارجية؛ هذه الحالة لا تعني أن المكالمة ستفشل. التسجيل يثبت الوصول فقط ولا يضمن الرد.",
                "Info: this device cannot verify registration for external SIP endpoints; this does not mean the call will fail. Registration proves reachability only and does not guarantee an answer."),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CallHistorySection(rt: GatewayRuntime) {
    val entries by rt.callHistory.collectAsState()
    Section(t("سجل المكالمات", "Call history")) {
        if (entries.isEmpty()) Text(t("لا توجد مكالمات مسجلة بعد.", "No calls recorded yet."))
        entries.take(30).forEach { entry ->
            val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(entry.startedAtMs))
            val reason = when (entry.endReason) {
                "CALLER_ENDED" -> t("أنهى المتصل المكالمة", "Caller ended the call")
                "BUSY" -> t("الموظف مشغول", "Employee busy")
                "NO_ANSWER" -> t("لم يرد", "No answer")
                "UNAVAILABLE" -> t("غير متاح مؤقتًا", "Temporarily unavailable")
                "REJECTED" -> t("رفض المكالمة", "Call rejected")
                "NETWORK_ERROR" -> t("خطأ شبكة", "Network error")
                "NOT_FOUND" -> t("الوجهة غير موجودة", "Destination not found")
                "FORBIDDEN" -> t("الوجهة غير مسموح بها", "Destination forbidden")
                "INTERNAL_ERROR" -> t("خطأ داخلي", "Internal error")
                "NORMAL" -> t("انتهت المكالمة", "Call ended")
                else -> t("سبب غير معروف", "Unknown reason")
            }
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("$time · ${entry.destinationLabel}", style = MaterialTheme.typography.titleSmall)
                Text(t("المتصل ${entry.maskedCaller} · ${if (entry.answered) "أُجيب" else "لم يُجب"} · المدة ${entry.durationSeconds} ث",
                    "Caller ${entry.maskedCaller} · ${if (entry.answered) "answered" else "not answered"} · ${entry.durationSeconds}s"),
                    style = MaterialTheme.typography.bodySmall)
                Text(reason, style = MaterialTheme.typography.bodySmall)
                entry.sipResponseCode?.let {
                    Text(t("رمز استجابة SIP: $it", "SIP response code: $it"), style = MaterialTheme.typography.bodySmall)
                }
                if (entry.attemptSummary.isNotBlank()) Text(
                    t("المحاولات/البدائل: ${entry.attemptSummary}", "Attempts/fallbacks: ${entry.attemptSummary}"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(
    label: String,
    options: List<T>,
    selected: T,
    name: (T) -> String,
    enabled: (T) -> Boolean = { true },
    info: String? = null,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LabelWithInfo(label, info, style = MaterialTheme.typography.bodySmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            options.forEachIndexed { i, opt ->
                SegmentedButton(
                    modifier = Modifier.fillMaxHeight(),
                    selected = opt == selected,
                    onClick = { onSelect(opt) },
                    enabled = enabled(opt),
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(name(opt)) }
            }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit, info: String? = null, detail: () -> String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            LabelWithInfo(label, info)
            Text(detail(), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Field(label: String, value: String, type: KeyboardType = KeyboardType.Text, info: String? = null, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = type), modifier = Modifier.weight(1f),
        )
        if (info != null) InfoButton(info)
    }
}

@Composable
private fun Section(title: String, info: String? = null, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LabelWithInfo(title, info, style = MaterialTheme.typography.titleMedium.copy(
                color = MaterialTheme.colorScheme.primary,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            ))
            content()
        }
    }
}

@Composable
private fun StatusBanner(ready: Boolean, title: String, detail: String) {
    val tone = if (ready) Color(0xFF2E7D32) else Color(0xFFB45309)
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = tone.copy(alpha = 0.10f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, tone.copy(alpha = 0.32f)),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (ready) "✓" else "!", color = tone, fontSize = 22.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Check(label: String, ok: Boolean, detail: String = "") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "●" else "○", color = if (ok) Color(0xFF2E7D32) else Color(0xFFC62828))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}
