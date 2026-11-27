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
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt
import kotlin.math.roundToInt
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject


/** Text-only Gemini helper for customer marketing SMS. Separate from the configured Live audio model. */
internal class GeminiSmsWriter(private val apiKey: () -> String) {
    private val client = OkHttpClient.Builder()
        .callTimeout(25, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    fun createThreeMessages(customer: VoicxCustomer, businessContext: String): List<String> {
        val prompt = """
            اكتب ثلاث رسائل SMS تسويقية عربية قصيرة ومهنية ومخصصة لهذا العميل.
            استخدم اسمه ووظيفته وشركته والسياق التجاري وملاحظاته عند توفرها.
            الرسالة 1 افتتاحية لطيفة ومختصرة، الرسالة 2 متابعة بعد أسبوع دون ضغط،
            الرسالة 3 متابعة أخيرة محترمة. لا تخترع أسعاراً أو وعوداً أو معلومات غير مذكورة.
            لا تستخدم تحية طويلة أو رموزاً كثيرة. كل رسالة <= 300 حرف.
            أعد JSON فقط بالشكل {"messages":["...","...","..."]}.
            العميل: الاسم=${customer.name}; الوظيفة=${customer.role}; الشركة=${customer.company}
            ملاحظات=${customer.notes}; تعليمات=${customer.instructions}
            سياق النشاط: $businessContext
        """.trimIndent()
        val raw = generate(prompt)
        val start = raw.indexOf('{').coerceAtLeast(0)
        val end = raw.lastIndexOf('}').let { if (it >= start) it + 1 else raw.length }
        val messages = JSONObject(raw.substring(start, end)).optJSONArray("messages") ?: error("Gemini لم يُرجع قائمة الرسائل")
        require(messages.length() == 3) { "Gemini لم يُرجع ثلاث رسائل" }
        return (0..2).map { messages.getString(it).trim().also { message ->
            require(message.isNotBlank() && message.length <= CustomerSmsSequencePolicy.MAX_MESSAGE_LENGTH) {
                "يجب أن تكون كل رسالة بين حرف واحد و${CustomerSmsSequencePolicy.MAX_MESSAGE_LENGTH} حرفاً"
            }
        } }
    }

    fun createFollowUp(customer: VoicxCustomer, stage: Int, replies: List<String>, history: List<String>, businessContext: String): String {
        val replyContext = if (replies.isEmpty()) "لم يصل أي رد من العميل بعد الرسالة السابقة. اكتب تذكيراً تسويقياً مهذباً."
            else "وصلت ردود من العميل. أجب عن مضمونها مباشرة وباحترام، ولا ترسل تذكيراً عاماً. الردود: " + replies.joinToString("\n")
        val prompt = """
            أنت مساعد مبيعات. اكتب رسالة SMS واحدة قصيرة بالعربية، بحد أقصى 300 حرف.
            هذه متابعة رقم $stage للعميل ${customer.name}، وظيفته ${customer.role} في ${customer.company}.
            $replyContext
            سياق الرسائل السابقة: ${history.joinToString("\n")}
            ملاحظات العميل: ${customer.notes}
            تعليمات العميل: ${customer.instructions}
            سياق النشاط: $businessContext
            لا تخترع حقائق أو أسعاراً أو عروضاً. لا تضغط على العميل. أعد نص الرسالة فقط دون علامات اقتباس.
        """.trimIndent()
        return generate(prompt).trim().trim('"', '،').also {
            require(it.isNotBlank() && it.length <= CustomerSmsSequencePolicy.MAX_MESSAGE_LENGTH) {
                "رسالة المتابعة تجاوزت ${CustomerSmsSequencePolicy.MAX_MESSAGE_LENGTH} حرفاً"
            }
        }
    }

    private fun generate(prompt: String): String {
        val key = apiKey().trim()
        require(key.isNotBlank()) { "مفتاح Gemini API غير مضبوط" }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("temperature", 0.65).put("maxOutputTokens", 700))
            .toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("generativelanguage.googleapis.com")
            .addPathSegments("v1beta/models/gemini-2.5-flash:generateContent").addQueryParameter("key", key).build()
        val request = Request.Builder().url(url).post(body).build()
        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Gemini text API rejected request (${response.code})")
            val root = JSONObject(responseText)
            val parts = root.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                ?: error("Gemini لم يُرجع محتوى نصياً")
            return (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optString("text") }
                .joinToString("\n").takeIf(String::isNotBlank) ?: error("Gemini أرجع نصاً فارغاً")
        }
    }
}

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
    val turnCount: Int = 0,
    val conversationTranscript: String = "",
    val lastSpeechEndAt: Long? = null,
    val firstInputSentAt: Long? = null,
    val callId: String = "",
) {
    fun telegramMessages(): List<String> {
        val summary = buildString {
        appendLine("تقرير مكالمة الذكاء الاصطناعي · callId=${callId.ifBlank { "غير متاح" }}")
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
        // Measure first-response latency from the detected end of caller speech, not from
        // the last continuously streamed input frame (which may occur after Gemini responds).
        val responseMs = if (firstInputSentAt != null && firstOutputAt != null) (firstOutputAt - firstInputSentAt).takeIf { it >= 0 } else null
        val queuedMs = if (firstInputSentAt != null && firstOutputQueuedAt != null) (firstOutputQueuedAt - firstInputSentAt).takeIf { it >= 0 } else null
        val playbackMs = if (firstInputSentAt != null && firstOutputPlayedAt != null) (firstOutputPlayedAt - firstInputSentAt).takeIf { it >= 0 } else null
        appendLine("زمن وصول أول صوت Gemini بعد أول إطار دخل: ${responseMs?.let { "$it مللي ثانية" } ?: "غير متاح"}")
        appendLine("زمن وضع أول إطار صوت في الطابور: ${queuedMs?.let { "$it مللي ثانية" } ?: "غير متاح"}")
        appendLine("زمن أول تشغيل بعد أول إطار دخل: ${playbackMs?.let { "$it مللي ثانية" } ?: "غير متاح"}")
        appendLine("عدد جولات الحوار المكتملة: $turnCount")
        if (turnResponseCount > 0) appendLine("زمن استجابة Gemini بعد نهاية كلام العميل: ${lastTurnResponseMs} مللي ثانية؛ المتوسط: ${averageTurnResponseMs} مللي ثانية؛ عدد الجولات المقاسة: $turnResponseCount")
        if (lastTurnSpeechDurationMs != null) appendLine("مدة كلام العميل في آخر جولة: ${lastTurnSpeechDurationMs} مللي ثانية؛ المتوسط: ${averageTurnSpeechDurationMs} مللي ثانية")
        if (turnCompleteAt != null) appendLine("إشارة اكتمال دور Gemini: مسجلة (لا تمثل بالضرورة نهاية كلام الطرف الآخر)")
        if (inputText.isNotBlank()) appendLine("آخر كلام الطرف الآخر: ${inputText.take(500)}")
        if (outputText.isNotBlank()) appendLine("آخر رد Gemini: ${outputText.take(500)}")
        }.toString().take(3900)
        val transcript = conversationTranscript.trim()
        if (transcript.isBlank()) return listOf(summary)
        val chunks = transcript.chunked(3_500).mapIndexed { index, part ->
            "تفريغ المحادثة ${index + 1}:\n$part"
        }
        return listOf(summary) + chunks
    }
}

