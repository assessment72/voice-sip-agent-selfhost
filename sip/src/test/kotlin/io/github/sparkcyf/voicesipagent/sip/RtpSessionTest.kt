package io.github.sparkcyf.voicesipagent.sip

import io.github.sparkcyf.voicesipagent.sip.media.RtpSession
import io.github.sparkcyf.voicesipagent.sip.media.AudioCodec
import io.github.sparkcyf.voicesipagent.sip.media.RtpParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException

class RtpSessionTest {
    @Test
    fun bindSocketUsesEvenPortInsideRequestedRange() {
        RtpSession.bindSocket(null, 12000..12006).use { socket ->
            assertTrue(socket.localPort in 12000..12006)
            assertEquals(0, socket.localPort % 2)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun bindSocketRejectsInvalidRange() {
        RtpSession.bindSocket(null, 20000..10000)
    }

    @Test(expected = SocketException::class)
    fun bindSocketReportsExhaustedRange() {
        RtpSession.bindSocket(null, 1..1)
    }

    @Test
    fun acceptsRtpFromAdvertisedPortAndRejectsOtherPort() {
        DatagramSocket(0).use { receiver ->
            DatagramSocket(0).use { expectedSender ->
                DatagramSocket(0).use { wrongSender ->
                    val params = RtpParams(
                        remote = InetSocketAddress(InetAddress.getLoopbackAddress(), expectedSender.localPort),
                        codec = AudioCodec.PCMU,
                        payloadType = AudioCodec.PCMU.payloadType,
                        telephoneEventPt = null,
                        sendEnabled = false,
                    )
                    val session = RtpSession(receiver, params) { _, _ -> }
                    try {
                        session.start()
                        val packet = rtpPacket()
                        wrongSender.send(DatagramPacket(packet, packet.size, InetAddress.getLoopbackAddress(), receiver.localPort))
                        Thread.sleep(100)
                        assertEquals(0L, session.packetsReceived)

                        expectedSender.send(DatagramPacket(packet, packet.size, InetAddress.getLoopbackAddress(), receiver.localPort))
                        assertTrue(waitUntil { session.packetsReceived == 1L })
                    } finally {
                        session.close()
                    }
                }
            }
        }
    }

    private fun rtpPacket(): ByteArray = ByteArray(12 + 160).also { packet ->
        packet[0] = 0x80.toByte()
        packet[1] = AudioCodec.PCMU.payloadType.toByte()
        packet[7] = 1
        packet[11] = 2
        AudioCodec.PCMU.encode(ShortArray(160), packet, 12)
    }

    private fun waitUntil(timeoutMs: Long = 2_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }
}
