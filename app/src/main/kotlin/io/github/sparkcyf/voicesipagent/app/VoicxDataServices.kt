package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import org.bson.Document
import org.bson.BsonRegularExpression
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import java.util.Date
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Direct MongoDB Atlas access. Credentials are supplied at runtime and never logged. */
internal class VoicxMongoStore {
    @Volatile private var client: MongoClient? = null
    @Volatile private var database: MongoDatabase? = null
    @Volatile var state: MongoState = MongoState.DISCONNECTED
        private set
    @Volatile var lastSuccessAt: Long? = null
        private set
    @Volatile var lastError: String = ""
        private set

    @Synchronized
    fun test(uri: String, databaseName: String): Result<Long> {
        return runCatching {
            require(uri.trim().startsWith("mongodb")) { "MongoDB URI is required" }
            require(databaseName.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid database name" }
            val started = System.currentTimeMillis()
            val settings = MongoClientSettings.builder()
                .applyConnectionString(ConnectionString(uri.trim()))
                .applyToClusterSettings { it.serverSelectionTimeout(8, TimeUnit.SECONDS) }
                .applyToSocketSettings { it.connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS) }
                .build()
            MongoClients.create(settings).use { it.getDatabase(databaseName).runCommand(Document("ping", 1)) }
            val elapsed = System.currentTimeMillis() - started
            lastError = ""
            lastSuccessAt = System.currentTimeMillis()
            elapsed
        }.onFailure { e -> lastError = safeError(e) }
    }

    @Synchronized
    fun connect(uri: String, databaseName: String): Result<Unit> = runCatching {
        require(uri.trim().startsWith("mongodb")) { "MongoDB URI is required" }
        require(databaseName.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid database name" }
        disconnect()
        val settings = MongoClientSettings.builder()
            .applyConnectionString(ConnectionString(uri.trim()))
            .applyToClusterSettings { it.serverSelectionTimeout(8, TimeUnit.SECONDS) }
            .applyToSocketSettings { it.connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS) }
            .build()
        val c = MongoClients.create(settings)
        c.getDatabase(databaseName).runCommand(Document("ping", 1))
        client = c
        database = c.getDatabase(databaseName)
        state = MongoState.CONNECTED
        lastError = ""
        lastSuccessAt = System.currentTimeMillis()
    }.onFailure { e -> state = MongoState.ERROR; lastError = safeError(e) }

    @Synchronized fun disconnect() {
        client?.close(); client = null; database = null; state = MongoState.DISCONNECTED
    }

    fun requireDatabase(): MongoDatabase = database ?: error("MongoDB is not active")
    fun customers(): MongoCollection<Document> = requireDatabase().getCollection("customers")
    fun memories(): MongoCollection<Document> = requireDatabase().getCollection("customer_memories")
    fun calls(): MongoCollection<Document> = requireDatabase().getCollection("call_summaries")
    fun schedules(): MongoCollection<Document> = requireDatabase().getCollection("call_schedules")
    fun smsSchedules(): MongoCollection<Document> = requireDatabase().getCollection("sms_schedules")

    private fun safeError(e: Throwable): String = when (e) {
        is IllegalArgumentException -> e.message.orEmpty().take(240)
        else -> "${e.javaClass.simpleName}: connection failed".take(240)
    }
}

enum class MongoState { DISCONNECTED, CONNECTED, ERROR }

data class VoicxCustomer(
    val id: String? = null,
    val phone: String,
    val name: String,
    val role: String = "",
    val company: String = "",
    val instructions: String = "",
    val notes: String = "",
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
)

