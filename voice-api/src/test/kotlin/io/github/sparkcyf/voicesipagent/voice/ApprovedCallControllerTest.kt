package io.github.sparkcyf.voicesipagent.voice

import org.junit.Assert.*
import org.junit.Test

class ApprovedCallControllerTest {
    private class FakeLeg(
        id: String,
        direction: LegDirection,
        remote: Party,
    ) : AbstractCallLeg(id, "fake", direction, remote, null) {
        override fun ring() { transition(LegState.RINGING) }
        override fun answer() { transition(LegState.CONNECTED) }
        override fun hangup(cause: HangupCause) { transition(LegState.ENDED, cause) }
        override fun sendDtmf(digit: Char, durationMs: Int) {
            check(state == LegState.CONNECTED)
        }
    }

    private class FakeEndpoint : CallEndpoint {
        override val name = "cellular"
        override val status = EndpointStatus(true, "ready")
        private var incoming: IncomingCallHandler? = null
        var lastDialed: FakeLeg? = null

        override fun setIncomingCallHandler(handler: IncomingCallHandler?) { incoming = handler }
        override fun addStatusListener(listener: EndpointStatusListener) = Unit
        override fun removeStatusListener(listener: EndpointStatusListener) = Unit
        override fun dial(target: String, caller: Party): CallLeg =
            FakeLeg("out-${target}", LegDirection.OUTBOUND, Party(target)).also { lastDialed = it }
    }

    @Test fun dialRequiresApprovalAndThenTracksReturnedLeg() {
        val endpoint = FakeEndpoint()
        val controller = ApprovedCallController(listOf(endpoint))
        val request = controller.request(CallActionSpec(CallAction.DIAL, target = "+967700000000"))

        assertFalse(controller.execute(request.id).executed)
        controller.approve(request.id)
        val result = controller.execute(request.id)

        assertTrue(result.executed)
        assertEquals("out-+967700000000", result.call?.id)
        assertEquals(1, controller.activeCalls().size)
    }

    @Test fun inboundAnswerRequiresApproval() {
        val endpoint = FakeEndpoint()
        val controller = ApprovedCallController(listOf(endpoint))
        val incoming = FakeLeg("in-1", LegDirection.INBOUND, Party("+967700000001"))
        controller.trackIncoming(incoming)
        val request = controller.request(CallActionSpec(CallAction.ANSWER, callId = incoming.id))

        assertFalse(controller.execute(request.id).executed)
        controller.approve(request.id)
        assertTrue(controller.execute(request.id).executed)
        assertEquals(LegState.CONNECTED, controller.activeCalls().single().state)
    }

    @Test fun approvedDtmfIsBoundToCallAndDigit() {
        val endpoint = FakeEndpoint()
        val controller = ApprovedCallController(listOf(endpoint))
        val connected = FakeLeg("in-2", LegDirection.INBOUND, Party("+967700000002"))
        connected.answer()
        controller.trackIncoming(connected)
        val request = controller.request(
            CallActionSpec(CallAction.DTMF, callId = connected.id, dtmf = '5'),
        )
        controller.approve(request.id)

        assertTrue(controller.execute(request.id).executed)
        assertFalse(controller.execute(request.id).executed)
    }
}
