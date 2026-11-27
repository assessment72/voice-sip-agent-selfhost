package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Local, offline-first storage. Data is kept in the app's private SQLite database. */
internal class VoicxFirebaseStore(context: Context) {
    private val db = LocalDatabase(context.applicationContext)
    @Volatile var state: FirebaseState = FirebaseState.DISCONNECTED
        private set
    @Volatile var lastSuccessAt: Long? = null
        private set
    @Volatile var lastError: String = "Firebase sync adapter is not configured; local SQLite remains available"
        private set

    fun test(projectId: String = "", apiKey: String = "", applicationId: String = ""): Result<Long> {
        state = FirebaseState.ERROR
        lastError = "Firebase Firestore integration is not implemented in this build; data remains local in SQLite"
        return Result.failure(IllegalStateException(lastError))
    }

    fun connect(projectId: String = "", apiKey: String = "", applicationId: String = ""): Result<Unit> {
        state = FirebaseState.ERROR
        lastError = "Firebase Firestore integration is not implemented in this build; local SQLite is still available"
        return Result.failure(IllegalStateException(lastError))
    }

    fun disconnect() {
        state = FirebaseState.DISCONNECTED
        lastError = "Cloud sync is disabled; local SQLite remains available"
    }
    fun requireDatabase(): LocalDatabase = db
    fun collection(name: String) = db.all(name)
}

enum class FirebaseState { DISCONNECTED, CONNECTED, ERROR }

internal object LocalDocumentPatch {
    fun merge(existing: JSONObject?, values: Map<String, Any?>): JSONObject {
        val merged = existing?.let { JSONObject(it.toString()) } ?: JSONObject()
        values.forEach { (key, value) ->
            if (value == null) merged.remove(key)
            else merged.put(key, when (value) {
                is List<*> -> JSONArray(value)
                else -> value
            })
        }
        return merged
    }
}

internal object ScheduleLeasePolicy {
    const val CLAIM_LEASE_MS = 30 * 60 * 1000L

    fun isExpired(status: String, claimedAt: Long, now: Long): Boolean =
        status == "running" && claimedAt > 0L && claimedAt <= now - CLAIM_LEASE_MS
}

internal object CustomerSuppressionPolicy {
    fun isBlocked(memberStatus: String, globallyOptedOut: Boolean): Boolean =
        globallyOptedOut || memberStatus in listOf("admin", "opted_out", "paused")
}

internal class LocalDatabase(context: Context) : SQLiteOpenHelper(context, "voicx_local.db", null, 1) {
    private val databaseFile = context.getDatabasePath("voicx_local.db")
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE documents (collection TEXT NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(collection, id))")
        db.execSQL("CREATE INDEX documents_collection_idx ON documents(collection)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized fun exportTo(output: OutputStream) {
        writableDatabase.execSQL("PRAGMA wal_checkpoint(FULL)")
        output.use { destination -> databaseFile.inputStream().use { it.copyTo(destination) } }
    }

    @Synchronized fun importFrom(input: InputStream): File {
        val temp = File(databaseFile.parentFile, "voicx_local.db.import-${System.currentTimeMillis()}.tmp")
        input.use { source -> temp.outputStream().use { source.copyTo(it) } }
        val header = temp.inputStream().use { it.readNBytes(16).toString(Charsets.US_ASCII) }
        require(header == "SQLite format 3\u0000") { "الملف ليس قاعدة SQLite صالحة" }
        SQLiteDatabase.openDatabase(temp.path, null, SQLiteDatabase.OPEN_READONLY).use { checkDb ->
            checkDb.rawQuery("SELECT count(*) FROM documents", null).use { cursor -> require(cursor.moveToFirst()) { "قاعدة SQLite لا تحتوي على جدول Voicx" } }
        }
        close()
        File(databaseFile.path + "-wal").delete()
        File(databaseFile.path + "-shm").delete()
        val backup = File(databaseFile.parentFile, "voicx_local.db.backup-${System.currentTimeMillis()}")
        if (databaseFile.exists()) require(databaseFile.renameTo(backup)) { "تعذر إنشاء نسخة احتياطية" }
        try {
            require(temp.renameTo(databaseFile)) { "تعذر استبدال قاعدة البيانات" }
            return backup
        } catch (error: Exception) {
            if (databaseFile.exists()) databaseFile.delete()
            backup.renameTo(databaseFile)
            throw error
        } finally {
            temp.delete()
        }
    }

