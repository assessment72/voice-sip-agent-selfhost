package io.github.sparkcyf.voicesipagent.app

import org.json.JSONArray
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.TimeUnit

/** HTTPS-only client for the Vercel data API; this APK never opens a MongoDB connection. */
internal class VoicxDatabaseApi {
    @Volatile private var api: DbApiClient? = null
    @Volatile var state: MongoState = MongoState.DISCONNECTED
        private set
    @Volatile var lastSuccessAt: Long? = null
        private set
    @Volatile var lastError: String = ""
        private set

    @Synchronized
    fun test(apiUrl: String, databaseName: String, deviceId: String, token: String): Result<Long> = runCatching {
        val client = DbApiClient(apiUrl, deviceId, token, databaseName)
        val started = System.currentTimeMillis()
        val result = client.getStatus()
        check(result.optBoolean("ok")) { "MongoDB غير متاحة" }
        lastSuccessAt = System.currentTimeMillis()
        lastError = ""
        System.currentTimeMillis() - started
    }.onFailure { lastError = safeError(it) }

    @Synchronized
    fun connect(apiUrl: String, databaseName: String, deviceId: String, token: String): Result<Unit> = runCatching {
        val client = DbApiClient(apiUrl, deviceId, token, databaseName)
        val result = client.getStatus()
        check(result.optBoolean("ok")) { "MongoDB غير متاحة" }
        api = client
        state = MongoState.CONNECTED
        lastError = ""
        lastSuccessAt = System.currentTimeMillis()
    }.onFailure {
        api = null
        state = MongoState.ERROR
        lastError = safeError(it)
    }

    @Synchronized fun disconnect() { api = null; state = MongoState.DISCONNECTED }

    fun call(operation: String, args: JSONObject = JSONObject(), timeoutMs: Int = 25_000): JSONObject {
        val client = api ?: error("اتصال Vercel غير مفعّل")
        return try {
            client.post(operation, args, timeoutMs).also { lastSuccessAt = System.currentTimeMillis(); lastError = "" }
        } catch (failure: Throwable) {
            lastError = safeError(failure)
            state = MongoState.ERROR
            throw failure
        }
    }

    private fun safeError(error: Throwable): String = when (error) {
        is DbApiException -> when (error.status) {
            401 -> "رمز المصادقة غير صالح أو الجهاز غير مصرح"
            403 -> "لا تملك صلاحية تنفيذ العملية"
            408, 504 -> "انتهت مهلة الاتصال بخادم Vercel"
            503 -> "تعذر الوصول إلى MongoDB عبر الخادم"
            else -> "تعذر الاتصال بخادم Vercel (HTTP ${error.status})"
        }
        else -> if (error is java.net.UnknownHostException || error is java.net.ConnectException)
            "لا توجد شبكة أو تعذر الوصول إلى خادم Vercel" else error.message?.take(180) ?: "تعذر الاتصال بالخادم"
    }
}

private class DbApiClient(apiUrl: String, private val deviceId: String, private val token: String, private val expectedDatabase: String) {
    private val base: String
    init {
        val uri = URI(apiUrl.trim().trimEnd('/'))
        require(uri.scheme.equals("https", ignoreCase = true)) { "يلزم استخدام عنوان HTTPS لخادم Vercel" }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) { "عنوان API غير صالح" }
        require(deviceId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "معرّف الجهاز غير صالح" }
        require(token.length >= 32) { "رمز الجهاز يجب ألا يقل عن 32 حرفاً" }
        base = uri.toString().trimEnd('/')
    }

    fun getStatus(): JSONObject = request("GET", "/api/v1/db/status").also { result ->
        val actual = result.optString("databaseName")
        require(actual.isBlank() || actual == expectedDatabase) { "اسم قاعدة البيانات لا يطابق إعداد Vercel" }
    }
    fun post(operation: String, args: JSONObject, timeoutMs: Int = 25_000): JSONObject =
        request("POST", "/api/v1/db", JSONObject().put("operation", operation).put("args", args), timeoutMs)

    private fun request(method: String, path: String, payload: JSONObject? = null, timeoutMs: Int = 25_000): JSONObject {
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs.coerceIn(500, 25_000)
            readTimeout = timeoutMs.coerceIn(500, 25_000)
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-Voicx-Device-Id", deviceId)
            setRequestProperty("Accept", "application/json")
            if (payload != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (payload != null) connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw DbApiException(code, text.take(300))
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally { connection.disconnect() }
    }
}

