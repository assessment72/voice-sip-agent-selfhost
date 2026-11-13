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

/** UI language. Stored as "ru", "en", or "ar" in settings. */
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

/** Arabic translations for the main app controls and settings. Unknown strings safely fall back to English. */
private val arabicUi = mapOf(
    "Перехват" to "التحكم بالمكالمات",
    "Перехватывать звонки" to "تحويل المكالمات",
    "Выключится после текущего звонка" to "سيتم الإيقاف بعد انتهاء المكالمة الحالية",
    "Включится после завершения звонков" to "سيتم التفعيل بعد انتهاء المكالمات",
    "Меняется звонилка по умолчанию" to "جارٍ تغيير تطبيق الهاتف الافتراضي",
    "Ожидание роли звонилки по умолчанию" to "بانتظار تعيين التطبيق كهاتف افتراضي",
    "Ожидание возврата прежней звонилки" to "بانتظار استعادة تطبيق الهاتف السابق",
    "Звонки переводятся на софтфон" to "يتم تحويل المكالمات إلى تطبيق SIP",
    "Шлюз выключен; роль звонилки сохранена" to "البوابة متوقفة؛ وتم الاحتفاظ بدور تطبيق الهاتف الافتراضي",
    "Шлюз выключен; нужно вернуть звонилку" to "البوابة متوقفة؛ يلزم استعادة تطبيق الهاتف السابق",
    "Шлюз выключен; звонки у вашей звонилки" to "البوابة متوقفة؛ المكالمات يتولاها تطبيق الهاتف",
    "Включение запросит роль звонилки по умолчанию. Выключение вернёт прежнюю, если не включено сохранение роли в Advanced." to "عند التفعيل سيُطلب تعيين التطبيق كهاتف افتراضي. وعند الإيقاف سيُستعاد التطبيق السابق ما لم تختر الاحتفاظ بالدور في الإعدادات المتقدمة.",
    "Настройки применятся после завершения звонков" to "سيتم تطبيق الإعدادات بعد انتهاء المكالمات",
    "Состояние" to "الحالة",
    "Доступ к звонкам" to "صلاحية الوصول إلى المكالمات",
    "Звонилка по умолчанию" to "تطبيق الهاتف الافتراضي",
    "Рядом со звонилкой" to "بجانب تطبيق الهاتف",
    "Доступ к звуку вызова" to "الوصول إلى صوت المكالمة",
    "Исключён из экономии батареи" to "مستثنى من تحسينات توفير البطارية",
    "Регистрация SIP" to "تسجيل SIP",
    "Режим звонилки по умолчанию мостит звонки. Для ручного набора верни обычную звонилку." to "وضع تطبيق الهاتف الافتراضي يمرر المكالمات عبر البوابة. للاتصال يدوياً، أعد تطبيق الهاتف المعتاد.",
    "Включить перехват" to "تفعيل تحويل المكالمات",
    "Выбор звонилки по умолчанию недоступен на этом устройстве." to "اختيار تطبيق الهاتف الافتراضي غير متاح على هذا الجهاز.",
    "Вернуть прежнюю звонилку" to "استعادة تطبيق الهاتف السابق",
    "Выбрать звонилку в настройках" to "اختيار تطبيق الهاتف من الإعدادات",
    "Отменить смену звонилки" to "إلغاء تغيير تطبيق الهاتف",
    "Выдать Shizuku" to "منح صلاحية Shizuku",
    "Перенастроить" to "إعادة الإعداد",
    "Перерегистрация" to "إعادة تسجيل SIP",
    "Звонок" to "المكالمة",
    "Нет активной сессии" to "لا توجد جلسة نشطة",
    "Журнал" to "السجل",
    "Скрыть доп. настройки" to "إخفاء الإعدادات المتقدمة",
    "Дополнительные настройки" to "الإعدادات المتقدمة",
    "Интеграция с системой" to "التكامل مع النظام",
    "Авто" to "تلقائي",
    "Рядом" to "بجانب تطبيق الهاتف",
    "Звонилка" to "تطبيق الهاتف الافتراضي",
    "Сохранять роль звонилки при выключении" to "الاحتفاظ بدور تطبيق الهاتف الافتراضي عند الإيقاف",
    "Применится при следующем выключении перехвата. Прежнюю звонилку можно вернуть кнопкой ниже." to "سيُطبق ذلك عند إيقاف تحويل المكالمات في المرة القادمة. يمكنك استعادة التطبيق السابق بالزر أدناه.",
    "Стать звонилкой по умолчанию" to "تعيين التطبيق كهاتف افتراضي",
    "Способ захвата звука" to "طريقة التقاط صوت المكالمة",
    "Прямой тракт" to "المسار الصوتي المباشر",
    "Переадресация" to "إعادة توجيه الصوت",
    "★ Если звонок соединился, но никого не слышно — переключай именно этот параметр (подробно в ⓘ)." to "★ إذا اتصلت المكالمة دون سماع الصوت، غيّر هذا الخيار تحديداً. راجع شرح ⓘ.",
    "Глушить динамик шлюза" to "كتم صوت مكبر هاتف البوابة",
    "Оставляет звук вызова в динамике; микрофон глушится. С следующего звонка." to "يُبقي صوت المكالمة على مكبر الهاتف مع كتم الميكروفون. يبدأ من المكالمة التالية.",
    "Глушит звук вызова, если поддерживается; с следующего звонка" to "يكتم صوت المكالمة إذا كان الجهاز يدعم ذلك؛ يبدأ من المكالمة التالية",
    "Недоступно с текущей платформой или методом звука" to "غير متاح مع النظام أو طريقة الصوت الحالية",
    "Эхо-тест (зацикливание входящих)" to "اختبار الصدى للمكالمات الواردة",
    "Возвращает входящие эхом вместо соединения с софтфоном" to "يعيد صوت المكالمة الواردة كصدى بدلاً من توصيلها بتطبيق SIP",
    "Подробный журнал" to "سجل تفصيلي",
    "Подробные логи, номера маскируются" to "سجل تفصيلي مع إخفاء أرقام الهواتف",
    "Диагностика" to "التشخيص",
    "Ожидание помощника" to "بانتظار الخدمة المساعدة",
    "Счётчики звука не доказывают доставку. Проверяй обе стороны на слух." to "عدادات الصوت لا تثبت وصوله للطرف الآخر. اختبر السماع في الاتجاهين.",
    "♡ Поддержать разработку (добровольно)" to "♡ دعم التطوير (اختياري)",
    "Справка и частые вопросы" to "المساعدة والأسئلة الشائعة",
    "Скрыть" to "إخفاء",
    "Показать справку / рекомендации клиентов" to "عرض المساعدة / التطبيقات المقترحة",
    "Настройки" to "الإعدادات",
    "Режим «устройство в себе» (встроенный SIP-сервер)" to "وضع الجهاز المستقل (خادم SIP مدمج)",
    "Этот телефон сам — SIP-сервер; софтфон (baresip) регистрируется прямо на него. Внешний сервер не нужен, поля PBX ниже необязательны." to "يعمل هذا الهاتف كخادم SIP، ويسجل تطبيق الهاتف البرمجي مثل baresip عليه مباشرةً. لا تحتاج إلى خادم خارجي، وحقول PBX أدناه اختيارية.",
    "Выкл: шлюз регистрируется на внешнюю SIP-АТС. Заполни PBX host, user и password ниже." to "عند الإيقاف، يسجل الجهاز لدى خادم SIP خارجي. أدخل عنوان الخادم واسم المستخدم وكلمة المرور أدناه.",
    "Порт регистратора" to "منفذ خادم التسجيل",
    "Укажи в софтфоне  <IP этого телефона>:$localPort  по UDP." to "أدخل في تطبيق SIP عنوان هذا الهاتف والمنفذ $localPort باستخدام UDP.",
    "Аренда регистрации (сек)" to "مدة صلاحية التسجيل (ثانية)",
    "Пароль для софтфона (защита регистратора)" to "كلمة مرور تطبيق SIP (حماية خادم التسجيل)",
    "Пароль обязателен для исходящих SIP-звонков через SIM и для доступа из интернета. Введи тот же пароль в baresip. Без пароля шлюз отклоняет исходящие INVITE." to "كلمة المرور مطلوبة لإجراء مكالمات SIP عبر شريحة SIM وللوصول من الإنترنت. أدخل كلمة المرور نفسها في baresip. بدونها سترفض البوابة طلبات الاتصال الصادرة.",
    "SIP-стек" to "محرك SIP",
    "Транспорт" to "بروتوكول النقل",
    "SRTP (только TLS)" to "تشفير SRTP (يتطلب TLS)",
    "Внешняя АТС (необязательно в режиме «устройство в себе»)" to "خادم SIP خارجي (اختياري في وضع الجهاز المستقل)",
    "Внешняя АТС" to "خادم SIP خارجي",
    "Адрес АТС (PBX host)" to "عنوان خادم SIP",
    "Порт АТС" to "منفذ خادم SIP",
    "SIP-домен (пусто = адрес АТС)" to "نطاق SIP (اتركه فارغاً لاستخدام عنوان الخادم)",
    "Логин / extension" to "اسم المستخدم / الرقم الداخلي",
    "Пароль" to "كلمة المرور",
    "Куда переводить входящие (пусто = логин)" to "وجهة تحويل المكالمات الواردة (فارغ = اسم المستخدم)",
    "Префиксы для исходящих (через запятую, пусто = любой)" to "بادئات الأرقام المسموح بالاتصال بها (افصل بينها بفواصل؛ فارغ = جميع الأرقام)",
    "Второй звонок при занятости" to "التعامل مع مكالمة ثانية أثناء الانشغال",
    "Отклонять" to "رفض",
    "Отдать звонилке" to "تركها لتطبيق الهاتف",
    "Сохранить и применить" to "حفظ وتطبيق"
)

