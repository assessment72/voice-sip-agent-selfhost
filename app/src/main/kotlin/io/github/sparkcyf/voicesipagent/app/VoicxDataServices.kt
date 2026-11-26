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
import com.mongodb.spi.dns.DnsClient
import com.mongodb.spi.dns.DnsException
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
                .dnsClient(AndroidDnsClient())
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
            .dnsClient(AndroidDnsClient())
            .applyToClusterSettings { it.serverSelectionTimeout(8, TimeUnit.SECONDS) }
            .applyToSocketSettings { it.connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS) }
            .build()
        val c = MongoClients.create(settings)
        try {
            c.getDatabase(databaseName).runCommand(Document("ping", 1))
            client = c
            database = c.getDatabase(databaseName)
            ensureIndexes()
            state = MongoState.CONNECTED
            lastError = ""
            lastSuccessAt = System.currentTimeMillis()
        } catch (failure: Throwable) {
            c.close()
            client = null
            database = null
            throw failure
        }
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
    fun campaigns(): MongoCollection<Document> = requireDatabase().getCollection("campaigns")
    fun campaignCustomers(): MongoCollection<Document> = requireDatabase().getCollection("campaign_customers")
    fun interestedCustomers(): MongoCollection<Document> = requireDatabase().getCollection("interested_customers")

    private fun ensureIndexes() {
        customers().createIndex(Document("createdAt", 1).append("_id", 1))
        schedules().createIndex(Document("status", 1).append("runAt", 1))
        schedules().createIndex(Document("idempotencyKey", 1))
        smsSchedules().createIndex(Document("status", 1).append("runAt", 1))
        smsSchedules().createIndex(Document("idempotencyKey", 1))
        campaigns().createIndex(Document("status", 1).append("startAt", 1))
        campaignCustomers().createIndex(Document("campaignId", 1).append("status", 1).append("sequence", 1))
        campaignCustomers().createIndex(Document("phone", 1).append("createdAt", -1))
        interestedCustomers().createIndex(Document("campaignCustomerId", 1), com.mongodb.client.model.IndexOptions().unique(true))
    }

    private fun safeError(e: Throwable): String {
        val chain = generateSequence(e) { it.cause }.toList()
        val missingClass = chain.filterIsInstance<NoClassDefFoundError>().firstOrNull()
        if (missingClass != null) {
            return "Missing runtime class: ${missingClass.message.orEmpty().replace('/', '.').take(180)}"
        }
        val root = chain.lastOrNull() ?: e
        return when (root) {
            is IllegalArgumentException -> root.message.orEmpty().take(240)
            else -> "${root.javaClass.simpleName}: ${root.message?.take(180).orEmpty().ifBlank { "connection failed" }}".take(240)
        }
    }
}

