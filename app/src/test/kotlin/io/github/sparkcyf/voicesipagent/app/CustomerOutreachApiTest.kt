package io.github.sparkcyf.voicesipagent.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerOutreachApiTest {
    @Test
    fun omittedManusBackgroundJobFieldRemainsUnknown() {
        val missing = parseManusTaskDetail(JSONObject().put("id", "task-1").put("status", "stopped"))
        val active = parseManusTaskDetail(JSONObject().put("id", "task-2").put("status", "stopped").put("has_running_background_jobs", true))
        val finished = parseManusTaskDetail(JSONObject().put("id", "task-3").put("status", "stopped").put("has_running_background_jobs", false))

        assertNull(missing.hasRunningBackgroundJobs)
        assertEquals(true, active.hasRunningBackgroundJobs)
        assertEquals(false, finished.hasRunningBackgroundJobs)
    }

    @Test
    fun manusTaskBodyUsesConfiguredConnectorPrivateVisibilityAndLiteralEmailFields() {
        val connectorId = "connector-from-config"
        val recipient = "person@example.test"
        val subject = "Final subject exactly"
        val finalBody = "Line one\nLine two — keep punctuation!"

        val request = buildManusTaskCreateBody(connectorId, recipient, subject, finalBody)
        val message = request.getJSONObject("message")
        val connectorIds = message.getJSONArray("connectors")
        val instruction = message.getJSONArray("content").getJSONObject(0).getString("text")

        assertEquals("private", request.getString("share_visibility"))
        assertEquals(1, connectorIds.length())
        assertEquals(connectorId, connectorIds.getString(0))
        assertTrue(instruction.contains("بريدًا إلكترونيًا فعليًا الآن"))
        assertTrue(instruction.contains("وليس مسودة"))
        assertTrue(instruction.contains("المستلم (حرفيًا):\n$recipient"))
        assertTrue(instruction.contains("العنوان (حرفيًا):\n$subject"))
        assertTrue(instruction.contains("النص النهائي (حرفيًا، أرسله كما هو):\n$finalBody"))

        val schema = request.getJSONObject("structured_output_schema")
        assertEquals("object", schema.getString("type"))
        assertFalse(schema.getBoolean("additionalProperties"))
        assertEquals(
            setOf("sent", "recipient", "subject", "message_id"),
            schema.getJSONObject("properties").keys().asSequence().toSet(),
        )
        assertEquals(
            listOf("sent", "recipient", "subject", "message_id"),
            schema.getJSONArray("required").toStringList(),
        )
    }

    @Test
    fun whatsappBodyUsesApprovedTemplateAndOrderedPositionalTextParameters() {
        val body = buildWhatsAppTemplateBody(
            recipient = "+1 (650) 555-1234",
            templateName = "customer_outreach",
            languageCode = "en_US",
            positionalBodyVariables = listOf("Mina", "Acme Ltd"),
        )

        assertEquals("whatsapp", body.getString("messaging_product"))
        assertEquals("individual", body.getString("recipient_type"))
        assertEquals("16505551234", body.getString("to"))
        assertEquals("template", body.getString("type"))
        val template = body.getJSONObject("template")
        assertEquals("customer_outreach", template.getString("name"))
        assertEquals("en_US", template.getJSONObject("language").getString("code"))
        val component = template.getJSONArray("components").getJSONObject(0)
        assertEquals("body", component.getString("type"))
        val parameters = component.getJSONArray("parameters")
        assertEquals(2, parameters.length())
        assertEquals("text", parameters.getJSONObject(0).getString("type"))
        assertEquals("Mina", parameters.getJSONObject(0).getString("text"))
        assertEquals("Acme Ltd", parameters.getJSONObject(1).getString("text"))
    }

    @Test
    fun graphUrlsContainValidatedVersionAndPhoneId() {
        assertEquals(
            "https://graph.facebook.com/v27.0/123456789/messages",
            whatsappMessagesUrl("v27.0", "123456789").toString(),
        )
        assertEquals(
            "https://graph.facebook.com/v27.0/123456789",
            whatsappPhoneNumberUrl("v27.0", "123456789").toString(),
        )
    }

    @Test
    fun invalidGraphVersionAndPhoneNumberIdAreRejectedBeforeUrlAssembly() {
        assertRejected { whatsappMessagesUrl("v27.0/messages", "123456789") }
        assertRejected { whatsappMessagesUrl("27.0", "123456789") }
        assertRejected { whatsappPhoneNumberUrl("v27.0", "123/456") }
    }

    @Test
    fun onlyExactFetchedGmailSendPayloadMatches() {
        val event = gmailEvent(
            recipients = listOf("person@example.test"),
            subject = "Subject",
            content = "Exact body\n",
        )

        assertTrue(matchesExactGmailSendAction(event, "person@example.test", "Subject", "Exact body\n"))
        assertFalse(matchesExactGmailSendAction(event, "person@example.test", "Subject", "Exact body"))
        assertFalse(matchesExactGmailSendAction(event, "other@example.test", "Subject", "Exact body\n"))
        assertFalse(matchesExactGmailSendAction(gmailEvent(listOf("person@example.test", "copy@example.test"), "Subject", "Exact body\n"), "person@example.test", "Subject", "Exact body\n"))
        assertFalse(matchesExactGmailSendAction(event.put("type", "assistant_message"), "person@example.test", "Subject", "Exact body\n"))
    }

    @Test
    fun gmailActionWithExtraRecipientOrAttachmentMustRemainForReview() {
        val ccEvent = gmailEvent(listOf("person@example.test"), "Subject", "Body")
        ccEvent.getJSONObject("tool_used").getJSONObject("params")
            .getJSONArray("messages").getJSONObject(0).put("cc", JSONArray().put("copy@example.test"))
        val attachmentEvent = gmailEvent(listOf("person@example.test"), "Subject", "Body")
        attachmentEvent.getJSONObject("tool_used").getJSONObject("params")
            .getJSONArray("messages").getJSONObject(0).put("attachments", JSONArray().put(JSONObject()))

        assertFalse(matchesExactGmailSendAction(ccEvent, "person@example.test", "Subject", "Body"))
        assertFalse(matchesExactGmailSendAction(attachmentEvent, "person@example.test", "Subject", "Body"))
    }

    @Test
    fun whatsappTemplateRejectsInvalidNameAndWrongNumberOfVariables() {
        assertRejected {
            buildWhatsAppTemplateBody("+16505551234", "Template Name", "en_US", listOf("Mina", "Acme"))
        }
        assertRejected {
            buildWhatsAppTemplateBody("+16505551234", "template", "en_US", listOf("Mina"))
        }
    }

    private fun gmailEvent(recipients: List<String>, subject: String, content: String): JSONObject {
        val to = JSONArray()
        recipients.forEach(to::put)
        val email = JSONObject()
            .put("to", to)
            .put("cc", JSONArray())
            .put("bcc", JSONArray())
            .put("subject", subject)
            .put("content", content)
            .put("attachments", JSONArray())
        return JSONObject()
            .put("id", "event-123")
            .put("type", "tool_used")
            .put("tool_used", JSONObject()
                .put("tool", "gmail")
                .put("params", JSONObject().put("messages", JSONArray().put(email))))
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).map { getString(it) }

    private fun assertRejected(block: () -> Any) {
        try {
            block()
            throw AssertionError("Expected validation to reject the input")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure.
        }
    }
}
