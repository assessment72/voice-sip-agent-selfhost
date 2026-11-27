package io.github.sparkcyf.voicesipagent.app

/**
 * Human-review gate for a schedule whose external call/SMS outcome is unknown.
 * This policy deliberately does not query providers or execute side effects.
 */
internal object ScheduleReconciliationPolicy {
    const val STATUS = "needs_reconciliation"

    enum class Action { MARK_COMPLETED, MARK_FAILED, RETRY }

    data class Decision(
        val action: Action,
        val actor: String,
        val evidence: String,
        val retryConfirmed: Boolean = false,
    )

    data class Result(val status: String, val auditAction: String)

    fun requiresReview(status: String): Boolean = status == STATUS

    fun resolve(currentStatus: String, decision: Decision): Result {
        require(currentStatus == STATUS) { "Only needs_reconciliation items can be resolved" }
        require(decision.actor.isNotBlank()) { "A manager identity is required" }
        require(decision.evidence.isNotBlank()) { "A verification note or evidence reference is required" }
        return when (decision.action) {
            Action.MARK_COMPLETED -> Result("completed", "schedule.reconciliation.completed")
            Action.MARK_FAILED -> Result("failed", "schedule.reconciliation.failed")
            Action.RETRY -> {
                require(decision.retryConfirmed) { "Retry requires explicit manager confirmation" }
                Result("pending", "schedule.reconciliation.retry_authorized")
            }
        }
    }
}
