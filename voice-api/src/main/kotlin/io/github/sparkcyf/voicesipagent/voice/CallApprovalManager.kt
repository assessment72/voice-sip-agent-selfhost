package io.github.sparkcyf.voicesipagent.voice

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Operations exposed to a local control client. Every operation is approval-gated. */
enum class CallAction { DIAL, ANSWER, REJECT, HANGUP, DTMF }

enum class ApprovalState { PENDING, APPROVED, REJECTED, EXPIRED, CONSUMED }

data class CallActionSpec(
    val action: CallAction,
    val callId: String? = null,
    val target: String? = null,
    val route: String = "auto",
    val dtmf: Char? = null,
) {
    init {
        require(callId == null || callId.isNotBlank())
        require(target == null || target.isNotBlank())
        require(route.isNotBlank())
        if (action == CallAction.DIAL) require(!target.isNullOrBlank()) { "DIAL requires a target" }
        if (action != CallAction.DIAL) require(!callId.isNullOrBlank()) { "$action requires a callId" }
        if (action == CallAction.DTMF) require(dtmf != null && dtmf in "0123456789*#ABCD") { "Invalid DTMF digit" }
        else require(dtmf == null) { "DTMF digit is only valid for DTMF actions" }
    }
}

data class CallApproval(
    val id: String,
    val spec: CallActionSpec,
    val state: ApprovalState,
    val createdAt: Instant,
    val expiresAt: Instant,
    val decidedAt: Instant? = null,
)

data class CallAuditEntry(
    val approvalId: String,
    val action: CallAction,
    val outcome: ApprovalState,
    val timestamp: Instant,
    val detail: String,
)

/**
 * In-memory, single-use approval gate. The caller must invoke consumeApproved with the exact
 * operation spec immediately before touching the call engine. No approval can be reused.
 *
 * Persistence and authenticated transport are deliberately supplied by the Android host layer;
 * this class has no network listener and cannot place calls itself.
 */
class CallApprovalManager(
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = Duration.ofSeconds(90),
    private val maxPending: Int = 32,
) {
    private val approvals = linkedMapOf<String, CallApproval>()
    private val audit = ArrayDeque<CallAuditEntry>()

    init {
        require(!ttl.isZero && !ttl.isNegative) { "ttl must be positive" }
        require(maxPending > 0) { "maxPending must be positive" }
    }

    @Synchronized
    fun request(spec: CallActionSpec): CallApproval {
        expireOutstanding()
        require(approvals.values.count { it.state == ApprovalState.PENDING } < maxPending) {
            "Too many pending call approvals"
        }
        val now = clock.instant()
        val approval = CallApproval(
            id = UUID.randomUUID().toString(),
            spec = spec,
            state = ApprovalState.PENDING,
            createdAt = now,
            expiresAt = now.plus(ttl),
        )
        approvals[approval.id] = approval
        record(approval, "approval requested")
        return approval
    }

    @Synchronized
    fun pending(): List<CallApproval> {
        expireOutstanding()
        return approvals.values.filter { it.state == ApprovalState.PENDING }
    }

    @Synchronized
    fun get(id: String): CallApproval? {
        expireOutstanding()
        return approvals[id]
    }

    @Synchronized
    fun approve(id: String): CallApproval? {
        expireOutstanding()
        val current = approvals[id] ?: return null
        if (current.state != ApprovalState.PENDING) return current
        val updated = current.copy(state = ApprovalState.APPROVED, decidedAt = clock.instant())
        approvals[id] = updated
        record(updated, "explicitly approved")
        return updated
    }

    @Synchronized
    fun reject(id: String): CallApproval? {
        expireOutstanding()
        val current = approvals[id] ?: return null
        if (current.state != ApprovalState.PENDING) return current
        val updated = current.copy(state = ApprovalState.REJECTED, decidedAt = clock.instant())
        approvals[id] = updated
        record(updated, "explicitly rejected")
        return updated
    }

    /**
     * Atomically consumes an approval only if it is approved, unexpired, and exactly matches
     * the operation about to be executed. Returns false for stale, mismatched or replayed requests.
     */
    @Synchronized
    fun consumeApproved(id: String, actual: CallActionSpec): Boolean {
        expireOutstanding()
        val current = approvals[id] ?: return false
        if (current.state != ApprovalState.APPROVED || current.spec != actual) {
            record(current, "execution blocked: approval missing, mismatched or already used")
            return false
        }
        val updated = current.copy(state = ApprovalState.CONSUMED, decidedAt = clock.instant())
        approvals[id] = updated
        record(updated, "approval consumed for exact operation")
        return true
    }

    @Synchronized
    fun auditLog(limit: Int = 100): List<CallAuditEntry> {
        require(limit > 0)
        return audit.toList().takeLast(limit)
    }

    private fun expireOutstanding() {
        val now = clock.instant()
        approvals.entries.toList().forEach { (id, current) ->
            if ((current.state == ApprovalState.PENDING || current.state == ApprovalState.APPROVED) && !now.isBefore(current.expiresAt)) {
                val expired = current.copy(state = ApprovalState.EXPIRED, decidedAt = now)
                approvals[id] = expired
                record(expired, "approval expired")
            }
        }
    }

    private fun record(approval: CallApproval, detail: String) {
        audit.addLast(
            CallAuditEntry(
                approvalId = approval.id,
                action = approval.spec.action,
                outcome = approval.state,
                timestamp = clock.instant(),
                detail = detail,
            ),
        )
        while (audit.size > 500) audit.removeFirst()
    }
}