    @Synchronized fun put(collection: String, id: String, values: Map<String, Any?>) {
        val json = JSONObject()
        values.forEach { (key, value) -> if (value != null) json.put(key, when (value) {
            is List<*> -> JSONArray(value)
            else -> value
        }) }
        writableDatabase.insertWithOnConflict("documents", null, android.content.ContentValues().apply {
            put("collection", collection); put("id", id); put("payload", json.toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Run a group of repository operations under one SQLite write transaction. */
    @Synchronized fun <T> transaction(block: () -> T): T {
        val database = writableDatabase
        database.beginTransaction()
        try {
            val result = block()
            database.setTransactionSuccessful()
            return result
        } finally {
            database.endTransaction()
        }
    }

    /**
     * Merge a partial document update without erasing fields written by earlier steps.
     * A null value removes that key; non-null values replace only their own keys.
     */
    @Synchronized fun patch(collection: String, id: String, values: Map<String, Any?>) {
        val merged = LocalDocumentPatch.merge(read(collection, id)?.data, values)
        writableDatabase.insertWithOnConflict("documents", null, android.content.ContentValues().apply {
            put("collection", collection); put("id", id); put("payload", merged.toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun read(collection: String, id: String): LocalDocument? {
        readableDatabase.query("documents", arrayOf("id", "payload"), "collection=? AND id=?", arrayOf(collection, id), null, null, null).use {
            return if (it.moveToFirst()) LocalDocument(it.getString(0), JSONObject(it.getString(1))) else null
        }
    }

    @Synchronized fun all(collection: String): List<LocalDocument> {
        val out = mutableListOf<LocalDocument>()
        readableDatabase.query("documents", arrayOf("id", "payload"), "collection=?", arrayOf(collection), null, null, null).use {
            while (it.moveToNext()) out += LocalDocument(it.getString(0), JSONObject(it.getString(1)))
        }
        return out
    }
}

internal data class LocalDocument(val id: String, val data: JSONObject) {
    fun text(key: String) = data.optString(key, "")
    fun long(key: String) = data.optLong(key, 0L)
    fun int(key: String) = data.optInt(key, 0)
    fun nullableText(key: String) = text(key).ifBlank { null }
    fun strings(key: String): List<String> = data.optJSONArray(key)?.let { a ->
        (0 until a.length()).mapNotNull { a.optString(it, null) }
    } ?: emptyList()
}

data class VoicxCustomer(val id: String? = null, val phone: String, val name: String, val role: String = "", val company: String = "", val instructions: String = "", val notes: String = "", val groups: List<String> = emptyList(), val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = System.currentTimeMillis())
data class Campaign(val id: String? = null, val name: String, val startAt: Long, val timezone: String = java.time.ZoneId.systemDefault().id, val targetGroup: String = "", val channel: String = "call_sms", val smsTemplate: String = "", val status: String = "queued", val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = System.currentTimeMillis())
data class CampaignCustomer(val id: String? = null, val campaignId: String, val customerId: String, val phone: String, val sequence: Int, val status: String = "queued", val attempts: Int = 0, val lastCallAt: Long? = null, val lastSmsAt: Long? = null, val nextCallAt: Long? = null, val nextSmsAt: Long? = null, val lastOutcome: String = "", val followUpNote: String = "", val interestedAt: Long? = null, val adminAt: Long? = null, val pausedAt: Long? = null, val optedOutAt: Long? = null, val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = System.currentTimeMillis())
data class CallSchedule(val id: String? = null, val customerId: String, val phone: String, val runAt: Long, val status: String = "pending", val note: String = "", val campaignId: String? = null, val campaignCustomerId: String? = null, val idempotencyKey: String? = null, val attempts: Int = 0)
data class SmsSchedule(val id: String? = null, val customerId: String = "", val phone: String, val runAt: Long, val message: String, val status: String = "queued", val campaignId: String? = null, val note: String = "", val campaignCustomerId: String? = null, val idempotencyKey: String? = null, val attempts: Int = 0)

private fun idOrNew(id: String?) = id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()

internal class VoicxCustomerRepository(private val store: VoicxFirebaseStore) {
    private val db get() = store.requireDatabase()
    private fun save(name: String, id: String, data: Map<String, Any?>) = db.patch(name, id, data)
    private fun read(name: String, id: String) = db.read(name, id)
    private fun query(name: String, predicate: (LocalDocument) -> Boolean = { true }) = db.all(name).filter(predicate)

    fun upsert(c: VoicxCustomer): String = db.transaction {
        val phone = PhoneNumberNormalizer.normalizeYemen(c.phone)
        val existing = findByPhone(phone) ?: findByPhone(c.phone.trim())
        val id = c.id?.takeIf(String::isNotBlank) ?: existing?.id ?: idOrNew(null)
        save("customers", id, mapOf(
            "phone" to phone,
            "name" to c.name.trim(),
            "role" to c.role.trim(),
            "company" to c.company.trim(),
            "instructions" to c.instructions.trim(),
            "notes" to c.notes.trim(),
            "groups" to c.groups.distinct().filter(String::isNotBlank),
            "createdAt" to (existing?.createdAt ?: c.createdAt),
            "updatedAt" to System.currentTimeMillis(),
        ))
        id
    }

    fun findById(id: String): VoicxCustomer? = if (id.isBlank()) null else read("customers", id)?.let(::from)
    fun findByPhone(phone: String): VoicxCustomer? = query("customers") { it.text("phone") == phone.trim() }.firstOrNull()?.let(::from)
    fun search(q: String, limit: Int = 20): List<VoicxCustomer> { val s = q.trim().lowercase(); return query("customers").mapNotNull(::from).filter { s.isBlank() || listOf(it.phone, it.name, it.company).any { v -> v.lowercase().contains(s) } }.take(limit.coerceIn(1, 50)) }
    fun addMemory(customerId: String, text: String, source: String) = save("customer_memories", UUID.randomUUID().toString(), mapOf("customerId" to customerId, "text" to text.take(4000), "source" to source, "createdAt" to System.currentTimeMillis()))
    fun instructionFor(phone: String) = findByPhone(phone)?.instructions.orEmpty()
    fun memoriesFor(phone: String, limit: Int = 10): String { val id = findByPhone(phone)?.id ?: return ""; return query("customer_memories") { it.text("customerId") == id }.sortedByDescending { it.long("createdAt") }.take(limit.coerceIn(1, 20)).map { it.text("text") }.filter(String::isNotBlank).asReversed().joinToString("\n") { "- $it" } }

    fun saveSchedule(s: CallSchedule): String = db.transaction {
        val existing = s.idempotencyKey?.takeIf(String::isNotBlank)?.let { key ->
            query("call_schedules") { it.text("idempotencyKey") == key }.firstOrNull()?.id
        }
        // Repeated enqueue requests must never reset completed/cancelled work to pending.
        if (existing != null) return@transaction existing
        val id = idOrNew(s.id)
        save("call_schedules", id, mapOf(
            "customerId" to s.customerId, "phone" to s.phone, "runAt" to s.runAt,
            "status" to s.status, "note" to s.note, "campaignId" to s.campaignId,
            "campaignCustomerId" to s.campaignCustomerId, "idempotencyKey" to s.idempotencyKey,
            "attempts" to s.attempts,
        ))
        id
    }

        fun claimDueSchedule(now: Long = System.currentTimeMillis()): CallSchedule? = claim("call_schedules", "pending", now) { scheduleFrom(it) }?.second
    fun updateScheduleStatus(id: String, status: String, error: String = "") { if (id.isNotBlank()) save("call_schedules", id, mapOf("status" to status, "error" to error.takeIf(String::isNotBlank))) }
    fun deferSchedule(id: String, runAt: Long, reason: String) {
        if (id.isBlank()) return
        save("call_schedules", id, mapOf(
            "status" to "pending",
            "runAt" to runAt,
            "claimedAt" to null,
            "error" to reason.take(300),
        ))
    }
    fun saveSmsSchedule(s: SmsSchedule): String = db.transaction {
        val existing = s.idempotencyKey?.takeIf(String::isNotBlank)?.let { key ->
            query("sms_schedules") { it.text("idempotencyKey") == key }.firstOrNull()?.id
        }
        // Do not resurrect a sent or cancelled SMS when the enqueue operation is replayed.
        if (existing != null) return@transaction existing
        val id = idOrNew(s.id)
        save("sms_schedules", id, mapOf(
            "customerId" to s.customerId, "phone" to s.phone, "runAt" to s.runAt,
            "message" to s.message, "status" to s.status, "campaignId" to s.campaignId,
            "note" to s.note, "campaignCustomerId" to s.campaignCustomerId,
            "idempotencyKey" to s.idempotencyKey, "attempts" to s.attempts,
        ))
        id
    }

        fun claimDueSmsSchedule(now: Long = System.currentTimeMillis()): SmsSchedule? = claim("sms_schedules", "queued", now) { smsScheduleFrom(it) }?.second
    fun updateSmsScheduleStatus(id: String, status: String, error: String = "") { if (id.isNotBlank()) save("sms_schedules", id, mapOf("status" to status, "error" to error.takeIf(String::isNotBlank))) }

    private fun <T> claim(name: String, status: String, now: Long, mapper: (LocalDocument) -> T): Pair<String, T>? =
        db.transaction {
            // Recover abandoned claims, then select and mark one due item while holding
            // the same SQLite write transaction. Competing workers cannot claim it twice.
            query(name) { ScheduleLeasePolicy.isExpired(it.text("status"), it.long("claimedAt"), now) }
                .forEach { save(name, it.id, mapOf("status" to status, "claimedAt" to null)) }
            val candidate = query(name) { it.text("status") == status && it.long("runAt") <= now }
                .minByOrNull { it.long("runAt") } ?: return@transaction null
            save(name, candidate.id, mapOf(
                "status" to "running",
                "claimedAt" to now,
                "attempts" to candidate.int("attempts") + 1,
            ))
            candidate.id to mapper(read(name, candidate.id) ?: candidate)
        }

    fun isPhoneOptedOut(phone: String): Boolean {
        val normalized = runCatching { PhoneNumberNormalizer.normalizeYemen(phone) }.getOrNull() ?: return false
        return read("contact_suppressions", normalized)?.text("reason") == "opted_out"
    }

    private fun suppressPhone(phone: String, reason: String = "opted_out") {
        val normalized = PhoneNumberNormalizer.normalizeYemen(phone)
        save("contact_suppressions", normalized, mapOf(
            "phone" to normalized,
            "reason" to reason,
            "updatedAt" to System.currentTimeMillis(),
        ))
    }

    fun campaignCustomerBlocked(id: String?): Boolean = id?.takeIf(String::isNotBlank)?.let {
        val member = read("campaign_customers", it) ?: return@let false
        CustomerSuppressionPolicy.isBlocked(member.text("status"), isPhoneOptedOut(member.text("phone")))
    } ?: false
    fun latestCampaignCustomer(phone: String): String? = query("campaign_customers") { it.text("phone") == phone.trim() }.maxByOrNull { it.long("createdAt") }?.id
    fun saveCallReport(report: AiCallReport, scheduleId: String? = null, campaignCustomerId: String? = null) {
        val campaignId = campaignCustomerId?.let { read("campaign_customers", it)?.text("campaignId") }
            ?: scheduleId?.let { read("call_schedules", it)?.text("campaignId") }
        val id = UUID.randomUUID().toString()
        save("call_summaries", id, mapOf(
            "phone" to report.number.orEmpty(), "name" to report.name.orEmpty(), "role" to report.role.orEmpty(),
            "company" to report.company.orEmpty(), "startedAt" to report.startedAt, "endedAt" to report.endedAt,
            "callId" to report.callId, "scheduleId" to scheduleId.orEmpty(),
            "campaignId" to campaignId.orEmpty(), "campaignCustomerId" to campaignCustomerId.orEmpty(),
            "cause" to report.cause?.name.orEmpty(), "failureDetail" to report.failureDetail.take(500),
            "inputText" to report.inputText.take(12000), "outputText" to report.outputText.take(12000),
            "conversationTranscript" to report.conversationTranscript.take(24000),
            "inputFrames" to report.inputFrames, "outputFrames" to report.outputFrames,
            "inputAttempts" to report.inputAttempts, "inputSendFailures" to report.inputSendFailures,
            "outputAudioChunks" to report.outputAudioChunks, "outputAudioBytes" to report.outputAudioBytes,
            "outputDroppedFrames" to report.outputDroppedFrames, "outputQueuePeak" to report.outputQueuePeak,
            "firstInputSentAt" to (report.firstInputSentAt ?: 0L), "lastInputSentAt" to (report.lastInputSentAt ?: 0L),
            "firstOutputAt" to (report.firstOutputAt ?: 0L), "firstOutputQueuedAt" to (report.firstOutputQueuedAt ?: 0L),
            "firstOutputPlayedAt" to (report.firstOutputPlayedAt ?: 0L), "turnCompleteAt" to (report.turnCompleteAt ?: 0L),
            "turnResponseCount" to report.turnResponseCount, "lastTurnResponseMs" to (report.lastTurnResponseMs ?: 0L),
            "averageTurnResponseMs" to (report.averageTurnResponseMs ?: 0L), "turnCount" to report.turnCount,
            "lastSpeechEndAt" to (report.lastSpeechEndAt ?: 0L), "createdAt" to System.currentTimeMillis(),
        ))
        report.number?.let(::findByPhone)?.id?.let { addMemory(it, "نتيجة مكالمة ${report.endedAt}: ${report.cause?.name ?: "NORMAL"}\n${report.conversationTranscript.ifBlank { report.outputText }}", "gemini_call") }
    }
    fun listSchedules(limit: Int = 20) = query("call_schedules").sortedBy { it.long("runAt") }.take(limit).map(::scheduleFrom)
    fun listSmsSchedules(limit: Int = 20) = query("sms_schedules").sortedBy { it.long("runAt") }.take(limit).map(::smsScheduleFrom)

    fun createCampaign(c: Campaign): String = db.transaction {
        val id = idOrNew(c.id)
        // Replaying a caller-supplied campaign ID is a no-op; never reset member outcomes.
        if (c.id?.isNotBlank() == true && read("campaigns", id) != null) return@transaction id
        save("campaigns", id, mapOf(
            "name" to c.name.trim(), "startAt" to c.startAt, "timezone" to c.timezone,
            "targetGroup" to c.targetGroup.trim(), "channel" to c.channel,
            "smsTemplate" to c.smsTemplate.take(1000), "status" to c.status,
            "createdAt" to c.createdAt, "updatedAt" to c.updatedAt,
        ))
        val targets = query("customers").mapNotNull(::from).filter {
            !isPhoneOptedOut(it.phone) &&
                (c.targetGroup.isBlank() || c.targetGroup.equals("all", true) || it.groups.contains(c.targetGroup.trim()))
        }
        targets.forEachIndexed { i, customer ->
            save("campaign_customers", "$id-$i", mapOf(
                "campaignId" to id, "customerId" to customer.id, "phone" to customer.phone,
                "sequence" to i, "status" to "queued", "attempts" to 0,
                "createdAt" to System.currentTimeMillis(), "updatedAt" to System.currentTimeMillis(),
            ))
        }
        if (targets.isNotEmpty() && c.channel.contains("call")) {
            val cc = "$id-0"
            val first = targets.first()
            saveSchedule(CallSchedule(
                customerId = first.id.orEmpty(), phone = first.phone, runAt = c.startAt,
                campaignId = id, campaignCustomerId = cc, idempotencyKey = "$id:call:$cc",
            ))
            save("campaign_customers", cc, mapOf("status" to "call_scheduled"))
        } else if (targets.isNotEmpty() && c.channel == "sms" && c.smsTemplate.isNotBlank()) {
            targets.forEachIndexed { i, customer ->
                val cc = "$id-$i"
                saveSmsSchedule(SmsSchedule(
                    customerId = customer.id.orEmpty(), phone = customer.phone, runAt = c.startAt,
                    message = c.smsTemplate, campaignId = id, campaignCustomerId = cc,
                    idempotencyKey = "$id:sms:$cc:${c.startAt}",
                ))
                save("campaign_customers", cc, mapOf("status" to "sms_scheduled"))
            }
        }
        id
    }

        fun setCampaignDecision(id: String, outcome: String, note: String) = db.transaction {
        val status = when (outcome) {
            "interested", "admin" -> "admin"
            "opted_out" -> "opted_out"
            "paused" -> "paused"
            else -> "follow_up"
        }
        if (read("campaign_customers", id) == null) return@transaction
        save("campaign_customers", id, mapOf(
            "status" to status, "lastOutcome" to outcome, "followUpNote" to note.take(1000),
            "updatedAt" to System.currentTimeMillis(),
        ))
        if (outcome == "opted_out") {
            val phone = read("campaign_customers", id)?.text("phone").orEmpty()
            if (phone.isNotBlank()) {
                val normalized = PhoneNumberNormalizer.normalizeYemen(phone)
                suppressPhone(normalized)
                query("campaign_customers") { it.text("phone") == normalized && it.text("status") !in listOf("admin", "opted_out") }
                    .forEach { save("campaign_customers", it.id, mapOf("status" to "opted_out", "lastOutcome" to "opted_out", "updatedAt" to System.currentTimeMillis())) }
                query("call_schedules") { it.text("phone") == normalized && it.text("status") in listOf("pending", "running") }
                    .forEach { updateScheduleStatus(it.id, "cancelled", "contact opted out") }
                query("sms_schedules") { it.text("phone") == normalized && it.text("status") in listOf("queued", "running") }
                    .forEach { updateSmsScheduleStatus(it.id, "cancelled", "contact opted out") }
            }
        } else if (status in listOf("admin", "paused")) {
            query("call_schedules") {
                it.text("campaignCustomerId") == id && it.text("status") in listOf("pending", "running")
            }.forEach { updateScheduleStatus(it.id, "cancelled", "campaign customer is blocked") }
            query("sms_schedules") {
                it.text("campaignCustomerId") == id && it.text("status") in listOf("queued", "running")
            }.forEach { updateSmsScheduleStatus(it.id, "cancelled", "campaign customer is blocked") }
        }
    }

        fun scheduleFollowUp(id: String, nextCallAt: Long?, nextSmsAt: Long?, note: String): Boolean = db.transaction {
        val cc = read("campaign_customers", id) ?: return@transaction false
        if (cc.text("status") in listOf("admin", "opted_out", "paused")) return@transaction false
        val customerId = cc.text("customerId")
        val phone = cc.text("phone")
        val campaignId = cc.text("campaignId")
        save("campaign_customers", id, mapOf(
            "status" to "follow_up", "followUpNote" to note.take(1000),
            "nextCallAt" to nextCallAt, "nextSmsAt" to nextSmsAt,
            "updatedAt" to System.currentTimeMillis(),
        ))
        nextCallAt?.let {
            saveSchedule(CallSchedule(
                customerId = customerId, phone = phone, runAt = it, note = note,
                campaignId = campaignId, campaignCustomerId = id, idempotencyKey = "$id:call:$it",
            ))
        }
        nextSmsAt?.let {
            saveSmsSchedule(SmsSchedule(
                customerId = customerId, phone = phone, runAt = it, message = note,
                note = note, campaignId = campaignId, campaignCustomerId = id,
                idempotencyKey = "$id:sms:$it",
            ))
        }
        true
    }

        /** Queue the campaign SMS only after a completed call, never alongside the call itself. */
    fun scheduleCampaignSmsAfterCall(id: String, delayMs: Long = 60_000L): Boolean {
        val member = read("campaign_customers", id) ?: return false
        if (member.text("status") in listOf("admin", "opted_out", "paused")) return false
        val campaign = read("campaigns", member.text("campaignId")) ?: return false
        if (campaign.text("channel") != "call_sms") return false
        val template = campaign.text("smsTemplate")
        if (template.isBlank()) return false
        saveSmsSchedule(SmsSchedule(
            customerId = member.text("customerId"),
            phone = member.text("phone"),
            runAt = System.currentTimeMillis() + delayMs.coerceIn(0L, 86_400_000L),
            message = template,
            campaignId = campaign.id,
            campaignCustomerId = id,
            idempotencyKey = "$id:sms:after-call",
        ))
        return true
    }

    fun advanceCampaign(id: String) = db.transaction {
        val current = read("campaign_customers", id) ?: return@transaction
        val campaignId = current.text("campaignId")
        val next = query("campaign_customers") {
            it.text("campaignId") == campaignId && it.text("status") == "queued"
        }.minByOrNull { it.int("sequence") } ?: return@transaction
        val campaign = read("campaigns", campaignId) ?: return@transaction
        val at = System.currentTimeMillis()
        val customerId = next.text("customerId")
        val phone = next.text("phone")
        if (campaign.text("channel").contains("call")) {
            saveSchedule(CallSchedule(
                customerId = customerId, phone = phone, runAt = at,
                campaignId = campaignId, campaignCustomerId = next.id,
                idempotencyKey = "$campaignId:call:${next.id}",
            ))
            save("campaign_customers", next.id, mapOf("status" to "call_scheduled"))
        } else if (campaign.text("channel") == "sms" && campaign.text("smsTemplate").isNotBlank()) {
            saveSmsSchedule(SmsSchedule(
                customerId = customerId, phone = phone, runAt = at,
                message = campaign.text("smsTemplate"), campaignId = campaignId,
                campaignCustomerId = next.id, idempotencyKey = "$campaignId:sms:${next.id}",
            ))
            save("campaign_customers", next.id, mapOf("status" to "sms_scheduled"))
        }
    }

    private fun scheduleFrom(d: LocalDocument) = CallSchedule(d.id, d.text("customerId"), d.text("phone"), d.long("runAt"), d.text("status"), d.text("note"), d.nullableText("campaignId"), d.nullableText("campaignCustomerId"), d.nullableText("idempotencyKey"), d.int("attempts"))
    private fun smsScheduleFrom(d: LocalDocument) = SmsSchedule(d.id, d.text("customerId"), d.text("phone"), d.long("runAt"), d.text("message"), d.text("status"), d.nullableText("campaignId"), d.text("note"), d.nullableText("campaignCustomerId"), d.nullableText("idempotencyKey"), d.int("attempts"))
    private fun from(d: LocalDocument): VoicxCustomer? = VoicxCustomer(d.id, d.text("phone"), d.text("name"), d.text("role"), d.text("company"), d.text("instructions"), d.text("notes"), d.strings("groups"), d.long("createdAt"), d.long("updatedAt"))
}

internal class SmsApiClient(private val apiKey: String, private val endpoint: String = "https://adasms-standalone.vercel.app/api/sms/send") {
    fun send(phone: String, message: String): Result<String> = runCatching { require(apiKey.isNotBlank()) { "SMS API key is not configured" }; require(phone.matches(Regex("\\+?[0-9]{7,20}"))) { "Invalid phone number" }; require(message.isNotBlank() && message.length <= 1000) { "Invalid SMS message" }; val body = JSONObject().put("phone", phone).put("message", message).toString().toRequestBody("application/json".toMediaType()); val request = Request.Builder().url(endpoint).header("Authorization", "Bearer $apiKey").post(body).build(); SMS_HTTP.newCall(request).execute().use { response -> if (!response.isSuccessful) error("SMS service rejected request (${response.code})"); "SMS sent" } }
    private companion object { val SMS_HTTP: OkHttpClient = OkHttpClient.Builder().callTimeout(java.util.concurrent.TimeUnit.SECONDS.toMillis(15), java.util.concurrent.TimeUnit.MILLISECONDS).build() }
}