private class DbApiException(val status: Int, detail: String) : IllegalStateException("Vercel API HTTP $status: $detail")

enum class MongoState { DISCONNECTED, CONNECTED, ERROR }

data class VoicxCustomer(
    val id: String? = null,
    val phone: String,
    val name: String,
    val role: String = "",
    val company: String = "",
    val instructions: String = "",
    val notes: String = "",
    val groups: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

data class Campaign(
    val id: String? = null,
    val name: String,
    val startAt: Long,
    val timezone: String = java.time.ZoneId.systemDefault().id,
    val targetGroup: String = "",
    val channel: String = "call_sms",
    val smsTemplate: String = "",
    val status: String = "queued",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

data class CampaignCustomer(
    val id: String? = null,
    val campaignId: String,
    val customerId: String,
    val phone: String,
    val sequence: Int,
    val status: String = "queued",
    val attempts: Int = 0,
    val lastCallAt: Long? = null,
    val lastSmsAt: Long? = null,
    val nextCallAt: Long? = null,
    val nextSmsAt: Long? = null,
    val lastOutcome: String = "",
    val followUpNote: String = "",
    val interestedAt: Long? = null,
    val adminAt: Long? = null,
    val pausedAt: Long? = null,
    val optedOutAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

data class CallSchedule(
    val id: String? = null,
    val customerId: String,
    val phone: String,
    val runAt: Long,
    val status: String = "pending",
    val note: String = "",
    val campaignId: String? = null,
    val campaignCustomerId: String? = null,
    val idempotencyKey: String? = null,
    val attempts: Int = 0,
)

data class SmsSchedule(
    val id: String? = null,
    val customerId: String = "",
    val phone: String,
    val runAt: Long,
    val message: String,
    val status: String = "queued",
    val campaignId: String? = null,
    val note: String = "",
    val campaignCustomerId: String? = null,
    val idempotencyKey: String? = null,
    val attempts: Int = 0,
)

internal class VoicxCustomerRepository(private val store: VoicxDatabaseApi, private val settingsStore: SettingsStore) {
    fun upsert(customer: VoicxCustomer): String {
        val id = store.call("customer.upsert", JSONObject().put("customer", customer.toJson())).optString("id")
        cacheCustomer(customer.copy(id = id))
        return id
    }
    fun findById(id: String): VoicxCustomer? = store.call("customer.findById", JSONObject().put("id", id)).optJSONObject("customer")?.toCustomer()?.also(::cacheCustomer)
    fun findByPhone(phone: String): VoicxCustomer? = store.call("customer.findByPhone", JSONObject().put("phone", phone)).optJSONObject("customer")?.toCustomer()?.also(::cacheCustomer)
    fun search(query: String, limit: Int = 20): List<VoicxCustomer> = store.call("customer.search", JSONObject().put("query", query).put("limit", limit))
        .optJSONArray("customers").toObjects().mapNotNull { (it as? JSONObject)?.toCustomer() }.also { rows -> rows.forEach(::cacheCustomer) }
    fun addMemory(customerId: String, text: String, source: String = "call") {
        store.call("memory.add", JSONObject().put("customerId", customerId).put("text", text.take(4000)).put("source", source))
    }
    fun instructionFor(phone: String): String = cachedCallContext(phone)?.instructions.orEmpty().ifBlank {
        runCatching { refreshCallContext(phone)?.instructions.orEmpty() }.getOrDefault("")
    }
    fun memoriesFor(phone: String, limit: Int = 10): String = store.call("memory.forPhone", JSONObject().put("phone", phone).put("limit", limit))
        .optJSONArray("memories").toObjects().map { it.toString() }.filter(String::isNotBlank).also { cacheMemories(phone, it) }.joinToString("\n") { "- $it" }
    fun cachedCallContext(phone: String): GeminiAiEndpoint.CallContext? = settingsStore.cachedCustomerContext(phone)?.toCallContext()
    fun refreshCallContext(phone: String): GeminiAiEndpoint.CallContext? {
        val result = store.call("customer.callContext", JSONObject().put("phone", phone), timeoutMs = 1_500)
        val customer = result.optJSONObject("customer")
        val memories = result.optJSONArray("memories") ?: JSONArray()
        if (customer == null) return null
        settingsStore.cacheCustomerContext(phone, JSONObject().put("customer", customer).put("memories", memories))
        return JSONObject().put("customer", customer).put("memories", memories).toCallContext()
    }
    fun saveSchedule(schedule: CallSchedule): String = store.call("schedule.save", JSONObject().put("schedule", schedule.toJson())).optString("id")
    fun claimDueSchedule(now: Long = System.currentTimeMillis()): CallSchedule? = store.call("schedule.claim", JSONObject().put("now", now)).optJSONObject("schedule")?.toCallSchedule()
    fun updateScheduleStatus(id: String, status: String, error: String = "") { store.call("schedule.update", JSONObject().put("id", id).put("status", status).put("error", error)) }
    fun saveSmsSchedule(schedule: SmsSchedule): String = store.call("smsSchedule.save", JSONObject().put("schedule", schedule.toJson())).optString("id")
    fun claimDueSmsSchedule(now: Long = System.currentTimeMillis()): SmsSchedule? = store.call("smsSchedule.claim", JSONObject().put("now", now)).optJSONObject("schedule")?.toSmsSchedule()
    fun campaignCustomerBlocked(id: String?): Boolean = store.call("campaignCustomer.blocked", JSONObject().put("id", id ?: "")).optBoolean("blocked")
    fun latestCampaignCustomer(phone: String): String? = store.call("campaignCustomer.latestForPhone", JSONObject().put("phone", phone)).optString("id").takeIf { it.isNotBlank() && it != "null" }
    fun updateSmsScheduleStatus(id: String, status: String, error: String = "") { store.call("smsSchedule.update", JSONObject().put("id", id).put("status", status).put("error", error)) }
    fun retrySmsSchedule(id: String, error: String): String = store.call("smsSchedule.retry", JSONObject().put("id", id).put("error", error.take(500))).optString("status")
    fun callReportPayload(report: AiCallReport, reportId: String = java.util.UUID.randomUUID().toString()): String =
        JSONObject().put("reportId", reportId).put("report", report.toJson()).toString()
    fun saveCallReport(report: AiCallReport) = saveCallReportPayload(callReportPayload(report))
    fun saveCallReportPayload(payload: String) {
        val envelope = JSONObject(payload)
        store.call("call.report", JSONObject().put("reportId", envelope.optString("reportId")).put("report", envelope.getJSONObject("report")))
    }
    fun listSchedules(limit: Int = 20): List<CallSchedule> = store.call("schedule.list", JSONObject().put("limit", limit)).optJSONArray("schedules").toObjects().mapNotNull { (it as? JSONObject)?.toCallSchedule() }
    fun listSmsSchedules(limit: Int = 20): List<SmsSchedule> = store.call("smsSchedule.list", JSONObject().put("limit", limit)).optJSONArray("schedules").toObjects().mapNotNull { (it as? JSONObject)?.toSmsSchedule() }
    fun createCampaign(campaign: Campaign): String = store.call("campaign.create", JSONObject().put("campaign", campaign.toJson())).optString("id")
    fun setCampaignDecision(campaignCustomerId: String, outcome: String, note: String = "") {
        store.call("campaign.decision", JSONObject().put("campaignCustomerId", campaignCustomerId).put("outcome", outcome).put("note", note.take(1000)))
    }
    fun scheduleFollowUp(campaignCustomerId: String, nextCallAt: Long?, nextSmsAt: Long?, note: String): Boolean {
        val args = JSONObject().put("campaignCustomerId", campaignCustomerId).put("note", note.take(1000))
        if (nextCallAt != null) args.put("nextCallAt", nextCallAt) else args.put("nextCallAt", JSONObject.NULL)
        if (nextSmsAt != null) args.put("nextSmsAt", nextSmsAt) else args.put("nextSmsAt", JSONObject.NULL)
        return store.call("campaign.followUp", args).optBoolean("scheduled")
    }
    fun advanceCampaign(campaignCustomerId: String) { store.call("campaign.advance", JSONObject().put("campaignCustomerId", campaignCustomerId)) }

    private fun cacheCustomer(customer: VoicxCustomer) {
        val memories = settingsStore.cachedCustomerContext(customer.phone)?.optJSONArray("memories") ?: JSONArray()
        settingsStore.cacheCustomerContext(customer.phone, JSONObject().put("customer", customer.toJson()).put("memories", memories))
    }
    private fun cacheMemories(phone: String, memories: List<String>) {
        val current = settingsStore.cachedCustomerContext(phone) ?: JSONObject()
        current.put("memories", JSONArray(memories))
        settingsStore.cacheCustomerContext(phone, current)
    }
    private fun JSONObject.toCallContext(): GeminiAiEndpoint.CallContext {
        val customer = optJSONObject("customer")
        val memories = optJSONArray("memories").toObjects().map { it.toString() }.filter(String::isNotBlank)
        return GeminiAiEndpoint.CallContext(name = customer?.optString("name").orEmpty(), role = customer?.optString("role").orEmpty(),
            company = customer?.optString("company").orEmpty(), instructions = customer?.optString("instructions").orEmpty(),
            memories = memories.joinToString("\n") { "- $it" })
    }

    private fun VoicxCustomer.toJson() = JSONObject().put("id", id ?: JSONObject.NULL).put("phone", phone).put("name", name)
        .put("role", role).put("company", company).put("instructions", instructions).put("notes", notes)
        .put("groups", JSONArray(groups)).put("createdAt", createdAt).put("updatedAt", updatedAt)
    private fun JSONObject.toCustomer() = VoicxCustomer(
        id = optString("id").takeIf(String::isNotBlank), phone = optString("phone"), name = optString("name"), role = optString("role"),
        company = optString("company"), instructions = optString("instructions"), notes = optString("notes"),
        groups = optJSONArray("groups").toObjects().map { it.toString() }, createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt"))
    private fun CallSchedule.toJson() = JSONObject().put("id", id ?: JSONObject.NULL).put("customerId", customerId).put("phone", phone)
        .put("runAt", runAt).put("status", status).put("note", note).put("campaignId", campaignId ?: JSONObject.NULL)
        .put("campaignCustomerId", campaignCustomerId ?: JSONObject.NULL).put("idempotencyKey", idempotencyKey ?: JSONObject.NULL).put("attempts", attempts)
    private fun JSONObject.toCallSchedule() = CallSchedule(optString("id").takeIf(String::isNotBlank), optString("customerId"), optString("phone"), optLong("runAt"),
        optString("status", "pending"), optString("note"), optString("campaignId").takeIf(String::isNotBlank), optString("campaignCustomerId").takeIf(String::isNotBlank),
        optString("idempotencyKey").takeIf(String::isNotBlank), optInt("attempts"))
    private fun SmsSchedule.toJson() = JSONObject().put("id", id ?: JSONObject.NULL).put("customerId", customerId).put("phone", phone).put("runAt", runAt)
        .put("message", message).put("status", status).put("campaignId", campaignId ?: JSONObject.NULL).put("note", note)
        .put("campaignCustomerId", campaignCustomerId ?: JSONObject.NULL).put("idempotencyKey", idempotencyKey ?: JSONObject.NULL).put("attempts", attempts)
    private fun JSONObject.toSmsSchedule() = SmsSchedule(id = optString("id").takeIf(String::isNotBlank), customerId = optString("customerId"), phone = optString("phone"),
        runAt = optLong("runAt"), message = optString("message"), status = optString("status", "queued"), campaignId = optString("campaignId").takeIf(String::isNotBlank),
        note = optString("note"), campaignCustomerId = optString("campaignCustomerId").takeIf(String::isNotBlank),
        idempotencyKey = optString("idempotencyKey").takeIf(String::isNotBlank), attempts = optInt("attempts"))
    private fun Campaign.toJson() = JSONObject().put("id", id ?: JSONObject.NULL).put("name", name).put("startAt", startAt).put("timezone", timezone)
        .put("targetGroup", targetGroup).put("channel", channel).put("smsTemplate", smsTemplate).put("status", status).put("createdAt", createdAt).put("updatedAt", updatedAt)
    private fun AiCallReport.toJson() = JSONObject().put("number", number ?: JSONObject.NULL).put("name", name ?: JSONObject.NULL).put("role", role ?: JSONObject.NULL)
        .put("company", company ?: JSONObject.NULL).put("startedAt", startedAt).put("endedAt", endedAt).put("cause", cause?.name ?: "NORMAL")
        .put("failureDetail", failureDetail).put("inputText", inputText).put("outputText", outputText).put("conversationTranscript", conversationTranscript)
        .put("inputFrames", inputFrames).put("outputFrames", outputFrames).put("inputAttempts", inputAttempts).put("inputSendFailures", inputSendFailures)
        .put("outputAudioChunks", outputAudioChunks).put("outputAudioBytes", outputAudioBytes).put("outputDroppedFrames", outputDroppedFrames)
        .put("outputQueuePeak", outputQueuePeak).put("firstInputSentAt", firstInputSentAt ?: 0L).put("lastInputSentAt", lastInputSentAt ?: 0L)
        .put("firstOutputAt", firstOutputAt ?: 0L).put("firstOutputQueuedAt", firstOutputQueuedAt ?: 0L).put("firstOutputPlayedAt", firstOutputPlayedAt ?: 0L)
        .put("turnCompleteAt", turnCompleteAt ?: 0L).put("turnResponseCount", turnResponseCount).put("lastTurnResponseMs", lastTurnResponseMs ?: 0L)
        .put("averageTurnResponseMs", averageTurnResponseMs ?: 0L).put("lastTurnSpeechDurationMs", lastTurnSpeechDurationMs ?: 0L)
        .put("averageTurnSpeechDurationMs", averageTurnSpeechDurationMs ?: 0L).put("turnCount", turnCount).put("lastSpeechEndAt", lastSpeechEndAt ?: 0L)
}

private fun JSONArray?.toObjects(): List<Any> = this?.let { array -> (0 until array.length()).mapNotNull { array.opt(it).takeUnless { value -> value == JSONObject.NULL } } }.orEmpty()

internal class SmsApiClient(private val apiKey: String, private val endpoint: String = "https://adasms-standalone.vercel.app/api/sms/send") {
    fun send(phone: String, message: String, messageId: String = java.util.UUID.randomUUID().toString()): Result<String> = runCatching {
        require(apiKey.isNotBlank()) { "SMS API key is not configured" }
        require(phone.matches(Regex("\\+?[0-9]{7,20}"))) { "Invalid phone number" }
        require(message.isNotBlank() && message.length <= 1000) { "Invalid SMS message" }
        require(messageId.length in 1..128) { "Invalid SMS message ID" }
        val body = JSONObject().put("phone", phone).put("message", message).put("idempotency_key", messageId).toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(endpoint).header("Authorization", "Bearer $apiKey")
            .header("Idempotency-Key", messageId).post(body).build()
        SMS_HTTP.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("SMS service rejected request (${response.code})")
            "SMS sent"
        }
    }

    private companion object {
        /** Shared client avoids creating a dispatcher and connection pool per SMS. */
        val SMS_HTTP: OkHttpClient = OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
