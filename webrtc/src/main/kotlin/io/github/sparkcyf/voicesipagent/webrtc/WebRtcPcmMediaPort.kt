package io.github.sparkcyf.voicesipagent.webrtc

import io.github.sparkcyf.voicesipagent.voice.FrameClockListener
import io.github.sparkcyf.voicesipagent.voice.MediaPort
import io.github.sparkcyf.voicesipagent.voice.PcmFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Adapts a binary WebRTC DataChannel to the gateway's existing 20 ms PCM MediaPort contract.
 *
 * Wire frame = unsigned 64-bit sequence number (big-endian) + one PCM16LE frame. The receiver
 * reorders a small number of out-of-order packets, bounds latency, and inserts silence on underrun.
 * It deliberately does not retransmit stale audio.
 */
class WebRtcPcmMediaPort(
    private val channel: PcmDataChannel,
    override val format: PcmFormat = PcmFormat.NARROWBAND,
    private val maxBufferedFrames: Int = DEFAULT_MAX_BUFFERED_FRAMES,
    private val prebufferFrames: Int = DEFAULT_PREBUFFER_FRAMES,
    private val gapWaitNanos: Long = DEFAULT_GAP_WAIT_NANOS,
    private val clockNanos: () -> Long = System::nanoTime,
) : MediaPort, AutoCloseable {
    private val lock = Any()
    private val outgoingSequence = AtomicLong(0)
    private val incoming = TreeMap<Long, ShortArray>()
    private var expectedSequence: Long? = null
    private var gapStartedAtNanos: Long? = null
    private var closed = false

    init {
        require(maxBufferedFrames >= 2) { "At least two buffered frames are required" }
        require(prebufferFrames in 1..maxBufferedFrames) { "Invalid prebuffer size" }
        require(gapWaitNanos >= 0) { "Gap wait must not be negative" }
        channel.setMessageHandler(::acceptMessage)
    }

    override val isClockSource: Boolean = false

    /** This port is clockless; the cellular/media bridge supplies the frame cadence. */
    override fun setClockListener(listener: FrameClockListener?) = Unit

    override fun readFrame(): ShortArray = synchronized(lock) {
        if (closed) return@synchronized format.silence()

        if (expectedSequence == null) {
            if (incoming.size < prebufferFrames) return@synchronized format.silence()
            expectedSequence = incoming.firstKey()
        }

        val expected = expectedSequence!!
        incoming.remove(expected)?.let { frame ->
            expectedSequence = expected + 1
            gapStartedAtNanos = null
            return@synchronized frame
        }

        val next = incoming.firstKeyOrNull()
        if (next != null && next > expected) {
            val now = clockNanos()
            val gapStarted = gapStartedAtNanos ?: now.also { gapStartedAtNanos = it }
            if (incoming.size >= prebufferFrames || now - gapStarted >= gapWaitNanos) {
                expectedSequence = next + 1
                gapStartedAtNanos = null
                return@synchronized incoming.remove(next) ?: format.silence()
            }
        } else {
            gapStartedAtNanos = null
        }
        format.silence()
    }

    override fun writeFrame(frame: ShortArray) {
        require(frame.size == format.frameSamples) {
            "Expected ${format.frameSamples} samples, received ${frame.size}"
        }
        if (!channel.isOpen) return
        val packet = ByteArray(HEADER_BYTES + format.frameBytes)
        ByteBuffer.wrap(packet, 0, HEADER_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(outgoingSequence.getAndIncrement())
        format.encodeLe(frame, packet, HEADER_BYTES)
        // A full/closing data channel may reject a frame. Do not queue it for later playback.
        channel.send(packet)
    }

    private fun acceptMessage(packet: ByteArray) {
        if (packet.size != HEADER_BYTES + format.frameBytes) return
        val sequence = ByteBuffer.wrap(packet, 0, HEADER_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .long
        if (sequence < 0L) return
        val frame = runCatching { format.decodeLe(packet, HEADER_BYTES) }.getOrNull() ?: return

        synchronized(lock) {
            if (closed) return
            val expected = expectedSequence
            if (expected != null && sequence < expected) return
            if (incoming.containsKey(sequence)) return
            incoming[sequence] = frame

            while (incoming.size > maxBufferedFrames) {
                val removed = incoming.pollFirstEntry() ?: break
                if (expectedSequence?.let { it <= removed.key } == true) {
                    expectedSequence = incoming.firstKeyOrNull()
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            incoming.clear()
            expectedSequence = null
            gapStartedAtNanos = null
        }
        channel.setMessageHandler(null)
    }

    private fun TreeMap<Long, ShortArray>.firstKeyOrNull(): Long? =
        if (isEmpty()) null else firstKey()

    companion object {
        const val HEADER_BYTES = 8
        const val DEFAULT_MAX_BUFFERED_FRAMES = 8
        const val DEFAULT_PREBUFFER_FRAMES = 3
        const val DEFAULT_GAP_WAIT_NANOS = 40_000_000L
    }
}
