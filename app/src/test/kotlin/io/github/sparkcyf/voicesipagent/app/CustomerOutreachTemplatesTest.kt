package io.github.sparkcyf.voicesipagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerOutreachTemplatesTest {
    @Test fun exactlyTenEditableStagesAreProvided() {
        assertEquals(CustomerOutreachTemplates.COUNT, CustomerOutreachTemplates.defaults.size)
        assertEquals((1..10).toList(), CustomerOutreachTemplates.defaults.map { it.stage })
    }

    @Test fun renderingReplacesCustomerFieldsAndUsesSafeFallbacks() {
        val rendered = CustomerOutreachTemplates.render("السلام {name}، بشأن {company}", "محمود", "الشركة")
        assertEquals("السلام محمود، بشأن الشركة", rendered)
        assertEquals("السلام عميلنا الكريم، بشأن مؤسستكم", CustomerOutreachTemplates.render("السلام {name}، بشأن {company}", " ", ""))
    }

    @Test fun emailSubjectDoesNotAllowNewlineInjection() {
        val subject = CustomerOutreachTemplates.safeSubject("موضوع\r\nBcc: attacker@example.com", "محمود", "شركة")
        assertFalse(subject.contains('\n'))
        assertFalse(subject.contains('\r'))
        assertTrue(subject.startsWith("موضوع Bcc:"))
    }
}
