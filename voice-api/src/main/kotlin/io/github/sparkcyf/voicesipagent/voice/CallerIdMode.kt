package io.github.sparkcyf.voicesipagent.voice

/**
 * Presentation policy requested for an outgoing call.
 *
 * PRIVATE, UNKNOWN and HIDDEN are distinct user-facing choices, but carrier/SIP-network
 * signalling ultimately controls the exact text shown to the remote party.
 */
enum class CallerIdMode(val id: String) {
    NORMAL("normal"),
    PRIVATE("private"),
    UNKNOWN("unknown"),
    HIDDEN("hidden");

    val requestsRestriction: Boolean get() = this != NORMAL

    val sipDisplayName: String
        get() = when (this) {
            NORMAL -> ""
            PRIVATE -> "Private Number"
            UNKNOWN -> "Unknown"
            HIDDEN -> "Anonymous"
        }

    companion object {
        fun fromId(id: String?): CallerIdMode =
            entries.firstOrNull { it.id == id } ?: NORMAL
    }
}
