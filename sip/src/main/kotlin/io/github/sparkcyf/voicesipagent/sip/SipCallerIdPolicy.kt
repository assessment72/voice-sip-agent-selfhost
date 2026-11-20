package io.github.sparkcyf.voicesipagent.sip

import io.github.sparkcyf.voicesipagent.voice.CallerIdMode

/** SIP signalling policy for outgoing caller identity. */
object SipCallerIdPolicy {
    fun from(
        caller: Party,
        accountDisplayName: String?,
        accountAor: String,
        selfDomain: String,
        serverMode: Boolean,
        mode: CallerIdMode,
    ): NameAddr {
        if (mode.requestsRestriction) {
            return NameAddr(mode.sipDisplayName, "sip:anonymous@anonymous.invalid")
        }
        if (!serverMode) return NameAddr(caller.displayName ?: caller.number ?: accountDisplayName, accountAor)
        val fromUser = caller.number ?: "gateway"
        return NameAddr(caller.displayName ?: caller.number ?: accountDisplayName, "sip:$fromUser@$selfDomain")
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