/** Android has no JNDI DNS provider, so mongodb+srv needs an explicit DNS client. */
private class AndroidDnsClient : DnsClient {
    override fun getResourceRecordData(name: String, type: String): List<String> = runCatching {
        val encodedName = java.net.URLEncoder.encode(name, "UTF-8")
        val request = Request.Builder()
            .url("https://dns.google/resolve?name=$encodedName&type=$type")
            .header("Accept", "application/dns-json")
            .get().build()
        ANDROID_DNS_HTTP.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("DNS HTTPS request failed (${response.code})")
            val body = response.body?.string().orEmpty()
            val json = JSONObject(body)
            val status = json.optInt("Status", 0)
            if (status != 0) return@use emptyList<String>()
            val answers = json.optJSONArray("Answer") ?: return@use emptyList<String>()
            buildList {
                for (index in 0 until answers.length()) {
                    val data = answers.optJSONObject(index)?.optString("data").orEmpty()
                    if (data.isNotBlank()) add(if (type.equals("TXT", true)) data.trim().removeSurrounding("\"") else data)
                }
            }
        }
    }.getOrElse { throw DnsException("DNS lookup failed for $name ($type)", it) }

    private companion object {
        /** One process-wide client reuses dispatcher, connection-pool and TLS resources. */
        val ANDROID_DNS_HTTP: OkHttpClient = OkHttpClient.Builder()
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
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

internal class VoicxCustomerRepository(private val store: VoicxMongoStore) {
    fun upsert(customer: VoicxCustomer): String {
        val id = customer.id?.takeIf { ObjectId.isValid(it) }?.let(::ObjectId) ?: ObjectId()
        val doc = Document("phone", customer.phone.trim()).append("name", customer.name.trim())
            .append("role", customer.role.trim()).append("company", customer.company.trim())
            .append("instructions", customer.instructions.trim()).append("notes", customer.notes.trim())
            .append("groups", customer.groups.distinct().filter { it.isNotBlank() })
            .append("createdAt", Date(customer.createdAt)).append("updatedAt", Date(customer.updatedAt))
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
        schedule.idempotencyKey?.takeIf { it.isNotBlank() }?.let { key ->
            store.schedules().find(Filters.eq("idempotencyKey", key)).first()?.getObjectId("_id")?.let { return it.toHexString() }
        }
        val id = schedule.id?.takeIf { ObjectId.isValid(it) }?.let(::ObjectId) ?: ObjectId()
        store.schedules().replaceOne(Document("_id", id), Document("customerId", schedule.customerId)
            .append("phone", schedule.phone).append("runAt", Date(schedule.runAt))
            .append("status", schedule.status).append("note", schedule.note)
            .append("campaignId", schedule.campaignId.orEmpty())
            .append("campaignCustomerId", schedule.campaignCustomerId.orEmpty())
            .append("idempotencyKey", schedule.idempotencyKey.orEmpty())
            .append("attempts", schedule.attempts), com.mongodb.client.model.ReplaceOptions().upsert(true))
        return id.toHexString()
    }
    fun claimDueSchedule(now: Long = System.currentTimeMillis()): CallSchedule? {
        store.schedules().updateMany(Filters.and(Filters.eq("status", "running"), Filters.lt("claimedAt", Date(now - 10 * 60 * 1000L))), Updates.combine(Updates.set("status", "pending"), Updates.unset("claimedAt")))
        val doc = store.schedules().findOneAndUpdate(
            Filters.and(
                Filters.eq("status", "pending"),
                Filters.lte("runAt", Date(now)),
            ),
            Updates.combine(Updates.set("status", "running"), Updates.set("claimedAt", Date(now)), Updates.inc("attempts", 1)),
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
        schedule.idempotencyKey?.takeIf { it.isNotBlank() }?.let { key ->
            store.smsSchedules().find(Filters.eq("idempotencyKey", key)).first()?.getObjectId("_id")?.let { return it.toHexString() }
        }
        val id = schedule.id?.takeIf { ObjectId.isValid(it) }?.let(::ObjectId) ?: ObjectId()
        store.smsSchedules().replaceOne(Document("_id", id), Document("customerId", schedule.customerId)
            .append("phone", schedule.phone).append("runAt", Date(schedule.runAt))
            .append("message", schedule.message).append("status", schedule.status)
            .append("campaignId", schedule.campaignId.orEmpty()).append("note", schedule.note)
            .append("campaignCustomerId", schedule.campaignCustomerId.orEmpty())
            .append("idempotencyKey", schedule.idempotencyKey.orEmpty())
            .append("attempts", schedule.attempts),
            com.mongodb.client.model.ReplaceOptions().upsert(true))
        return id.toHexString()
    }
    fun claimDueSmsSchedule(now: Long = System.currentTimeMillis()): SmsSchedule? {
        store.smsSchedules().updateMany(Filters.and(Filters.eq("status", "running"), Filters.lt("claimedAt", Date(now - 10 * 60 * 1000L))), Updates.combine(Updates.set("status", "queued"), Updates.unset("claimedAt")))
        val doc = store.smsSchedules().findOneAndUpdate(
            Filters.and(Filters.eq("status", "queued"), Filters.lte("runAt", Date(now))),
            Updates.combine(Updates.set("status", "running"), Updates.set("claimedAt", Date(now)), Updates.inc("attempts", 1)),
            FindOneAndUpdateOptions().sort(Document("runAt", 1)).returnDocument(ReturnDocument.AFTER),
        ) ?: return null
        return smsScheduleFrom(doc)
    }
    fun campaignCustomerBlocked(id: String?): Boolean {
        if (id.isNullOrBlank() || !ObjectId.isValid(id)) return false
        return store.campaignCustomers().find(Filters.eq("_id", ObjectId(id))).first()?.getString("status") in listOf("admin", "opted_out", "paused")
    }
    fun latestCampaignCustomer(phone: String): String? = store.campaignCustomers().find(Filters.eq("phone", phone.trim()))
        .sort(Document("createdAt", -1)).first()?.getObjectId("_id")?.toHexString()
    fun updateSmsScheduleStatus(id: String, status: String, error: String = "") {
        if (!ObjectId.isValid(id)) return
        val updates = mutableListOf<Bson>(Updates.set("status", status))
        if (error.isBlank()) updates += Updates.unset("error") else updates += Updates.set("error", error.take(500))
        store.smsSchedules().updateOne(Filters.eq("_id", ObjectId(id)), Updates.combine(updates))
    }
    fun saveCallReport(report: AiCallReport, scheduleId: String? = null, campaignCustomerId: String? = null) {
        val campaignId = campaignCustomerId?.takeIf(ObjectId::isValid)?.let { id ->
            store.campaignCustomers().find(Filters.eq("_id", ObjectId(id))).first()?.getString("campaignId")
        }
        store.calls().insertOne(Document("phone", report.number.orEmpty())
            .append("name", report.name.orEmpty()).append("role", report.role.orEmpty()).append("company", report.company.orEmpty())
            .append("startedAt", Date(report.startedAt)).append("endedAt", Date(report.endedAt))
            .append("callId", report.callId).append("scheduleId", scheduleId.orEmpty())
            .append("campaignId", campaignId.orEmpty()).append("campaignCustomerId", campaignCustomerId.orEmpty())
            .append("cause", report.cause?.name.orEmpty())
            .append("failureDetail", report.failureDetail.take(500))
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

    /** Creates a stable customer snapshot and schedules only the first call in a call campaign. */
    fun createCampaign(campaign: Campaign): String {
        val campaignId = campaign.id?.takeIf(ObjectId::isValid)?.let(::ObjectId) ?: ObjectId()
        store.campaigns().insertOne(Document("_id", campaignId).append("name", campaign.name.trim())
            .append("startAt", Date(campaign.startAt)).append("timezone", campaign.timezone)
            .append("targetGroup", campaign.targetGroup.trim()).append("channel", campaign.channel)
            .append("smsTemplate", campaign.smsTemplate.take(1000)).append("status", campaign.status)
            .append("createdAt", Date(campaign.createdAt)).append("updatedAt", Date(campaign.updatedAt)))
        val filter: Bson = Filters.and(Filters.ne("optedOut", true), if (campaign.targetGroup.isBlank() || campaign.targetGroup.equals("all", true))
            Document() else Filters.eq("groups", campaign.targetGroup.trim()))
        val customers = store.customers().find(filter).sort(Document("createdAt", 1).append("_id", 1)).toList()
        customers.forEachIndexed { index, doc ->
            val customerId = doc.getObjectId("_id")?.toHexString() ?: return@forEachIndexed
            store.campaignCustomers().insertOne(Document("campaignId", campaignId.toHexString())
                .append("customerId", customerId).append("phone", doc.getString("phone").orEmpty())
                .append("sequence", index).append("status", "queued").append("attempts", 0)
                .append("createdAt", Date()).append("updatedAt", Date()))
        }
        if (customers.isNotEmpty() && campaign.channel.contains("call")) {
            val first = store.campaignCustomers().find(Filters.and(Filters.eq("campaignId", campaignId.toHexString()), Filters.eq("sequence", 0))).first()
            if (first != null) {
                val ccId = first.getObjectId("_id")?.toHexString().orEmpty()
                val phone = first.getString("phone").orEmpty(); val customerId = first.getString("customerId").orEmpty()
                saveSchedule(CallSchedule(customerId = customerId, phone = phone, runAt = campaign.startAt,
                    campaignId = campaignId.toHexString(), campaignCustomerId = ccId,
                    idempotencyKey = "${campaignId}:call:$ccId:${campaign.startAt}"))
                if (campaign.channel == "call_sms" && campaign.smsTemplate.isNotBlank()) {
                    saveSmsSchedule(SmsSchedule(customerId = customerId, phone = phone, runAt = campaign.startAt,
                        message = campaign.smsTemplate, campaignId = campaignId.toHexString(), campaignCustomerId = ccId,
                        idempotencyKey = "${campaignId}:sms:$ccId:${campaign.startAt}"))
                }
                store.campaignCustomers().updateOne(Filters.eq("_id", first.getObjectId("_id")), Updates.set("status", "call_scheduled"))
            }
        } else if (customers.isNotEmpty() && campaign.channel == "sms" && campaign.smsTemplate.isNotBlank()) {
            customers.forEachIndexed { index, doc ->
                val customerId = doc.getObjectId("_id")?.toHexString().orEmpty(); val phone = doc.getString("phone").orEmpty()
                val cc = store.campaignCustomers().find(Filters.and(Filters.eq("campaignId", campaignId.toHexString()), Filters.eq("sequence", index))).first()
                val ccId = cc?.getObjectId("_id")?.toHexString().orEmpty()
                saveSmsSchedule(SmsSchedule(customerId = customerId, phone = phone, runAt = campaign.startAt,
                    message = campaign.smsTemplate, campaignId = campaignId.toHexString(), campaignCustomerId = ccId,
                    idempotencyKey = "${campaignId}:sms:$ccId:${campaign.startAt}"))
                cc?.getObjectId("_id")?.let { store.campaignCustomers().updateOne(Filters.eq("_id", it), Updates.set("status", "sms_scheduled")) }
            }
        }
        return campaignId.toHexString()
    }

    fun setCampaignDecision(campaignCustomerId: String, outcome: String, note: String = "") {
        if (!ObjectId.isValid(campaignCustomerId)) return
        val now = Date(); val status = when (outcome) { "interested", "admin" -> "admin"; "opted_out" -> "opted_out"; "paused" -> "paused"; else -> "follow_up" }
        val updates = mutableListOf<Bson>(Updates.set("status", status), Updates.set("lastOutcome", outcome), Updates.set("followUpNote", note.take(1000)), Updates.set("updatedAt", now))
        if (status == "admin") { updates += Updates.set("interestedAt", now); updates += Updates.set("adminAt", now) }
        if (status == "paused") updates += Updates.set("pausedAt", now)
        if (status == "opted_out") updates += Updates.set("optedOutAt", now)
        store.campaignCustomers().updateOne(Filters.eq("_id", ObjectId(campaignCustomerId)), Updates.combine(updates))
        if (status == "admin" || status == "opted_out" || status == "paused") {
            store.schedules().updateMany(Filters.and(Filters.eq("campaignCustomerId", campaignCustomerId), Filters.eq("status", "pending")), Updates.set("status", "cancelled"))
            store.smsSchedules().updateMany(Filters.and(Filters.eq("campaignCustomerId", campaignCustomerId), Filters.eq("status", "queued")), Updates.set("status", "cancelled"))
        }
        if (status == "admin") store.interestedCustomers().updateOne(Filters.eq("campaignCustomerId", campaignCustomerId),
            Updates.combine(Updates.set("campaignCustomerId", campaignCustomerId), Updates.set("outcome", outcome), Updates.set("note", note.take(1000)), Updates.set("updatedAt", now)),
            com.mongodb.client.model.UpdateOptions().upsert(true))
        if (status == "opted_out") {
            val customerId = store.campaignCustomers().find(Filters.eq("_id", ObjectId(campaignCustomerId))).first()?.getString("customerId")
            if (!customerId.isNullOrBlank() && ObjectId.isValid(customerId)) store.customers().updateOne(Filters.eq("_id", ObjectId(customerId)), Updates.set("optedOut", true))
        }
    }

    fun scheduleFollowUp(campaignCustomerId: String, nextCallAt: Long?, nextSmsAt: Long?, note: String): Boolean {
        if (!ObjectId.isValid(campaignCustomerId)) return false
        val cc = store.campaignCustomers().find(Filters.eq("_id", ObjectId(campaignCustomerId))).first() ?: return false
        if (cc.getString("status") in listOf("admin", "opted_out")) return false
        val campaignId = cc.getString("campaignId").orEmpty(); val customerId = cc.getString("customerId").orEmpty(); val phone = cc.getString("phone").orEmpty()
        val changes = mutableListOf<Bson>(Updates.set("status", "follow_up"), Updates.set("followUpNote", note.take(1000)), Updates.set("updatedAt", Date()))
        changes += if (nextCallAt == null) Updates.unset("nextCallAt") else Updates.set("nextCallAt", Date(nextCallAt))
        changes += if (nextSmsAt == null) Updates.unset("nextSmsAt") else Updates.set("nextSmsAt", Date(nextSmsAt))
        store.campaignCustomers().updateOne(Filters.eq("_id", ObjectId(campaignCustomerId)), Updates.combine(changes))
        if (nextCallAt != null) saveSchedule(CallSchedule(customerId = customerId, phone = phone, runAt = nextCallAt, note = note, campaignId = campaignId, campaignCustomerId = campaignCustomerId, idempotencyKey = "$campaignCustomerId:call:$nextCallAt"))
        if (nextSmsAt != null) saveSmsSchedule(SmsSchedule(customerId = customerId, phone = phone, runAt = nextSmsAt, message = note, note = note, campaignId = campaignId, campaignCustomerId = campaignCustomerId, idempotencyKey = "$campaignCustomerId:sms:$nextSmsAt"))
        return true
    }

    fun advanceCampaign(campaignCustomerId: String) {
        if (!ObjectId.isValid(campaignCustomerId)) return
        val current = store.campaignCustomers().find(Filters.eq("_id", ObjectId(campaignCustomerId))).first() ?: return
        val next = store.campaignCustomers().find(Filters.and(Filters.eq("campaignId", current.getString("campaignId")), Filters.eq("status", "queued"))).sort(Document("sequence", 1)).first() ?: return
        val campaign = store.campaigns().find(Filters.eq("_id", ObjectId(current.getString("campaignId")))).first() ?: return
        val nextId = next.getObjectId("_id")?.toHexString().orEmpty(); val at = System.currentTimeMillis()
        if (campaign.getString("channel").orEmpty().contains("call")) {
            saveSchedule(CallSchedule(customerId = next.getString("customerId").orEmpty(), phone = next.getString("phone").orEmpty(), runAt = at,
                campaignId = current.getString("campaignId"), campaignCustomerId = nextId, idempotencyKey = "${current.getString("campaignId")}:call:$nextId:$at"))
            if (campaign.getString("channel") == "call_sms" && campaign.getString("smsTemplate").orEmpty().isNotBlank()) saveSmsSchedule(SmsSchedule(customerId = next.getString("customerId").orEmpty(), phone = next.getString("phone").orEmpty(), runAt = at, message = campaign.getString("smsTemplate"), campaignId = current.getString("campaignId"), campaignCustomerId = nextId, idempotencyKey = "${current.getString("campaignId")}:sms:$nextId:$at"))
            store.campaignCustomers().updateOne(Filters.eq("_id", next.getObjectId("_id")), Updates.set("status", "call_scheduled"))
        }
    }
    private fun scheduleFrom(it: Document): CallSchedule = CallSchedule(
        it.getObjectId("_id")?.toHexString(), it.getString("customerId").orEmpty(), it.getString("phone").orEmpty(),
        it.getDate("runAt")?.time ?: 0L, it.getString("status").orEmpty(), it.getString("note").orEmpty(),
        it.getString("campaignId")?.ifBlank { null }, it.getString("campaignCustomerId")?.ifBlank { null },
        it.getString("idempotencyKey")?.ifBlank { null }, it.getInteger("attempts", 0),
    )
    private fun smsScheduleFrom(it: Document): SmsSchedule = SmsSchedule(
        id = it.getObjectId("_id")?.toHexString(), customerId = it.getString("customerId").orEmpty(),
        phone = it.getString("phone").orEmpty(), runAt = it.getDate("runAt")?.time ?: 0L,
        message = it.getString("message").orEmpty(), status = it.getString("status").orEmpty(),
        campaignId = it.getString("campaignId")?.ifBlank { null }, note = it.getString("note").orEmpty(),
        campaignCustomerId = it.getString("campaignCustomerId")?.ifBlank { null },
        idempotencyKey = it.getString("idempotencyKey")?.ifBlank { null }, attempts = it.getInteger("attempts", 0),
    )
    private fun from(d: Document?): VoicxCustomer? = d?.let { VoicxCustomer(
        id = it.getObjectId("_id")?.toHexString(), phone = it.getString("phone").orEmpty(), name = it.getString("name").orEmpty(),
        role = it.getString("role").orEmpty(), company = it.getString("company").orEmpty(), instructions = it.getString("instructions").orEmpty(),
        notes = it.getString("notes").orEmpty(), groups = it.getList("groups", String::class.java) ?: emptyList(),
        createdAt = it.getDate("createdAt")?.time ?: it.getDate("updatedAt")?.time ?: 0L,
        updatedAt = it.getDate("updatedAt")?.time ?: 0L) }
}

internal class SmsApiClient(private val apiKey: String, private val endpoint: String = "https://adasms-standalone.vercel.app/api/sms/send") {
    fun send(phone: String, message: String): Result<String> = runCatching {
        require(apiKey.isNotBlank()) { "SMS API key is not configured" }
        require(phone.matches(Regex("\\+?[0-9]{7,20}"))) { "Invalid phone number" }
        require(message.isNotBlank() && message.length <= 1000) { "Invalid SMS message" }
        val body = JSONObject().put("phone", phone).put("message", message).toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(endpoint).header("Authorization", "Bearer $apiKey").post(body).build()
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
