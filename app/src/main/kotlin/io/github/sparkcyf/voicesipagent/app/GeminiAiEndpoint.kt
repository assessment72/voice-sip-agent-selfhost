package io.github.sparkcyf.voicesipagent.app

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
import java.util.concurrent.atomic.AtomicBoolean

internal data class AiCallReport(
    val number: String?, val name: String?, val role: String?, val company: String?,
    val startedAt: Long, val endedAt: Long, val cause: HangupCause?,
    val inputFrames: Long, val outputFrames: Long, val inputText: String, val outputText: String,
    val failureDetail: String = "",
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
        appendLine("التقاط وإرسال الصوت: ${inputFrames > 0} (${inputFrames} إطار)")
        appendLine("استقبال صوت Gemini: ${outputFrames > 0} (${outputFrames} إطار)")
        if (inputText.isNotBlank()) appendLine("آخر كلام الطرف الآخر: ${inputText.take(500)}")
        if (outputText.isNotBlank()) appendLine("آخر رد Gemini: ${outputText.take(500)}")
    }.take(3900)
}

internal class GeminiAiEndpoint(
    private val apiKey: () -> String,
    private val model: () -> String,
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

    data class CallContext(val name: String = "", val role: String = "", val company: String = "")

    fun setContext(value: CallContext) { context = value }

    override fun setIncomingCallHandler(handler: io.github.sparkcyf.voicesipagent.voice.IncomingCallHandler?) { this.handler = handler?.let { h -> { leg -> h.onIncomingCall(leg) } } }
    override fun addStatusListener(listener: EndpointStatusListener) { statuses += listener }
    override fun removeStatusListener(listener: EndpointStatusListener) { statuses -= listener }

    override fun dial(target: String, caller: Party): CallLeg {
        active?.hangup(HangupCause.BUSY)
        val leg = AiLeg("ai-${System.nanoTime()}", caller, target, context, apiKey, model, report) { active = null }
        active = leg
        leg.start()
        return leg
    }

    fun stop() { active?.hangup(HangupCause.CANCELLED) }

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
        val ai = dial(number, caller)
        val watcher = object : CallLeg.Listener {
            override fun onStateChanged(leg: CallLeg, state: LegState) {
                if (state == LegState.ENDED) {
                    if (leg === cell) ai.hangup(leg.endCause ?: HangupCause.NORMAL)
                    else if (leg === ai) cell.hangup(leg.endCause ?: HangupCause.NORMAL)
                }
                connectIfReady(cell, ai)
            }
            override fun onMediaChanged(leg: CallLeg, media: MediaPort?) = connectIfReady(cell, ai)
        }
        cell.addListener(watcher)
        ai.addListener(watcher)
    }

    private fun connectIfReady(cell: CallLeg, ai: CallLeg) {
        if (cell.state == LegState.ENDED || ai.state == LegState.ENDED) {
            directBridge?.stop(); directBridge = null
            return
        }
        if (directBridge == null && cell.state == LegState.CONNECTED && ai.state == LegState.CONNECTED &&
            cell.media != null && ai.media != null) {
            directBridge = MediaBridge(cell.media!!, ai.media!!).also { it.start() }
        }
    }

    private class AiLeg(
        id: String, remote: Party, target: String?, private val context: CallContext, private val key: () -> String, private val model: () -> String,
        private val report: (AiCallReport) -> Unit, private val onEnd: () -> Unit,
    ) : AbstractCallLeg(id, "gemini", LegDirection.OUTBOUND, remote, target) {
        private val port = AiPort()
        private val started = System.currentTimeMillis()
        private var client: GeminiLiveClient? = null
        @Volatile private var failureDetail = ""
        private var inputText = ""
        private var outputText = ""
        private val prompt = """أنت وكيل اتصال صوتي. تحدث بالعربية باختصار ووضوح وبأسلوب مهني.
الشخص المطلوب: ${context.name}. الوظيفة: ${context.role}. الشركة: ${context.company}.
نفّذ الحوار بأمان ولا تخترع معلومات ولا تعد بأسعار أو عقود.
"""

        fun start() {
            transition(LegState.PROCEEDING)
            client = GeminiLiveClient(key(), model(), prompt, port::addOutput,
                { inputText = (inputText + " " + it).trim().takeLast(1200) },
                { outputText = (outputText + " " + it).trim().takeLast(1200) },
                { transition(LegState.CONNECTED); publishMedia(port) },
                { detail ->
                    failureDetail = detail
                    AppLog.w("Gemini Live call failed: $detail")
                    if (state != LegState.ENDED) hangup(HangupCause.NETWORK_ERROR)
                })
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
            client?.close()
            publishMedia(null)
            transition(LegState.ENDED, cause)
            report(AiCallReport(remote.number, context.name.ifBlank { remote.displayName }, context.role, context.company, started, System.currentTimeMillis(), cause, port.inputFrames, port.outputFrames, inputText, outputText, failureDetail))
            onEnd()
        }
        override fun sendDtmf(digit: Char, durationMs: Int) {}

        private inner class AiPort : MediaPort {
            private val output = ArrayBlockingQueue<ShortArray>(100)
            private var pending = ShortArray(0)
            @Volatile var inputFrames = 0L
            @Volatile var outputFrames = 0L
            override val format = PcmFormat.NARROWBAND
            override val isClockSource = false
            override fun setClockListener(listener: FrameClockListener?) {}
            override fun readFrame(): ShortArray = output.poll()?.also { outputFrames++ } ?: format.silence()
            override fun writeFrame(frame: ShortArray) { inputFrames++; client?.sendPcm8(frame) }
            fun addOutput(bytes24k: ByteArray) {
                val samples = bytes24k.size / 2
                val all = ShortArray(samples) { i -> ((bytes24k[2 * i + 1].toInt() shl 8) or (bytes24k[2 * i].toInt() and 255)).toShort() }
                var pos = 0
                while (pos + 3 * format.frameSamples <= all.size) {
                    val frame = ShortArray(format.frameSamples) { i -> all[pos + i * 3] }
                    pos += 3 * format.frameSamples
                    if (!output.offer(frame)) output.poll()?.let { output.offer(frame) }
                }
            }
        }
    }
}
