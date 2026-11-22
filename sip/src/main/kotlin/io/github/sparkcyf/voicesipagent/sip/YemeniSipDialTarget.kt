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

    private data class Candidate(
        val source: String,
        val raw: String?,
        /** Bare nine-digit national numbers are valid only when carried as a SIP URI user. */
        val allowBareNational: Boolean = false,
    )

    private val supportedMobilePrefixes = setOf("70", "71", "73", "77", "78")

    fun resolve(request: SipRequest, fixedGatewayUser: String? = "gateway"): Result {
        val xVsa = request.headers["X-VSA-Dial"]
        val candidates = listOf(
            Candidate(
                "X-VSA-Dial",
                xVsa?.let(::xVsaUser),
                allowBareNational = xVsa?.let { it.contains("@") && NameAddr.parse(it) != null } == true,
            ),
            Candidate("To", request.headers["To"]?.let(::nameAddrUser), allowBareNational = true),
            Candidate("P-Called-Party-ID", request.headers["P-Called-Party-ID"]?.let(::nameAddrUser), allowBareNational = true),
            Candidate("Request-URI", SipUri.parse(request.uri)?.user, allowBareNational = true),
        )
        // Proxies commonly keep the gateway AoR in To or Request-URI. Treat that fixed account
        // identity as routing metadata, not as a destination; continue to the next source.
        val fixedUsers = setOfNotNull("gateway", fixedGatewayUser?.lowercase())
        val selected = candidates.firstOrNull {
            !it.raw.isNullOrBlank() && it.raw.trim().lowercase() !in fixedUsers
        } ?: return Result.Rejected(Invalid("none", "no dynamic target was supplied"))

        val encoded = selected.raw!!.trim()
        val national = when {
            encoded.startsWith("sip", ignoreCase = true) -> encoded.substring(3)
            selected.allowBareNational && encoded.length == 9 && encoded.all { it in '0'..'9' } -> encoded
            else -> return Result.Rejected(
                Invalid(selected.source, "expected a SIP URI user with 9 digits or sip followed by 9 digits"),
            )
        }
        if (national.length != 9 || national.any { it !in '0'..'9' }) {
            return Result.Rejected(Invalid(selected.source, "expected exactly 9 ASCII digits"))
        }
        if (national.first() != '7' || national.substring(0, 2) !in supportedMobilePrefixes) {
            return Result.Rejected(Invalid(selected.source, "number is not a supported Yemeni mobile prefix"))
        }

        return Result.Accepted(
            Valid(
                source = selected.source,
                encodedUser = "sip$national",
                e164 = "+967$national",
            ),
        )
    }

    private fun nameAddrUser(value: String): String? =
        NameAddr.parse(value)?.sipUri?.user

    private fun xVsaUser(value: String): String {
        val raw = value.trim()
        if (raw.contains("@") || raw.startsWith("<")) {
            NameAddr.parse(raw)?.sipUri?.user?.let { return it }
            return raw.substringBefore("@").trim().trim('<', '>')
        }
        return raw
    }
}
