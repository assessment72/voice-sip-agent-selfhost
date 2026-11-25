package io.github.sparkcyf.voicesipagent.app

import android.os.SystemClock
import io.github.sparkcyf.voicesipagent.voice.AbstractCallLeg
import io.github.sparkcyf.voicesipagent.voice.CallEndpoint
import io.github.sparkcyf.voicesipagent.voice.CallLeg
import io.github.sparkcyf.voicesipagent.voice.EndpointStatus
import io.github.sparkcyf.voicesipagent.voice.EndpointStatusListener
import io.github.sparkcyf.voicesipagent.voice.FrameClockListener
import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.LegDirection
import io.github.sparkcyf.voicesipagent.voice.LegState
import io.github.sparkcyf.voicesipagent.voice.MediaPort
import io.github.sparkcyf.voicesipagent.voice.Party
import io.github.sparkcyf.voicesipagent.voice.PcmFormat
import io.github.sparkcyf.voicesipagent.gateway.MediaBridge
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

internal data class AiCallReport(
    val number: String?, val name: String?, val role: String?, val company: String?,
    val startedAt: Long, val endedAt: Long, val cause: HangupCause?,
    val inputFrames: Long, val outputFrames: Long, val inputText: String, val outputText: String,
    val failureDetail: String = "",
    val inputAttempts: Long = inputFrames,
    val inputSendFailures: Long = 0,
    val outputAudioChunks: Long = 0,
    val outputAudioBytes: Long = 0,
    val outputDroppedFrames: Long = 0,
    val outputQueuePeak: Int = 0,
    val lastInputSentAt: Long? = null,
    val firstOutputAt: Long? = null,
    val firstOutputQueuedAt: Long? = null,
    val firstOutputPlayedAt: Long? = null,
    val turnCompleteAt: Long? = null,
    val turnResponseCount: Int = 0,
    val lastTurnResponseMs: Long? = null,
    val averageTurnResponseMs: Long? = null,
    val lastTurnSpeechDurationMs: Long? = null,
    val averageTurnSpeechDurationMs: Long? = null,
) {
    fun telegramText() = buildString {
        appendLine("تقرير مكالمة الذكاء الاصطناعي")
        appendLine("الرقم: ${number ?: "غير معروف"}")
        if (!name.isNullOrBlank()) appendLine("الاسم: $name")
        if (!role.isNullOrBlank()) appendLine("الوظيفة: $role")
        if (!company.isNullOrBlank()) appendLine("الشركة: $company")
        appendLine("المدة: ${((endedAt - startedAt).coerceAtLeast(0) / 1000)} ثانية")
        appendLine("النتيجة: ${cause ?: HangupCause.NORMAL}")
        if (failureDetail.isNotBlank()) appendLine("تفصيل الاتصال: ${failureDetail.take(240)}")
        appendLine("التقاط وإرسال الصوت: ${inputFrames > 0} (${inputFrames} إطار قُبل من ${inputAttempts}؛ فشل الإرسال: ${inputSendFailures})")
        appendLine("استقبال صوت Gemini: ${outputFrames > 0} (${outputAudioChunks} دفعة، ${outputAudioBytes} بايت؛ ${outputFrames} إطار مشغّل)")
        appendLine("إطارات صوت أُسقطت: ${outputDroppedFrames}؛ أقصى امتلاء للطابور: ${outputQueuePeak}/1000")
        // The opening greeting may arrive before any caller audio, while lastInputSentAt
        // is updated throughout the call. Do not report a negative cross-call duration.
        val responseMs = if (lastInputSentAt != null && firstOutputAt != null) (firstOutputAt - lastInputSentAt).takeIf { it >= 0 } else null
        val queuedMs = if (lastInputSentAt != null && firstOutputQueuedAt != null) (firstOutputQueuedAt - lastInputSentAt).takeIf { it >= 0 } else null
        val playbackMs = if (lastInputSentAt != null && firstOutputPlayedAt != null) (firstOutputPlayedAt - lastInputSentAt).takeIf { it >= 0 } else null
        appendLine("زمن وصول أول صوت Gemini بعد آخر إطار دخل: ${responseMs?.let { "$it مللي ثانية" } ?: "غير متاح"}")
        appendLine("زمن وضع أول صوت في الطابور: ${queuedMs?.let { "$it مللي ثانية" } ?: "غير متاح"}")
        appendLine("زمن أول تشغيل بعد آخر إطار دخل: ${playbackMs?.let { "$it مللي ثانية" } ?: "غير متاح"}")
        if (turnResponseCount > 0) appendLine("زمن استجابة Gemini بعد نهاية كلام العميل: ${lastTurnResponseMs} مللي ثانية؛ المتوسط: ${averageTurnResponseMs} مللي ثانية؛ عدد الجولات: $turnResponseCount")
        if (lastTurnSpeechDurationMs != null) appendLine("مدة كلام العميل في آخر جولة: ${lastTurnSpeechDurationMs} مللي ثانية؛ المتوسط: ${averageTurnSpeechDurationMs} مللي ثانية")
        if (turnCompleteAt != null) appendLine("إشارة اكتمال دور Gemini: مسجلة (لا تمثل بالضرورة نهاية كلام الطرف الآخر)")
        if (inputText.isNotBlank()) appendLine("آخر كلام الطرف الآخر: ${inputText.take(500)}")
        if (outputText.isNotBlank()) appendLine("آخر رد Gemini: ${outputText.take(500)}")
    }.take(3900)
}

