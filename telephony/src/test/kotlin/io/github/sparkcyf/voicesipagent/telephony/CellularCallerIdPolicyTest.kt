package io.github.sparkcyf.voicesipagent.telephony

import io.github.sparkcyf.voicesipagent.voice.CallerIdMode
import org.junit.Assert.assertEquals
import org.junit.Test

class CellularCallerIdPolicyTest {
    @Test fun normalModeKeepsTheDialledNumberUnchanged() {
        assertEquals("+967712345678", CellularCallerIdPolicy.dialString("+967712345678", CallerIdMode.NORMAL))
    }

    @Test fun everyPrivacyModeRequestsPerCallClir() {
        listOf(CallerIdMode.PRIVATE, CallerIdMode.UNKNOWN, CallerIdMode.HIDDEN).forEach { mode ->
            assertEquals("#31#+967712345678", CellularCallerIdPolicy.dialString("+967712345678", mode))
        }
    }

    @Test fun telecomMatchingIgnoresClirPrefix() {
        assertEquals("+967712345678", CellularCallerIdPolicy.numberForMatching("#31#+967712345678"))
        assertEquals("+967712345678", CellularCallerIdPolicy.numberForMatching("*31#+967712345678"))
    }
}
