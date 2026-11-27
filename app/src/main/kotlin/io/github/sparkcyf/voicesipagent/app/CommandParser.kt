package io.github.sparkcyf.voicesipagent.app

import java.util.Locale

data class ParsedCommand(val name: String, val argument: String) {
    fun fields(limit: Int = Int.MAX_VALUE): List<String> =
        if (argument.isBlank()) emptyList()
        else argument.split('|', limit = limit).map { it.trim() }
}

/** Parses Telegram command text without losing delimiters inside the final field. */
internal object CommandParser {
    fun parse(raw: String): ParsedCommand {
        val text = raw.trim()
        if (text.isEmpty()) return ParsedCommand("", "")
        val separator = text.indexOfFirst { it.isWhitespace() }
        val token = if (separator < 0) text else text.substring(0, separator)
        val argument = if (separator < 0) "" else text.substring(separator).trim()
        return ParsedCommand(token.substringBefore('@').lowercase(Locale.ROOT), argument)
    }
}
