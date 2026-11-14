package io.github.sparkcyf.voicesipagent.webrtc

import io.github.sparkcyf.voicesipagent.voice.PcmFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WebRtcPcmMediaPortTest {
    @Test
    fun writesSequenceAndPcm16LePayload() {
        val channel = FakeChannel()
        val port = WebRtcPcmMediaPort(channel)
        val frame = ShortArray(160) { (it - 80).toShort() }

        port.writeFrame(frame)

        val packet = channel.sent.single()
        assertEquals(8 + 320, packet.size)
        assertEquals(0L, ByteBuffer.wrap(packet, 0, 8).order(ByteOrder.BIG_ENDIAN).long)
        assertArrayEquals(frame, PcmFormat.NARROWBAND.decodeLe(packet, 8))
        port.close()
    }

    @Test
    fun prebuffersAndReadsFramesInSequence() {
        val channel = FakeChannel()
        val port = WebRtcPcmMediaPort(channel)
        channel.deliver(packet(0, 10))
        channel.deliver(packet(1, 11))
        channel.deliver(packet(2, 12))

        assertEquals(10.toShort(), port.readFrame().first())
        assertEquals(11.toShort(), port.readFrame().first())
        assertEquals(12.toShort(), port.readFrame().first())
        port.close()
    }

    @Test
    fun reordersOutOfOrderFrames() {
        val channel = FakeChannel()
        val port = WebRtcPcmMediaPort(channel)
        channel.deliver(packet(2, 12))
        channel.deliver(packet(0, 10))
        channel.deliver(packet(1, 11))

        assertEquals(10.toShort(), port.readFrame().first())
        assertEquals(11.toShort(), port.readFrame().first())
        assertEquals(12.toShort(), port.readFrame().first())
        port.close()
    }

    @Test
    fun ignoresDuplicateAndMalformedPackets() {
        val channel = FakeChannel()
        val port = WebRtcPcmMediaPort(channel)
        channel.deliver(byteArrayOf(1, 2, 3))
        channel.deliver(packet(0, 10))
        channel.deliver(packet(0, 99))
        channel.deliver(packet(1, 11))
        channel.deliver(packet(2, 12))

        assertEquals(10.toShort(), port.readFrame().first())
        assertEquals(11.toShort(), port.readFrame().first())
        assertEquals(12.toShort(), port.readFrame().first())
        port.close()
    }

    @Test
    fun returnsSilenceWhenBufferIsStarved() {
        val channel = FakeChannel()
        val port = WebRtcPcmMediaPort(channel)
        assertArrayEquals(ShortArray(160), port.readFrame())
        channel.deliver(packet(0, 10))
        channel.deliver(packet(1, 11))
        assertArrayEquals(ShortArray(160), port.readFrame())
        port.close()
    }

    @Test
    fun dropsOldestFramesWhenBufferWouldExceedBound() {
        val channel = FakeChannel()
        val port = WebRtcPcmMediaPort(channel, maxBufferedFrames = 4, prebufferFrames = 2)
        repeat(6) { channel.deliver(packet(it.toLong(), it + 10)) }

        assertEquals(12.toShort(), port.readFrame().first())
        port.close()
    }

    @Test
    fun skipsMissingFrameAfterJitterWait() {
        val channel = FakeChannel()
        var now = 0L
        val port = WebRtcPcmMediaPort(
            channel,
            prebufferFrames = 2,
            gapWaitNanos = 40_000_000L,
            clockNanos = { now },
        )
        channel.deliver(packet(0, 10))
        channel.deliver(packet(1, 11))
        channel.deliver(packet(3, 13))
        channel.deliver(packet(4, 14))

        assertEquals(10.toShort(), port.readFrame().first())
        assertEquals(11.toShort(), port.readFrame().first())
        assertArrayEquals(ShortArray(160), port.readFrame())
        now += 41_000_000L
        assertEquals(13.toShort(), port.readFrame().first())
        port.close()
    }

    @Test
    fun closeDetachesHandlerAndMakesPortSilent() {
        val channel = FakeChannel()
        val port = WebRtcPcmMediaPort(channel)
        port.close()
        assertFalse(port.isClockSource)
        assertTrue(channel.handler == null)
        channel.deliver(packet(0, 10))
        assertArrayEquals(ShortArray(160), port.readFrame())
    }

    private fun packet(sequence: Long, sample: Int): ByteArray {
        val frame = ShortArray(160) { sample.toShort() }
        val bytes = ByteArray(8 + 320)
        ByteBuffer.wrap(bytes, 0, 8).order(ByteOrder.BIG_ENDIAN).putLong(sequence)
        PcmFormat.NARROWBAND.encodeLe(frame, bytes, 8)
        return bytes
    }

    private class FakeChannel : PcmDataChannel {
        override var isOpen: Boolean = true
        val sent = mutableListOf<ByteArray>()
        var handler: ((ByteArray) -> Unit)? = null
        override fun send(message: ByteArray): Boolean {
            if (!isOpen) return false
            sent += message.copyOf()
            return true
        }
        override fun setMessageHandler(handler: ((ByteArray) -> Unit)?) {
            this.handler = handler
        }
        fun deliver(message: ByteArray) { handler?.invoke(message) }
    }
}
