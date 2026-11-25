package io.github.sparkcyf.voicesipagent.gateway

import io.github.sparkcyf.voicesipagent.voice.FrameClockListener
import io.github.sparkcyf.voicesipagent.voice.MediaPort
import io.github.sparkcyf.voicesipagent.voice.PcmFormat
import io.github.sparkcyf.voicesipagent.voice.VoiceFormat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Cross-connects two media ports. If either is a clock source (the cellular tap), its capture
 * cadence drives both directions so the path stays in a single clock domain; otherwise an
 * internal 20 ms ticker is used.
 */
class MediaBridge(private val a: MediaPort, private val b: MediaPort) {
    init {
        require(a.format == b.format) { "Media formats differ: ${a.format} / ${b.format}" }
    }
    private val master: MediaPort? = if (a.isClockSource) a else if (b.isClockSource) b else null
    private var ticker: ScheduledExecutorService? = null

    @Volatile var frames = 0L
        private set
    @Volatile var peakA = 0
        private set
    @Volatile var peakB = 0
        private set
    @Volatile var pumpFailures = 0L
        private set
    @Volatile var lastPumpFailure: String = ""
        private set

    fun diagnostics(): String = "frames=$frames peakA=$peakA peakB=$peakB pumpFailures=$pumpFailures" +
        (lastPumpFailure.takeIf { it.isNotBlank() }?.let { " lastPumpFailure=$it" } ?: "")

    fun start() {
        val m = master
        if (m != null) {
            m.setClockListener(FrameClockListener { pump() })
        } else {
            ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "media-bridge").apply { isDaemon = true } }
                .also { it.scheduleAtFixedRate(::pump, 0, VoiceFormat.FRAME_MS.toLong(), TimeUnit.MILLISECONDS) }
        }
    }

    fun stop() {
        master?.setClockListener(null)
        ticker?.shutdownNow()
        ticker = null
    }

    private fun pump() {
        try {
            val fa = a.readFrame()
            val fb = b.readFrame()
            b.writeFrame(fa)
            a.writeFrame(fb)
            peakA = maxOf(peakA, VoiceFormat.peak(fa))
            peakB = maxOf(peakB, VoiceFormat.peak(fb))
            frames++
        } catch (t: Throwable) {
            // Keep the clock alive, but retain evidence instead of silently hiding dropped ticks.
            pumpFailures++
            lastPumpFailure = "${t.javaClass.simpleName}: ${t.message.orEmpty()}".take(180)
        }
    }

    companion object {
        /** Echo test: whatever [port] captures is sent straight back to it. */
        fun loopback(port: MediaPort): MediaBridge = MediaBridge(port, LoopbackPort(port.format))
    }

    /** Clockless port that returns what was last written to it (one frame of delay). */
    private class LoopbackPort(override val format: PcmFormat) : MediaPort {
        @Volatile private var last = format.silence()
        override val isClockSource = false
        override fun setClockListener(listener: FrameClockListener?) {}
        override fun readFrame(): ShortArray = last
        override fun writeFrame(frame: ShortArray) { last = frame }
    }
}
