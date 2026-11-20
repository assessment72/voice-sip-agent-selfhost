package io.github.sparkcyf.voicesipagent.telephony

import io.github.sparkcyf.voicesipagent.voice.CallerIdMode

/**
 * Builds a per-call GSM CLIR dial string. #31# invokes CLIR for this call on GSM/UMTS/LTE
 * networks that support the standard supplementary-service MMI. It does not change the
 * subscription-wide caller-ID setting.
 */
object CellularCallerIdPolicy {
    const val CLIR_INVOCATION_PREFIX = "#31#"
    const val CLIR_SUPPRESSION_PREFIX = "*31#"

    fun dialString(number: String, mode: CallerIdMode): String =
        if (mode.requestsRestriction) CLIR_INVOCATION_PREFIX + number else number

    /** Telecom may report the MMI-prefixed address back in Call.Details.handle. */
    fun numberForMatching(address: String): String =
        address.removePrefix(CLIR_INVOCATION_PREFIX)
            .removePrefix(CLIR_SUPPRESSION_PREFIX)
}
