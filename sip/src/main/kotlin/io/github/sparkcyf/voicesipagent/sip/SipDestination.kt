package io.github.sparkcyf.voicesipagent.sip

/**
 * Validates the user part used for a dynamically routed SIP destination.
 *
 * Keep this deliberately narrower than a full SIP URI parser: callers provide a destination
 * user/number, while the configured trunk domain is appended by the SIP user agent. This prevents
 * a remote INVITE from injecting a different host, URI parameters, headers, or whitespace.
 */
object SipDestination {
    private val allowed = Regex("^[A-Za-z0-9+_.!~*'(),-]{1,64}$")

    fun normalize(raw: String): String? {
        val value = raw.trim()
        if (!allowed.matches(value)) return null
        if (value.count { it == '+' } > 1 || ('+' in value && !value.startsWith('+'))) return null
        return value
    }
}
