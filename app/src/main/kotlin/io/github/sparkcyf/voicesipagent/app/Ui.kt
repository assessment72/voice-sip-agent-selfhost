package io.github.sparkcyf.voicesipagent.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** UI language. Pure presentation; stored as "ru", "en", or "ar" in settings. */
enum class UiLang { RU, EN, AR;
    companion object { fun of(s: String?) = when (s) { "en" -> EN; "ar" -> AR; else -> RU } }
}

/**
 * Current UI language. A plain top-level value (not a CompositionLocal) so that [t] can be called
 * from ordinary lambdas too (e.g. a Choice's option-name lambda or a Toggle's detail lambda), not
 * only from @Composable scope. [Screen] keeps it in sync with the saved setting on every
 * recomposition, and a language change re-emits settings → recomposes → updates this before the
 * subtree reads it.
 */
@Volatile
var currentUiLang: UiLang = UiLang.RU

/** Arabic translations for the complete primary screen vocabulary. Technical protocol names stay standard. */
private val arabicUi = mapOf(
    "Takeover" to "إدارة المكالمات", "Take over calls" to "تحويل المكالمات",
    "Turning off after the current call" to "سيتم الإيقاف بعد انتهاء المكالمة الحالية",
    "Turning on after current calls end" to "سيتم التشغيل بعد انتهاء المكالمات الحالية",
    "Changing the default dialer" to "جارٍ تغيير تطبيق الهاتف الافتراضي",
    "Waiting to become the default dialer" to "بانتظار تعيين التطبيق هاتفاً افتراضياً",
    "Waiting to restore your previous dialer" to "بانتظار استعادة تطبيق الهاتف السابق",
    "Calls are bridged to the PBX" to "تم ربط المكالمات بمقسم SIP",
    "Gateway off; keeping the default-dialer role" to "البوابة متوقفة؛ مع الاحتفاظ بدور تطبيق الهاتف الافتراضي",
    "Gateway off; dialer restoration needs attention" to "البوابة متوقفة؛ يلزم استعادة تطبيق الهاتف",
    "Gateway off; calls stay with your dialer" to "البوابة متوقفة؛ المكالمات على تطبيق الهاتف المعتاد",
    "Settings will apply after current calls end" to "سيتم تطبيق الإعدادات بعد انتهاء المكالمات الحالية",
    "Background operation" to "العمل في الخلفية",
    "Battery optimization settings" to "إعدادات تحسين استهلاك البطارية",
    "Health" to "الحالة",
    "Call access" to "صلاحية المكالمات",
    "Default dialer" to "تطبيق الهاتف الافتراضي",
    "Alongside your dialer" to "بجانب تطبيق الهاتف",
    "Call audio access" to "الوصول إلى صوت المكالمة",
    "Doze allowlisted" to "مستثنى من وضع توفير الطاقة",
    "SIP registration" to "تسجيل SIP",
    "Enable takeover" to "تفعيل تحويل المكالمات",
    "Default dialer selection is unavailable on this device." to "اختيار تطبيق الهاتف الافتراضي غير متاح على هذا الجهاز.",
    "Restore previous dialer" to "استعادة تطبيق الهاتف السابق",
    "Choose dialer in Settings" to "اختر تطبيق الهاتف من الإعدادات",
    "Cancel dialer change" to "إلغاء تغيير تطبيق الهاتف",
    "Grant Shizuku" to "منح صلاحية Shizuku",
    "Re-run setup" to "إعادة الإعداد",
    "Re-register" to "إعادة التسجيل",
    "Call" to "مكالمة",
    "No active session" to "لا توجد جلسة نشطة",
    "Application log" to "سجل تشغيل التطبيق",
    "Copy log" to "نسخ السجل",
    "Share application log" to "مشاركة سجل التطبيق",
    "Share log" to "مشاركة السجل",
    "Hide advanced settings" to "إخفاء الإعدادات المتقدمة",
    "Advanced settings" to "الإعدادات المتقدمة",
    "Platform integration" to "تكامل النظام",
    "Automatic" to "تلقائي",
    "Keep default-dialer role when gateway is off" to "الاحتفاظ بدور تطبيق الهاتف الافتراضي عند إيقاف البوابة",
    "Restore manually below when needed." to "يمكن الاستعادة يدوياً من الأسفل عند الحاجة.",
    "Become default dialer" to "تعيين التطبيق كهاتف افتراضي",
    "Cellular audio" to "صوت شبكة الهاتف",
    "Direct telephony" to "المسار الهاتفي المباشر",
    "Call redirection" to "إعادة توجيه المكالمة",
    "Mute local call output" to "كتم صوت المكالمة على هذا الهاتف",
    "Loopback test (echo inbound calls)" to "اختبار الصدى للمكالمات الواردة",
    "Debug log" to "سجل التشخيص",
    "Diagnostics" to "التشخيص",
    "Waiting for helper" to "بانتظار المساعد",
    "♡ Support development (optional)" to "♡ دعم التطوير (اختياري)",
    "Help & FAQ" to "المساعدة والأسئلة الشائعة",
    "Hide" to "إخفاء",
    "Show help / recommended clients" to "عرض المساعدة والتطبيقات المقترحة",
    "Voicx Control API" to "واجهة تحكم Voicx",
    "Bridge HTTPS URL" to "عنوان جسر HTTPS",
    "This phone's device ID" to "معرّف هذا الهاتف",
    "Device token (at least 32 characters)" to "رمز الجهاز (32 حرفاً على الأقل)",
    "Save Voicx settings" to "حفظ إعدادات Voicx",
    "Direct Telegram control" to "التحكم المباشر عبر Telegram",
    "Bot token" to "رمز البوت",
    "Allowed Telegram numeric user ID" to "معرّف مستخدم Telegram المسموح",
    "Save Telegram connection" to "حفظ اتصال Telegram",
    "Settings" to "الإعدادات",
    "Device-in-itself mode (built-in SIP server)" to "وضع الخادم المدمج (خادم SIP داخل الهاتف)",
    "Local SIP port (registrar listens here)" to "منفذ SIP المحلي",
    "Registration lease (sec)" to "مدة صلاحية التسجيل (ثانية)",
    "Softphone password (registrar auth)" to "كلمة مرور تطبيق الاتصال",
    "SIP stack" to "محرك SIP",
    "Transport" to "بروتوكول النقل",
    "SRTP (TLS only)" to "تشفير SRTP (يتطلب TLS)",
    "External PBX (optional in device-in-itself mode)" to "مقسم SIP خارجي (اختياري في وضع الخادم المدمج)",
    "External PBX" to "مقسم SIP خارجي",
    "PBX host" to "عنوان مقسم SIP",
    "PBX port" to "منفذ مقسم SIP",
    "SIP domain (empty = PBX host)" to "نطاق SIP (فارغ = عنوان المقسم)",
    "Extension / user" to "الرقم الداخلي / المستخدم",
    "Password" to "كلمة المرور",
    "Second call while busy" to "مكالمة ثانية أثناء الانشغال",
    "Reject" to "رفض",
    "Leave to phone" to "اتركها لتطبيق الهاتف",
    "Save & apply" to "حفظ وتطبيق",
    "Unavailable with the current platform or audio method" to "غير متاح مع النظام أو طريقة الصوت الحالية",
    "Waiting for helper" to "بانتظار المساعد",
    "Show help / recommended clients" to "عرض المساعدة والتطبيقات المقترحة",
    "Alongside dialer" to "بجانب تطبيق الهاتف",
    "Turning on requests the default-dialer role. Turning off restores your previous dialer unless you keep the role in Advanced settings." to "عند التشغيل سيُطلب تعيين التطبيق كهاتف افتراضي. وعند الإيقاف ستتم استعادة تطبيق الهاتف السابق ما لم تختر الاحتفاظ بالدور من الإعدادات المتقدمة.",
    "The service uses an ongoing notification and is started after reboot. On HONOR, remove battery restrictions for this app." to "تعمل الخدمة بإشعار دائم وتبدأ بعد إعادة تشغيل الهاتف. على أجهزة HONOR أزل قيود البطارية عن التطبيق.",
    "Default-dialer mode bridges calls through the PBX. For manual calling, switch back to your usual dialer." to "وضع الهاتف الافتراضي يربط المكالمات عبر مقسم SIP. لإجراء مكالمة يدوياً، أعد تطبيق الهاتف المعتاد.",
    "Applies the next time takeover is switched off. Restore manually below when needed." to "يُطبّق عند إيقاف تحويل المكالمات في المرة القادمة. ويمكنك الاستعادة يدوياً من الزر أدناه.",
    "★ If a call connects but nobody is heard, switch this very setting (details in ⓘ)." to "★ إذا اتصلت المكالمة دون سماع صوت، غيّر هذا الخيار (التفاصيل في زر المعلومات).",
    "Keeps normal call output for local listening; microphone stays muted. Applies from next call." to "يُبقي صوت المكالمة على سماعة الهاتف للاستماع محلياً، مع كتم الميكروفون. يبدأ من المكالمة التالية.",
    "Mutes call output when supported; applies from next call" to "يكتم صوت المكالمة إذا كان الجهاز يدعم ذلك؛ يبدأ من المكالمة التالية.",
    "Echoes incoming calls instead of connecting the PBX" to "يعيد صوت المكالمة الواردة كصدى بدلاً من ربطها بمقسم SIP.",
    "Info-level logs with masked numbers" to "سجل معلومات مع إخفاء أرقام الهواتف.",
    "Audio access and frame counters do not verify remote delivery. Verify both directions on this device." to "صلاحية الصوت وعدّادات الإطارات لا تثبت وصول الصوت للطرف الآخر. اختبر السماع في الاتجاهين فعلياً.",
    "Optional connection to your private control bridge. HTTPS via VPN or TLS proxy is required. SIP settings are not changed." to "اتصال اختياري بجسر التحكم الخاص بك. يلزم HTTPS عبر VPN أو وسيط TLS. لا تتغير إعدادات SIP.",
    "The token is encrypted with Android Keystore. Leaving the bridge URL empty disables the connection settings and clears the saved credentials." to "يُشفّر الرمز باستخدام Android Keystore. ترك عنوان الجسر فارغاً يعطّل الاتصال ويمسح بيانات الاعتماد المحفوظة.",
    "This phone hosts the SIP registrar; your softphone (e.g. baresip) registers straight to it. No external PBX is needed, so the PBX fields below are optional." to "هذا الهاتف يستضيف مسجل SIP؛ سجّل تطبيق الاتصال (مثل baresip) عليه مباشرة. لا تحتاج إلى مقسم خارجي، لذا حقول المقسم أدناه اختيارية.",
    "Off: this gateway registers to an external PBX / SIP server. Fill in PBX host, user and password below." to "عند الإيقاف: تتصل البوابة بمقسم SIP خارجي. أدخل عنوان المقسم واسم المستخدم وكلمة المرور أدناه.",
    "Point your softphone at  <this phone's IP>:$localPort  over UDP." to "وجّه تطبيق الاتصال إلى <عنوان IP لهذا الهاتف>:$localPort باستخدام UDP.",
    "Empty = no password, anyone may register. Set one before exposing the gateway to the internet, and enter the same password in your softphone." to "تركه فارغاً يعني عدم وجود كلمة مرور وإمكانية تسجيل أي شخص. عيّن كلمة مرور قبل إتاحة البوابة عبر الإنترنت، واستخدمها نفسها في تطبيق الاتصال.",
    "Cellular inbound -> SIP target (blank = username)" to "وجهة مكالمات الشريحة الواردة إلى SIP (فارغ = اسم المستخدم)",
    "Allowed dial-out prefixes (comma separated, empty = any)" to "بادئات الأرقام المسموح الاتصال بها عبر الشريحة (افصل بفواصل، فارغ = جميع الأرقام)"
)

