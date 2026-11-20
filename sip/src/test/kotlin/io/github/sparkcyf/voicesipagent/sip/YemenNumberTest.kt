package io.github.sparkcyf.voicesipagent.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YemenNumberTest {
    @Test
    fun convertsFullSipAddressToInternationalYemenNumber() {
        assertEquals(
            "+967777405578",
            YemenNumber.normalize("sip777405578@sip2sip.info")
        )
    }

    @Test
    fun convertsSipUserWithoutDomain() {
        assertEquals(
            "+967777405578",
            YemenNumber.normalize("sip777405578")
        )
    }

    @Test
    fun rejectsInvalidEncodedUsers() {
        assertNull(YemenNumber.normalize("777405578@sip2sip.info"))
        assertNull(YemenNumber.normalize("sip12345@sip2sip.info"))
        assertNull(YemenNumber.normalize("sip7774055780@sip2sip.info"))
    }
}
