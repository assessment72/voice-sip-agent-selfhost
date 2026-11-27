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
    const val RECONCILIATION_STATUS = "needs_reconciliation"
    const val EXPIRED_CLAIM_ERROR =
        "Execution lease expired; external outcome is unknown. Verify call/SMS history before retrying."

    fun isExpired(status: String, claimedAt: Long, now: Long): Boolean =
        status == "running" && claimedAt > 0L && claimedAt <= now - CLAIM_LEASE_MS

    // Calls and SMS are external side effects. An expired worker may have completed
    // the action before dying, so automatic retry could contact a customer twice.
    fun recoveryStatus(): String = RECONCILIATION_STATUS
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
data class CustomerSmsSequence(val id: String, val customerId: String, val phone: String, val firstAt: Long, val secondAt: Long, val thirdAt: Long, val firstMessage: String, val secondMessage: String, val thirdMessage: String, val status: String, val firstSentAt: Long = 0L, val secondSentAt: Long = 0L, val thirdSentAt: Long = 0L)
enum class ScheduleType { CALL, SMS }
data class ScheduleReconciliationItem(
    val type: ScheduleType,
    val id: String,
    val customerId: String,
    val phone: String,
    val runAt: Long,
    val attempts: Int,
    val error: String,
    val claimedAt: Long,
)

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

    /** Persist inbound SMS only when the sender matches a known customer. Message IDs are
     * deterministic so Android broadcast re-delivery cannot create duplicate replies. */
    fun recordIncomingSms(rawPhone: String?, body: String, receivedAt: Long): Boolean {
        if (rawPhone.isNullOrBlank() || body.isBlank()) return false
        val phone = runCatching { PhoneNumberNormalizer.normalizeYemen(rawPhone) }.getOrNull() ?: return false
        val customer = findByPhone(phone) ?: return false
        val stable = "$phone|$receivedAt|$body"
        val messageId = java.security.MessageDigest.getInstance("SHA-256")
            .digest(stable.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return db.transaction {
            if (read("customer_messages", messageId) != null) return@transaction false
            save("customer_messages", messageId, mapOf(
                "customerId" to customer.id.orEmpty(),
                "phone" to phone,
                "direction" to "inbound",
                "channel" to "sms",
                "body" to body.take(4000),
                "receivedAt" to receivedAt,
                "createdAt" to System.currentTimeMillis(),
            ))
            val normalizedReply = body.trim().lowercase().replace(Regex("\\s+"), " ")
            val optedOut = normalizedReply in setOf("stop", "unsubscribe", "cancel", "إلغاء", "الغاء", "وقف", "لا ترسل", "لا ترسل لي", "أوقف الرسائل", "اوقف الرسائل")
            if (optedOut) {
                suppressPhone(phone)
                query("sms_schedules") { it.text("phone") == phone && it.text("status") in listOf("queued", "pending", "running") }
                    .forEach { save("sms_schedules", it.id, mapOf("status" to "cancelled", "error" to "customer opted out via inbound SMS")) }
                query("customer_sms_sequences") { it.text("phone") == phone && it.text("status") != "completed" }
                    .forEach { save("customer_sms_sequences", it.id, mapOf("status" to "opted_out", "updatedAt" to System.currentTimeMillis())) }
            }
            true
        }
    }

    fun customerSmsRepliesSince(customerId: String, since: Long): List<LocalDocument> =
        query("customer_messages") {
            it.text("customerId") == customerId && it.text("direction") == "inbound" &&
                it.text("channel") == "sms" && it.long("receivedAt") >= since
        }.sortedBy { it.long("receivedAt") }

    fun customerSmsHistorySince(customerId: String, since: Long): List<LocalDocument> =
        query("customer_messages") {
            it.text("customerId") == customerId && it.text("channel") == "sms" &&
                (it.long("receivedAt") >= since || it.long("sentAt") >= since)
        }.sortedBy { maxOf(it.long("receivedAt"), it.long("sentAt")) }

    /** Creates a three-step customer-specific SMS sequence. Only the first stage is queued. */
    fun createCustomerSmsSequence(customerId: String, firstAt: Long, first: String, second: String, third: String): String = db.transaction {
        val customer = findById(customerId) ?: error("العميل غير موجود")
        require(!isPhoneOptedOut(customer.phone)) { "العميل ألغى الاشتراك في الرسائل" }
        require(firstAt > System.currentTimeMillis()) { "موعد الرسالة الأولى يجب أن يكون في المستقبل" }
        require(listOf(first, second, third).all { it.isNotBlank() && it.length <= 1000 }) { "كل رسالة يجب أن تكون بين حرف واحد و1000 حرف" }
        val id = UUID.randomUUID().toString()
        val secondAt = firstAt + 7L * 24 * 60 * 60 * 1000
        val thirdAt = firstAt + 14L * 24 * 60 * 60 * 1000
        save("customer_sms_sequences", id, mapOf(
            "customerId" to customerId, "phone" to customer.phone, "firstAt" to firstAt,
            "secondAt" to secondAt, "thirdAt" to thirdAt,
            "firstMessage" to first.take(1000), "secondMessage" to second.take(1000),
            "thirdMessage" to third.take(1000), "status" to "draft",
            "firstSentAt" to 0L, "secondSentAt" to 0L, "thirdSentAt" to 0L,
            "createdAt" to System.currentTimeMillis(), "updatedAt" to System.currentTimeMillis(),
        ))
        id
    }

    fun activateCustomerSmsSequence(id: String): Boolean = db.transaction {
        val sequence = read("customer_sms_sequences", id) ?: return@transaction false
        if (sequence.text("status") != "draft") return@transaction false
        if (isPhoneOptedOut(sequence.text("phone"))) return@transaction false
        saveSmsScheduleInTransaction(SmsSchedule(
            customerId = sequence.text("customerId"), phone = sequence.text("phone"),
            runAt = sequence.long("firstAt"), message = sequence.text("firstMessage"),
            note = "customer_sequence:$id:1", idempotencyKey = "customer-sequence:$id:1",
        ))
        save("customer_sms_sequences", id, mapOf("status" to "scheduled", "updatedAt" to System.currentTimeMillis()))
        true
    }

    fun updateCustomerSmsSequence(id: String, stage: Int, runAt: Long, message: String): Boolean = db.transaction {
        require(stage in 1..3 && runAt > System.currentTimeMillis()) { "مرحلة أو موعد غير صالح" }
        require(message.isNotBlank() && message.length <= 1000) { "نص الرسالة غير صالح" }
        val current = read("customer_sms_sequences", id) ?: return@transaction false
        if (current.text("status") == "completed") return@transaction false
        val fieldAt = listOf("firstAt", "secondAt", "thirdAt")[stage - 1]
        val fieldMessage = listOf("firstMessage", "secondMessage", "thirdMessage")[stage - 1]
        save("customer_sms_sequences", id, mapOf(fieldAt to runAt, fieldMessage to message.take(1000), "updatedAt" to System.currentTimeMillis()))
        if (stage == 1) {
            query("sms_schedules") { it.text("note") == "customer_sequence:$id:1" && it.text("status") in listOf("queued", "pending") }
                .firstOrNull()?.let { save("sms_schedules", it.id, mapOf("runAt" to runAt, "message" to message.take(1000))) }
        } else if (stage == 2 && current.long("firstSentAt") > 0L) {
            query("sms_schedules") { it.text("note") == "customer_sequence:$id:2" && it.text("status") in listOf("queued", "pending") }
                .firstOrNull()?.let { save("sms_schedules", it.id, mapOf("runAt" to runAt, "message" to message.take(1000))) }
        } else if (stage == 3 && current.long("secondSentAt") > 0L) {
            query("sms_schedules") { it.text("note") == "customer_sequence:$id:3" && it.text("status") in listOf("queued", "pending") }
                .firstOrNull()?.let { save("sms_schedules", it.id, mapOf("runAt" to runAt, "message" to message.take(1000))) }
        }
        true
    }

    fun customerSmsSequence(id: String): LocalDocument? = read("customer_sms_sequences", id)
    fun listCustomerSmsSequences(limit: Int = 30): List<LocalDocument> = query("customer_sms_sequences").sortedByDescending { it.long("updatedAt") }.take(limit.coerceIn(1, 100))

    fun completeCustomerSmsStage(schedule: SmsSchedule, sentMessage: String, sentAt: Long = System.currentTimeMillis()) = db.transaction {
        val match = Regex("^customer_sequence:([^:]+):([123])$").matchEntire(schedule.note) ?: return@transaction
        val sequenceId = match.groupValues[1]
        val stage = match.groupValues[2].toInt()
        val sequence = read("customer_sms_sequences", sequenceId) ?: return@transaction
        val customer = findById(sequence.text("customerId")) ?: return@transaction
        schedule.id?.takeIf(String::isNotBlank)?.let { save("sms_schedules", it, mapOf("status" to "sent", "sentAt" to sentAt, "message" to sentMessage.take(1000), "claimedAt" to null)) }
        val messageId = "outbound-" + java.security.MessageDigest.getInstance("SHA-256")
            .digest("${schedule.id.orEmpty()}|${sentMessage}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        save("customer_messages", messageId, mapOf(
            "customerId" to customer.id.orEmpty(), "phone" to customer.phone,
            "direction" to "outbound", "channel" to "sms", "body" to sentMessage.take(4000),
            "scheduleId" to schedule.id.orEmpty(), "sentAt" to sentAt, "createdAt" to sentAt,
        ))
        when (stage) {
            1 -> {
                save("customer_sms_sequences", sequenceId, mapOf("firstSentAt" to sentAt, "status" to "stage1_sent", "updatedAt" to sentAt))
                val due = maxOf(sequence.long("secondAt"), sentAt + 7L * 24 * 60 * 60 * 1000)
                saveSmsScheduleInTransaction(SmsSchedule(
                    customerId = customer.id.orEmpty(), phone = customer.phone, runAt = due,
                    message = sequence.text("secondMessage"), note = "customer_sequence:$sequenceId:2",
                    idempotencyKey = "customer-sequence:$sequenceId:2",
                ))
            }
            2 -> {
                save("customer_sms_sequences", sequenceId, mapOf("secondSentAt" to sentAt, "status" to "stage2_sent", "updatedAt" to sentAt))
                val due = maxOf(sequence.long("thirdAt"), sentAt + 7L * 24 * 60 * 60 * 1000)
                saveSmsScheduleInTransaction(SmsSchedule(
                    customerId = customer.id.orEmpty(), phone = customer.phone, runAt = due,
                    message = sequence.text("thirdMessage"), note = "customer_sequence:$sequenceId:3",
                    idempotencyKey = "customer-sequence:$sequenceId:3",
                ))
            }
            3 -> save("customer_sms_sequences", sequenceId, mapOf("thirdSentAt" to sentAt, "status" to "completed", "updatedAt" to sentAt))
        }
    }

    fun sequenceStage(schedule: SmsSchedule): Int? =
        Regex("^customer_sequence:[^:]+:([123])$").matchEntire(schedule.note)?.groupValues?.get(1)?.toIntOrNull()


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
    fun renewCallScheduleLease(id: String, now: Long = System.currentTimeMillis()) {
        if (id.isNotBlank()) db.transaction {
            val current = read("call_schedules", id) ?: return@transaction
            if (current.text("status") == "running") save("call_schedules", id, mapOf("claimedAt" to now))
        }
    }
    fun deferSchedule(id: String, runAt: Long, reason: String) {
        if (id.isBlank()) return
        save("call_schedules", id, mapOf(
            "status" to "pending",
            "runAt" to runAt,
            "claimedAt" to null,
            "error" to reason.take(300),
        ))
    }
    private fun saveSmsScheduleInTransaction(s: SmsSchedule): String {
        val existing = s.idempotencyKey?.takeIf(String::isNotBlank)?.let { key ->
            query("sms_schedules") { it.text("idempotencyKey") == key }.firstOrNull()?.id
        }
        if (existing != null) return existing
        val id = idOrNew(s.id)
        save("sms_schedules", id, mapOf(
            "customerId" to s.customerId, "phone" to s.phone, "runAt" to s.runAt,
            "message" to s.message, "status" to s.status, "campaignId" to s.campaignId,
            "note" to s.note, "campaignCustomerId" to s.campaignCustomerId,
            "idempotencyKey" to s.idempotencyKey, "attempts" to s.attempts,
        ))
        return id
    }

    fun saveSmsSchedule(s: SmsSchedule): String = db.transaction { saveSmsScheduleInTransaction(s) }

        fun claimDueSmsSchedule(now: Long = System.currentTimeMillis()): SmsSchedule? = claim("sms_schedules", "queued", now) { smsScheduleFrom(it) }?.second
    fun updateSmsScheduleStatus(id: String, status: String, error: String = "") { if (id.isNotBlank()) save("sms_schedules", id, mapOf("status" to status, "error" to error.takeIf(String::isNotBlank))) }

    private fun <T> claim(name: String, status: String, now: Long, mapper: (LocalDocument) -> T): Pair<String, T>? =
        db.transaction {
            // Recover abandoned claims, then select and mark one due item while holding
            // the same SQLite write transaction. Competing workers cannot claim it twice.
            query(name) { ScheduleLeasePolicy.isExpired(it.text("status"), it.long("claimedAt"), now) }
                .forEach {
                    save(name, it.id, mapOf(
                        "status" to ScheduleLeasePolicy.recoveryStatus(),
                        "claimedAt" to null,
                        "error" to ScheduleLeasePolicy.EXPIRED_CLAIM_ERROR,
                    ))
                }
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

    /**
     * Lists quarantined external side effects for human review. Returning online does not
     * change these records or authorize another call/SMS attempt.
     */
    fun listSchedulesNeedingReconciliation(limit: Int = 100): List<ScheduleReconciliationItem> {
        val safeLimit = limit.coerceIn(1, 500)
        val calls = query("call_schedules") { ScheduleReconciliationPolicy.requiresReview(it.text("status")) }
            .map { ScheduleReconciliationItem(ScheduleType.CALL, it.id, it.text("customerId"), it.text("phone"),
                it.long("runAt"), it.int("attempts"), it.text("error"), it.long("claimedAt")) }
        val sms = query("sms_schedules") { ScheduleReconciliationPolicy.requiresReview(it.text("status")) }
            .map { ScheduleReconciliationItem(ScheduleType.SMS, it.id, it.text("customerId"), it.text("phone"),
                it.long("runAt"), it.int("attempts"), it.text("error"), it.long("claimedAt")) }
        return (calls + sms).sortedBy { it.runAt }.take(safeLimit)
    }

    /**
     * Resolves one quarantined schedule and writes its audit record in the same SQLite
     * transaction. Callers must enforce manager authentication before invoking this method.
     */
    fun resolveScheduleReconciliation(
        type: ScheduleType,
        scheduleId: String,
        decision: ScheduleReconciliationPolicy.Decision,
        now: Long = System.currentTimeMillis(),
    ): Boolean = db.transaction {
        require(scheduleId.isNotBlank()) { "Schedule ID is required" }
        val collection = if (type == ScheduleType.CALL) "call_schedules" else "sms_schedules"
        val current = read(collection, scheduleId) ?: return@transaction false
        val retryStatus = if (type == ScheduleType.CALL) "pending" else "queued"
        val result = ScheduleReconciliationPolicy.resolve(current.text("status"), decision, retryStatus)
        save(collection, scheduleId, mapOf(
            "status" to result.status,
            "claimedAt" to null,
            "error" to null,
        ))
        save("schedule_reconciliation_audit", UUID.randomUUID().toString(), mapOf(
            "scheduleType" to type.name.lowercase(),
            "scheduleId" to scheduleId,
            "actor" to decision.actor.trim(),
            "evidence" to decision.evidence.trim().take(2000),
            "action" to result.auditAction,
            "previousStatus" to current.text("status"),
            "newStatus" to result.status,
            "createdAt" to now,
        ))
        true
    }

    fun listScheduleReconciliationAudit(scheduleId: String, limit: Int = 50): List<LocalDocument> {
        if (scheduleId.isBlank()) return emptyList()
        return query("schedule_reconciliation_audit") { it.text("scheduleId") == scheduleId }
            .sortedByDescending { it.long("createdAt") }.take(limit.coerceIn(1, 200))
    }

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
