package io.github.sparkcyf.voicesipagent.app

/** Best-effort redaction before operational logs are copied or shared outside the app. */
object LogShareSanitizer {
    private val secretField = Regex(
        "(?i)\\b(password|passwd|registrar_password|registrarPassword|token|secret)\\b(\\s*[=:]\\s*)(\"[^\"]*\"|'[^']*'|[^,;\\s)}]+)",
    )
    private val authorization = Regex("(?i)(\\bauthorization\\s*[:=]\\s*(?:basic|bearer)\\s+)[^\\s,;]+")
    private val sipCredentials = Regex("(?i)\\b(sips?):([^@:/\\s]+):([^@/\\s]+)@")

    fun sanitize(text: String): String = text.lineSequence().joinToString("\n") { line ->
        line
            .replace(secretField) { match -> "${match.groupValues[1]}${match.groupValues[2]}<redacted>" }
            .replace(authorization) { match -> "${match.groupValues[1]}<redacted>" }
            .replace(sipCredentials) { match -> "${match.groupValues[1]}:${match.groupValues[2]}:<redacted>@" }
    }
}
