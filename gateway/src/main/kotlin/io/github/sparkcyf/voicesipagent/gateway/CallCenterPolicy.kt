package io.github.sparkcyf.voicesipagent.gateway

/** A routing destination; credentials are intentionally not part of this model. */
sealed interface EmployeeDestination {
    data class Sip(val uri: String) : EmployeeDestination
    /** A number dialled through an external SIP trunk, never through the handset SIM. */
    data class Mobile(val e164: String) : EmployeeDestination
}

data class CallCenterEmployee(
    val id: String,
    val displayName: String,
    val destination: EmployeeDestination,
    val priority: Int = 0,
    val enabled: Boolean = true,
    val ringSeconds: Int = 20,
) {
    init {
        require(id.isNotBlank()) { "employee id must not be blank" }
        require(displayName.isNotBlank()) { "employee display name must not be blank" }
        require(priority >= 0) { "priority must not be negative" }
        require(ringSeconds in 5..120) { "ringSeconds must be between 5 and 120" }
        when (destination) {
            is EmployeeDestination.Sip -> require(destination.uri.isNotBlank() && !destination.uri.any(Char::isWhitespace)) {
                "SIP URI must be non-blank and contain no whitespace"
            }
            is EmployeeDestination.Mobile -> require(E164.matches(destination.e164)) {
                "mobile destination must be an E.164 number"
            }
        }
    }

    companion object {
        private val E164 = Regex("\\+[1-9]\\d{6,14}")
    }
}

data class RecordingPolicy(
    val enabled: Boolean = true,
    val announcementRequired: Boolean = true,
    val retentionDays: Int = 30,
) {
    init {
        require(retentionDays in 1..3650) { "retentionDays must be between 1 and 3650" }
        require(!enabled || announcementRequired) {
            "recording cannot be enabled without a caller announcement"
        }
    }

    fun mayStartRecording(announcementPlayed: Boolean): Boolean =
        enabled && (!announcementRequired || announcementPlayed)
}

/**
 * Pure routing policy for the first safe implementation increment. It does not place calls,
 * create SIP registrations, or claim support for concurrent GSM calls.
 */
class CallCenterPolicy(
    employees: List<CallCenterEmployee>,
    val recording: RecordingPolicy = RecordingPolicy(),
    private val maxAttempts: Int = 2,
) {
    private val orderedEmployees = employees.sortedWith(compareBy<CallCenterEmployee> { it.priority }.thenBy { it.id })

    init {
        require(maxAttempts in 1..2) { "the initial policy supports at most two attempts" }
        require(orderedEmployees.map { it.id }.toSet().size == orderedEmployees.size) { "employee ids must be unique" }
    }

    fun nextEmployees(): List<CallCenterEmployee> = orderedEmployees.filter { it.enabled }.take(maxAttempts)
}