internal class GeminiAiEndpoint(
    private val apiKey: () -> String,
    private val model: () -> String,
    private val speechRate: () -> Float = { 1.0f },
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

    data class CallContext(
        val name: String = "", val role: String = "", val company: String = "",
        val salesInfo: String = "", val instructions: String = "", val memories: String = "",
    )

    fun setContext(value: CallContext) { context = value }
    fun hasActiveCall(): Boolean = (directCellLeg?.state?.let { it != LegState.ENDED } == true) ||
        (active?.state?.let { it != LegState.ENDED } == true)

    override fun setIncomingCallHandler(handler: io.github.sparkcyf.voicesipagent.voice.IncomingCallHandler?) { this.handler = handler?.let { h -> { leg -> h.onIncomingCall(leg) } } }
    override fun addStatusListener(listener: EndpointStatusListener) { statuses += listener }
    override fun removeStatusListener(listener: EndpointStatusListener) { statuses -= listener }

    override fun dial(target: String, caller: Party): CallLeg {
        val leg = createAiLeg("ai-${System.nanoTime()}", target, caller)
        leg.start()
        return leg
    }
    @Synchronized private fun createAiLeg(callId: String, target: String, caller: Party): AiLeg {
        check(active == null || active?.state == LegState.ENDED) { "Gemini call already active" }
        return AiLeg(
            callId, caller, target, context.copy(salesInfo = salesContext()),
            apiKey, model, speechRate, report, { if (active?.id == callId) active = null },
            { onHandoffRequested?.invoke() == true },
            { onHangupRequested?.invoke() },
        ).also { active = it }
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

    /** Opens Gemini only after Telecom confirms the SIM call is connected. */
    @Synchronized fun startOutbound(cellular: CallEndpoint, number: String, caller: Party) {
        check(directCellLeg == null || directCellLeg?.state == LegState.ENDED) { "An outbound call is already active" }
        val callId = "sim-${java.util.UUID.randomUUID()}"
        val cell = cellular.dial(number, caller)
        directBridge?.stop(); directBridge = null
        directCellLeg = cell
        directAiLeg = null
        directHumanLeg = null
        directHandoffCommitted = false
        val watcher = object : CallLeg.Listener {
            override fun onStateChanged(leg: CallLeg, state: LegState) {
                if (leg === cell && state == LegState.CONNECTED) {
                    synchronized(this@GeminiAiEndpoint) {
                        if (directCellLeg === cell && directAiLeg == null && cell.state == LegState.CONNECTED) {
                            val ai = createAiLeg(callId, number, caller)
                            directAiLeg = ai
                            ai.start()
                            ai.addListener(this)
                        }
                    }
                }
                if (state == LegState.ENDED) {
                    when (leg) {
                        cell -> {
                            if (directAiLeg == null) report(AiCallReport(
                                callId = callId, number = number, name = caller.displayName, role = context.role,
                                company = context.company, startedAt = System.currentTimeMillis(), endedAt = System.currentTimeMillis(),
                                cause = leg.endCause ?: HangupCause.NETWORK_ERROR,
                                inputFrames = 0, outputFrames = 0, inputText = "", outputText = "",
                                failureDetail = listOfNotNull(leg.endCode?.let { "SIP/Telecom code=$it" }, leg.endReason).joinToString(" ").ifBlank { "SIM call ended before answer: ${leg.endCause}" },
                            ))
                            directAiLeg?.takeIf { it.state != LegState.ENDED }?.hangup(leg.endCause ?: HangupCause.NORMAL)
                            directHumanLeg?.takeIf { it.state != LegState.ENDED }?.hangup(HangupCause.CANCELLED)
                            clearDirectCall(cell)
                        }
                        directAiLeg -> {
                            if (directHumanLeg?.state != LegState.CONNECTED) cell.hangup(leg.endCause ?: HangupCause.NORMAL)
                        }
                    }
                }
                if (leg === cell || leg === directAiLeg) connectIfReady(cell, directAiLeg)
            }
            override fun onMediaChanged(leg: CallLeg, media: MediaPort?) {
                if (leg === cell || leg === directAiLeg) connectIfReady(cell, directAiLeg)
            }
        }
        cell.addListener(watcher)
        when (cell.state) {
            LegState.CONNECTED -> watcher.onStateChanged(cell, LegState.CONNECTED)
            LegState.ENDED -> watcher.onStateChanged(cell, LegState.ENDED)
            else -> Unit
        }
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

    private fun connectIfReady(cell: CallLeg, ai: CallLeg?) {
        if (ai == null || directHandoffCommitted) return
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
        private val speechRate: () -> Float,
        private val report: (AiCallReport) -> Unit,
        private val onEnd: () -> Unit,
        private val requestHandoff: () -> Boolean,
        private val requestHangup: () -> Unit,
    ) : AbstractCallLeg(id, "gemini", LegDirection.OUTBOUND, remote, target) {
        companion object { private const val MAX_TRANSCRIPT_CHARS = 24_000 }
        private val port = AiPort()
        private val started = System.currentTimeMillis()
        private var client: GeminiLiveClient? = null
        @Volatile private var failureDetail = ""
        private var inputText = ""
        private var outputText = ""
        private val conversationTranscript = StringBuilder()
        private val transcriptLock = Any()
        private val completedTurns = AtomicInteger(0)
        private var turnCompleteAt: Long? = null
        private val openingTurnStarted = AtomicBoolean(false)
        private val callerIntentHandled = AtomicBoolean(false)
        private var intentTranscriptBuffer = ""
        private val hangupScheduler = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "GeminiSpokenHangup").apply { isDaemon = true }
        }
        @Volatile private var callerSpeechVersion = 0L
        @Volatile private var silenceFollowUpCount = 0
        @Volatile private var silenceCheck: ScheduledFuture<*>? = null
		private val prompt = """أنت سكرتير أعمال ومساعد مبيعات متخصص في تسويق توريد الديزل والوقود للشركات والمشاريع ومحطات الوقود والمصانع والمقاولين.

        أسلوبك: تحدث بلغة العميل. عند التحدث بالعربية استخدم افتراضياً لهجة يمنية محكية طبيعية ومفهومة، وبدّل إلى لغة العميل ولهجته المناسبة فوراً إذا بدأ بلغة أخرى. حافظ على نبرة مهنية وودودة وجمل قصيرة، ولا تكن آلياً أو ضاغطاً.
        - في اليمن استخدم لهجة يمنية بيضاء طبيعية ومفهومة عبر الهاتف، بعبارات مهنية بسيطة، ولا تبالغ في المفردات المحلية أو تقلد منطقة بعينها. إذا استخدم العميل لهجة يمنية محلية واضحة فاقترب منها باعتدال، وإذا كان من بلد آخر فطابق لغته ولهجته.

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
        - طابق لغة العميل؛ استخدم افتراضياً العربية اليمنية المحكية، وانتقل إلى لغة العميل الأخرى بطلاقة عند الحاجة، دون مبالغة أو تقليد مصطنع للهجة.
        - لا تقل «بحسب تحليلي لمشاعرك» أو «أفهم أنك غاضب». أظهر الفهم عملياً من خلال الرد المناسب.
        - إذا طلب العميل موظفاً بشرياً أو قال «حولني لموظف» أو «أريد أكلم شخصاً»، أوقف أسئلة البيع واطلب التحويل؛ سيجري النظام اتصال SIP بالموظف. لا تدّعِ نجاح التحويل قبل رد الموظف.
        - إذا قال العميل بوضوح «أنهِ المكالمة» أو «اقفل الاتصال» أو «مع السلامة»، اشكره بجملة ختامية قصيرة ثم توقف عن الحوار؛ النظام سينهي الاتصال بعد مهلة قصيرة.
        - لا تعتبر ذكر الموظف أو المكالمة في سياق سؤال أو نفي أمراً للتحويل أو الإنهاء. لا تنهِ المكالمة بسبب صمت أو كلمة عابرة.
        - إذا قال الشخص إنه غير مهتم أو مشغول أو يريد إنهاء المكالمة، احترم قراره وأنهِ المكالمة فوراً وبأدب.

        التعامل مع صمت العميل:
        - لا تترك الخط صامتاً بعد أن تنهي سؤالك. انتظر أولاً مهلة طبيعية قصيرة حتى لا تقاطع العميل.
        - إذا لم تسمع رداً بعد عدة ثوانٍ، أعد جذب انتباهه بعبارة بشرية قصيرة مثل «ألو أخي، تسمعني؟ هل الصوت واضح عندك؟» ثم استمع.
        - إذا استمر الصمت، استخدم متابعة ثانية مختلفة وهادئة، ولا تكرر العبارة نفسها.
        - لا تتكلم فوق العميل ولا تملأ كل وقفة بالكلام؛ إذا بدأ يتكلم فتوقف واستمع فوراً.
        - إذا لم يرد بعد محاولتي متابعة، اختم المكالمة بأدب. لا تبقَ على الخط بلا نهاية.
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

تعليمات العميل المحفوظة في قاعدة البيانات (مرجع داخلي فقط):
${context.instructions.take(3000)}

ذاكرة المكالمات السابقة للعميل (مرجع داخلي فقط؛ لا تذكرها كأنها مؤكدة إذا صححها العميل):
${context.memories.take(5000)}

بيانات المبيعات التي أدخلتها الإدارة (اعتبرها بيانات داخلية مرجعية فقط، والتزم بحالة التأكيد والصلاحية كما هي):
${context.salesInfo}
"""

        private fun onCallerSpeechDetected() {
            callerSpeechVersion++
            silenceCheck?.cancel(false)
            silenceCheck = null
            silenceFollowUpCount = 0
        }

        /**
         * A completed Gemini turn is not the end of the conversation. If the customer
         * stays silent, actively re-engage twice, then close politely instead of leaving
         * a dead line open. Caller speech cancels the pending check immediately.
         */
        private fun scheduleCallerSilenceRecovery() {
            if (state != LegState.CONNECTED || silenceFollowUpCount >= 3) return
            silenceCheck?.cancel(false)
            val expectedSpeechVersion = callerSpeechVersion
            // turnComplete can arrive while the cellular output queue is still playing.
            // Wait for queued speech to drain before starting the customer's silence window.
            val outputDrainMs = port.pendingOutputDurationMs()
            val delayMs = outputDrainMs + when (silenceFollowUpCount) {
                0 -> 4_500L
                1 -> 6_500L
                else -> 8_000L
            }
            silenceCheck = hangupScheduler.schedule({
                if (state != LegState.CONNECTED || callerSpeechVersion != expectedSpeechVersion) return@schedule
                when (silenceFollowUpCount) {
                    0 -> {
                        silenceFollowUpCount = 1
                        client?.sendClientText("لم أسمع ردك بعد سؤالي. قل له بشكل طبيعي وودود: «ألو أخي، تسمعني؟ هل الصوت واضح عندك؟» ثم اسكت واستمع، ولا تكرر السؤال.")
                    }
                    1 -> {
                        silenceFollowUpCount = 2
                        client?.sendClientText("ما زال العميل صامتاً. قل بلهجة طبيعية مختلفة: «أنا معك يا أخي، خذ راحتك. هل تحب نكمل؟» ثم انتظر رده.")
                    }
                    else -> {
                        silenceFollowUpCount = 3
                        client?.sendClientText("لم يرد العميل رغم محاولتين. قل باختصار وباحترام: «يبدو أن الوقت غير مناسب أو أن الصوت انقطع. نشكرك، ونتمنى لك يوماً طيباً». لا تسأل سؤالاً آخر.")
                        hangupScheduler.schedule({
                            if (state != LegState.ENDED) requestHangup()
                        }, 2_500L, TimeUnit.MILLISECONDS)
                    }
                }
            }, delayMs, TimeUnit.MILLISECONDS)
        }

        fun start() {
            transition(LegState.PROCEEDING)
            client = GeminiLiveClient(key(), model(), prompt, port::addOutput,
                { text ->
                    // Gemini can emit punctuation-only partials for noise or an empty turn;
                    // they are not evidence that the caller said something meaningful.
                    if (text.any { it.isLetterOrDigit() }) {
                        inputText = (inputText + " " + text).trim().takeLast(12_000)
                        appendTranscript("العميل", text)
                        intentTranscriptBuffer = (intentTranscriptBuffer + " " + text).trim().takeLast(240)
                    }
                    if (text.any { it.isLetterOrDigit() } && state != LegState.ENDED && callerIntentHandled.compareAndSet(false, true)) {
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
                { text ->
                    outputText = (outputText + " " + text).trim().takeLast(12_000)
                    appendTranscript("Gemini", text)
                },
                { transition(LegState.CONNECTED); publishMedia(port) },
                { detail ->
                    failureDetail = detail
                    AppLog.w("Gemini Live call failed: $detail")
                    if (state != LegState.ENDED) hangup(HangupCause.NETWORK_ERROR)
                },
                {
                    completedTurns.incrementAndGet()
                    turnCompleteAt = SystemClock.elapsedRealtime()
                    port.completeTurn()
                    scheduleCallerSilenceRecovery()
                },
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
                turnStats.first, turnStats.second, turnStats.third, speechStats.first, speechStats.second,
                completedTurns.get(), synchronized(transcriptLock) { conversationTranscript.toString() }, port.lastSpeechEndAt, port.firstInputSentAt, id))
            onEnd()
        }
        private fun appendTranscript(speaker: String, text: String) {
            val clean = text.trim().replace(Regex("\\s+"), " ")
            if (clean.isBlank()) return
            synchronized(transcriptLock) {
                if (conversationTranscript.length >= MAX_TRANSCRIPT_CHARS) return
                val prefix = if (conversationTranscript.isEmpty()) "" else "\\n"
                val line = "$prefix$speaker: $clean"
                val remaining = MAX_TRANSCRIPT_CHARS - conversationTranscript.length
                conversationTranscript.append(line.take(remaining))
            }
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
            private val frameSamples = PcmFormat.NARROWBAND.frameSamples
            private var playbackFrame: ShortArray? = null
            private var playbackNextFrame: ShortArray? = null
            private var playbackPosition = 0.0
            @Volatile var inputFrames = 0L
            @Volatile var inputAttempts = 0L
            @Volatile var inputSendFailures = 0L
            @Volatile var outputFrames = 0L
            @Volatile var outputAudioChunks = 0L
            @Volatile var outputAudioBytes = 0L
            @Volatile var outputDroppedFrames = 0L
            @Volatile var outputQueuePeak = 0
            @Volatile var lastInputSentAt: Long? = null
            @Volatile var firstInputSentAt: Long? = null
            @Volatile var firstOutputAt: Long? = null
            @Volatile var firstOutputQueuedAt: Long? = null
            @Volatile var firstOutputPlayedAt: Long? = null
            @Volatile var lastSpeechEndAt: Long? = null
            private var turnSpeechStartedAt: Long? = null
            private var lastSpeechAt: Long? = null
            private val turnEndGate = GeminiTurnEndGate()
            private var noiseFloorRms = 80.0
            private var currentTurnFirstOutputAt: Long? = null
            private val turnResponseLatencies = mutableListOf<Long>()
            private val turnSpeechDurations = mutableListOf<Long>()
            override val format = PcmFormat.NARROWBAND
            override val isClockSource = false
            override fun setClockListener(listener: FrameClockListener?) {}
            @Synchronized override fun readFrame(): ShortArray {
                if (playbackFrame == null) playbackFrame = output.poll()
                if (playbackNextFrame == null) playbackNextFrame = output.poll()
                while (playbackPosition >= frameSamples && playbackNextFrame != null) {
                    playbackPosition -= frameSamples
                    playbackFrame = playbackNextFrame
                    playbackNextFrame = output.poll()
                }
                if (playbackFrame == null) return format.silence()
                val result = ShortArray(frameSamples)
                val rate = speechRate().coerceIn(0.80f, 1.25f).toDouble()
                for (i in result.indices) {
                    val activeFrame = playbackFrame ?: break
                    val position = playbackPosition
                    val index = position.toInt().coerceIn(0, frameSamples - 1)
                    val fraction = (position - index).coerceIn(0.0, 1.0)
                    val a = activeFrame[index].toInt()
                    val b = if (index + 1 < frameSamples) activeFrame[index + 1].toInt()
                        else playbackNextFrame?.get(0)?.toInt() ?: a
                    result[i] = (a + (b - a) * fraction).roundToInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    playbackPosition += rate
                    while (playbackPosition >= frameSamples && playbackNextFrame != null) {
                        playbackPosition -= frameSamples
                        playbackFrame = playbackNextFrame
                        playbackNextFrame = output.poll()
                    }
                }
                outputFrames++
                if (firstOutputPlayedAt == null) firstOutputPlayedAt = SystemClock.elapsedRealtime()
                return result
            }
            @Synchronized override fun writeFrame(frame: ShortArray) {
                if (openingTurnStarted.compareAndSet(false, true)) {
                    val queued = client?.sendClientText("ابدأ بتحية قصيرة وطبيعية بلغة العميل المتوقعة؛ إن كانت المحادثة بالعربية فاستخدم اللهجة اليمنية المحكية. عرّف بنفسك كمساعد مبيعات للديزل دون اختراع اسم شركة، واسأل بلطف إن كان الوقت مناسباً لدقيقة واحدة. إذا قال الشخص إنه مشغول فلا تبدأ الأسئلة؛ اعرض وقتاً مناسباً للمتابعة أو أنهِ المكالمة باحترام، ثم توقف واستمع.") == true
                    AppLog.i("Gemini Live call opening turn started=$queued")
                }
                val now = SystemClock.elapsedRealtime()
                var endCallerTurn = false
                if (isSpeechLike(frame)) {
                    onCallerSpeechDetected()
                    if (turnSpeechStartedAt == null) turnSpeechStartedAt = now
                    lastSpeechAt = now
                } else if (turnSpeechStartedAt != null && lastSpeechAt != null &&
                    now - lastSpeechAt!! >= 350 && turnEndGate.trySignal()) {
                    // Finalize the caller turn explicitly. Without this signal Gemini can keep
                    // waiting for a server-side VAD edge that is unreliable on narrowband
                    // cellular audio, leaving the caller unheard after the opening greeting.
                    lastSpeechEndAt = lastSpeechAt
                    endCallerTurn = true
                }
                inputAttempts++
                if (client?.sendPcm8(frame) == true) {
                    inputFrames++
                    val sentAt = SystemClock.elapsedRealtime()
                    lastInputSentAt = sentAt
                    if (firstInputSentAt == null) firstInputSentAt = sentAt
                } else inputSendFailures++
                if (endCallerTurn) {
                    val ended = client?.sendAudioStreamEnd() == true
                    AppLog.i("Gemini caller audio turn ended locally after final frame: queued=$ended")
                }
            }
            @Synchronized fun addOutput(bytes24k: ByteArray) {
                val now = SystemClock.elapsedRealtime()
                if (firstOutputAt == null) firstOutputAt = now
                if (currentTurnFirstOutputAt == null) {
                    currentTurnFirstOutputAt = now
                    lastSpeechEndAt?.let { speechEndAt -> turnResponseLatencies += (now - speechEndAt).coerceAtLeast(0) }
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
                playbackFrame = null
                playbackNextFrame = null
                playbackPosition = 0.0
                outputBuffer.reset()
                currentTurnFirstOutputAt = null
                val now = SystemClock.elapsedRealtime()
                if (turnSpeechStartedAt == null) turnSpeechStartedAt = now
                lastSpeechAt = now
                AppLog.i("Gemini Live output interrupted by caller speech")
            }
            @Synchronized fun completeTurn() {
                // Pad and queue the last non-frame-aligned audio remainder before stats
                // are finalized; otherwise a short final chunk has no queue/play timestamp.
                for (frame in outputBuffer.flushPadded()) {
                    if (firstOutputQueuedAt == null) firstOutputQueuedAt = SystemClock.elapsedRealtime()
                    if (!output.offer(frame)) {
                        output.poll()
                        if (!output.offer(frame)) outputDroppedFrames++
                        else outputDroppedFrames++
                    }
                    outputQueuePeak = maxOf(outputQueuePeak, output.size)
                }
                currentTurnFirstOutputAt = null
                val startedAt = turnSpeechStartedAt
                val speechEndAt = lastSpeechAt
                if (startedAt != null && speechEndAt != null) {
                    turnSpeechDurations += (speechEndAt - startedAt + 20).coerceAtLeast(0)
                }
                turnSpeechStartedAt = null
                lastSpeechAt = null
                turnEndGate.reset()
            }
            @Synchronized fun pendingOutputDurationMs(): Long {
                val rate = speechRate().coerceIn(0.80f, 1.25f).toDouble()
                val queuedFrames = output.size + (if (playbackFrame == null) 0 else 1) + (if (playbackNextFrame == null) 0 else 1)
                return (queuedFrames * 20.0 / rate).toLong()
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
