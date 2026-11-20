package io.github.sparkcyf.voicesipagent.sip

/**
 * Resolves the dynamic Yemeni mobile target carried by an inbound SIP INVITE.
 *
 * The selected source is the first non-empty value in this strict order:
 * X-VSA-Dial, To, P-Called-Party-ID, Request-URI. Once a source is present, an invalid
 * value is rejected rather than falling through to a lower-priority field.
 */
internal object YemeniSipDialTarget {
    data class Valid(
        val source: String,
        val encodedUser: String,
        val e164: String,
    )

    data class Invalid(
        val source: String,
        val reason: String,
    )

    sealed interface Result {
        data class Accepted(val target: Valid) : Result
        data class Rejected(val error: Invalid) : Result
    }

    private data class Candidate(val source: String, val raw: String?)

    private val supportedMobilePrefixes = setOf("70", "71", "73", "77", "78")

    fun resolve(request: SipRequest): Result {
        val candidates = listOf(
            Candidate("X-VSA-Dial", request.headers["X-VSA-Dial"]),
            Candidate("To", request.headers["To"]?.let(::nameAddrUser)),
            Candidate("P-Called-Party-ID", request.headers["P-Called-Party-ID"]?.let(::nameAddrUser)),
            Candidate("Request-URI", SipUri.parse(request.uri)?.user),
        )
        val selected = candidates.firstOrNull { !it.raw.isNullOrBlank() }
            ?: return Result.Rejected(Invalid("none", "no dynamic target was supplied"))

        val encoded = selected.raw!!.trim()
        if (!encoded.startsWith("sip", ignoreCase = true)) {
            return Result.Rejected(Invalid(selected.source, "target must start with the literal sip prefix"))
        }

        val national = encoded.substring(3)
        if (national.length != 9 || national.any { it !in '0'..'9' }) {
            return Result.Rejected(Invalid(selected.source, "expected sip followed by exactly 9 digits"))
        }
        if (national.first() != '7' || national.substring(0, 2) !in supportedMobilePrefixes) {
            return Result.Rejected(Invalid(selected.source, "number is not a supported Yemeni mobile prefix"))
        }

        return Result.Accepted(
            Valid(
                source = selected.source,
                encodedUser = encoded,
                e164 = "+967$national",
            ),
        )
    }

    private fun nameAddrUser(value: String): String? =
        NameAddr.parse(value)?.sipUri?.user
}
