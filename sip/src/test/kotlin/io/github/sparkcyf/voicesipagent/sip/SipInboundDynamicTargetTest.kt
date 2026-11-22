package io.github.sparkcyf.voicesipagent.sip

import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.IncomingCallHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SipInboundDynamicTargetTest {
    private val quietLog = object : SipLog {
        override fun info(msg: String) {}
        override fun warn(msg: String, t: Throwable?) {}
    }

    private fun start(peer: DatagramSocket, handler: IncomingCallHandler? = null): SipUserAgent {
        val ua = SipUserAgent(
            SipConfig(
                account = SipAccount("127.0.0.1", peer.localPort, "gateway", "test-secret"),
                localPort = 0,
                serverMode = false,
                acceptOnlyFromServer = true,
            ),
            quietLog,
        )
        handler?.let(ua::setIncomingCallHandler)
        val registering = CountDownLatch(1)
        ua.addStatusListener { _, status ->
            if (status.detail == "registering") registering.countDown()
        }
        ua.start()
        assertTrue("SIP user agent did not start", registering.await(3, TimeUnit.SECONDS))
        // Consume the REGISTER emitted at startup before collecting the INVITE response.
        val packet = DatagramPacket(ByteArray(65535), 65535)
        peer.soTimeout = 3000
        peer.receive(packet)
        assertTrue(SipParser.parse(packet.data, packet.length) is SipRequest)
        return ua
    }

    private fun sendInvite(peer: DatagramSocket, ua: SipUserAgent, to: String, requestUri: String = "sip:gateway@sip2sip.info", withSdp: Boolean = false, extraHeaders: String = "") {
        val sdp = if (withSdp) {
            "v=0\r\n" +
                "o=- 1 1 IN IP4 127.0.0.1\r\n" +
                "s=test\r\n" +
                "c=IN IP4 127.0.0.1\r\n" +
                "t=0 0\r\n" +
                "m=audio 14000 RTP/AVP 0 8\r\n" +
                "a=rtpmap:0 PCMU/8000\r\n" +
                "a=rtpmap:8 PCMA/8000\r\n" +
                "a=sendrecv\r\n"
        } else ""
        val message = "INVITE $requestUri SIP/2.0\r\n" +
            "Via: SIP/2.0/UDP 127.0.0.1:${peer.localPort};branch=z9hG4bK-dynamic\r\n" +
            "From: <sip:caller@sip2sip.info>;tag=caller1\r\n" +
            "To: <$to>\r\n" +
            "Contact: <sip:caller@127.0.0.1:${peer.localPort}>\r\n" +
            "Call-ID: dynamic-invite-${System.nanoTime()}\r\n" +
            "CSeq: 1 INVITE\r\n" +
            extraHeaders +
            (if (withSdp) "Content-Type: application/sdp\r\n" else "") +
            "Content-Length: ${sdp.toByteArray(StandardCharsets.UTF_8).size}\r\n\r\n" +
            sdp
        val bytes = message.toByteArray(StandardCharsets.UTF_8)
        peer.send(DatagramPacket(bytes, bytes.size, InetSocketAddress(InetAddress.getByName("127.0.0.1"), ua.localPort)))
    }

    @Test fun invalidDynamicTargetGets484BeforeAnyCallIsCreated() {
        DatagramSocket(0).use { peer ->
            val ua = start(peer)
            try {
                sendInvite(peer, ua, "sip:77740557@sip2sip.info")
                val packet = DatagramPacket(ByteArray(65535), 65535)
                peer.receive(packet)
                val response = SipParser.parse(packet.data, packet.length) as SipResponse
                assertEquals(484, response.code)
                assertTrue(ua.calls.isEmpty())
            } finally {
                ua.stop(timeoutMs = 50)
            }
        }
    }

    @Test fun plainNineDigitToUserFromLinphoneStyleUriReachesCellularHandlerAsE164() {
        DatagramSocket(0).use { peer ->
            val received = CountDownLatch(1)
            val target = AtomicReference<String?>()
            val ua = start(peer, IncomingCallHandler { leg ->
                target.set(leg.target)
                received.countDown()
                leg.hangup(HangupCause.NORMAL)
            })
            try {
                sendInvite(peer, ua, "sip:737445440@sip.linphone.org", withSdp = true)
                assertTrue("valid Yemeni SIP URI user did not reach the bridge handler", received.await(3, TimeUnit.SECONDS))
                assertEquals("+967737445440", target.get())
            } finally {
                ua.stop(timeoutMs = 50)
            }
        }
    }

    @Test fun xVsaDialHeaderTakesPriorityOverTheToHeader() {
        DatagramSocket(0).use { peer ->
            val received = CountDownLatch(1)
            val target = AtomicReference<String?>()
            val ua = start(peer, IncomingCallHandler { leg ->
                target.set(leg.target)
                received.countDown()
                leg.hangup(HangupCause.NORMAL)
            })
            try {
                sendInvite(
                    peer,
                    ua,
                    "sip:777405578@sip.linphone.org",
                    withSdp = true,
                    extraHeaders = "X-VSA-Dial: sip737445440\r\n",
                )
                assertTrue("X-VSA-Dial INVITE did not reach the bridge handler", received.await(3, TimeUnit.SECONDS))
                assertEquals("+967737445440", target.get())
            } finally {
                ua.stop(timeoutMs = 50)
            }
        }
    }

    @Test fun malformedXVsaDialIsRejectedInsteadOfFallingBackToTo() {
        DatagramSocket(0).use { peer ->
            val ua = start(peer)
            try {
                sendInvite(
                    peer,
                    ua,
                    "sip:777405578@sip.linphone.org",
                    extraHeaders = "X-VSA-Dial: 12345\r\n",
                )
                val packet = DatagramPacket(ByteArray(65535), 65535)
                peer.receive(packet)
                val response = SipParser.parse(packet.data, packet.length) as SipResponse
                assertEquals(484, response.code)
                assertTrue(ua.calls.isEmpty())
            } finally {
                ua.stop(timeoutMs = 50)
            }
        }
    }

    @Test fun fixedRequestUriAndDynamicToReachTheIncomingHandlerAsE164() {
        DatagramSocket(0).use { peer ->
            val received = CountDownLatch(1)
            val target = AtomicReference<String?>()
            val ua = start(peer, IncomingCallHandler { leg ->
                target.set(leg.target)
                received.countDown()
                leg.hangup(HangupCause.NORMAL)
            })
            try {
                sendInvite(peer, ua, "sip:sip777405578@sip2sip.info", withSdp = true)
                assertTrue("valid INVITE did not reach the bridge handler", received.await(3, TimeUnit.SECONDS))
                assertEquals("+967777405578", target.get())
            } finally {
                ua.stop(timeoutMs = 50)
            }
        }
    }
}
