package io.github.sparkcyf.voicesipagent.voice

data class CallCommandResult(
    val executed: Boolean,
    val detail: String,
    val call: ManagedCall? = null,
)

fun interface CallEndpointRouter {
    /** Resolve a named route or "auto" to a ready endpoint. */
    fun resolve(route: String, endpoints: List<CallEndpoint>): CallEndpoint?
}

/** Deterministic default router: explicit endpoint name first, otherwise first ready endpoint. */
class ReadyEndpointRouter : CallEndpointRouter {
    override fun resolve(route: String, endpoints: List<CallEndpoint>): CallEndpoint? {
        val ready = endpoints.filter { it.status.ready }
        return if (route.equals("auto", ignoreCase = true)) ready.firstOrNull()
        else ready.firstOrNull { it.name.equals(route, ignoreCase = true) }
    }
}

/**
 * Approval-gated facade over the existing CallEndpoint/CallLeg abstractions.
 * It never opens sockets and never bypasses CallApprovalManager.
 */
class ApprovedCallController(
    private val endpoints: List<CallEndpoint>,
    val registry: CallRegistry = CallRegistry(),
    val approvals: CallApprovalManager = CallApprovalManager(),
    private val router: CallEndpointRouter = ReadyEndpointRouter(),
    private val caller: Party = Party("local-control", "Local control"),
) {
    init { require(endpoints.isNotEmpty()) { "At least one call endpoint is required" } }

    fun request(spec: CallActionSpec): CallApproval = approvals.request(spec)
    fun pendingApprovals(): List<CallApproval> = approvals.pending()
    fun approve(approvalId: String): CallApproval? = approvals.approve(approvalId)
    fun reject(approvalId: String): CallApproval? = approvals.reject(approvalId)
    fun activeCalls(): List<ManagedCall> = registry.active()
    fun callHistory(limit: Int = 100): List<ManagedCall> = registry.history(limit)

    @Synchronized
    fun execute(approvalId: String): CallCommandResult {
        val approval = approvals.get(approvalId)
            ?: return CallCommandResult(false, "approval not found or expired")
        if (approval.state != ApprovalState.APPROVED) {
            return CallCommandResult(false, "explicit approval is required")
        }
        val spec = approval.spec
        val leg = spec.callId?.let { registry.get(it)?.let { _ -> findLeg(it) } }

        // Preflight before consuming the one-time approval.
        val endpoint = if (spec.action == CallAction.DIAL) {
            router.resolve(spec.route, endpoints)
                ?: return CallCommandResult(false, "no ready endpoint for route '${spec.route}'")
        } else null

        if (spec.action != CallAction.DIAL) {
            if (leg == null) return CallCommandResult(false, "call not found")
            if (leg.state == LegState.ENDED) return CallCommandResult(false, "call has already ended")
            if (spec.action in setOf(CallAction.ANSWER, CallAction.REJECT) &&
                leg.direction != LegDirection.INBOUND) {
                return CallCommandResult(false, "only inbound calls can be answered or rejected")
            }
            if (spec.action == CallAction.DTMF && leg.state != LegState.CONNECTED) {
                return CallCommandResult(false, "DTMF requires a connected call")
            }
        }

        if (!approvals.consumeApproved(approvalId, spec)) {
            return CallCommandResult(false, "approval is stale, mismatched or already consumed")
        }

        return try {
            when (spec.action) {
                CallAction.DIAL -> {
                    val created = endpoint!!.dial(spec.target!!, caller)
                    val snapshot = registry.track(created)
                    CallCommandResult(true, "dial request submitted", snapshot)
                }
                CallAction.ANSWER -> {
                    leg!!.answer()
                    CallCommandResult(true, "answer request submitted", registry.track(leg))
                }
                CallAction.REJECT -> {
                    leg!!.hangup(HangupCause.REJECTED)
                    CallCommandResult(true, "reject request submitted", registry.track(leg))
                }
                CallAction.HANGUP -> {
                    leg!!.hangup(HangupCause.NORMAL)
                    CallCommandResult(true, "hangup request submitted", registry.track(leg))
                }
                CallAction.DTMF -> {
                    leg!!.sendDtmf(spec.dtmf!!)
                    CallCommandResult(true, "DTMF request submitted", registry.track(leg))
                }
            }
        } catch (failure: Exception) {
            CallCommandResult(false, "call operation failed: ${failure.javaClass.simpleName}")
        }
    }

    /** Resolve the live object only from the controller's private registry. */
    private fun findLeg(callId: String): CallLeg? = registryLegs[callId]

    // Kept separate from public snapshots: remote clients never receive CallLeg references.
    private val registryLegs = LinkedHashMap<String, CallLeg>()

    @Synchronized
    fun trackIncoming(leg: CallLeg): ManagedCall {
        registryLegs[leg.id] = leg
        return registry.track(leg)
    }
}
