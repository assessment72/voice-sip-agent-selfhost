package io.github.sparkcyf.voicesipagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PhoneNumberNormalizerTest {
    @Test fun localYemeniMobileBecomesE164() {
        assertEquals("+967730501035", PhoneNumberNormalizer.normalizeYemen("730501035"))
    }

    @Test fun internationalAndTrunkPrefixesAreCanonicalized() {
        assertEquals("+967730501035", PhoneNumberNormalizer.normalizeYemen("+967 730 501 035"))
        assertEquals("+967730501035", PhoneNumberNormalizer.normalizeYemen("00967-730-501-035"))
        assertEquals("+967730501035", PhoneNumberNormalizer.normalizeYemen("0730501035"))
    }

    @Test fun arabicIndicDigitsAreAccepted() {
        assertEquals("+967730501035", PhoneNumberNormalizer.normalizeYemen("٧٣٠٥٠١٠٣٥"))
    }

    @Test fun nonYemeniMobileAndMalformedValuesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { PhoneNumberNormalizer.normalizeYemen("73050103") }
        assertThrows(IllegalArgumentException::class.java) { PhoneNumberNormalizer.normalizeYemen("+966501234567") }
        assertThrows(IllegalArgumentException::class.java) { PhoneNumberNormalizer.normalizeYemen("abc") }
    }
}
