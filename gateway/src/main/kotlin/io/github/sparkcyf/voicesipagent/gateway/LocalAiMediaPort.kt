package io.github.sparkcyf.voicesipagent.gateway

import io.github.sparkcyf.voicesipagent.voice.MediaPort
import io.github.sparkcyf.voicesipagent.voice.PcmFormat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Device-local speech engine boundary. Implementations must keep inference off the RTP/bridge
 * thread: offerCallerFrame() must be non-blocking and pollAssistantFrame() must return immediately.
 *
 * The implementation is expected to own bounded input/output queues and to run VAD, ASR, local
 * dialogue inference and TTS on dedicated worker threads. No network API is part of this contract.
 */
interface LocalSpeechEngine : AutoCloseable {
    val isReady: Boolean
    fun offerCallerFrame(frame: ShortArray): Boolean
    fun pollAssistantFrame(): ShortArray?
    override fun close()
}

/**
 * SIP-facing full-duplex media port for an on-device AI agent.
 * MediaBridge writes caller audio into this port and reads assistant audio from it.
 * The port performs no inference, resampling, codec conversion, disk I/O or networking.
 * Frames are mono PCM16 at format (8 kHz by default), exactly one 20 ms frame each.
 */
class LocalAiMediaPort(
    private val engine: LocalSpeechEngine,
    override val format: PcmFormat = PcmFormat.NARROWBAND,
) : MediaPort, AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val callerFrames = AtomicLong(0)
    private val assistantFrames = AtomicLong(0)
    private val droppedCallerFrames = AtomicLong(0)
    private val missingAssistantFrames = AtomicLong(0)

    val stats: Stats
        get() = Stats(callerFrames.get(), assistantFrames.get(), droppedCallerFrames.get(),
            missingAssistantFrames.get(), !closed.get() && engine.isReady)

    override val isClockSource: Boolean = false
    override fun setClockListener(listener: io.github.sparkcyf.voicesipagent.voice.FrameClockListener?) {}

    override fun readFrame(): ShortArray {
        if (closed.get()) return format.silence()
        val frame = runCatching { engine.pollAssistantFrame() }.getOrNull()
        if (frame == null) {
            missingAssistantFrames.incrementAndGet()
            return format.silence()
        }
        require(frame.size == format.frameSamples) {
            "Local AI returned ${frame.size} samples; expected ${format.frameSamples}"
        }
        assistantFrames.incrementAndGet()
        return frame.copyOf()
    }

    override fun writeFrame(frame: ShortArray) {
        if (closed.get()) return
        require(frame.size == format.frameSamples) {
            "SIP supplied ${frame.size} samples; expected ${format.frameSamples}"
        }
        val accepted = runCatching { engine.offerCallerFrame(frame.copyOf()) }.getOrDefault(false)
        if (accepted) callerFrames.incrementAndGet() else droppedCallerFrames.incrementAndGet()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) engine.close()
    }

    data class Stats(
        val callerFrames: Long,
        val assistantFrames: Long,
        val droppedCallerFrames: Long,
        val missingAssistantFrames: Long,
        val ready: Boolean,
    )
}
