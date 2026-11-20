package io.github.sparkcyf.voicesipagent.sip

/** Converts the encoded SIP user sip777405578 into +967777405578. */
object YemenNumber {
    private val encodedUser = Regex("""^sip(7\d{8})$""")

    fun normalize(rawTarget: String?): String? {
        val value = rawTarget
            ?.trim()
            ?.substringBefore(';')
            ?.substringBefore('@')
            ?: return null

        val user = value
            .removePrefix("sip:")
            .removePrefix("sips:")
            .trim()

        val localNumber = encodedUser
            .matchEntire(user)
            ?.groupValues
            ?.get(1)
            ?: return null

        return "+967$localNumber"
    }
}