internal class VoicxCustomerRepository(private val store: VoicxMongoStore) {
    fun upsert(customer: VoicxCustomer): String {
        val id = customer.id?.takeIf { ObjectId.isValid(it) }?.let(::ObjectId) ?: ObjectId()
        val doc = Document("phone", customer.phone.trim()).append("name", customer.name.trim())
            .append("role", customer.role.trim()).append("company", customer.company.trim())
            .append("instructions", customer.instructions.trim()).append("notes", customer.notes.trim())
            .append("updatedAt", Date(customer.updatedAt))
        store.customers().replaceOne(Document("_id", id), doc, com.mongodb.client.model.ReplaceOptions().upsert(true))
        return id.toHexString()
    }
    fun findById(id: String): VoicxCustomer? = if (!ObjectId.isValid(id)) null else from(store.customers().find(Document("_id", ObjectId(id))).first())
    fun findByPhone(phone: String): VoicxCustomer? = from(store.customers().find(Document("phone", phone.trim())).first())
    fun search(query: String, limit: Int = 20): List<VoicxCustomer> {
        val q = query.trim()
        val regex = BsonRegularExpression(".*${java.util.regex.Pattern.quote(q)}.*", "i")
        val filter = if (q.isBlank()) Document() else Document("\$or", listOf(
            Document("phone", regex),
            Document("name", regex),
            Document("company", regex),
        ))
        return store.customers().find(filter).limit(limit.coerceIn(1, 50)).map(::from).filterNotNull().toList()
    }
    fun addMemory(customerId: String, text: String, source: String = "call") {
        store.memories().insertOne(Document("customerId", customerId).append("text", text.take(4000))
            .append("source", source).append("createdAt", Date()))
    }
    fun instructionFor(phone: String): String = findByPhone(phone)?.instructions.orEmpty()
    fun memoriesFor(phone: String, limit: Int = 10): String {
        val customerId = findByPhone(phone)?.id?.takeIf(ObjectId::isValid) ?: return ""
        return store.memories().find(Filters.eq("customerId", customerId)).sort(Document("createdAt", -1))
            .limit(limit.coerceIn(1, 20)).map { it.getString("text").orEmpty() }.filter(String::isNotBlank)
            .toList().asReversed().joinToString("\n") { "- $it" }
    }
    fun saveSchedule(schedule: CallSchedule): String {
        val id = schedule.id?.takeIf { ObjectId.isValid(it) }?.let(::ObjectId) ?: ObjectId()
        store.schedules().replaceOne(Document("_id", id), Document("customerId", schedule.customerId)
            .append("phone", schedule.phone).append("runAt", Date(schedule.runAt))
            .append("status", schedule.status).append("note", schedule.note)
            .append("campaignId", schedule.campaignId.orEmpty()), com.mongodb.client.model.ReplaceOptions().upsert(true))
        return id.toHexString()
    }
    fun claimDueSchedule(now: Long = System.currentTimeMillis()): CallSchedule? {
        val doc = store.schedules().findOneAndUpdate(
            Filters.and(
                Filters.eq("status", "pending"),
                Filters.lte("runAt", Date(now)),
            ),
            Updates.set("status", "running"),
            FindOneAndUpdateOptions().sort(Document("runAt", 1)).returnDocument(ReturnDocument.AFTER),
        ) ?: return null
        return scheduleFrom(doc)
    }
    fun updateScheduleStatus(id: String, status: String, error: String = "") {
        if (!ObjectId.isValid(id)) return
        val updates = mutableListOf<Bson>(Updates.set("status", status))
        if (error.isBlank()) updates += Updates.unset("error") else updates += Updates.set("error", error.take(500))
        store.schedules().updateOne(Filters.eq("_id", ObjectId(id)), Updates.combine(updates))
    }
    fun saveSmsSchedule(schedule: SmsSchedule): String {
        val id = schedule.id?.takeIf { ObjectId.isValid(it) }?.let(::ObjectId) ?: ObjectId()
        store.smsSchedules().replaceOne(Document("_id", id), Document("customerId", schedule.customerId)
            .append("phone", schedule.phone).append("runAt", Date(schedule.runAt))
            .append("message", schedule.message).append("status", schedule.status)
            .append("campaignId", schedule.campaignId.orEmpty()).append("note", schedule.note),
            com.mongodb.client.model.ReplaceOptions().upsert(true))
        return id.toHexString()
    }
    fun claimDueSmsSchedule(now: Long = System.currentTimeMillis()): SmsSchedule? {
        val doc = store.smsSchedules().findOneAndUpdate(
            Filters.and(Filters.eq("status", "queued"), Filters.lte("runAt", Date(now))),
            Updates.set("status", "running"),
            FindOneAndUpdateOptions().sort(Document("runAt", 1)).returnDocument(ReturnDocument.AFTER),
        ) ?: return null
        return smsScheduleFrom(doc)
    }
    fun updateSmsScheduleStatus(id: String, status: String, error: String = "") {
        if (!ObjectId.isValid(id)) return
        val updates = mutableListOf<Bson>(Updates.set("status", status))
        if (error.isBlank()) updates += Updates.unset("error") else updates += Updates.set("error", error.take(500))
        store.smsSchedules().updateOne(Filters.eq("_id", ObjectId(id)), Updates.combine(updates))
    }
    fun saveCallReport(report: AiCallReport) {
        store.calls().insertOne(Document("phone", report.number.orEmpty())
            .append("name", report.name.orEmpty()).append("role", report.role.orEmpty()).append("company", report.company.orEmpty())
            .append("startedAt", Date(report.startedAt)).append("endedAt", Date(report.endedAt))
            .append("cause", report.cause?.name.orEmpty()).append("failureDetail", report.failureDetail.take(500))
            .append("inputText", report.inputText.take(12000)).append("outputText", report.outputText.take(12000))
            .append("conversationTranscript", report.conversationTranscript.take(24000))
            .append("inputFrames", report.inputFrames).append("outputFrames", report.outputFrames)
            .append("inputAttempts", report.inputAttempts).append("inputSendFailures", report.inputSendFailures)
            .append("outputAudioChunks", report.outputAudioChunks).append("outputAudioBytes", report.outputAudioBytes)
            .append("outputDroppedFrames", report.outputDroppedFrames).append("outputQueuePeak", report.outputQueuePeak)
            .append("firstInputSentAt", report.firstInputSentAt ?: 0L).append("lastInputSentAt", report.lastInputSentAt ?: 0L)
            .append("firstOutputAt", report.firstOutputAt ?: 0L).append("firstOutputQueuedAt", report.firstOutputQueuedAt ?: 0L)
            .append("firstOutputPlayedAt", report.firstOutputPlayedAt ?: 0L).append("turnCompleteAt", report.turnCompleteAt ?: 0L)
            .append("turnResponseCount", report.turnResponseCount).append("lastTurnResponseMs", report.lastTurnResponseMs ?: 0L)
            .append("averageTurnResponseMs", report.averageTurnResponseMs ?: 0L)
            .append("lastTurnSpeechDurationMs", report.lastTurnSpeechDurationMs ?: 0L)
            .append("averageTurnSpeechDurationMs", report.averageTurnSpeechDurationMs ?: 0L)
            .append("turnCount", report.turnCount).append("lastSpeechEndAt", report.lastSpeechEndAt ?: 0L)
            .append("createdAt", Date()))
        val customerId = report.number?.let(::findByPhone)?.id
        if (!customerId.isNullOrBlank()) {
            val memory = buildString {
                append("نتيجة مكالمة ${Date(report.endedAt)}: ")
                append(report.cause?.name ?: "NORMAL")
                if (report.conversationTranscript.isNotBlank()) append("\n").append(report.conversationTranscript)
                else if (report.outputText.isNotBlank()) append("\nرد Gemini: ").append(report.outputText)
            }
            addMemory(customerId, memory, "gemini_call")
        }
    }
    fun listSchedules(limit: Int = 20): List<CallSchedule> = store.schedules().find().sort(Document("runAt", 1)).limit(limit)
        .map(::scheduleFrom)
        .toList()
    fun listSmsSchedules(limit: Int = 20): List<SmsSchedule> = store.smsSchedules().find().sort(Document("runAt", 1)).limit(limit)
        .map(::smsScheduleFrom)
        .toList()
    private fun scheduleFrom(it: Document): CallSchedule = CallSchedule(
        it.getObjectId("_id")?.toHexString(), it.getString("customerId").orEmpty(), it.getString("phone").orEmpty(),
        it.getDate("runAt")?.time ?: 0L, it.getString("status").orEmpty(), it.getString("note").orEmpty(),
        it.getString("campaignId")?.ifBlank { null },
    )
    private fun smsScheduleFrom(it: Document): SmsSchedule = SmsSchedule(
        id = it.getObjectId("_id")?.toHexString(), customerId = it.getString("customerId").orEmpty(),
        phone = it.getString("phone").orEmpty(), runAt = it.getDate("runAt")?.time ?: 0L,
        message = it.getString("message").orEmpty(), status = it.getString("status").orEmpty(),
        campaignId = it.getString("campaignId")?.ifBlank { null }, note = it.getString("note").orEmpty(),
    )
    private fun from(d: Document?): VoicxCustomer? = d?.let { VoicxCustomer(
        id = it.getObjectId("_id")?.toHexString(), phone = it.getString("phone").orEmpty(), name = it.getString("name").orEmpty(),
        role = it.getString("role").orEmpty(), company = it.getString("company").orEmpty(), instructions = it.getString("instructions").orEmpty(),
        notes = it.getString("notes").orEmpty(), updatedAt = it.getDate("updatedAt")?.time ?: 0L) }
}

internal class SmsApiClient(private val apiKey: String, private val endpoint: String = "https://adasms-standalone.vercel.app/api/sms/send") {
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    fun send(phone: String, message: String): Result<String> = runCatching {
        require(apiKey.isNotBlank()) { "SMS API key is not configured" }
        require(phone.matches(Regex("\\+?[0-9]{7,20}"))) { "Invalid phone number" }
        require(message.isNotBlank() && message.length <= 1000) { "Invalid SMS message" }
        val body = JSONObject().put("phone", phone).put("message", message).toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(endpoint).header("Authorization", "Bearer $apiKey").post(body).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("SMS service rejected request (${response.code})")
            "SMS sent"
        }
    }
}
