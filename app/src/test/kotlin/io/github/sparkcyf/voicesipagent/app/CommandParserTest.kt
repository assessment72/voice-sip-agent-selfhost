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
}