/** Pick the string for the current UI language. Safe to call anywhere. */
fun t(ru: String, en: String): String = when (currentUiLang) {
    UiLang.RU -> ru
    UiLang.EN -> en
    UiLang.AR -> arabicUi[en] ?: en
}

/**
 * A small "ⓘ" affordance next to a label. Tapping it opens a dialog that explains, in the current
 * language, what the setting is, why it matters and (where relevant) where to get what it needs.
 * [key] selects the text from [helpText]; unknown keys render nothing.
 */
@Composable
fun InfoButton(key: String) {
    val lang = currentUiLang
    val entry = helpText(key, lang) ?: return
    var open by remember { mutableStateOf(false) }
    Text(
        "  ⓘ",
        color = MaterialTheme.colorScheme.primary,
        fontSize = 16.sp,
        modifier = Modifier.clickable { open = true }.padding(2.dp),
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { open = false }) { Text(if (lang == UiLang.RU) "Понятно" else "Got it") } },
            title = { Text(entry.first, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.second, fontSize = 14.sp, lineHeight = 20.sp)
                }
            },
        )
    }
}

/** A label line that carries an inline info button. */
@Composable
fun LabelWithInfo(label: String, infoKey: String?, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = style)
        if (infoKey != null) InfoButton(infoKey)
    }
}

