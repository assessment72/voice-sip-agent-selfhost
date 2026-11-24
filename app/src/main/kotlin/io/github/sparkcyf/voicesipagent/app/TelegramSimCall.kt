package io.github.sparkcyf.voicesipagent.app

import io.github.sparkcyf.voicesipagent.gateway.MediaBridge
import io.github.sparkcyf.voicesipagent.voice.CallEndpoint
import io.github.sparkcyf.voicesipagent.voice.CallLeg
import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.LegState
import io.github.sparkcyf.voicesipagent.voice.Party
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A Telegram-authorized SIM call bridged to the registered SIP phone. */
internal class TelegramSimCall(
    private val cellular: CallEndpoint,
    private val sip: CallEndpoint,
    private val number: String,
    private val sipTarget: String,
    private val notify: (String) -> Unit,
    private val batteryStatus: () -> String,
    private val onFinished: (TelegramCallRecord) -> Unit,
) {
    private val lock = Any()
    private val id = UUID.randomUUID().toString().take(8)
    private val startedAtMs = System.currentTimeMillis()
    private val timeout = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "telegram-simcall-timeout").apply { isDaemon = true }
    }
    @Volatile private var ended = false
    private var sipStarting = false
    private var sipLeg: CallLeg? = null
    private var simLeg: CallLeg? = null
    private var mediaBridge: MediaBridge? = null
    private var answeredAtMs: Long? = null
    private var bridgeStarted = false

    fun start() {
        notify("مكالمة #$id: بدأ الاتصال عبر SIM إلى ${maskNumber(number)}؛ بانتظار الرنين.")
        timeout.schedule({ finish(HangupCause.NO_ANSWER) }, 120, TimeUnit.SECONDS)
        val leg = cellular.dial(number, Party(null))
        synchronized(lock) { simLeg = leg }
        leg.addListener(listener)
        inspect(leg)
    }

    fun cancel() = finish(HangupCause.CANCELLED)

    private val listener = object : CallLeg.Listener {
        override fun onStateChanged(leg: CallLeg, state: LegState) = onState(leg, state)
        override fun onMediaChanged(leg: CallLeg, media: io.github.sparkcyf.voicesipagent.voice.MediaPort?) = tryStartBridge()
    }

    private fun inspect(leg: CallLeg) {
        when (leg.state) {
            LegState.RINGING, LegState.CONNECTED -> onState(leg, leg.state)
            LegState.ENDED -> finish(leg.endCause ?: HangupCause.NETWORK_ERROR)
            else -> Unit
        }
    }

    private fun onState(leg: CallLeg, state: LegState) {
        if (synchronized(lock) { ended }) return
        if (leg === simLeg) {
            when (state) {
                LegState.RINGING -> {
                    notify("مكالمة #$id: الرقم المطلوب يرن عبر SIM؛ جارٍ الاتصال بهاتف SIP المسجّل.")
                    startSipLeg()
                }
                LegState.CONNECTED -> {
                    synchronized(lock) { if (answeredAtMs == null) answeredAtMs = System.currentTimeMillis() }
                    notify("مكالمة #$id: تم الرد على اتصال SIM؛ بانتظار إجابة هاتف SIP.")
                    startSipLeg()
                    tryStartBridge()
                }
                LegState.ENDED -> finish(leg.endCause ?: HangupCause.NETWORK_ERROR)
                else -> Unit
            }
        } else if (leg === sipLeg) {
            when (state) {
                LegState.RINGING -> notify("مكالمة #$id: هاتف SIP يرن.")
                LegState.CONNECTED -> {
                    notify("مكالمة #$id: أجاب هاتف SIP؛ جارٍ إعداد الصوت.")
                    tryStartBridge()
                }
                LegState.ENDED -> finish(leg.endCause ?: HangupCause.NETWORK_ERROR)
                else -> Unit
            }
        }
    }

    private fun startSipLeg() {
        val shouldStart = synchronized(lock) {
            if (ended || sipStarting || sipLeg != null) false else { sipStarting = true; true }
        }
        if (!shouldStart) return
        val leg = sip.dial(sipTarget, Party(number))
        val hangImmediately = synchronized(lock) {
            if (ended) true else { sipLeg = leg; false }
        }
        if (hangImmediately) {
            leg.hangup(HangupCause.CANCELLED)
            return
        }
        leg.addListener(listener)
        inspect(leg)
    }

    private fun tryStartBridge() {
        val ports = synchronized(lock) {
            if (ended || bridgeStarted) return
            val a = simLeg ?: return
            val b = sipLeg ?: return
            if (a.state != LegState.CONNECTED || b.state != LegState.CONNECTED) return
            val ma = a.media ?: return
            val mb = b.media ?: return
            bridgeStarted = true
            ma to mb
        }
        try {
            val bridge = MediaBridge(ports.first, ports.second)
            synchronized(lock) {
                if (ended) return@synchronized
                mediaBridge = bridge
            }
            if (synchronized(lock) { ended }) bridge.stop() else {
                bridge.start()
                notify("مكالمة #$id: تم ربط الصوت بين SIM وSIP.")
            }
        } catch (_: Exception) {
            finish(HangupCause.INTERNAL_ERROR)
        }
    }

    private fun finish(cause: HangupCause) {
        val finishState = synchronized(lock) {
            if (ended) return
            ended = true
            val a = simLeg
            val b = sipLeg
            val bridge = mediaBridge
            val audioFrames = bridge?.frames ?: 0L
            val peakA = bridge?.peakA ?: 0
            val peakB = bridge?.peakB ?: 0
            val audioStatus = formatAudioStatus(bridge != null, audioFrames, peakA, peakB)
            val record = TelegramCallRecord(
                id = id,
                number = number,
                startedAtMs = startedAtMs,
                answeredAtMs = answeredAtMs,
                endedAtMs = System.currentTimeMillis(),
                result = resultText(cause),
                audioStatus = audioStatus,
                batteryStatus = batteryStatus(),
            )
            Triple(Triple(a, b, bridge), record, cause)
        }
        timeout.shutdownNow()
        finishState.first.third?.stop()
        finishState.first.first?.let { if (it.state != LegState.ENDED) it.hangup(cause) }
        finishState.first.second?.let { if (it.state != LegState.ENDED) it.hangup(cause) }
        val record = finishState.second
        notify("انتهت مكالمة #${record.id}: ${record.result}؛ المدة بعد رد SIM: ${formatDuration(record.durationSeconds)}.\nحالة الصوت: ${record.audioStatus}.\n🔋 ${record.batteryStatus}")
        onFinished(record)
    }

    private fun resultText(cause: HangupCause) = when (cause) {
        HangupCause.NORMAL -> "انتهت"
        HangupCause.BUSY -> "مشغول"
        HangupCause.NO_ANSWER -> "لا يوجد رد / انتهت المهلة"
        HangupCause.REJECTED -> "مرفوضة"
        HangupCause.UNAVAILABLE -> "غير متاحة"
        HangupCause.NOT_FOUND -> "رقم غير صالح أو غير موجود"
        HangupCause.FORBIDDEN -> "مرفوضة للصلاحيات أو سياسة الاتصال"
        HangupCause.CANCELLED -> "ألغيت"
        HangupCause.NETWORK_ERROR -> "خطأ شبكة"
        HangupCause.INTERNAL_ERROR -> "خطأ داخلي"
    }

    companion object {
        fun formatAudioStatus(bridgeStarted: Boolean, frames: Long, peakA: Int, peakB: Int): String = when {
            !bridgeStarted -> "مسار الصوت لم يبدأ"
            frames <= 0L -> "تم إعداد الجسر لكن لم تُسجل إطارات صوت"
            peakA > 0 && peakB > 0 -> "إطارات=$frames؛ نشاط صوت مرصود بالاتجاهين (مؤشران=$peakA/$peakB)"
            else -> "إطارات=$frames؛ الجسر قائم، ولم يُرصد نشاط صوت في أحد الاتجاهين (مؤشران=$peakA/$peakB)"
        }

        fun maskNumber(value: String): String {
            val clean = value.filter { it.isDigit() || it == '+' }
            if (clean.length <= 4) return clean
            return "…" + clean.takeLast(4)
        }
        fun formatDuration(seconds: Long): String {
            val s = seconds.coerceAtLeast(0)
            return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        }
    }
}

data class TelegramCallRecord(
    val id: String,
    val number: String,
    val startedAtMs: Long,
    val answeredAtMs: Long?,
    val endedAtMs: Long,
    val result: String,
    val audioStatus: String = "غير مسجل",
    val batteryStatus: String = "غير متاح",
) {
    val durationSeconds: Long
        get() = answeredAtMs?.let { ((endedAtMs - it).coerceAtLeast(0) / 1000L) } ?: 0L
}
