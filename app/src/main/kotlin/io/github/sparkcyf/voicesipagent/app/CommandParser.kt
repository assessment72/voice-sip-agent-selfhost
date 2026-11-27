package io.github.sparkcyf.voicesipagent.app

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

internal object ScheduleCommandParser {
    private val displayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    fun parseTime(raw: String?, now: Long = System.currentTimeMillis()): Long? = raw
        ?.trim()
        ?.takeIf { it.matches(Regex("\\d{10,13}")) }
        ?.toLongOrNull()
        ?.let { if (it in 1_000_000_000L..9_999_999_999L) it * 1000 else it }
        ?.takeIf { it > now }

    fun displayTime(epochMs: Long): String = runCatching { displayFormatter.format(Instant.ofEpochMilli(epochMs)) }
        .getOrElse { epochMs.toString() }

    fun manualCallKey(customerId: String, phone: String, runAt: Long, note: String): String =
        "manual-call:${customerId.trim()}:${phone.trim()}:$runAt:${note.trim()}"

    fun recurrence(raw: String): String? = when (raw.trim().lowercase(Locale.ROOT)) {
        "", "once", "none", "مرة", "مرة واحدة", "بدون", "لا" -> "none"
        "daily", "day", "يومي", "يومياً", "يوميا" -> "daily"
        "weekly", "week", "أسبوعي", "أسبوعياً", "اسبوعي" -> "weekly"
        "monthly", "month", "شهري", "شهرياً" -> "monthly"
        else -> null
    }

    fun recurrenceLabel(raw: String): String = when (recurrence(raw)) {
        "daily" -> "يومي"
        "weekly" -> "أسبوعي"
        "monthly" -> "شهري"
        else -> "مرة واحدة"
    }

    fun nextOccurrence(runAt: Long, repeat: String): Long? = when (recurrence(raw = repeat)) {
        "daily" -> runAt + 24L * 60 * 60 * 1000
        "weekly" -> runAt + 7L * 24 * 60 * 60 * 1000
        "monthly" -> java.time.Instant.ofEpochMilli(runAt).atZone(ZoneId.systemDefault()).plusMonths(1).toInstant().toEpochMilli()
        else -> null
    }
}
