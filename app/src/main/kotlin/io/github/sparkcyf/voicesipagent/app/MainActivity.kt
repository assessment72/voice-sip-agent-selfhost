package io.github.sparkcyf.voicesipagent.app

import android.os.Bundle
import android.Manifest
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.sparkcyf.voicesipagent.gateway.BusyPolicy
import io.github.sparkcyf.voicesipagent.privileged.HealthKeys
import io.github.sparkcyf.voicesipagent.privileged.AudioMethod
import io.github.sparkcyf.voicesipagent.privileged.CallAccess
import io.github.sparkcyf.voicesipagent.privileged.ShizukuConnector

class MainActivity : DialerRoleActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = GatewayRuntime.get(this)
        GatewayService.start(this)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val message by roleMessage.collectAsState()
                Scaffold { pad -> Screen(runtime, Modifier.padding(pad), ::requestDialer, ::switchDialer,
                    ::chooseDialerInSettings, ::cancelDialerChange, ::setTakeoverFromUi, message) }
            }
        }
    }
}

@Composable
private fun Screen(rt: GatewayRuntime, modifier: Modifier, requestDialer: () -> Unit, switchDialer: () -> Unit,
    chooseDialer: () -> Unit, cancelDialer: () -> Unit, setTakeover: (Boolean) -> Unit, roleMessage: String) {
    val h by rt.health.collectAsState()
    val log by AppLog.tail.collectAsState()
    val cur by rt.settingsFlow.collectAsState()
    currentUiLang = UiLang.of(cur.uiLanguage)
    CompositionLocalProvider(LocalLayoutDirection provides if (currentUiLang == UiLang.AR) LayoutDirection.Rtl else LayoutDirection.Ltr) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("GSMtoSIP", style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val lang = UiLang.of(cur.uiLanguage)
                TextButton(onClick = { rt.setUiLanguage("ru") }, enabled = lang != UiLang.RU) { Text("РУ") }
                TextButton(onClick = { rt.setUiLanguage("en") }, enabled = lang != UiLang.EN) { Text("EN") }
                TextButton(onClick = { rt.setUiLanguage("ar") }, enabled = lang != UiLang.AR) { Text("ع") }
                InfoButton("language")
            }
        }
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
        Section(t("Работа в фоне", "Background operation")) {
            val context = LocalContext.current
            Text(t("Служба работает с постоянным уведомлением и запускается после перезагрузки. Для HONOR отключи для приложения ограничения батареи.",
                "The service uses an ongoing notification and is started after reboot. On HONOR, remove battery restrictions for this app."),
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }) {
                Text(t("Настройки оптимизации батареи", "Battery optimization settings"))
            }
        }
        Section(t("Состояние", "Health")) {
            val p = h.privileged
            Check("Shizuku", h.shizuku == ShizukuConnector.State.CONNECTED, "${h.shizuku} ${h.shizukuDetail}")
            Check(t("Доступ к звонкам", "Call access"), h.callAccessReady,
                if (h.effectiveCallAccess == CallAccess.DIALER) t("Звонилка по умолчанию", "Default dialer") else t("Рядом со звонилкой", "Alongside your dialer"))
            Check(t("Доступ к звуку вызова", "Call audio access"), h.audioReady)
            Check(t("Исключён из экономии батареи", "Doze allowlisted"), p?.get(HealthKeys.DOZE_ALLOWLISTED) == true)
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
        Section(t("Звонок", "Call")) {
            Text(h.calls.summary)
            Text(h.session ?: t("Нет активной сессии", "No active session"))
        }
        SettingsSection(rt)
        Section(t("سجل تشغيل التطبيق", "Application log")) {
            val context = LocalContext.current
            val logText = log.joinToString("\n")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Voicx application log", logText))
                }, enabled = log.isNotEmpty()) { Text(t("نسخ السجل", "Copy log")) }
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Voicx application log")
                        putExtra(Intent.EXTRA_TEXT, logText)
                    }
                    context.startActivity(Intent.createChooser(send, t("مشاركة سجل التشغيل", "Share application log")))
                }, enabled = log.isNotEmpty()) { Text(t("مشاركة السجل", "Share log")) }
            }
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
                Text(t("★ Если звонок соединился, но никого не слышно — переключай именно этот параметр (подробно в ⓘ).",
                        "★ If a call connects but nobody is heard, switch this very setting (details in ⓘ)."),
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
    var target by remember(cur) { mutableStateOf(cur.inboundTarget) }
    var humanHandoffTarget by remember(cur) { mutableStateOf(cur.humanHandoffTarget) }
    var prefixes by remember(cur) { mutableStateOf(cur.allowedPrefixes) }
    var domain by remember(cur) { mutableStateOf(cur.domain) }
    var stack by remember(cur) { mutableStateOf(cur.sipStack) }
    var transport by remember(cur) { mutableStateOf(SipTransport.of(cur.sipTransport) ?: SipTransport.UDP) }
    var srtp by remember(cur) { mutableStateOf(SrtpPolicy.of(cur.srtp) ?: SrtpPolicy.OPTIONAL) }
    var busy by remember(cur) { mutableStateOf(cur.busyPolicy) }
    var lease by remember(cur) { mutableStateOf(cur.registrarLeaseSec.toString()) }
    var regPassword by remember(cur) { mutableStateOf(cur.registrarPassword) }
    var bridgeUrl by remember(cur) { mutableStateOf(cur.controlBridgeUrl) }
    var bridgeDeviceId by remember(cur) { mutableStateOf(cur.controlBridgeDeviceId) }
    var bridgeToken by remember(cur) { mutableStateOf(cur.controlBridgeToken) }
    var telegramBotToken by remember(cur) { mutableStateOf(cur.telegramBotToken) }
    var telegramAllowedUserId by remember(cur) { mutableStateOf(if (cur.telegramAllowedUserId > 0) cur.telegramAllowedUserId.toString() else "") }
    var geminiApiKey by remember(cur) { mutableStateOf(cur.geminiApiKey) }
    var mongoUri by remember { mutableStateOf("") }
    var mongoDatabase by remember(cur) { mutableStateOf(cur.mongoDatabaseName) }
    var smsApiKey by remember { mutableStateOf("") }
    var mongoResult by remember { mutableStateOf("") }
    var telegramTestRunning by remember { mutableStateOf(false) }
    var telegramTestResult by remember { mutableStateOf("") }
    val telegramScope = rememberCoroutineScope()
    val context = LocalContext.current
    val builtin = stack == SipStack.BUILTIN
    val importMongoFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (text.trimStart().startsWith("{")) {
                val json = org.json.JSONObject(text)
                mongoUri = json.optString("connection_uri", json.optString("MONGODB_URI"))
                mongoDatabase = json.optString("database_name", json.optString("MONGODB_DB_NAME", mongoDatabase))
            } else text.lineSequence().map { it.trim() }.filter { it.contains('=') }.forEach { line ->
                val key = line.substringBefore('=').trim()
                val value = line.substringAfter('=').trim().trim('"', '\'')
                when (key) { "MONGODB_URI" -> mongoUri = value; "MONGODB_DB_NAME" -> mongoDatabase = value }
            }
            mongoResult = "تم استيراد إعدادات MongoDB. اضغط اختبار الاتصال."
        }.onFailure { mongoResult = "فشل استيراد الملف دون كشف محتواه." }
    }

    Section(t("إعدادات قاعدة البيانات", "Database settings")) {
        Text(t("يتم الاتصال مباشرة من التطبيق. URI وكلمة المرور لا تظهر بعد الحفظ وتُخزّن عبر Android Keystore.", "The app connects directly. The URI and password are hidden after saving and encrypted with Android Keystore."), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = mongoUri, onValueChange = { mongoUri = it }, label = { Text("MongoDB URI") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        Field(t("اسم قاعدة البيانات", "Database name"), mongoDatabase) { mongoDatabase = it }
        Text(if (cur.mongoUri.isBlank()) t("لا توجد إعدادات محفوظة", "No saved connection") else t("URI محفوظ ومخفي", "Saved URI is hidden"), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { importMongoFile.launch(arrayOf("application/json", "text/*")) }) { Text(t("استيراد JSON/ENV", "Import JSON/ENV")) }
            OutlinedButton(onClick = { rt.testMongoConnection(mongoUri, mongoDatabase) { mongoResult = it } }, enabled = mongoUri.isNotBlank()) { Text(t("اختبار الاتصال", "Test connection")) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { rt.saveMongoSettings(mongoUri, mongoDatabase, true) { mongoResult = it }; mongoUri = "" }, enabled = mongoUri.isNotBlank()) { Text(t("حفظ وتفعيل", "Save & activate")) }
            OutlinedButton(onClick = { rt.saveMongoSettings(mongoUri, mongoDatabase, false) { mongoResult = it }; mongoUri = "" }) { Text(t("حفظ دون تفعيل", "Save without activating")) }
            OutlinedButton(onClick = { rt.disconnectMongo(); mongoResult = "تم قطع اتصال MongoDB" }) { Text(t("قطع الاتصال", "Disconnect")) }
        }
        Text(rt.mongoStatus(), style = MaterialTheme.typography.bodySmall)
        if (mongoResult.isNotBlank()) Text(mongoResult, style = MaterialTheme.typography.bodySmall)
        Text(if (cur.smsApiKey.isBlank()) t("لا يوجد مفتاح SMS محفوظ", "No SMS key saved") else t("مفتاح SMS محفوظ ومخفي", "Saved SMS key is hidden"), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = smsApiKey, onValueChange = { smsApiKey = it }, label = { Text("SMS API key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { rt.updateSmsApiKey(smsApiKey) }) { Text(t("حفظ مفتاح SMS", "Save SMS key")) }
    }

    Section(t("Voicx Control API", "Voicx Control API")) {
        Text(t("Необязательное подключение к частному центру управления. Требуется HTTPS через VPN или TLS-прокси. Настройки SIP не меняются.",
            "Optional connection to your private control bridge. HTTPS via VPN or TLS proxy is required. SIP settings are not changed."),
            style = MaterialTheme.typography.bodySmall)
        Field(t("عنوان الجسر HTTPS", "Bridge HTTPS URL"), bridgeUrl) { bridgeUrl = it }
        Field(t("معرّف هذا الهاتف", "This phone's device ID"), bridgeDeviceId) { bridgeDeviceId = it }
        OutlinedTextField(
            value = bridgeToken, onValueChange = { bridgeToken = it },
            label = { Text(t("رمز الجهاز (32 حرفاً على الأقل)", "Device token (at least 32 characters)")) },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Text(t("يُخزّن الرمز مشفراً في Android Keystore. ترك عنوان الجسر فارغاً يعطّل إعداد الربط ويمسح بياناته المحفوظة.",
            "The token is encrypted with Android Keystore. Leaving the bridge URL empty disables the connection settings and clears the saved credentials."),
            style = MaterialTheme.typography.bodySmall)
        Button(onClick = { rt.updateControlBridgeSettings(bridgeUrl, bridgeDeviceId, bridgeToken) }) {
            Text(t("حفظ إعدادات Voicx", "Save Voicx settings"))
        }
    }

    Section(t("التحكم المباشر عبر Telegram", "Direct Telegram control")) {
        Text(t(
            "يرتبط هذا الهاتف مباشرة ببوت تيليجرام. أدخل رمز البوت ومعرّف حسابك الرقمي؛ لا يحتاج Termux أو خادماً وسيطاً. لا تشارك رمز البوت مع أحد.",
            "This phone connects directly to your Telegram bot. Enter the bot token and your numeric Telegram user ID. No Termux or relay server is used. Never share the bot token."
        ), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = telegramBotToken,
            onValueChange = { telegramBotToken = it },
            label = { Text(t("رمز البوت", "Bot token")) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Field(t("معرّف حساب Telegram المسموح", "Allowed Telegram numeric user ID"), telegramAllowedUserId, KeyboardType.Number) {
            telegramAllowedUserId = it.filter { ch -> ch.isDigit() }
        }
        Text(t(
            "للعثور على المعرّف: افتح محادثة البوت المساعد الذي يعرض Telegram ID، ثم أدخل الرقم هنا. الأوامر لا تُقبل إلا من هذا الحساب وفي محادثة خاصة.",
            "Get your numeric ID from a trusted Telegram ID helper bot. Commands are accepted only from this account in a private chat."
        ), style = MaterialTheme.typography.bodySmall)
        Button(onClick = { rt.updateTelegramBotSettings(telegramBotToken, telegramAllowedUserId) }) {
            Text(t("حفظ اتصال Telegram", "Save Telegram connection"))
        }
        OutlinedTextField(value = geminiApiKey, onValueChange = { geminiApiKey = it }, label = { Text(t("مفتاح Gemini API", "Gemini API key")) }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { rt.updateGeminiSettings(geminiApiKey) }) { Text(t("حفظ إعداد Gemini", "Save Gemini settings")) }
        Toggle(t("الرد الآلي بواسطة Gemini", "Gemini auto-answer"), cur.aiAutoAnswer, { rt.setAiAutoAnswer(it) }, detail = { t("يجيب Gemini على المكالمات الواردة.", "Gemini answers incoming calls.") })
        OutlinedButton(
            enabled = !telegramTestRunning && telegramBotToken.isNotBlank(),
            onClick = {
                telegramTestRunning = true
                telegramTestResult = ""
                telegramScope.launch {
                    telegramTestResult = try {
                        val bot = withContext(Dispatchers.IO) { TelegramBotClient(telegramBotToken.trim()).testConnection() }
                        t("الاتصال ناجح: $bot", "Connection successful: $bot")
                    } catch (_: Exception) {
                        // Do not show exception text: network errors may contain the tokenized URL.
                        t("فشل الاتصال. تحقق من الرمز والإنترنت.", "Connection failed. Check the token and internet access.")
                    } finally {
                        telegramTestRunning = false
                    }
                }
            },
        ) {
            Text(if (telegramTestRunning) t("جارٍ الاختبار…", "Testing…") else t("اختبار اتصال Telegram", "Test Telegram connection"))
        }
        if (telegramTestResult.isNotEmpty()) Text(telegramTestResult, style = MaterialTheme.typography.bodySmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(t("أوامر البوت", "Bot commands"), style = MaterialTheme.typography.titleSmall)
                val commands = "/start — بدء / Start\n/help — المساعدة / Help\n/status — حالة البوابة / Gateway status\n/sip — حالة SIP / SIP status\n/calls — حالة المكالمات / Call status\n/simcall <رقم> — اتصال SIM وربطه بـ SIP / Call via SIM and bridge to SIP\n/call الرقم | الاسم | الوظيفة | الشركة — اتصال SIM مع Gemini / SIM call with Gemini\n/ai_status — فحص اتصال Gemini Live API / Test Gemini Live API\n/ai_errors — آخر تشخيصات Gemini / Recent Gemini diagnostics\n/auto_answer_on — تشغيل الرد الآلي بـ Gemini / Enable Gemini auto-answer\n/auto_answer_off — إيقاف الرد الآلي / Disable auto-answer\n/ai_stop — إيقاف مكالمة Gemini / Stop Gemini call\n/simcall_cancel — إنهاء اتصال SIM / End SIM call\n/account — سجل المكالمات / Call history\n/simcall_delete <id> — حذف سجل / Delete a record\n/service_on — تشغيل البوابة / Enable gateway\n/service_off — إيقاف التحويل / Disable takeover"
                Text(commands, style = MaterialTheme.typography.bodySmall)
                Text(t("إيقاف التحويل لا يقطع مكالمة جارية.", "Disabling takeover does not interrupt an active call."), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val commandList = commands.lines().map { it.substringBefore(" —") }.joinToString("\n")
                    clipboard.setPrimaryClip(ClipData.newPlainText("Telegram bot commands", commandList))
                }) { Text(t("نسخ الأوامر", "Copy commands")) }
            }
        }
    }

    Section(t("Настройки", "Settings")) {
        // Core mode: this phone is its own SIP server, or it registers to an external PBX.
        Toggle(t("Режим «устройство в себе» (встроенный SIP-сервер)", "Device-in-itself mode (built-in SIP server)"), serverMode, { serverMode = it }, info = "server_mode") {
            if (serverMode) t("Этот телефон сам — SIP-сервер; софтфон (baresip) регистрируется прямо на него. Внешний сервер не нужен, поля PBX ниже необязательны.",
                "This phone hosts the SIP registrar; your softphone (e.g. baresip) registers straight to it. No external PBX is needed, so the PBX fields below are optional.")
            else t("Выкл: шлюз регистрируется на внешнюю SIP-АТС. Заполни PBX host, user и password ниже.",
                "Off: this gateway registers to an external PBX / SIP server. Fill in PBX host, user and password below.")
        }
        Field(t("Порт регистратора", "Local SIP port (registrar listens here)"), localPort, KeyboardType.Number, info = "local_port") { localPort = it }
        if (serverMode) Text(t("Укажи в софтфоне  <IP этого телефона>:$localPort  по UDP.", "Point your softphone at  <this phone's IP>:$localPort  over UDP."),
            style = MaterialTheme.typography.bodySmall)
        Field(t("Аренда регистрации (сек)", "Registration lease (sec)"), lease, KeyboardType.Number, info = "lease") { lease = it }
        if (serverMode) {
            OutlinedTextField(
                value = regPassword, onValueChange = { regPassword = it },
                label = { Text(t("Пароль для софтфона (защита регистратора)", "Softphone password (registrar auth)")) },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Text(t("Пусто = без пароля, регистрироваться может кто угодно. Задай пароль перед выходом в интернет и введи тот же пароль в софтфоне.",
                "Empty = no password, anyone may register. Set one before exposing the gateway to the internet, and enter the same password in your softphone."),
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
        Field(t("Куда переводить входящие (пусто = логин)", "Cellular inbound -> SIP target (blank = username)"), target, info = "inbound_target") { target = it }
        Field(t("Добавочный номер сотрудника (внешняя АТС)", "Employee extension (external PBX)"), humanHandoffTarget) { humanHandoffTarget = it }
        Text(if (serverMode) t("Встроенный SIP-сервер переводит звонок на единственный зарегистрированный софтфон. Для отдельных добавочных номеров сотрудников переключите режим на внешнюю АТС.",
            "Built-in SIP server mode transfers to the single registered softphone. Use external PBX mode to reach distinct employee extensions.")
        else t("Укажите добавочный номер или SIP URI сотрудника. Клиент остаётся с Gemini, пока сотрудник не ответит; если он не ответит, разговор вернётся к ИИ.",
            "Enter the employee extension or SIP URI. The caller stays with Gemini until the employee answers; if unanswered, the AI call is restored."), style = MaterialTheme.typography.bodySmall)
        Field(t("Префиксы для исходящих (через запятую, пусто = любой)", "Allowed dial-out prefixes (comma separated, empty = any)"), prefixes, info = "prefixes") { prefixes = it }
        Choice(t("Второй звонок при занятости", "Second call while busy"), BusyPolicy.entries, busy,
            { when (it) { BusyPolicy.REJECT -> t("Отклонять", "Reject"); BusyPolicy.IGNORE -> t("Отдать звонилке", "Leave to phone") } }, info = "busy") {
            busy = it
        }
        Button(onClick = {
            rt.applySettings(
                cur.copy(
                    serverMode = serverMode,
                    localSipPort = localPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: cur.localSipPort,
                    registrarLeaseSec = lease.toIntOrNull()?.takeIf { it in 30..86400 } ?: cur.registrarLeaseSec,
                    registrarPassword = regPassword,
                    server = server, port = port.toIntOrNull() ?: transport.defaultPort, username = user, password = password,
                    inboundTarget = target, humanHandoffTarget = humanHandoffTarget, allowedPrefixes = prefixes, domain = domain,
                    sipStack = stack, sipTransport = transport.id, srtp = srtp.id, busyPolicy = busy,
                ),
            )
        }) { Text(t("Сохранить и применить", "Save & apply")) }
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
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LabelWithInfo(title, info, style = MaterialTheme.typography.titleMedium)
            content()
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
