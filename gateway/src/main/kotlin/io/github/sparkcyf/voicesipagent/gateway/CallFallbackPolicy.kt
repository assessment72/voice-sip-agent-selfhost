package io.github.sparkcyf.voicesipagent.gateway

/** What to do when no configured employee fallback can be started. */
enum class FinalCallAction {
    END_CALL,
    RETURN_TO_IVR_ONCE,
}

/** Bounded pre-answer forwarding policy. Employee order is explicit and never inferred from SIP errors. */
data class CallFallbackPolicy(
    val fallbackExtensions: List<String> = listOf("2"),
    val maxAttempts: Int = 3,
    val finalAction: FinalCallAction = FinalCallAction.RETURN_TO_IVR_ONCE,
) {
    init {
        require(maxAttempts in 1..4) { "maxAttempts must be between 1 and 4" }
        require(fallbackExtensions.size <= 8) { "at most eight fallback extensions are supported" }
        require(fallbackExtensions.all { it.matches(Regex("[0-9]{1,4}")) }) {
            "fallback extensions must contain 1 to 4 digits"
        }
        require(fallbackExtensions.distinct().size == fallbackExtensions.size) {
            "fallback extensions must be unique"
        }
    }

    fun nextFallback(
        employees: List<CallCenterEmployee>,
        attemptedEmployeeIds: Set<String>,
    ): CallCenterEmployee? = fallbackExtensions.asSequence()
        .mapNotNull { extension -> employees.firstOrNull { it.enabled && it.extension == extension } }
        .firstOrNull { it.id !in attemptedEmployeeIds }
}
