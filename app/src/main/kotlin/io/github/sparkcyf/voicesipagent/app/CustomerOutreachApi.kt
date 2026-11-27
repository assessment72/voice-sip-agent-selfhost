package io.github.sparkcyf.voicesipagent.app

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manus API v2 client for submitting an instruction to send a real email through a configured
 * connector. Task creation is asynchronous: a returned task ID is not confirmation that an email
 * was sent or delivered.
 */
class ManusV2Client(
    private val apiKey: String,
    private val connectorId: String,
    private val httpClient: OkHttpClient = DEFAULT_HTTP_CLIENT,
) {
    init {
        require(apiKey.isNotBlank()) { "Manus API key is not configured" }
    }

    /** Calls GET /v2/user.me and returns true only when Manus confirms the credential is valid. */
    fun checkApiKey(): Boolean {
        val request = manusRequest("user.me").get().build()
        return httpClient.executeSafely(request, "Manus") { response ->
            parseJsonSafely(response.body?.string().orEmpty(), "Manus").optBoolean("ok", false)
        }
    }

    /**
     * Submits an asynchronous private Manus task. Recipient, subject, and final body are inserted
     * unchanged into the instruction; the task is explicitly told to send the email now, not draft
     * it. The result reports submission only, never successful email delivery.
     */
    fun submitEmailTask(recipient: String, subject: String, finalBody: String): ManusTaskSubmission {
        require(connectorId.isNotBlank()) { "Manus email connector is not configured" }
        require(recipient.isNotBlank()) { "Email recipient is required" }
        require(subject.isNotBlank()) { "Email subject is required" }
        require(finalBody.isNotBlank()) { "Final email body is required" }

        val requestBody = buildManusTaskCreateBody(connectorId, recipient, subject, finalBody)
            .toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = manusRequest("task.create").post(requestBody).build()
        return httpClient.executeSafely(request, "Manus") { response ->
            val payload = parseJsonSafely(response.body?.string().orEmpty(), "Manus")
            val taskId = payload.optString("task_id").takeIf(String::isNotBlank)
                ?: throw IllegalStateException("Manus accepted no task ID")
            ManusTaskSubmission(taskId = taskId)
        }
    }

    /** Polls task events with verbose=true; optionally fetches an event by its exact start_event_id. */
    fun listTaskMessages(taskId: String, startEventId: String? = null): JSONObject {
        require(taskId.isNotBlank()) { "Manus task ID is required" }
        val url = manusUrl("task.listMessages").newBuilder()
            .addQueryParameter("task_id", taskId)
            .addQueryParameter("order", "desc")
            .addQueryParameter("limit", if (startEventId == null) "100" else "1")
            .addQueryParameter("verbose", "true")
            .apply { startEventId?.let { addQueryParameter("start_event_id", it) } }
            .build()
        val request = Request.Builder().url(url).header("x-manus-api-key", apiKey).get().build()
        return httpClient.executeSafely(request, "Manus") { response ->
            parseJsonSafely(response.body?.string().orEmpty(), "Manus")
        }
    }

    /** Reads task.detail status and background-job metadata. */
    fun getTaskDetail(taskId: String): ManusTaskDetail {
        require(taskId.isNotBlank()) { "Manus task ID is required" }
        val url = manusUrl("task.detail").newBuilder().addQueryParameter("task_id", taskId).build()
        val request = Request.Builder().url(url).header("x-manus-api-key", apiKey).get().build()
        return httpClient.executeSafely(request, "Manus") { response ->
            val task = parseJsonSafely(response.body?.string().orEmpty(), "Manus").optJSONObject("task")
                ?: throw IllegalStateException("Manus returned no task details")
            parseManusTaskDetail(task)
        }
    }

    /**
     * Confirms only a current Gmail confirmation whose target event was fetched using
     * start_event_id and whose sole recipient, subject, and body exactly match this request.
     * Other action types, absent targets, additional recipients, and any mismatch remain for review.
     */
    fun confirmExactPendingGmailAction(
        taskId: String,
        expectedRecipient: String,
        expectedSubject: String,
        expectedContent: String,
        isAlreadyConfirmed: (String) -> Boolean = { false },
        beforeConfirmation: (String) -> Unit = {},
    ): GmailActionReview {
        require(taskId.isNotBlank()) { "Manus task ID is required" }
        val latest = listTaskMessages(taskId)
        val messages = latest.optJSONArray("messages") ?: return GmailActionReview.waitingForReview()
        val waitingMessage = (0 until messages.length())
            .mapNotNull(messages::optJSONObject)
            .firstOrNull { it.optJSONObject("status_update")?.optString("agent_status") == "waiting" }
            ?: return GmailActionReview.waitingForReview()
        val status = waitingMessage.optJSONObject("status_update") ?: return GmailActionReview.waitingForReview()
        val detail = status.optJSONObject("status_detail") ?: return GmailActionReview.waitingForReview()
        if (detail.optString("waiting_for_event_type") != "gmailSendAction") {
            return GmailActionReview.waitingForReview()
        }
        val eventId = detail.optString("waiting_for_event_id").takeIf(String::isNotBlank)
            ?: return GmailActionReview.waitingForReview()
        if (isAlreadyConfirmed(eventId)) return GmailActionReview.waitingForReview(eventId)

        // This second request explicitly fetches the event referred to by waiting_for_event_id.
        val targetPage = listTaskMessages(taskId, startEventId = eventId)
        val targetMessages = targetPage.optJSONArray("messages") ?: return GmailActionReview.waitingForReview()
        val target = (0 until targetMessages.length())
            .mapNotNull(targetMessages::optJSONObject)
            .firstOrNull { it.optString("id") == eventId && it.optString("type") == "tool_used" }
            ?: return GmailActionReview.waitingForReview()

        if (!matchesExactGmailSendAction(target, expectedRecipient, expectedSubject, expectedContent)) {
            return GmailActionReview.waitingForReview()
        }

        // Persist the intent before the side effect. If the response is lost, the caller must not
        // issue the same confirmation again; a human can reconcile the task in Manus/Gmail.
        beforeConfirmation(eventId)
        val confirmBody = JSONObject()
            .put("task_id", taskId)
            .put("event_id", eventId)
            .put("input", JSONObject().put("accept", true))
            .toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = manusRequest("task.confirmAction").post(confirmBody).build()
        val confirmed = httpClient.executeSafely(request, "Manus") { response ->
            parseJsonSafely(response.body?.string().orEmpty(), "Manus").optBoolean("confirmed", false)
        }
        return if (confirmed) GmailActionReview.confirmationSubmitted(eventId)
        else GmailActionReview.waitingForReview(eventId)
    }

    /**
     * Returns the structured result only after the main task has stopped and background jobs have
     * finished. Null means the result is not ready or extraction did not produce a valid result.
     */
    fun getStructuredEmailResult(taskId: String): ManusEmailSendResult? {
        val detail = getTaskDetail(taskId)
        if (detail.status != "stopped" || detail.hasRunningBackgroundJobs != false) return null
        val events = listTaskMessages(taskId).optJSONArray("messages") ?: return null
        val resultEvent = (0 until events.length())
            .mapNotNull(events::optJSONObject)
            .firstOrNull { it.optString("type") == "structured_output_result" }
            ?.optJSONObject("structured_output_result") ?: return null
        if (!resultEvent.optBoolean("success", false)) return null
        val value = resultEvent.optJSONObject("value") ?: return null
        if (!value.has("sent") || !value.has("recipient") || !value.has("subject") || !value.has("message_id")) return null
        return ManusEmailSendResult(
            sent = value.optBoolean("sent", false),
            recipient = value.optString("recipient"),
            subject = value.optString("subject"),
            messageId = value.optString("message_id"),
        )
    }

    private fun manusRequest(endpoint: String): Request.Builder =
        Request.Builder().url(manusUrl(endpoint)).header("x-manus-api-key", apiKey)

    private companion object {
        val DEFAULT_HTTP_CLIENT: OkHttpClient = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}

/** An acknowledgment that Manus accepted the task request; it is not an email delivery receipt. */
data class ManusTaskSubmission(
    val taskId: String,
    val status: Status = Status.SUBMITTED,
) {
    enum class Status { SUBMITTED }
}

data class ManusTaskDetail(
    val taskId: String,
    val status: String,
    /** Null means Manus omitted the field; never treat that as proof background work finished. */
    val hasRunningBackgroundJobs: Boolean?,
)

internal fun parseManusTaskDetail(task: JSONObject): ManusTaskDetail = ManusTaskDetail(
    taskId = task.optString("id"),
    status = task.optString("status"),
    hasRunningBackgroundJobs = if (task.has("has_running_background_jobs") && !task.isNull("has_running_background_jobs")) {
        task.optBoolean("has_running_background_jobs")
    } else null,
)

data class ManusEmailSendResult(
    val sent: Boolean,
    val recipient: String,
    val subject: String,
    val messageId: String,
)

data class GmailActionReview(
    val status: Status,
    val eventId: String? = null,
) {
    enum class Status { CONFIRMATION_SUBMITTED, WAITING_FOR_REVIEW }

    companion object {
        fun confirmationSubmitted(eventId: String) = GmailActionReview(Status.CONFIRMATION_SUBMITTED, eventId)
        fun waitingForReview(eventId: String? = null) = GmailActionReview(Status.WAITING_FOR_REVIEW, eventId)
    }
}

/** Meta accepted the message-send request; delivery must be confirmed separately via status events. */
data class WhatsAppMessageSubmission(
    val messageId: String?,
    val status: Status = Status.ACCEPTED,
) {
    enum class Status { ACCEPTED }
}

/**
 * WhatsApp Cloud API client. Sending is restricted to approved message templates; outside the
 * customer service window, Meta requires template messages rather than free-form text.
 */
class WhatsAppCloudApiClient(
    private val accessToken: String,
    private val phoneNumberId: String,
    graphVersion: String = "v27.0",
    private val httpClient: OkHttpClient = DEFAULT_HTTP_CLIENT,
) {
    private val graphVersion: String

    init {
        require(accessToken.isNotBlank()) { "WhatsApp access token is not configured" }
        require(phoneNumberId.matches(PHONE_NUMBER_ID_PATTERN)) { "Invalid WhatsApp phone number ID" }
        require(graphVersion.matches(GRAPH_VERSION_PATTERN)) { "Invalid Graph API version" }
        this.graphVersion = graphVersion
    }

    /** GETs the configured phone-number object to verify that the token can access it. */
    fun checkPhoneNumberConnection(): Boolean {
        val request = Request.Builder()
            .url(whatsappPhoneNumberUrl(graphVersion, phoneNumberId))
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        return httpClient.executeSafely(request, "WhatsApp Cloud API") { response ->
            parseJsonSafely(response.body?.string().orEmpty(), "WhatsApp Cloud API")
            true
        }
    }

    /**
     * Sends an approved body template with exactly two positional text variables, in caller order.
     * A successful response means Meta accepted the message request, not that it was delivered.
     */
    fun sendApprovedTemplate(
        recipient: String,
        templateName: String,
        languageCode: String,
        positionalBodyVariables: List<String>,
    ): WhatsAppMessageSubmission {
        val body = buildWhatsAppTemplateBody(
            recipient = recipient,
            templateName = templateName,
            languageCode = languageCode,
            positionalBodyVariables = positionalBodyVariables,
        ).toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(whatsappMessagesUrl(graphVersion, phoneNumberId))
            .header("Authorization", "Bearer $accessToken")
            .post(body)
            .build()
        return httpClient.executeSafely(request, "WhatsApp Cloud API") { response ->
            val payload = parseJsonSafely(response.body?.string().orEmpty(), "WhatsApp Cloud API")
            val messageId = payload.optJSONArray("messages")?.optJSONObject(0)?.optString("id")
                ?.takeIf(String::isNotBlank)
            WhatsAppMessageSubmission(messageId = messageId)
        }
    }

    private companion object {
        val DEFAULT_HTTP_CLIENT: OkHttpClient = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        val GRAPH_VERSION_PATTERN = Regex("v[0-9]+\\.[0-9]+")
        val PHONE_NUMBER_ID_PATTERN = Regex("[0-9]{1,40}")
    }
}

/** Builds the documented Manus v2 task.create payload; connector ID comes from caller configuration. */
internal fun buildManusTaskCreateBody(
    connectorId: String,
    recipient: String,
    subject: String,
    finalBody: String,
): JSONObject {
    require(connectorId.isNotBlank()) { "Manus email connector is not configured" }
    require(recipient.isNotBlank()) { "Email recipient is required" }
    require(subject.isNotBlank()) { "Email subject is required" }
    require(finalBody.isNotBlank()) { "Final email body is required" }

    val instruction = buildString {
        appendLine("أرسل بريدًا إلكترونيًا فعليًا الآن إلى المستلم أدناه، وليس مسودة.")
        appendLine("استخدم المستلم والعنوان والنص النهائي حرفيًا كما أُعطيت، دون تخمين أو تعديل أو إضافة.")
        appendLine("إذا تعذر عليك إرسال البريد فعليًا باستخدام الموصل، فأبلغ بوضوح عن التعذر ولا تدّعِ الإرسال.")
        appendLine("أعد نتيجة منظمة مطابقة للمخطط: sent=true فقط بعد التأكد من نجاح الإرسال الفعلي؛ وإلا sent=false.")
        appendLine("أعد recipient وsubject كما أُعطيا، وmessage_id من نتيجة الإرسال أو نصًا فارغًا إن لم يتم الإرسال.")
        appendLine()
        appendLine("المستلم (حرفيًا):")
        appendLine(recipient)
        appendLine()
        appendLine("العنوان (حرفيًا):")
        appendLine(subject)
        appendLine()
        appendLine("النص النهائي (حرفيًا، أرسله كما هو):")
        append(finalBody)
    }
    val content = JSONArray().put(
        JSONObject()
            .put("type", "text")
            .put("text", instruction)
            .put("visibility", "visible"),
    )
    return JSONObject()
        .put("message", JSONObject()
            .put("content", content)
            .put("connectors", JSONArray().put(connectorId)))
        .put("share_visibility", "private")
        .put("structured_output_schema", emailSendResultSchema())
}

/** Strict output contract returned after the Manus task finishes. */
internal fun emailSendResultSchema(): JSONObject = JSONObject()
    .put("type", "object")
    .put("properties", JSONObject()
        .put("sent", JSONObject().put("type", "boolean"))
        .put("recipient", JSONObject().put("type", "string"))
        .put("subject", JSONObject().put("type", "string"))
        .put("message_id", JSONObject().put("type", "string")))
    .put("required", JSONArray().put("sent").put("recipient").put("subject").put("message_id"))
    .put("additionalProperties", false)

/**
 * Returns true only for the exact, single-recipient Gmail send payload produced by the fetched
 * tool_used target event. Additional CC/BCC or attachments are deliberately rejected as review-only.
 */
internal fun matchesExactGmailSendAction(
    targetEvent: JSONObject,
    expectedRecipient: String,
    expectedSubject: String,
    expectedContent: String,
): Boolean {
    if (targetEvent.optString("type") != "tool_used") return false
    val tool = targetEvent.optJSONObject("tool_used") ?: return false
    if (!tool.optString("tool").equals("gmail", ignoreCase = true)) return false
    val params = tool.optJSONObject("params") ?: return false
    val messages = params.optJSONArray("messages") ?: return false
    if (messages.length() != 1) return false
    val email = messages.optJSONObject(0) ?: return false
    val to = email.optJSONArray("to") ?: return false
    if (to.length() != 1 || to.optString(0) != expectedRecipient) return false
    if (email.optString("subject") != expectedSubject || email.optString("content") != expectedContent) return false
    return isEmptyOrMissingArray(email, "cc") && isEmptyOrMissingArray(email, "bcc") &&
        isEmptyOrMissingArray(email, "attachments")
}

private fun isEmptyOrMissingArray(value: JSONObject, key: String): Boolean {
    if (!value.has(key) || value.isNull(key)) return true
    val array = value.optJSONArray(key) ?: return false
    return array.length() == 0
}

/** Builds the standard Meta template-message payload, with two ordered positional body variables. */
internal fun buildWhatsAppTemplateBody(
    recipient: String,
    templateName: String,
    languageCode: String,
    positionalBodyVariables: List<String>,
): JSONObject {
    require(templateName.matches(Regex("[a-z0-9_]{1,512}"))) { "Invalid WhatsApp template name" }
    require(languageCode.isNotBlank()) { "WhatsApp template language is required" }
    require(positionalBodyVariables.size == 2) { "Exactly two positional template variables are required" }
    require(positionalBodyVariables.all(String::isNotBlank)) { "Template variables must not be blank" }

    val parameters = JSONArray()
    positionalBodyVariables.forEach { value ->
        parameters.put(JSONObject().put("type", "text").put("text", value))
    }
    return JSONObject()
        .put("messaging_product", "whatsapp")
        .put("recipient_type", "individual")
        .put("to", normalizeInternationalRecipient(recipient))
        .put("type", "template")
        .put("template", JSONObject()
            .put("name", templateName)
            .put("language", JSONObject().put("code", languageCode))
            .put("components", JSONArray().put(
                JSONObject()
                    .put("type", "body")
                    .put("parameters", parameters),
            )))
}

/** Removes common phone formatting and returns E.164 international digits without a leading plus. */
internal fun normalizeInternationalRecipient(recipient: String): String {
    require(recipient.isNotBlank()) { "WhatsApp recipient is required" }
    require(recipient.matches(Regex("\\+?[0-9 .()\\-]+"))) { "Invalid WhatsApp recipient" }
    val digits = recipient.filter { it in '0'..'9' }
    require(digits.length in 7..15 && digits.firstOrNull() != '0') { "Invalid international WhatsApp recipient" }
    return digits
}

internal fun whatsappMessagesUrl(graphVersion: String, phoneNumberId: String): HttpUrl =
    graphBaseUrl(graphVersion, phoneNumberId).addPathSegment("messages").build()

internal fun whatsappPhoneNumberUrl(graphVersion: String, phoneNumberId: String): HttpUrl =
    graphBaseUrl(graphVersion, phoneNumberId).build()

private fun graphBaseUrl(graphVersion: String, phoneNumberId: String): HttpUrl.Builder {
    require(graphVersion.matches(Regex("v[0-9]+\\.[0-9]+"))) { "Invalid Graph API version" }
    require(phoneNumberId.matches(Regex("[0-9]{1,40}"))) { "Invalid WhatsApp phone number ID" }
    return HttpUrl.Builder()
        .scheme("https")
        .host("graph.facebook.com")
        .addPathSegment(graphVersion)
        .addPathSegment(phoneNumberId)
}

private fun manusUrl(endpoint: String): HttpUrl = HttpUrl.Builder()
    .scheme("https")
    .host("api.manus.ai")
    .addPathSegment("v2")
    .addPathSegment(endpoint)
    .build()

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

private inline fun <T> OkHttpClient.executeSafely(
    request: Request,
    serviceName: String,
    consume: (okhttp3.Response) -> T,
): T {
    val response = try {
        newCall(request).execute()
    } catch (_: IOException) {
        throw IllegalStateException("$serviceName request failed (network error)")
    }
    response.use {
        if (!it.isSuccessful) throw IllegalStateException("$serviceName rejected request (${it.code})")
        return consume(it)
    }
}

private fun parseJsonSafely(text: String, serviceName: String): JSONObject = try {
    JSONObject(text)
} catch (_: Exception) {
    throw IllegalStateException("$serviceName returned an invalid response")
}
