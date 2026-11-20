package io.github.sparkcyf.voicesipagent.voice

import org.junit.Assert.*
import org.junit.Test

class CallRegistryTest {
    private class FakeLeg(id: String) : AbstractCallLeg(
        id = id,
        kind = "sip",
        direction = LegDirection.INBOUND,
        remote = Party("+967700000000", "caller"),
        target = "gateway",
    ) {
        override fun ring() { transition(LegState.RINGING) }
        override fun answer() { transition(LegState.CONNECTED) }
        override fun hangup(cause: HangupCause) { transition(LegState.ENDED, cause) }
        override fun sendDtmf(digit: Char, durationMs: Int) = Unit
    }

    @Test fun registryTracksStateChangesAndRetainsEndedCall() {
        val registry = CallRegistry()
        val leg = FakeLeg("call-123")

        assertEquals(LegState.NEW, registry.track(leg).state)
        leg.ring()
        assertEquals(LegState.RINGING, registry.get("call-123")?.state)
        leg.hangup(HangupCause.REJECTED)

        assertTrue(registry.active().isEmpty())
        assertEquals(HangupCause.REJECTED, registry.get("call-123")?.endCause)
        assertTrue(registry.forgetEnded("call-123"))
        assertNull(registry.get("call-123"))
    }

    @Test fun duplicateIdCannotReplaceDifferentCallInstance() {
        val registry = CallRegistry()
        registry.track(FakeLeg("same-id"))
        try {
            registry.track(FakeLeg("same-id"))
            fail("Expected duplicate call id to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
