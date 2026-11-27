package io.github.sparkcyf.voicesipagent.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VoicxCustomerContactPolicyTest {
    @Test fun emailValuesAreTrimmedAndInvalidValuesDoNotRejectTheCustomerRecord() {
        assertEquals("person@example.com", CustomerContactPolicy.emailOrEmpty("  person@example.com  "))
        assertEquals("", CustomerContactPolicy.emailOrEmpty(""))
        assertEquals("", CustomerContactPolicy.emailOrEmpty("not-an-email"))
        assertEquals("", CustomerContactPolicy.emailOrEmpty("person @example.com"))
    }

    @Test fun contactFieldsDefaultToNoContactConsent() {
        val customer = VoicxCustomer(phone = "+967730501035", name = "Example")

        assertEquals("", customer.personalEmail)
        assertEquals("", customer.workEmail)
        assertFalse(customer.phoneHasWhatsApp)
        assertFalse(customer.emailContactConsent)
        assertFalse(customer.whatsappContactConsent)
    }

    @Test fun legacyCustomerDocumentsDecodeMissingConsentAsFalse() {
        val legacyDocument = LocalDocument("legacy-id", JSONObject().put("phone", "+967730501035").put("name", "Legacy"))

        assertFalse(legacyDocument.boolean("phoneHasWhatsApp"))
        assertFalse(legacyDocument.boolean("emailContactConsent"))
        assertFalse(legacyDocument.boolean("whatsappContactConsent"))
    }

    @Test fun legacyReAddPreservesContactValuesWhileExplicitInputCanRevokeConsent() {
        val existing = VoicxCustomer(
            phone = "+967730501035", name = "Existing", personalEmail = "personal@example.com",
            workEmail = "work@example.com", phoneHasWhatsApp = true, emailContactConsent = true,
            whatsappContactConsent = true,
        )
        val legacyInput = VoicxCustomer(phone = "+967730501035", name = "Updated")

        assertEquals(
            CustomerContactPolicy.Values("personal@example.com", "work@example.com", true, true, true),
            CustomerContactPolicy.valuesFor(legacyInput, existing),
        )
        assertEquals(
            CustomerContactPolicy.Values("", "", false, false, false),
            CustomerContactPolicy.valuesFor(legacyInput.copy(contactFieldsProvided = true), existing),
        )
    }
}