/**
 * Help / info text per key, returned as (title, body) already localized.
 * Keep bodies self-contained and practical: what it is, why, and where to get anything it needs.
 */
fun helpText(key: String, lang: UiLang): Pair<String, String>? {
    val ru = lang == UiLang.RU
    fun p(titleRu: String, titleEn: String, bodyRu: String, bodyEn: String) =
        if (ru) titleRu to bodyRu else titleEn to bodyEn
    return when (key) {
        "takeover" -> p(
            "Перехват звонков", "Take over calls",
            "Главный выключатель. Включён — входящие сотовые звонки на SIM этого телефона перехватываются и переводятся на твой софтфон по интернету. Выключен — звонки звонят как обычно на этом телефоне.",
            "Master switch. On: incoming cellular calls on this phone's SIM are intercepted and bridged to your softphone over the internet. Off: calls ring normally on this phone.",
        )
        "server_mode" -> p(
            "Режим «устройство в себе»", "Device-in-itself mode",
            "Включён (рекомендуется): этот телефон сам работает SIP-сервером. Твой софтфон (например baresip) регистрируется прямо на него — внешний сервер/АТС не нужны.\n\nВыключен: шлюз сам подключается к внешней SIP-АТС — тогда заполни поля PBX host / user / password ниже.",
            "On (recommended): this phone is its own SIP server. Your softphone (e.g. baresip) registers straight to it — no external server/PBX needed.\n\nOff: the gateway registers to an external SIP PBX — then fill in PBX host / user / password below.",
        )
        "local_port" -> p(
            "Порт регистратора", "Local SIP port",
            "UDP-порт, на котором этот телефон ждёт регистрацию софтфона. Именно его указываешь в софтфоне как адрес: «<IP телефона>:<порт>», транспорт UDP. По умолчанию 5062.",
            "The UDP port this phone listens on for the softphone's registration. Enter it in the softphone as «<phone IP>:<port>», transport UDP. Default 5062.",
        )
        "lease" -> p(
            "Аренда регистрации", "Registration lease",
            "Сколько секунд держать регистрацию софтфона. Софтфон обновляет её примерно раз в половину этого времени. Больше значение — реже обновления, устойчивее к «засыпанию» софтфона в фоне. Адрес удерживается ещё примерно вдвое дольше, прежде чем шлюз решит, что телефон пропал. По умолчанию 600 с.\n\nЕсли регистрация у тебя «протухает» — увеличь это значение (например, 1200) и убери ограничения батареи у софтфона.",
            "How many seconds to keep the softphone's registration. The softphone refreshes it about every half of this. Larger = fewer refreshes, more tolerant of the softphone sleeping in the background. The contact is held for about twice this before the gateway gives up. Default 600 s.\n\nIf your registration keeps expiring, raise this (e.g. 1200) and lift battery limits on the softphone.",
        )
        "sip_stack" -> p(
            "SIP-стек", "SIP stack",
            "builtin — встроенный лёгкий стек (только UDP и кодек G.711, узкополосный звук), ничего доустанавливать не нужно. pjsua2 — нативный стек (TCP/TLS, кодек Opus), в этой сборке не включён.",
            "builtin — the lightweight built-in stack (UDP only, G.711 codec, narrowband), nothing to install. pjsua2 — native stack (TCP/TLS, Opus), not enabled in this build.",
        )
        "transport" -> p(
            "Транспорт", "Transport",
            "Протокол для SIP. Со встроенным стеком доступен только UDP. TCP и TLS — только с pjsua2.",
            "Transport protocol for SIP. With the built-in stack only UDP is available. TCP and TLS need pjsua2.",
        )
        "srtp" -> p(
            "Шифрование медиа (SRTP)", "Media encryption (SRTP)",
            "Шифрование голоса. Работает только при транспорте TLS (pjsua2). off — выкл, optional — если поддерживается, mandatory — обязательно.",
            "Voice encryption. Works only with TLS transport (pjsua2). off, optional (if supported), mandatory.",
        )
        "pbx" -> p(
            "Внешняя АТС (PBX)", "External PBX",
            "Нужны только если «Режим устройство в себе» выключен. PBX host — адрес сервера; user/password — логин на нём; SIP domain — домен, если отличается от адреса.",
            "Only needed when Device-in-itself mode is off. PBX host — the server address; user/password — your login there; SIP domain — the domain if different from the address.",
        )
        "inbound_target" -> p(
            "Куда переводить входящие", "Inbound → SIP target",
            "SIP-адресат, которому отдаётся входящий сотовый звонок. Пусто — на твой зарегистрированный софтфон (в режиме «устройство в себе») или на имя пользователя (в режиме АТС).",
            "The SIP destination an incoming cellular call is handed to. Empty — your registered softphone (device-in-itself mode) or the username (PBX mode).",
        )
        "prefixes" -> p(
            "Префиксы для исходящих", "Dial-out prefixes",
            "Ограничение на номера, которые можно набирать через SIM (для будущих исходящих звонков). Через запятую. Пусто — любой номер.",
            "Restricts which numbers may be dialed out through the SIM (for future outbound calls). Comma-separated. Empty — any number.",
        )
        "busy" -> p(
            "Второй звонок при занятости", "Second call while busy",
            "Reject — отклонять второй входящий во время разговора. Leave to phone — оставить его обычной звонилке телефона (а SIP-стороне вернуть «занято»).",
            "Reject — refuse a second incoming call during a conversation. Leave to phone — let the phone's native dialer handle it (SIP side gets busy).",
        )
        "call_access" -> p(
            "Доступ к звонкам", "Call access",
            "Как приложение получает доступ к звонкам. Automatic — само. Alongside dialer — рядом со штатной звонилкой (роль «телефон по умолчанию» не забирается, обычно лучший вариант). Default dialer — стать телефоном по умолчанию (нужно на части прошивок и на Android 11, если «рядом» не работает).",
            "How the app gets access to calls. Automatic — chooses for you. Alongside dialer — works next to the native dialer without taking the default-dialer role (usually best). Default dialer — become the default phone app (needed on some firmwares and Android 11 if the alongside mode fails).",
        )
        "keep_dialer" -> p(
            "Сохранять роль звонилки", "Keep default-dialer role",
            "Если шлюз был «телефоном по умолчанию» — при выключении оставить роль за ним, а не возвращать прежнюю звонилку. Применяется при следующем выключении.",
            "If the gateway was the default phone app, keep that role when switching off instead of restoring the previous dialer. Applies at the next switch-off.",
        )
        "audio_method" -> p(
            "Способ захвата звука", "Cellular audio method",
            "САМЫЙ ВАЖНЫЙ параметр при проблемах со звуком — зависит от модели телефона.\n\n• Direct telephony — прямой захват тракта телефонии (рабочий на многих Qualcomm, в т.ч. Nubia).\n• Call redirection — официальная переадресация звука (Android 13+), на части устройств не работает.\n• Automatic — выбор сам, но иногда ошибается.\n\nКак проверить: включи «pjsip debug log», позвони, в журнале найди строку «tap closed … method=… backendFailures=…». Правильный метод: backendFailures ПУСТО и собеседники слышат друг друга. Если тишина или есть «record read 0» — переключи метод и позвони снова.",
            "THE most important setting for audio problems — it depends on the phone model.\n\n• Direct telephony — direct capture of the telephony path (works on many Qualcomm phones, incl. Nubia).\n• Call redirection — the official call-audio redirection (Android 13+); fails on some devices.\n• Automatic — chooses for you, but sometimes wrongly.\n\nHow to check: turn on «pjsip debug log», make a call, find the log line «tap closed … method=… backendFailures=…». The right method shows backendFailures EMPTY and both sides hear each other. If there's silence or «record read 0», switch the method and call again.",
        )
        "mute_earpiece" -> p(
            "Глушить динамик шлюза", "Mute local call output",
            "Глушить звук вызова из динамика самого телефона-шлюза, пока идёт мост (чтобы он не «фонил» рядом). Применяется со следующего звонка.",
            "Mute the call audio from the gateway phone's own speaker while bridging. Applies from the next call.",
        )
        "loopback" -> p(
            "Эхо-тест", "Loopback test",
            "Диагностика: входящие звонки не соединяются с софтфоном, а возвращаются эхом звонящему. Удобно проверить сам звуковой тракт. В обычной работе — выкл.",
            "Diagnostic: incoming calls are echoed back to the caller instead of bridging to the softphone. Handy to test the audio path. Keep off in normal use.",
        )
        "debug_log" -> p(
            "Подробный журнал", "Debug log",
            "Включает подробный журнал (номера маскируются). Нужен для диагностики звука и регистрации. В обычной работе можно выключить.",
            "Enables verbose logging (phone numbers masked). Useful for diagnosing audio and registration. Can be off in normal use.",
        )
        "language" -> p(
            "Язык интерфейса", "Interface language",
            "Язык этого приложения. Меняется сразу, перезапуск не нужен.",
            "The language of this app. Applies immediately, no restart needed.",
        )
        else -> null
    }
}

