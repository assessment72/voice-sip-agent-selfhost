package io.github.sparkcyf.voicesipagent.app

import io.github.sparkcyf.voicesipagent.voice.FrameClockListener
import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.LegState
import io.github.sparkcyf.voicesipagent.voice.MediaPort
import io.github.sparkcyf.voicesipagent.voice.Party
import io.github.sparkcyf.voicesipagent.voice.PcmFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRtcCallEndpointTest {
    @Test
    fun outboundCellularCallRingsBrowserAndConnectsAfterAcceptance() {
        val signals = mutableListOf<Pair<String, String?>>()
        val endpoint = WebRtcCallEndpoint(FakeMediaPort()) { type, target -> signals += type to target }

        val leg = endpoint.dial("phone", Party("123", "Cellular"))
        assertEquals(LegState.PROCEEDING, leg.state)
        assertEquals("ringing", signals.single().first)

        endpoint.onRemoteSignal("accepted")
        assertEquals(LegState.CONNECTED, leg.state)
        assertTrue(leg.media != null)

        leg.hangup(HangupCause.NORMAL)
        assertEquals(LegState.ENDED, leg.state)
        assertEquals("hangup", signals.last().first)
        endpoint.close()
    }

    @Test
    fun browserDialCreatesInboundLegAndBridgeAnswerSignalsAcceptance() {
        val signals = mutableListOf<String>()
        val endpoint = WebRtcCallEndpoint(FakeMediaPort()) { type, _ -> signals += type }
        var inbound: io.github.sparkcyf.voicesipagent.voice.CallLeg? = null
        endpoint.setIncomingCallHandler { inbound = it }

        endpoint.onRemoteSignal("dial", "+967711234567")

        val leg = requireNotNull(inbound)
        assertEquals(LegState.PROCEEDING, leg.state)
        assertEquals("+967711234567", leg.target)
        leg.answer()
        assertEquals(LegState.CONNECTED, leg.state)
        assertTrue("accepted" in signals)

        endpoint.onRemoteSignal("hangup")
        assertEquals(LegState.ENDED, leg.state)
        endpoint.close()
    }

    @Test
    fun endpointRejectsConcurrentCallAndClosesActiveLeg() {
        val endpoint = WebRtcCallEndpoint(FakeMediaPort()) { _, _ -> }
        val first = endpoint.dial("phone", Party("123"))
        val second = endpoint.dial("phone", Party("456"))
        assertEquals(LegState.ENDED, second.state)
        assertEquals(HangupCause.BUSY, second.endCause)

        endpoint.close()
        assertEquals(LegState.ENDED, first.state)
        assertFalse(endpoint.status.ready)
    }

    private class FakeMediaPort : MediaPort {
        override val format = PcmFormat.NARROWBAND
        override val isClockSource = false
        override fun setClockListener(listener: FrameClockListener?) = Unit
        override fun readFrame() = format.silence()
        override fun writeFrame(frame: ShortArray) = Unit
    }
}
