package io.github.sparkcyf.voicesipagent.gateway

/**
 * Pure DTMF-to-employee routing decision. It does not create call legs, dial,
 * play prompts, or mutate CallBridge state.
 */
class ClinicCallRouter(private val policy: CallCenterPolicy) {
    /**
     * Routes one DTMF digit using the following stable policy:
     *
     * - `0`: the next eligible employee not present in [attemptedEmployeeIds].
     * - `1`: the first eligible employee in priority/id order.
     * - `2`: the second eligible employee in priority/id order.
     *
     * Employee destinations are already validated by [CallCenterEmployee]. No
     * credentials or other dialing configuration are part of the result.
     */
    fun route(digit: Char, attemptedEmployeeIds: Set<String> = emptySet()): RouteDecision {
        require(attemptedEmployeeIds.none { it.isBlank() }) {
            "attempted employee ids must not be blank"
        }

        val candidates = policy.nextEmployees()
        val employee = when (digit) {
            '0' -> candidates.firstOrNull { it.id !in attemptedEmployeeIds }
            '1' -> candidates.getOrNull(0)
            '2' -> candidates.getOrNull(1)
            else -> return RouteDecision.UnsupportedDigit(digit)
        }

        return employee?.let { RouteDecision.Connect(it.id, it.destination) }
            ?: RouteDecision.NoAvailableEmployee
    }
}

/** A side-effect-free result that an integration layer may pass to its dialer. */
sealed interface RouteDecision {
    data class Connect(
        val employeeId: String,
        val destination: EmployeeDestination,
    ) : RouteDecision

    data class UnsupportedDigit(val digit: Char) : RouteDecision

    data object NoAvailableEmployee : RouteDecision
}
