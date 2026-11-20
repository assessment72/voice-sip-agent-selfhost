package io.github.sparkcyf.voicesipagent.voice

import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class CallApprovalManagerTest {
    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    @Test fun approvalMustMatchExactOperationAndCanOnlyBeConsumedOnce() {
        val clock = MutableClock(Instant.parse("2026-11-20T10:00:00Z"))
        val manager = CallApprovalManager(clock)
        val spec = CallActionSpec(CallAction.DIAL, target = "+967700000000", route = "auto")
        val request = manager.request(spec)

        assertEquals(ApprovalState.PENDING, request.state)
        assertEquals(ApprovalState.APPROVED, manager.approve(request.id)?.state)
        assertFalse(manager.consumeApproved(request.id, spec.copy(target = "+967711111111")))
        assertTrue(manager.consumeApproved(request.id, spec))
        assertFalse(manager.consumeApproved(request.id, spec))
        assertEquals(ApprovalState.CONSUMED, manager.get(request.id)?.state)
    }

    @Test fun rejectedApprovalCannotBeConsumed() {
        val manager = CallApprovalManager()
        val spec = CallActionSpec(CallAction.HANGUP, callId = "call-1")
        val request = manager.request(spec)

        assertEquals(ApprovalState.REJECTED, manager.reject(request.id)?.state)
        assertFalse(manager.consumeApproved(request.id, spec))
    }

    @Test fun pendingApprovalExpiresAndCannotBeApproved() {
        val clock = MutableClock(Instant.parse("2026-11-20T10:00:00Z"))
        val manager = CallApprovalManager(clock, Duration.ofSeconds(30))
        val request = manager.request(CallActionSpec(CallAction.ANSWER, callId = "call-2"))

        clock.now = clock.now.plusSeconds(30)
        assertEquals(ApprovalState.EXPIRED, manager.get(request.id)?.state)
        assertEquals(ApprovalState.EXPIRED, manager.approve(request.id)?.state)
        assertFalse(manager.consumeApproved(request.id, request.spec))
    }

    @Test fun pendingQueueIsBoundedAndAuditIsRecorded() {
        val manager = CallApprovalManager(maxPending = 1)
        manager.request(CallActionSpec(CallAction.REJECT, callId = "call-3"))

        try {
            manager.request(CallActionSpec(CallAction.HANGUP, callId = "call-3"))
            fail("Expected pending queue limit")
        } catch (_: IllegalArgumentException) {
            // Expected: a caller cannot flood the local approval queue.
        }
        assertTrue(manager.auditLog().any { it.detail == "approval requested" })
    }

    @Test fun approvedRequestsCountTowardOutstandingLimit() {
        val manager = CallApprovalManager(maxPending = 1)
        val first = manager.request(CallActionSpec(CallAction.HANGUP, callId = "call-10"))
        manager.approve(first.id)

        try {
            manager.request(CallActionSpec(CallAction.HANGUP, callId = "call-11"))
            fail("Expected approved request to count as outstanding")
        } catch (_: IllegalArgumentException) {
            // An approved but not yet executed operation still occupies a slot.
        }
    }

    @Test fun callTargetRejectsShellControlCharacters() {
        try {
            CallActionSpec(CallAction.DIAL, target = "+967700000000;reboot")
            fail("Expected unsafe call target to be rejected")
        } catch (_: IllegalArgumentException) {
            // The target is passed through the ADB command line and must remain a single safe token.
        }
    }

    @Test fun dtmfRequiresValidDigitAndCallId() {
        try {
            CallActionSpec(CallAction.DTMF, callId = "call-4", dtmf = 'Z')
            fail("Expected invalid DTMF to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
