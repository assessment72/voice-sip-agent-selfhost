package io.github.sparkcyf.voicesipagent.sip

import io.github.sparkcyf.voicesipagent.sip.media.AudioCodec
import io.github.sparkcyf.voicesipagent.sip.media.RtpParams
import io.github.sparkcyf.voicesipagent.sip.media.RtpSession
import io.github.sparkcyf.voicesipagent.voice.VoiceFormat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class NatMediaTest {
    @Test
    fun symmetricRtpLearnsTranslatedSourcePort() {
        val loopback = InetAddress.getLoopbackAddress()
        DatagramSocket(0).use { sender ->
            val rtpSocket = DatagramSocket(InetSocketAddress(loopback, 0))
            val advertisedPort = (sender.localPort + 1000).coerceAtMost(65535)
            val session = RtpSession(
                rtpSocket,
                RtpParams(
                    remote = InetSocketAddress(loopback, advertisedPort),
                    codec = AudioCodec.PCMU,
                    payloadType = AudioCodec.PCMU.payloadType,
                    telephoneEventPt = null,
                    sendEnabled = true,
                    symmetricRtp = true,
                ),
            ) { _, _ -> }
            try {
                session.start()
                val packetBytes = ByteArray(172)
                packetBytes[0] = 0x80.toByte()
                packetBytes[1] = AudioCodec.PCMU.payloadType.toByte()
                val inbound = DatagramPacket(packetBytes, packetBytes.size, loopback, rtpSocket.localPort)
                sender.send(inbound)

                val deadline = System.currentTimeMillis() + 2_000
                while (session.packetsReceived == 0L && System.currentTimeMillis() < deadline) Thread.sleep(10)
                assertEquals(1L, session.packetsReceived)

                session.writeFrame(ShortArray(VoiceFormat.FRAME_SAMPLES))
                sender.soTimeout = 2_000
                val response = DatagramPacket(ByteArray(2048), 2048)
                sender.receive(response)
                assertEquals(rtpSocket.localPort, response.port)
                assertEquals(AudioCodec.PCMU.payloadType, response.data[1].toInt() and 0x7f)
            } finally {
                session.close()
            }
        }
    }
}
