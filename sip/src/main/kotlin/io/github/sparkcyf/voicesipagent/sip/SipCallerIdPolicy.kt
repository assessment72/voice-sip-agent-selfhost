package io.github.sparkcyf.voicesipagent.sip

import io.github.sparkcyf.voicesipagent.voice.CallerIdMode
import io.github.sparkcyf.voicesipagent.voice.Party

/** SIP signalling policy for outgoing caller identity. */
object SipCallerIdPolicy {
    fun from(
        caller: Party,
        accountDisplayName: String?,
        accountAor: String,
        selfDomain: String,
        serverMode: Boolean,
        mode: CallerIdMode,
        authorizedCallerId: String = "",
    ): NameAddr {
        if (mode.requestsRestriction) {
            return NameAddr(mode.sipDisplayName, "sip:anonymous@anonymous.invalid")
        }
        if (!serverMode) {
            val alternate = normalizeAuthorizedCallerId(authorizedCallerId)
            if (alternate != null) return NameAddr(caller.displayName ?: accountDisplayName, "sip:$alternate@$selfDomain")
            return NameAddr(caller.displayName ?: caller.number ?: accountDisplayName, accountAor)
        }
        val fromUser = caller.number ?: "gateway"
        return NameAddr(caller.displayName ?: caller.number ?: accountDisplayName, "sip:$fromUser@$selfDomain")
    }

    /** RFC 3323 user privacy plus RFC 3325 asserted-identity privacy. */
    fun privacyHeader(mode: CallerIdMode): String? =
        if (mode.requestsRestriction) "id;user" else null

    fun assertedIdentity(caller: Party, domain: String, mode: CallerIdMode, authorizedCallerId: String = ""): String? {
        if (mode.requestsRestriction) return null
        val number = normalizeAuthorizedCallerId(authorizedCallerId) ?: caller.number ?: return null
        return "<sip:$number@$domain>"
    }

    /** Accept only an E.164-like number; never permit arbitrary SIP URI/header injection. */
    fun normalizeAuthorizedCallerId(value: String): String? {
        val candidate = value.trim()
        return candidate.takeIf { it.matches(Regex("^\\+[1-9][0-9]{6,14}$")) }
    }
}