internal class GeminiAiEndpoint(
    private val apiKey: () -> String,
    private val model: () -> String,
    private val salesContext: () -> String = { "لا توجد إعدادات تجارية محمّلة." },
    private val report: (AiCallReport) -> Unit,
) : CallEndpoint {
    override val name = "gemini"
    override val status: EndpointStatus get() = EndpointStatus(apiKey().isNotBlank(), if (apiKey().isBlank()) "Gemini API key not configured" else "Gemini ready")
    private val statuses = CopyOnWriteArrayList<EndpointStatusListener>()
    private val statusProbeRunning = AtomicBoolean(false)
    @Volatile private var handler: ((CallLeg) -> Unit)? = null
    @Volatile private var active: AiLeg? = null
    @Volatile private var context = CallContext()
    @Volatile private var directBridge: MediaBridge? = null
    @Volatile private var directCellLeg: CallLeg? = null
    @Volatile private var directAiLeg: AiLeg? = null
    @Volatile private var directHumanLeg: CallLeg? = null
    @Volatile private var directHandoffCommitted = false
    @Volatile var onHandoffRequested: (() -> Boolean)? = null
    @Volatile var onHangupRequested: (() -> Unit)? = null

    data class CallContext(val name: String = "", val role: String = "", val company: String = "", val salesInfo: String = "")

    fun setContext(value: CallContext) { context = value }

    override fun setIncomingCallHandler(handler: io.github.sparkcyf.voicesipagent.voice.IncomingCallHandler?) { this.handler = handler?.let { h -> { leg -> h.onIncomingCall(leg) } } }
    override fun addStatusListener(listener: EndpointStatusListener) { statuses += listener }
    override fun removeStatusListener(listener: EndpointStatusListener) { statuses -= listener }

    override fun dial(target: String, caller: Party): CallLeg {
        active?.hangup(HangupCause.BUSY)
        val leg = AiLeg(
            "ai-${System.nanoTime()}", caller, target, context.copy(salesInfo = salesContext()),
            apiKey, model, report, { active = null },
            { onHandoffRequested?.invoke() == true },
            { onHangupRequested?.invoke() },
        )
        active = leg
        leg.start()
        return leg
    }

    fun stop() {
        active?.hangup(HangupCause.CANCELLED)
        directCellLeg?.takeIf { it.state != LegState.ENDED }?.hangup(HangupCause.CANCELLED)
        directHumanLeg?.takeIf { it.state != LegState.ENDED }?.hangup(HangupCause.CANCELLED)
    }

    /** Opens a short Live session and reports only after Gemini confirms setupComplete. */
    fun checkConnection(callback: (String) -> Unit) {
        val key = apiKey()
        if (key.isBlank()) {
            callback("Gemini Live API غير متصل: لم يتم حفظ مفتاح API.")
            return
        }
        if (!statusProbeRunning.compareAndSet(false, true)) {
            callback("فحص اتصال Gemini Live جارٍ بالفعل.")
            return
        }
        val selectedModel = model()
        val completed = AtomicBoolean(false)
        lateinit var probe: GeminiLiveClient
        fun finish(message: String) {
            if (completed.compareAndSet(false, true)) {
                statusProbeRunning.set(false)
                probe.close("status check complete")
                callback(message)
            }
        }
        probe = GeminiLiveClient(
            apiKey = key,
            model = selectedModel,
            instructions = "Connectivity check only. Do not speak unless audio is received.",
            onAudio24k = {},
            onInputTranscript = {},
            onOutputTranscript = {},
            onReady = { finish("اتصال Gemini Live API ناجح. النموذج: $selectedModel") },
            onClosed = { detail -> finish("Gemini Live API غير متصل: $detail") },
        )
        runCatching { probe.connect() }.onFailure {
            finish("فشل فحص Gemini Live API (${it.javaClass.simpleName}).")
        }
    }

    /** Places a SIM call and directly cross-connects it to a new Gemini leg. */
    fun startOutbound(cellular: CallEndpoint, number: String, caller: Party) {
        val cell = cellular.dial(number, caller)
        val ai = dial(number, caller) as AiLeg
        directBridge?.stop()
        directBridge = null
        directCellLeg = cell
        directAiLeg = ai
        directHumanLeg = null
        directHandoffCommitted = false
        val watcher = object : CallLeg.Listener {
            override fun onStateChanged(leg: CallLeg, state: LegState) {
                if (state == LegState.ENDED) {
                    when (leg) {
                        cell -> {
                            ai.hangup(leg.endCause ?: HangupCause.NORMAL)
                            directHumanLeg?.takeIf { it.state != LegState.ENDED }?.hangup(HangupCause.CANCELLED)
                            clearDirectCall(cell)
                        }
                        ai -> {
                            if (directHumanLeg?.state != LegState.CONNECTED) {
                                directHumanLeg?.takeIf { it.state != LegState.ENDED }?.hangup(HangupCause.CANCELLED)
                                cell.hangup(leg.endCause ?: HangupCause.NORMAL)
                            }
                        }
                    }
                }
                connectIfReady(cell, ai)
            }
            override fun onMediaChanged(leg: CallLeg, media: MediaPort?) = connectIfReady(cell, ai)
        }
        cell.addListener(watcher)
        ai.addListener(watcher)
    }

    /** Starts an attended employee handoff for a SIM call originated by Telegram /call. */
    @Synchronized
    fun handoffDirectTo(endpoint: CallEndpoint, target: String): Boolean {
        val cell = directCellLeg ?: return false
        val ai = directAiLeg ?: return false
        if (target.isBlank() || directHumanLeg != null || directHandoffCommitted ||
            cell.state != LegState.CONNECTED || ai.state != LegState.CONNECTED) return false
        val human = endpoint.dial(target.trim(), cell.remote)
        if (human.state == LegState.ENDED) return false
        directHumanLeg = human
        human.addListener(object : CallLeg.Listener {
            override fun onStateChanged(leg: CallLeg, state: LegState) {
                if (state == LegState.ENDED) {
                    if (directHandoffCommitted) {
                        cell.hangup(leg.endCause ?: HangupCause.NORMAL)
                        clearDirectCall(cell)
                    } else if (directHumanLeg === human) {
                        directHumanLeg = null
                        ai.notifyHandoffFailed()
                    }
                } else {
                    connectDirectHandoffIfReady(cell, ai, human)
                }
            }
            override fun onMediaChanged(leg: CallLeg, media: MediaPort?) =
                connectDirectHandoffIfReady(cell, ai, human)
        })
        connectDirectHandoffIfReady(cell, ai, human)
        return true
    }

    private fun connectIfReady(cell: CallLeg, ai: CallLeg) {
        if (directHandoffCommitted) return
        if (cell.state == LegState.ENDED || ai.state == LegState.ENDED) {
            directBridge?.stop(); directBridge = null
            return
        }
        if (directBridge == null && cell.state == LegState.CONNECTED && ai.state == LegState.CONNECTED &&
            cell.media != null && ai.media != null) {
            directBridge = MediaBridge(cell.media!!, ai.media!!).also { it.start() }
        }
    }

    private fun connectDirectHandoffIfReady(cell: CallLeg, ai: CallLeg, human: CallLeg) {
        if (directCellLeg !== cell || directAiLeg !== ai || directHumanLeg !== human || directHandoffCommitted) return
        if (cell.state == LegState.CONNECTED && human.state == LegState.CONNECTED &&
            cell.media != null && human.media != null) {
            directHandoffCommitted = true
            directBridge?.stop()
            directBridge = MediaBridge(cell.media!!, human.media!!).also { it.start() }
            ai.hangup(HangupCause.CANCELLED)
            AppLog.i("Gemini direct call handed off to SIP employee")
        }
    }

    private fun clearDirectCall(cell: CallLeg) {
        if (directCellLeg !== cell) return
        directBridge?.stop()
        directBridge = null
        directCellLeg = null
        directAiLeg = null
        directHumanLeg = null
        directHandoffCommitted = false
    }

    private class AiLeg(
        id: String, remote: Party, target: String?, private val context: CallContext, private val key: () -> String, private val model: () -> String,
        private val report: (AiCallReport) -> Unit,
        private val onEnd: () -> Unit,
        private val requestHandoff: () -> Boolean,
        private val requestHangup: () -> Unit,
    ) : AbstractCallLeg(id, "gemini", LegDirection.OUTBOUND, remote, target) {
        private val port = AiPort()
        private val started = System.currentTimeMillis()
        private var client: GeminiLiveClient? = null
        @Volatile private var failureDetail = ""
        private var inputText = ""
        private var outputText = ""
        private var turnCompleteAt: Long? = null
        private val openingTurnStarted = AtomicBoolean(false)
        private val callerIntentHandled = AtomicBoolean(false)
        private var intentTranscriptBuffer = ""
        private val hangupScheduler = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "GeminiSpokenHangup").apply { isDaemon = true }
        }
		private val prompt = """أنت سكرتير أعمال ومساعد مبيعات متخصص في تسويق توريد الديزل والوقود للشركات والمشاريع ومحطات الوقود والمصانع والمقاولين.

        أسلوبك: تحدث بالعربية باللهجة اليمنية الطبيعية والمفهومة، بنبرة مهنية وودودة، وردود قصيرة ومباشرة. لا تكن آلياً ولا تكرر العبارات، ولا تضغط على العميل. افهم احتياجه قبل عرض أي خطوة.

        إدارة الحوار الصوتي الطبيعي:
        - تعامل مع المكالمة كحوار بشري حي، وليس كنموذج أسئلة وأجوبة أو رسالة تسويقية مسجلة.
        - استمع إلى المعنى والسياق ونبرة الكلام، وليس إلى الكلمات المنفردة فقط. لاحظ مؤشرات الاهتمام أو التردد أو الاستعجال أو عدم الاهتمام، لكن لا تدّعِ أنك حللت مشاعر الشخص ولا تجزم بما يشعر به.
        - لا تبدأ الرد على كلمة قصيرة أو تردد أو ضوضاء. إذا بدا الكلام غير مكتمل، انتظر لحظة قصيرة أو اطلب التوضيح بلطف.
        - إذا كان التفريغ أو الصوت غير واضح أو احتوى كلمات متناقضة، لا تخمّن ولا تبنِ جواباً على كلمة مشكوك فيها. قل باختصار: «عذراً، لم أسمع الجزء الأخير بوضوح، ممكن تعيده؟».
        - أظهر أنك فهمت كلام الشخص أحياناً بعبارة قصيرة وطبيعية مثل «فهمت عليك» أو «يعني احتياجكم هو...»، لكن لا تكرر نفس العبارة في كل رد.
        - أجب عن سؤال الشخص أولاً قبل الانتقال إلى هدف التأهيل. إذا صحح معلومة أو غيّر الموضوع، اتبع التصحيح والموضوع الجديد ثم ارجع للهدف فقط عندما يكون مناسباً.
        - اسأل سؤالاً واحداً فقط في كل دور. لا تجمع الاسم والمدينة والكمية وموعد التوريد في سؤال واحد.
        - بعد كل إجابة مهمة، اترك مساحة للطرف الآخر. لا تبدأ سؤالاً جديداً قبل أن ينتهي من فكرته.
        - اجعل معظم الردود جملة أو جملتين قصيرتين. لا تستخدم قوائم طويلة أو شرحاً تسويقياً متواصلاً.
        - إذا بدأ الشخص بالكلام أثناء ردك، توقف عن الكلام فوراً واستمع. لا تكمل الجملة القديمة ولا تدافع عن ردك السابق، وابنِ ردك التالي على الكلام الجديد.
        - إذا كان الشخص مستعجلاً، اختصر واسأله إن كان يفضل تسجيل طلب مختصر. إذا كان متردداً، وضّح الخطوة التالية دون ضغط. إذا كان منزعجاً، اعتذر باختصار وخفف الأسئلة.
        - لا تعتبر «نعم» أو «طيب» موافقة على كل شيء. تأكد من المقصود إذا كانت الإجابة مختصرة أو تحتمل أكثر من معنى.
        - احتفظ بملخص داخلي محدث للمعلومات المؤكدة، ولا تكرر سؤالاً أجاب عنه الشخص. إذا صحح معلومة، استبدل القديمة فوراً.
        - استخدم العربية الطبيعية المفهومة بلهجة يمنية خفيفة، وتجنب المبالغة في اللهجة أو تقليد ألفاظ الشخص بطريقة مصطنعة.
        - لا تقل «بحسب تحليلي لمشاعرك» أو «أفهم أنك غاضب». أظهر الفهم عملياً من خلال الرد المناسب.
        - إذا طلب العميل موظفاً بشرياً أو قال «حولني لموظف» أو «أريد أكلم شخصاً»، أوقف أسئلة البيع واطلب التحويل؛ سيجري النظام اتصال SIP بالموظف. لا تدّعِ نجاح التحويل قبل رد الموظف.
        - إذا قال العميل بوضوح «أنهِ المكالمة» أو «اقفل الاتصال» أو «مع السلامة»، اشكره بجملة ختامية قصيرة ثم توقف عن الحوار؛ النظام سينهي الاتصال بعد مهلة قصيرة.
        - لا تعتبر ذكر الموظف أو المكالمة في سياق سؤال أو نفي أمراً للتحويل أو الإنهاء. لا تنهِ المكالمة بسبب صمت أو كلمة عابرة.
        - إذا قال الشخص إنه غير مهتم أو مشغول أو يريد إنهاء المكالمة، احترم قراره وأنهِ المكالمة فوراً وبأدب.

        التعبير الصوتي:
        - تحدث بصوت دافئ وطبيعي ومهني، كموظف مبيعات حقيقي يتحدث عبر الهاتف، وليس كقارئ نص أو إعلان.
        - غيّر النبرة حسب الموقف: ودودة عند الترحيب، هادئة عند الاستماع، مطمئنة عند التوضيح، ومختصرة ومحترمة عند إنهاء المكالمة.
        - استخدم سرعة كلام متوسطة وواضحة تناسب الهاتف. أبطئ قليلاً عند ذكر رقم أو كمية أو مدينة أو موعد.
        - استخدم وقفات قصيرة طبيعية بين الجمل وبعد السؤال، ولا تملأ الصمت بكلام زائد.
        - أبرز المعلومات المهمة باعتدال من خلال النبرة، دون رفع الصوت أو استخدام نبرة إعلانية.
        - عبّر عن الاهتمام من خلال الرد المناسب، ولا تكرر «ممتاز» أو «رائع» في كل مرة.
        - لا تستخدم حماساً مبالغاً فيه ولا نبرة حزينة أو غاضبة أو مسرحية، ولا تقلد انفعال العميل بطريقة مصطنعة.
        - عند عدم الفهم استخدم نبرة هادئة وغير محرجة، مثل: «عذراً، الصوت ما كان واضحاً عندي، ممكن تعيد الجزء الأخير؟».
        - عند تردد العميل خفف سرعة الكلام واترك له وقتاً للتفكير. لا ترفع الحماس ولا تضغط عليه.
        - عند استعجال العميل اختصر الجمل وخفف المقدمات. وعند طلب إنهاء المكالمة استخدم نبرة ختامية قصيرة ومحترمة.
        - إذا ذكر العميل معلومة مهمة، أظهر أنك التقطتها بتأكيد صوتي قصير ثم تابع بسؤال مناسب.
        - إذا ذكر رقماً أو كمية، أعدها ببطء مرة واحدة للتأكد من الفهم، دون تكرار مزعج.

هدف المكالمة: التأهل الأولي لفرصة توريد ديزل، فهم نشاط العميل واحتياجه، ثم عرض تسجيل طلب متابعة أو إعداد طلب عرض سعر للمراجعة من الإدارة.

سير الحوار:
1. رحّب باختصار وتأكد بلطف من أنك تتحدث مع الشخص المناسب، واطلب الإذن لمتابعة الحديث.
2. اسأل سؤالاً واحداً في كل مرة، وبحسب ما ذكره العميل لا تكرر السؤال: اسم الشركة والشخص المسؤول، النشاط، المدينة وموقع المشروع، كمية الديزل اليومية أو الشهرية، وتكرار التوريد.
3. استفسر عند الحاجة عن موعد بدء التوريد، مواصفات الوقود المطلوبة، وطريقة ووقت مناسبين للمتابعة.
4. وضّح أن بإمكانك جمع الاحتياج ورفعه لفريق المبيعات ليتحقق من التغطية والتوفر والتفاصيل، ثم اسأل إن كان العميل يرغب بمتابعة أو بطلب عرض سعر.
5. قبل إنهاء المكالمة، لخّص المعلومات التي وافق العميل على مشاركتها، وتأكد منها باختصار. لا تطلب البيانات غير اللازمة ولا تكرر بياناته الحساسة.

العملاء المحتملون قد يكونون من المقاولات والإنشاءات، النقل والشحن، المصانع، محطات الوقود، التعدين والمحاجر، المستشفيات والمنشآت الكبيرة، المشاريع المعتمدة على المولدات، أو مشغلي المعدات الثقيلة.

قواعد الصدق والصلاحيات:
- لا تخترع أو تؤكد أسعاراً، مخزوناً، شاحنات، تغطية جغرافية، جودة أو شهادات، كميات أو مواعيد توريد، ولا تعد بعقد أو نتيجة غير مؤكدة.
- يجوز ذكر السعر الافتراضي المدرج أدناه فقط إذا طابقت منطقة العميل وكان تاريخ اليوم ضمن فترة صلاحيته؛ صفه بأنه سعر مسجل استرشادي غير نهائي ويحتاج تأكيد الإدارة. إذا لم يوجد سعر مطابق وسارٍ فلا تذكر رقماً.
- المناطق المصنفة «تحتاج تحقق» ليست تغطية مؤكدة، فلا تعد بالتوريد فيها.
- لا تقدم عرض سعر نهائياً؛ سجّل رغبة العميل واحتياجه ليراجعهما فريق المبيعات والإدارة.
- لا تقل إنك بحثت في السوق أو تحققت من التوفر أو حفظت البيانات في CRM؛ لا يوجد في هذه المكالمة اتصال مباشر ببحث سوق أو CRM. يمكنك جمع التفاصيل بموافقة العميل وتلخيصها في تقرير المكالمة داخل التطبيق ليطّلع عليها المسؤول.
- لا تبدأ اتصالاً أو ترسل رسالة تسويقية أو تعد بمتابعة من نفسك. خذ موافقة العميل على أن يتواصل معه فريق المبيعات، واسأل عن الوقت أو القناة المناسبة.
- إذا سأل العميل عن سعر أو توفر أو تغطية أو مواصفة غير مؤكدة، قل بوضوح إنك تحتاج إلى التحقق من الفريق قبل التأكيد، ثم اعرض تسجيل طلبه.
- إذا لم يكن العميل مهتماً، اشكره وأنهِ المكالمة باحترام. وإذا كان غير مناسب أو كانت المعلومات ناقصة، لا تضغط عليه.

سياق العميل المتاح من التطبيق: الاسم ${context.name.ifBlank { "غير معروف" }}، الوظيفة ${context.role.ifBlank { "غير معروفة" }}، الشركة ${context.company.ifBlank { "غير معروفة" }}. استخدمه فقط إذا كان ملائماً، ولا تفترض أن أي معلومة صحيحة إذا صححها العميل.

بيانات المبيعات التي أدخلتها الإدارة (اعتبرها بيانات داخلية مرجعية فقط، والتزم بحالة التأكيد والصلاحية كما هي):
${context.salesInfo}
"""

        fun start() {
            transition(LegState.PROCEEDING)
            client = GeminiLiveClient(key(), model(), prompt, port::addOutput,
                { text ->
                    inputText = (inputText + " " + text).trim().takeLast(1200)
                    intentTranscriptBuffer = (intentTranscriptBuffer + " " + text).trim().takeLast(240)
                    if (state != LegState.ENDED && callerIntentHandled.compareAndSet(false, true)) {
                        when (VoiceCallIntentDetector.detect(intentTranscriptBuffer)) {
                            VoiceCallIntent.HANDOFF_TO_EMPLOYEE -> {
                                if (requestHandoff()) {
                                    client?.sendClientText("قل للعميل باختصار: سأحولك الآن إلى أحد الموظفين، وابقَ على الخط.")
                                } else {
                                    client?.sendClientText("اعتذر للعميل باختصار: لا أستطيع تحويل المكالمة إلى موظف الآن. اسأله إن كان يريد متابعة الحديث معك أو إنهاء المكالمة.")
                                }
                            }
                            VoiceCallIntent.HANG_UP -> {
                                client?.sendClientText("قل للعميل: شكراً لاتصالك، سعدنا بخدمتك، مع السلامة.")
                                hangupScheduler.schedule({
                                    if (state != LegState.ENDED) requestHangup()
                                }, 1800, TimeUnit.MILLISECONDS)
                            }
                            VoiceCallIntent.NONE -> callerIntentHandled.set(false)
                        }
                    }
                },
                { outputText = (outputText + " " + it).trim().takeLast(1200) },
                { transition(LegState.CONNECTED); publishMedia(port) },
                { detail ->
                    failureDetail = detail
                    AppLog.w("Gemini Live call failed: $detail")
                    if (state != LegState.ENDED) hangup(HangupCause.NETWORK_ERROR)
                },
                { turnCompleteAt = SystemClock.elapsedRealtime(); port.completeTurn() },
                { port.interruptOutput() })
            runCatching { client?.connect() }.onFailure {
                failureDetail = "Gemini client setup failed (${it.javaClass.simpleName})"
                AppLog.w(failureDetail)
                hangup(HangupCause.INTERNAL_ERROR)
            }
        }

        override fun ring() {}
        override fun answer() {}
        override fun hangup(cause: HangupCause) {
            if (state == LegState.ENDED) return
            hangupScheduler.shutdownNow()
            client?.close()
            publishMedia(null)
            transition(LegState.ENDED, cause)
            val turnStats = port.responseStats()
            val speechStats = port.speechStats()
            report(AiCallReport(remote.number, context.name.ifBlank { remote.displayName }, context.role, context.company,
                started, System.currentTimeMillis(), cause, port.inputFrames, port.outputFrames, inputText, outputText,
                failureDetail, port.inputAttempts, port.inputSendFailures, port.outputAudioChunks, port.outputAudioBytes,
                port.outputDroppedFrames, port.outputQueuePeak, port.lastInputSentAt, port.firstOutputAt,
                port.firstOutputQueuedAt, port.firstOutputPlayedAt, turnCompleteAt,
                turnStats.first, turnStats.second, turnStats.third, speechStats.first, speechStats.second))
            onEnd()
        }
        fun notifyHandoffFailed() {
            client?.sendClientText("اعتذر للعميل: الموظف غير متاح حالياً. سأكمل مساعدتك أو يمكنك طلب إنهاء المكالمة.")
        }

        override fun sendDtmf(digit: Char, durationMs: Int) {}

        private inner class AiPort : MediaPort {
            // Gemini may deliver spoken output in bursts faster than the 20 ms cellular clock.
            // A short 2-second queue discarded speech; 20 seconds is still bounded and modest in memory.
            private val output = ArrayBlockingQueue<ShortArray>(1_000)
            private val outputBuffer = GeminiPcmOutputBuffer(PcmFormat.NARROWBAND.frameSamples)
            @Volatile var inputFrames = 0L
            @Volatile var inputAttempts = 0L
            @Volatile var inputSendFailures = 0L
            @Volatile var outputFrames = 0L
            @Volatile var outputAudioChunks = 0L
            @Volatile var outputAudioBytes = 0L
            @Volatile var outputDroppedFrames = 0L
            @Volatile var outputQueuePeak = 0
            @Volatile var lastInputSentAt: Long? = null
            @Volatile var firstOutputAt: Long? = null
            @Volatile var firstOutputQueuedAt: Long? = null
            @Volatile var firstOutputPlayedAt: Long? = null
            private var turnSpeechStartedAt: Long? = null
            private var lastSpeechAt: Long? = null
            private var speechEndSignaled = false
            private var noiseFloorRms = 80.0
            private var currentTurnFirstOutputAt: Long? = null
            private val turnResponseLatencies = mutableListOf<Long>()
            private val turnSpeechDurations = mutableListOf<Long>()
            override val format = PcmFormat.NARROWBAND
            override val isClockSource = false
            override fun setClockListener(listener: FrameClockListener?) {}
            override fun readFrame(): ShortArray = output.poll()?.also {
                outputFrames++
                if (firstOutputPlayedAt == null) firstOutputPlayedAt = SystemClock.elapsedRealtime()
            } ?: format.silence()
            override fun writeFrame(frame: ShortArray) {
                if (openingTurnStarted.compareAndSet(false, true)) {
                    val queued = client?.sendClientText("ابدأ بتحية قصيرة وطبيعية باللهجة اليمنية الخفيفة. عرّف بنفسك كمساعد مبيعات للديزل دون اختراع اسم شركة، واسأل بلطف إن كان الوقت مناسباً لدقيقة واحدة. إذا قال الشخص إنه مشغول فلا تبدأ الأسئلة؛ اعرض وقتاً مناسباً للمتابعة أو أنهِ المكالمة باحترام، ثم توقف واستمع.") == true
                    AppLog.i("Gemini Live call opening turn started=$queued")
                }
                val now = SystemClock.elapsedRealtime()
                if (isSpeechLike(frame)) {
                    if (turnSpeechStartedAt == null) turnSpeechStartedAt = now
                    lastSpeechAt = now
                    speechEndSignaled = false
                } else if (turnSpeechStartedAt != null && lastSpeechAt != null && !speechEndSignaled &&
                    now - lastSpeechAt!! >= 600) {
                    // Hybrid VAD: preserve automatic server VAD, but finalize promptly after
                    // a locally detected speech end instead of waiting for the full server window.
                    speechEndSignaled = client?.sendAudioStreamEnd() == true
                }
                inputAttempts++
                if (client?.sendPcm8(frame) == true) {
                    inputFrames++
                    lastInputSentAt = SystemClock.elapsedRealtime()
                } else inputSendFailures++
            }
            @Synchronized fun addOutput(bytes24k: ByteArray) {
                val now = SystemClock.elapsedRealtime()
                if (firstOutputAt == null) firstOutputAt = now
                if (currentTurnFirstOutputAt == null) {
                    currentTurnFirstOutputAt = now
                    lastSpeechAt?.let { speechEndAt -> turnResponseLatencies += (now - speechEndAt).coerceAtLeast(0) }
                }
                outputAudioChunks++
                outputAudioBytes += bytes24k.size
                for (frame in outputBuffer.append(bytes24k)) {
                    if (firstOutputQueuedAt == null) firstOutputQueuedAt = SystemClock.elapsedRealtime()
                    if (!output.offer(frame)) {
                        val removed = output.poll()
                        val added = output.offer(frame)
                        outputDroppedFrames += (if (removed == null) 0 else 1) + (if (added) 0 else 1)
                    }
                    outputQueuePeak = maxOf(outputQueuePeak, output.size)
                }
            }
            @Synchronized fun interruptOutput() {
                output.clear()
                outputBuffer.reset()
                currentTurnFirstOutputAt = null
                val now = SystemClock.elapsedRealtime()
                if (turnSpeechStartedAt == null) turnSpeechStartedAt = now
                lastSpeechAt = now
                AppLog.i("Gemini Live output interrupted by caller speech")
            }
            @Synchronized fun completeTurn() {
                currentTurnFirstOutputAt = null
                val startedAt = turnSpeechStartedAt
                val speechEndAt = lastSpeechAt
                if (startedAt != null && speechEndAt != null) {
                    turnSpeechDurations += (speechEndAt - startedAt + 20).coerceAtLeast(0)
                }
                turnSpeechStartedAt = null
                lastSpeechAt = null
                speechEndSignaled = false
            }
            @Synchronized fun responseStats(): Triple<Int, Long?, Long?> {
                val values = turnResponseLatencies.toList()
                return Triple(values.size, values.lastOrNull(), values.average().takeIf { values.isNotEmpty() }?.toLong())
            }
            @Synchronized fun speechStats(): Pair<Long?, Long?> {
                val values = turnSpeechDurations.toList()
                return Pair(values.lastOrNull(), values.average().takeIf { values.isNotEmpty() }?.toLong())
            }

            private fun isSpeechLike(frame: ShortArray): Boolean {
                if (frame.isEmpty()) return false
                var sumSquares = 0.0
                var peak = 0
                for (sample in frame) {
                    val amplitude = kotlin.math.abs(sample.toInt())
                    sumSquares += amplitude.toDouble() * amplitude
                    if (amplitude > peak) peak = amplitude
                }
                val rms = sqrt(sumSquares / frame.size)
                val threshold = maxOf(220.0, noiseFloorRms * 2.8)
                val speech = rms >= threshold && peak >= 420
                if (!speech) noiseFloorRms = noiseFloorRms * 0.98 + rms * 0.02
                return speech
            }
        }
    }
}