/** Pick the string for the current UI language. Safe to call anywhere. */
fun t(ru: String, en: String): String = when (currentUiLang) {
    UiLang.RU -> ru
    UiLang.EN -> en
    UiLang.AR -> arabicUi[ru] ?: en
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
            confirmButton = { TextButton(onClick = { open = false }) { Text(when (lang) { UiLang.RU -> "Понятно"; UiLang.AR -> "حسناً"; UiLang.EN -> "Got it" }) } },
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
            "Префиксы номеров, на которые разрешены исходящие вызовы через SIM. Разделяй их запятыми. Пустое поле разрешает любые номера.",
            "Restricts which numbers may be dialed out through the SIM. Comma-separated. Empty — any number.",
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
            "Рекомендуем baresip — бесплатный, открытый, работает в фоне и умеет page-mode SMS. Где взять: F-Droid (пакет com.tutpro.baresip). После установки: выключи оптимизацию батареи для baresip и разреши фоновую работу/автозапуск, иначе регистрация будет слетать.\n\nНастройка аккаунта: меню ⋮ → «Профили» → поле «SIP URI или Новый аккаунт» → «sip:s23@<IP телефона-шлюза>:<порт регистратора>» → «+». Порт в адресе обязателен. В редакторе аккаунта: «Пароль аутентификации» — тот же пароль, что на шлюзе (обязателен для исходящих вызовов через SIM), транспорт UDP, включи тумблер «Регистрация». И обязательно выдай baresip доступ к микрофону.",
            "Which softphone for the FIELD phone (Android)?",
            "We recommend baresip — free, open-source, runs in the background, and does page-mode SMS. Where to get it: F-Droid (package com.tutpro.baresip). After installing: turn off battery optimization for baresip and allow background/autostart, otherwise the registration will keep dropping.\n\nAccount setup: menu ⋮ → «Accounts» → «SIP URI or New account» field → «sip:s23@<gateway phone IP>:<registrar port>» → «+». The port in the address is required. In the account editor: «Auth password» — the same password set on the gateway (required for outgoing calls through the SIM), transport UDP, enable the «Registration» toggle. And do grant baresip microphone access.",
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