/** FAQ / help content, as a list of (question, answer) pairs in the current language. */
fun faqEntries(lang: UiLang): List<Pair<String, String>> {
    val ru = lang == UiLang.RU
    fun e(qRu: String, aRu: String, qEn: String, aEn: String) = if (ru) qRu to aRu else qEn to aEn
    return listOf(
        e(
            "Что это вообще такое?",
            "Старый телефон с SIM лежит дома на зарядке и Wi-Fi. Входящие звонки и SMS на его номер он сам отдаёт через интернет на твой второй телефон (софтфон), где бы ты ни был. Ни ПК, ни внешнего сервера не нужно — SIP-сервер встроен в это приложение.",
            "What is this, exactly?",
            "An old phone with a SIM sits at home on a charger and Wi-Fi. Incoming calls and SMS to its number are bridged over the internet to your second phone (a softphone), wherever you are. No PC and no external server — the SIP server is built into this app.",
        ),
        e(
            "Какой софтфон ставить на ДАЛЬНИЙ телефон (Android)?",
            "Рекомендуем baresip — бесплатный, открытый, работает в фоне и умеет page-mode SMS. Где взять: F-Droid (пакет com.tutpro.baresip). После установки: выключи оптимизацию батареи для baresip и разреши фоновую работу/автозапуск, иначе регистрация будет слетать.\n\nНастройка аккаунта: меню ⋮ → «Профили» → поле «SIP URI или Новый аккаунт» → «sip:s23@<IP телефона-шлюза>:<порт регистратора>» → «+». Порт в адресе обязателен. В редакторе аккаунта: «Пароль аутентификации» — тот же пароль, что на шлюзе (пусто только для локальной сети), транспорт UDP, включи тумблер «Регистрация». И обязательно выдай baresip доступ к микрофону.",
            "Which softphone for the FIELD phone (Android)?",
            "We recommend baresip — free, open-source, runs in the background, and does page-mode SMS. Where to get it: F-Droid (package com.tutpro.baresip). After installing: turn off battery optimization for baresip and allow background/autostart, otherwise the registration will keep dropping.\n\nAccount setup: menu ⋮ → «Accounts» → «SIP URI or New account» field → «sip:s23@<gateway phone IP>:<registrar port>» → «+». The port in the address is required. In the account editor: «Auth password» — the same password set on the gateway (empty only for a local network), transport UDP, enable the «Registration» toggle. And do grant baresip microphone access.",
        ),
        e(
            "А на iPhone (iOS)?",
            "Честно: iOS выгружает приложения из фона, поэтому надёжный приём звонков при «своём» сервере без push-уведомлений не гарантирован — приложение должно быть открыто. Push на iOS умеют платные клиенты (Groundwire, Bria), но их push идёт через их облако, а не через наш встроенный сервер. Практичнее в роли дальнего телефона держать Android (baresip); iPhone хорошо подойдёт для исходящих и при открытом приложении.",
            "What about iPhone (iOS)?",
            "Honestly: iOS suspends backgrounded apps, so reliable incoming-call delivery with a self-hosted, push-less server is not guaranteed — the app must be in the foreground. Paid iOS clients (Groundwire, Bria) do support push, but their push goes through their cloud, not our built-in server. It's more practical to use an Android phone as the field device (baresip); an iPhone works well for outbound and while the app is open.",
        ),
        e(
            "Почему протухает регистрация SIP?",
            "Софтфон должен периодически «напоминать о себе» (re-REGISTER). Если телефон с софтфоном засыпает в фоне, напоминания прекращаются. Что сделать: 1) выключить оптимизацию батареи у софтфона и разрешить фон/автозапуск; 2) здесь увеличить «Аренду регистрации» (например до 1200 с); 3) держать телефон-шлюз живым (оптимизацию батареи у ЭТОГО приложения и Shizuku тоже выключить, особенно на ZTE/Nubia/Xiaomi). Этот шлюз держит последний адрес ещё около двух сроков аренды, так что короткие пропуски не страшны.",
            "Why does the SIP registration keep expiring?",
            "The softphone must periodically re-REGISTER. If the phone running the softphone sleeps in the background, those refreshes stop. What to do: 1) disable battery optimization for the softphone and allow background/autostart; 2) raise «Registration lease» here (e.g. to 1200 s); 3) keep the gateway phone alive (also disable battery optimization for THIS app and Shizuku, especially on ZTE/Nubia/Xiaomi). This gateway holds the last contact for about two lease periods, so brief gaps are fine.",
        ),
        e(
            "Что такое Shizuku и где его взять?",
            "Shizuku даёт приложению права уровня отладки без root — через него работает перехват звука. Для MediaTek/Xiaomi/Android 16 нужна пропатченная сборка (обычная падает). Бери её из материалов проекта (файл shizuku-patched-…apk). Shizuku нужно запускать заново после каждой перезагрузки телефона (по USB или через беспроводную отладку).",
            "What is Shizuku and where do I get it?",
            "Shizuku grants the app ADB-level permissions without root — the audio interception works through it. On MediaTek/Xiaomi/Android 16 a patched build is required (the stock one crashes). Use the one from the project materials (shizuku-patched-….apk). Shizuku must be started again after every reboot of the phone (over USB or via wireless debugging).",
        ),
        e(
            "Звонок соединился, но никого не слышно",
            "Почти всегда это «Способ захвата звука» (см. ⓘ у этого пункта в Advanced). Переключи между «Direct telephony» и «Call redirection» и позвони снова. Если не слышат именно тебя — проверь, выдан ли софтфону доступ к микрофону.",
            "The call connects but nobody is heard",
            "Almost always the «Cellular audio method» (see the ⓘ on that item under Advanced). Switch between «Direct telephony» and «Call redirection» and call again. If only you can't be heard — check that the softphone has microphone access.",
        ),
        e(
            "Софтфон виснет на «подключении»",
            "1) Оба телефона в одной сети, в роутере нет «изоляции клиентов». 2) Верные IP:порт и транспорт UDP. 3) Правильный пароль регистратора в софтфоне — тот же, что задан на шлюзе.",
            "Softphone hangs on «connecting»",
            "1) Both phones on the same network, no «client isolation» on the router. 2) Correct IP:port and UDP transport. 3) Correct registrar password in the softphone — the same one set on the gateway.",
        ),
        e(
            "Как работать не в одной Wi-Fi (в поездке)?",
            "Либо белый IP дома и проброс порта регистратора (UDP) на роутере, либо Tailscale на обоих телефонах (бесплатно, без белого IP): в софтфоне укажи Tailscale-адрес шлюза. Регистратор запоминает адрес по факту регистрации, так что роуминг он переживёт.",
            "How to use it outside one Wi-Fi (while travelling)?",
            "Either a public IP at home with the registrar port (UDP) forwarded on the router, or Tailscale on both phones (free, no public IP needed): point the softphone at the gateway's Tailscale address. The registrar learns the address from the registration, so roaming is fine.",
        ),
    )
}
