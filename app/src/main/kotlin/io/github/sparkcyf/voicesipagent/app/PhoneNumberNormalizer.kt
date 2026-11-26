package io.github.sparkcyf.voicesipagent.app

/** Provider-facing normalization for Yemeni mobile numbers (E.164). */
internal object PhoneNumberNormalizer {
    fun normalizeYemen(raw: String): String {
        val ascii = raw.trim().map { ch ->
            when (ch) {
                in '\u0660'..'\u0669' -> ('0'.code + ch.code - '\u0660'.code).toChar()
                in '\u06f0'..'\u06f9' -> ('0'.code + ch.code - '\u06f0'.code).toChar()
                else -> ch
            }
        }.joinToString("")
        val compact = ascii.filterNot { it.isWhitespace() || it in "()-" }
        require(compact.all { it.isDigit() || it == '+' }) { "Invalid phone number" }
        val digits = compact.removePrefix("+").removePrefix("00")
        val internationalDigits = when {
            compact.startsWith("+") && digits.startsWith("967") -> digits
            compact.startsWith("00") && digits.startsWith("967") -> digits
            digits.startsWith("967") -> digits
            digits.length == 9 && digits.firstOrNull() == '7' -> "967$digits"
            digits.length == 10 && digits.startsWith("07") -> "967${digits.drop(1)}"
            else -> digits
        }
        require(internationalDigits.matches(Regex("9677[0-9]{8}"))) { "Invalid Yemeni mobile number" }
        return "+$internationalDigits"
    }
}
