package io.github.sparkcyf.voicesipagent.sip

import io.github.sparkcyf.voicesipagent.voice.CallerIdMode

/** SIP signalling policy for outgoing caller identity. */
object SipCallerIdPolicy {
    fun from(caller: Party, accountDisplayName: String?, mode: CallerIdMode): NameAddr {
        if (mode.requestsRestriction) {
            return NameAddr(mode.sipDisplayName, "sip:anonymous@anonymous.invalid")
        }
        val display = caller.displayName ?: caller.number ?: accountDisplayName
        val user = caller.number ?: "gateway"
        return NameAddr(display, "sip:$user@anonymous.invalid")
    }

    /** RFC 3323 user privacy plus RFC 3325 asserted-identity privacy. */
    fun privacyHeader(mode: CallerIdMode): String? =
        if (mode.requestsRestriction) "id;user" else null

    fun assertedIdentity(caller: Party, domain: String, mode: CallerIdMode): String? {
        if (mode.requestsRestriction) return null
        val number = caller.number ?: return null
        return "<sip:$number@$domain>"
    }
}
