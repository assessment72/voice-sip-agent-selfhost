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

    fun parseTime(raw: String?, now: Long = System.currentTimeMillis()): Long? = parseEpochMillis(raw)?.takeIf { it > now }

    fun parseEpochMillis(raw: String?): Long? = raw
        ?.trim()
        ?.takeIf { it.matches(Regex("\\d{10,13}")) }
        ?.toLongOrNull()
        ?.let { if (it in 1_000_000_000L..9_999_999_999L) it * 1000 else it }

    /** Rejects legacy rows that would otherwise be rendered as an empty contact at Unix epoch. */
    fun isDisplayableSchedule(phone: String, runAt: Long): Boolean =
        phone.isNotBlank() && runAt >= 946_684_800_000L

    /** Keeps Telegram schedule rows on one line and prevents oversized notes from flooding the chat. */
    fun compactScheduleText(value: String, maxLength: Int = 180): String =
        value.replace(Regex("\\s+"), " ").trim().take(maxLength)

    fun displayTime(epochMs: Long): String = runCatching { displayFormatter.format(Instant.ofEpochMilli(epochMs)) }
        .getOrElse { epochMs.toString() }

    fun manualCallKey(
        customerId: String,
        phone: String,
        runAt: Long,
        note: String,
        recurrence: String = "none",
        recurrenceUntil: Long? = null,
    ): String = "manual-call:${customerId.trim()}:${phone.trim()}:$runAt:${note.trim()}:${recurrence.trim()}:${recurrenceUntil ?: 0L}"

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

    fun recurrenceDescription(raw: String, until: Long?): String {
        if (recurrence(raw) == "none") return recurrenceLabel(raw)
        return "${recurrenceLabel(raw)}${until?.let { " حتى ${displayTime(it)}" } ?: " حتى الإلغاء"}"
    }

    fun nextOccurrence(runAt: Long, repeat: String): Long? = when (recurrence(raw = repeat)) {
        "daily" -> runAt + 24L * 60 * 60 * 1000
        "weekly" -> runAt + 7L * 24 * 60 * 60 * 1000
        "monthly" -> java.time.Instant.ofEpochMilli(runAt).atZone(ZoneId.systemDefault()).plusMonths(1).toInstant().toEpochMilli()
        else -> null
    }

    /** Skips missed occurrences after downtime instead of enqueueing a burst of overdue calls. */
    fun nextOccurrenceAfter(runAt: Long, repeat: String, after: Long): Long? {
        var next = nextOccurrence(runAt, repeat) ?: return null
        var skipped = 0
        while (next <= after && skipped < 10_000) {
            next = nextOccurrence(next, repeat) ?: return null
            skipped++
        }
        return next.takeIf { it > after }
    }
}
