package io.github.sparkcyf.voicesipagent.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandParserTest {
    @Test fun commandNameIgnoresBotSuffixAndLeadingWhitespace() {
        val command = CommandParser.parse("  /sms@voicx_bot  +967730501035 | أهلاً  ")
        assertEquals("/sms", command.name)
        assertEquals("+967730501035 | أهلاً", command.argument)
    }

    @Test fun finalFieldKeepsVerticalBarsInsideMessage() {
        val fields = CommandParser.parse("/sms +967730501035 | عرض | سعر | اليوم").fields(limit = 2)
        assertEquals(listOf("+967730501035", "عرض | سعر | اليوم"), fields)
    }

    @Test fun blankInputProducesNoCommandOrFields() {
        val command = CommandParser.parse("   ")
        assertEquals("", command.name)
        assertEquals(emptyList<String>(), command.fields())
    }

    @Test fun scheduleTimeAcceptsUnixSecondsAndMillisecondsOnlyInTheFuture() {
        assertEquals(1_700_000_000_000L, ScheduleCommandParser.parseTime("1700000000", 1_600_000_000_000L))
        assertEquals(1_700_000_000_000L, ScheduleCommandParser.parseTime("1700000000000", 1_600_000_000_000L))
        assertEquals(null, ScheduleCommandParser.parseTime("1600000000", 1_600_000_000_000L))
        assertEquals(null, ScheduleCommandParser.parseTime("not-a-time", 1_600_000_000_000L))
    }

    @Test fun manualScheduleKeyIsStableForRetries() {
        assertEquals(
            ScheduleCommandParser.manualCallKey("c1", "+967700000000", 1_700_000_000_000L, " note "),
            ScheduleCommandParser.manualCallKey("c1", "+967700000000", 1_700_000_000_000L, " note "),
        )
    }

    @Test fun recurringScheduleKeysIncludeTheRepeatAndEndTime() {
        val oneTime = ScheduleCommandParser.manualCallKey("c1", "+967700000000", 1_700_000_000_000L, "note")
        val weekly = ScheduleCommandParser.manualCallKey("c1", "+967700000000", 1_700_000_000_000L, "note", "weekly")
        val boundedWeekly = ScheduleCommandParser.manualCallKey("c1", "+967700000000", 1_700_000_000_000L, "note", "weekly", 1_800_000_000_000L)
        assertEquals(false, oneTime == weekly)
        assertEquals(false, weekly == boundedWeekly)
    }

    @Test fun epochParserConvertsSecondsWithoutRequiringFutureTime() {
        assertEquals(1_700_000_000_000L, ScheduleCommandParser.parseEpochMillis("1700000000"))
        assertEquals(1_700_000_000_000L, ScheduleCommandParser.parseEpochMillis("1700000000000"))
        assertEquals(null, ScheduleCommandParser.parseEpochMillis("tomorrow"))
    }

    @Test fun invalidLegacyScheduleRowsAreNotDisplayable() {
        assertEquals(false, ScheduleCommandParser.isDisplayableSchedule("", 0L))
        assertEquals(false, ScheduleCommandParser.isDisplayableSchedule("+967730501035", 0L))
        assertEquals(true, ScheduleCommandParser.isDisplayableSchedule("+967730501035", 1_700_000_000_000L))
    }

    @Test fun scheduleTextIsCompactedWithoutChangingItsContentOrder() {
        assertEquals("stage:busy_retry نتيجة المكالمة: NORMAL كلام العميل: نعم", ScheduleCommandParser.compactScheduleText("stage:busy_retry\nنتيجة المكالمة: NORMAL\nكلام العميل: نعم"))
    }

    @Test fun recurrenceAcceptsOnceDailyWeeklyAndMonthly() {
        assertEquals("none", ScheduleCommandParser.recurrence("مرة واحدة"))
        assertEquals("daily", ScheduleCommandParser.recurrence("يومي"))
        assertEquals("weekly", ScheduleCommandParser.recurrence("weekly"))
        assertEquals("monthly", ScheduleCommandParser.recurrence("شهرياً"))
    }

    @Test fun recurrenceRejectsUnknownValue() {
        assertEquals(null, ScheduleCommandParser.recurrence("every two weeks"))
    }

    @Test fun recurrenceDescriptionShowsWhetherTheSeriesHasAnEnd() {
        val until = 1_800_000_000_000L
        assertEquals("أسبوعي حتى ${ScheduleCommandParser.displayTime(until)}", ScheduleCommandParser.recurrenceDescription("weekly", until))
        assertEquals("شهري حتى الإلغاء", ScheduleCommandParser.recurrenceDescription("monthly", null))
        assertEquals("مرة واحدة", ScheduleCommandParser.recurrenceDescription("none", null))
    }

    @Test fun recurrenceNextOccurrenceUsesExpectedIntervals() {
        val start = 1_700_000_000_000L
        assertEquals(start + 24L * 60 * 60 * 1000, ScheduleCommandParser.nextOccurrence(start, "daily"))
        assertEquals(start + 7L * 24 * 60 * 60 * 1000, ScheduleCommandParser.nextOccurrence(start, "weekly"))
        assertEquals(null, ScheduleCommandParser.nextOccurrence(start, "once"))
    }

    @Test fun recurrenceSkipsMissedOccurrencesInsteadOfCatchingUpInABurst() {
        val start = 1_700_000_000_000L
        val day = 24L * 60 * 60 * 1000
        assertEquals(start + 4 * day, ScheduleCommandParser.nextOccurrenceAfter(start, "daily", start + 3 * day))
    }
}
