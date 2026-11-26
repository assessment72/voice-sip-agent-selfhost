package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Transaction
import com.google.android.gms.tasks.Tasks
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Direct Firebase Cloud Firestore access. Firebase credentials are supplied at runtime and never logged. */
internal class VoicxFirebaseStore(private val context: Context) {
    @Volatile private var firestore: FirebaseFirestore? = null
    @Volatile var state: FirebaseState = FirebaseState.DISCONNECTED
        private set
    @Volatile var lastSuccessAt: Long? = null
        private set
    @Volatile var lastError: String = ""
        private set

    @Synchronized
    fun test(projectId: String, apiKey: String, applicationId: String): Result<Long> = runCatching {
        require(projectId.matches(Regex("[a-z0-9-]{6,63}"))) { "Firebase Project ID غير صالح" }
        require(apiKey.isNotBlank()) { "Firebase API key مطلوب" }
        require(applicationId.isNotBlank()) { "Firebase Application ID مطلوب" }
        val started = System.currentTimeMillis()
        val db = createFirestore(projectId, apiKey, applicationId)
        Tasks.await(db.collection("_voicx_health").document("ping").get(), 8, TimeUnit.SECONDS)
        val elapsed = System.currentTimeMillis() - started
        lastError = ""
        lastSuccessAt = System.currentTimeMillis()
        elapsed
    }.onFailure { lastError = safeError(it) }

    @Synchronized
    fun connect(projectId: String, apiKey: String, applicationId: String): Result<Unit> = runCatching {
        disconnect()
        val db = createFirestore(projectId.trim(), apiKey.trim(), applicationId.trim())
        Tasks.await(db.collection("_voicx_health").document("ping").get(), 8, TimeUnit.SECONDS)
        firestore = db
        state = FirebaseState.CONNECTED
        lastError = ""
        lastSuccessAt = System.currentTimeMillis()
    }.onFailure { state = FirebaseState.ERROR; lastError = safeError(it) }

    @Synchronized fun disconnect() {
        // Do not terminate the SDK instance: Firestore instances cannot be reused after
        // terminate(), while the UI supports disconnecting and reconnecting at runtime.
        firestore = null
        state = FirebaseState.DISCONNECTED
    }

    fun requireDatabase(): FirebaseFirestore = firestore ?: error("Firebase is not active")
    fun collection(name: String) = requireDatabase().collection(name)

    private fun createFirestore(projectId: String, apiKey: String, applicationId: String): FirebaseFirestore {
        val appName = "voicx-${projectId.hashCode().toUInt().toString(16)}"
        val app = FirebaseApp.getApps(context).firstOrNull { it.name == appName } ?:
            FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
                .setProjectId(projectId)
                .setApiKey(apiKey)
                .setApplicationId(applicationId)
                .build(), appName)
                ?: error("تعذر تهيئة Firebase")
        return FirebaseFirestore.getInstance(app)
    }

    private fun safeError(e: Throwable): String {
        val root = generateSequence(e) { it.cause }.lastOrNull() ?: e
        return when (root) {
            is IllegalArgumentException -> root.message.orEmpty().take(240)
            else -> "${root.javaClass.simpleName}: ${root.message?.take(180).orEmpty().ifBlank { "connection failed" }}".take(240)
        }
    }
}

enum class FirebaseState { DISCONNECTED, CONNECTED, ERROR }

data class VoicxCustomer(val id: String? = null, val phone: String, val name: String, val role: String = "", val company: String = "", val instructions: String = "", val notes: String = "", val groups: List<String> = emptyList(), val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = System.currentTimeMillis())
data class Campaign(val id: String? = null, val name: String, val startAt: Long, val timezone: String = java.time.ZoneId.systemDefault().id, val targetGroup: String = "", val channel: String = "call_sms", val smsTemplate: String = "", val status: String = "queued", val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = System.currentTimeMillis())
data class CampaignCustomer(val id: String? = null, val campaignId: String, val customerId: String, val phone: String, val sequence: Int, val status: String = "queued", val attempts: Int = 0, val lastCallAt: Long? = null, val lastSmsAt: Long? = null, val nextCallAt: Long? = null, val nextSmsAt: Long? = null, val lastOutcome: String = "", val followUpNote: String = "", val interestedAt: Long? = null, val adminAt: Long? = null, val pausedAt: Long? = null, val optedOutAt: Long? = null, val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = System.currentTimeMillis())
data class CallSchedule(val id: String? = null, val customerId: String, val phone: String, val runAt: Long, val status: String = "pending", val note: String = "", val campaignId: String? = null, val campaignCustomerId: String? = null, val idempotencyKey: String? = null, val attempts: Int = 0)
data class SmsSchedule(val id: String? = null, val customerId: String = "", val phone: String, val runAt: Long, val message: String, val status: String = "queued", val campaignId: String? = null, val note: String = "", val campaignCustomerId: String? = null, val idempotencyKey: String? = null, val attempts: Int = 0)


private fun idOrNew(id: String?): String = id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
private fun Map<String, Any?>.withoutNulls() = filterValues { it != null }
private fun DocumentSnapshot.text(key: String) = getString(key).orEmpty()
private fun DocumentSnapshot.long(key: String) = getLong(key) ?: 0L
private fun DocumentSnapshot.int(key: String) = (getLong(key) ?: 0L).toInt()

internal class VoicxCustomerRepository(private val store: VoicxFirebaseStore) {
    private fun c(name: String) = store.collection(name)
    private fun doc(name: String, id: String) = c(name).document(id)
    private fun save(name: String, id: String, data: Map<String, Any?>) { Tasks.await(doc(name, id).set(data.withoutNulls(), SetOptions.merge())) }
    private fun read(name: String, id: String) = Tasks.await(doc(name, id).get())
    private fun query(name: String, block: Query.() -> Query): List<DocumentSnapshot> = Tasks.await(block(c(name)).get()).documents

    fun upsert(customer: VoicxCustomer): String {
        val id = idOrNew(customer.id); save("customers", id, mapOf("phone" to customer.phone.trim(), "name" to customer.name.trim(), "role" to customer.role.trim(), "company" to customer.company.trim(), "instructions" to customer.instructions.trim(), "notes" to customer.notes.trim(), "groups" to customer.groups.distinct().filter(String::isNotBlank), "createdAt" to customer.createdAt, "updatedAt" to customer.updatedAt)); return id
    }
    fun findById(id: String): VoicxCustomer? = if (id.isBlank()) null else from(read("customers", id))
    fun findByPhone(phone: String): VoicxCustomer? = query("customers") { whereEqualTo("phone", phone.trim()).limit(1) }.firstOrNull()?.let(::from)
    fun search(query: String, limit: Int = 20): List<VoicxCustomer> {
        val q = query.trim().lowercase()
        return query("customers") { limit(50) }.mapNotNull(::from).filter { q.isBlank() || listOf(it.phone, it.name, it.company).any { value -> value.lowercase().contains(q) } }.take(limit.coerceIn(1, 50))
    }
    fun addMemory(customerId: String, text: String, source: String) { val id = UUID.randomUUID().toString(); save("customer_memories", id, mapOf("customerId" to customerId, "text" to text.take(4000), "source" to source, "createdAt" to System.currentTimeMillis())) }
    fun instructionFor(phone: String): String = findByPhone(phone)?.instructions.orEmpty()
    fun memoriesFor(phone: String, limit: Int = 10): String { val customerId = findByPhone(phone)?.id ?: return ""; return query("customer_memories") { whereEqualTo("customerId", customerId).orderBy("createdAt", Query.Direction.DESCENDING).limit(limit.coerceIn(1, 20).toLong()) }.map { it.text("text") }.filter(String::isNotBlank).asReversed().joinToString("\n") { "- $it" } }

    fun saveSchedule(s: CallSchedule): String { val existing = s.idempotencyKey?.takeIf(String::isNotBlank)?.let { query("call_schedules") { whereEqualTo("idempotencyKey", it).limit(1) }.firstOrNull()?.id }; val id = existing ?: idOrNew(s.id); save("call_schedules", id, mapOf("customerId" to s.customerId, "phone" to s.phone, "runAt" to s.runAt, "status" to s.status, "note" to s.note, "campaignId" to s.campaignId, "campaignCustomerId" to s.campaignCustomerId, "idempotencyKey" to s.idempotencyKey, "attempts" to s.attempts)); return id }
    fun claimDueSchedule(now: Long = System.currentTimeMillis()): CallSchedule? = claim("call_schedules", "pending", now, ::scheduleFrom)?.second
    fun updateScheduleStatus(id: String, status: String, error: String = "") { if (id.isNotBlank()) save("call_schedules", id, mapOf("status" to status, "error" to error.takeIf(String::isNotBlank))) }
    fun saveSmsSchedule(s: SmsSchedule): String { val existing = s.idempotencyKey?.takeIf(String::isNotBlank)?.let { query("sms_schedules") { whereEqualTo("idempotencyKey", it).limit(1) }.firstOrNull()?.id }; val id = existing ?: idOrNew(s.id); save("sms_schedules", id, mapOf("customerId" to s.customerId, "phone" to s.phone, "runAt" to s.runAt, "message" to s.message, "status" to s.status, "campaignId" to s.campaignId, "note" to s.note, "campaignCustomerId" to s.campaignCustomerId, "idempotencyKey" to s.idempotencyKey, "attempts" to s.attempts)); return id }
    fun claimDueSmsSchedule(now: Long = System.currentTimeMillis()): SmsSchedule? = claim("sms_schedules", "queued", now, ::smsScheduleFrom)?.second
    fun updateSmsScheduleStatus(id: String, status: String, error: String = "") { if (id.isNotBlank()) save("sms_schedules", id, mapOf("status" to status, "error" to error.takeIf(String::isNotBlank))) }

    private fun <T> claim(name: String, status: String, now: Long, mapper: (DocumentSnapshot) -> T): Pair<String, T>? {
        val stale = query(name) { whereEqualTo("status", "running").limit(50) }.filter { it.long("claimedAt") in 1..(now - 600_000L) }
        stale.forEach { save(name, it.id, mapOf("status" to status, "claimedAt" to null)) }
        val ref = c(name)
        val candidate = Tasks.await(ref.whereEqualTo("status", status).limit(50).get()).documents
            .filter { it.long("runAt") <= now }.minByOrNull { it.long("runAt") } ?: return null
        val id = Tasks.await(store.requireDatabase().runTransaction { tx: Transaction ->
            val found = tx.get(ref.document(candidate.id))
            if (!found.exists() || found.text("status") != status || found.long("runAt") > now) return@runTransaction null
            tx.update(found.reference, mapOf("status" to "running", "claimedAt" to now, "attempts" to (found.int("attempts") + 1).toLong()))
            found.id
        }, 8, TimeUnit.SECONDS) ?: return null
        val snapshot = Tasks.await(ref.document(id).get()); return id to mapper(snapshot)
    }

    fun campaignCustomerBlocked(id: String?): Boolean = id?.takeIf(String::isNotBlank)?.let { read("campaign_customers", it).text("status") in listOf("admin", "opted_out", "paused") } ?: false
    fun latestCampaignCustomer(phone: String): String? = query("campaign_customers") { whereEqualTo("phone", phone.trim()).orderBy("createdAt", Query.Direction.DESCENDING).limit(1) }.firstOrNull()?.id
    fun saveCallReport(report: AiCallReport) { val id = UUID.randomUUID().toString(); save("call_summaries", id, mapOf("phone" to report.number.orEmpty(), "name" to report.name.orEmpty(), "role" to report.role.orEmpty(), "company" to report.company.orEmpty(), "startedAt" to report.startedAt, "endedAt" to report.endedAt, "cause" to report.cause?.name.orEmpty(), "failureDetail" to report.failureDetail.take(500), "inputText" to report.inputText.take(12000), "outputText" to report.outputText.take(12000), "conversationTranscript" to report.conversationTranscript.take(24000), "inputFrames" to report.inputFrames, "outputFrames" to report.outputFrames, "createdAt" to System.currentTimeMillis())); report.number?.let(::findByPhone)?.id?.let { addMemory(it, "نتيجة مكالمة ${report.endedAt}: ${report.cause?.name ?: "NORMAL"}\n${report.conversationTranscript.ifBlank { report.outputText }}", "gemini_call") } }
    fun listSchedules(limit: Int = 20): List<CallSchedule> = query("call_schedules") { orderBy("runAt").limit(limit.toLong()) }.map(::scheduleFrom)
    fun listSmsSchedules(limit: Int = 20): List<SmsSchedule> = query("sms_schedules") { orderBy("runAt").limit(limit.toLong()) }.map(::smsScheduleFrom)

    fun createCampaign(campaign: Campaign): String {
        val id = idOrNew(campaign.id)
        save("campaigns", id, mapOf("name" to campaign.name.trim(), "startAt" to campaign.startAt, "timezone" to campaign.timezone, "targetGroup" to campaign.targetGroup.trim(), "channel" to campaign.channel, "smsTemplate" to campaign.smsTemplate.take(1000), "status" to campaign.status, "createdAt" to campaign.createdAt, "updatedAt" to campaign.updatedAt))
        val targets = query("customers") { orderBy("createdAt").limit(500) }.mapNotNull(::from).filter { campaign.targetGroup.isBlank() || campaign.targetGroup.equals("all", true) || it.groups.contains(campaign.targetGroup.trim()) }
        targets.forEachIndexed { index, customer -> save("campaign_customers", "$id-$index", mapOf("campaignId" to id, "customerId" to customer.id, "phone" to customer.phone, "sequence" to index, "status" to "queued", "attempts" to 0, "createdAt" to System.currentTimeMillis(), "updatedAt" to System.currentTimeMillis())) }
        if (targets.isNotEmpty() && campaign.channel.contains("call")) {
            val ccId = "$id-0"; val first = targets.first()
            saveSchedule(CallSchedule(customerId = first.id.orEmpty(), phone = first.phone, runAt = campaign.startAt, campaignId = id, campaignCustomerId = ccId, idempotencyKey = "$id:call:$ccId:${campaign.startAt}"))
            if (campaign.channel == "call_sms" && campaign.smsTemplate.isNotBlank()) saveSmsSchedule(SmsSchedule(customerId = first.id.orEmpty(), phone = first.phone, runAt = campaign.startAt, message = campaign.smsTemplate, campaignId = id, campaignCustomerId = ccId, idempotencyKey = "$id:sms:$ccId:${campaign.startAt}"))
            save("campaign_customers", ccId, mapOf("status" to "call_scheduled"))
        } else if (targets.isNotEmpty() && campaign.channel == "sms" && campaign.smsTemplate.isNotBlank()) {
            targets.forEachIndexed { index, customer -> val ccId = "$id-$index"; saveSmsSchedule(SmsSchedule(customerId = customer.id.orEmpty(), phone = customer.phone, runAt = campaign.startAt, message = campaign.smsTemplate, campaignId = id, campaignCustomerId = ccId, idempotencyKey = "$id:sms:$ccId:${campaign.startAt}")); save("campaign_customers", ccId, mapOf("status" to "sms_scheduled")) }
        }
        return id
    }

    fun setCampaignDecision(campaignCustomerId: String, outcome: String, note: String) { val status = when (outcome) { "interested", "admin" -> "admin"; "opted_out" -> "opted_out"; "paused" -> "paused"; else -> "follow_up" }; save("campaign_customers", campaignCustomerId, mapOf("status" to status, "lastOutcome" to outcome, "followUpNote" to note.take(1000), "updatedAt" to System.currentTimeMillis())); if (status in listOf("admin", "opted_out", "paused")) { query("call_schedules") { whereEqualTo("campaignCustomerId", campaignCustomerId).whereEqualTo("status", "pending") }.forEach { updateScheduleStatus(it.id, "cancelled", "") }; query("sms_schedules") { whereEqualTo("campaignCustomerId", campaignCustomerId).whereEqualTo("status", "queued") }.forEach { updateSmsScheduleStatus(it.id, "cancelled", "") } }; if (status == "opted_out") read("campaign_customers", campaignCustomerId).text("customerId").takeIf(String::isNotBlank)?.let { save("customers", it, mapOf("optedOut" to true)) } }
    fun scheduleFollowUp(id: String, nextCallAt: Long?, nextSmsAt: Long?, note: String): Boolean { val cc = read("campaign_customers", id); if (!cc.exists() || cc.text("status") in listOf("admin", "opted_out")) return false; val customerId = cc.text("customerId"); val phone = cc.text("phone"); val campaignId = cc.text("campaignId"); save("campaign_customers", id, mapOf("status" to "follow_up", "followUpNote" to note.take(1000), "nextCallAt" to nextCallAt, "nextSmsAt" to nextSmsAt, "updatedAt" to System.currentTimeMillis())); nextCallAt?.let { saveSchedule(CallSchedule(customerId = customerId, phone = phone, runAt = it, note = note, campaignId = campaignId, campaignCustomerId = id, idempotencyKey = "$id:call:$it")) }; nextSmsAt?.let { saveSmsSchedule(SmsSchedule(customerId = customerId, phone = phone, runAt = it, message = note, note = note, campaignId = campaignId, campaignCustomerId = id, idempotencyKey = "$id:sms:$it")) }; return true }
    fun advanceCampaign(campaignCustomerId: String) {
        val current = read("campaign_customers", campaignCustomerId); val campaignId = current.text("campaignId")
        val next = query("campaign_customers") { whereEqualTo("campaignId", campaignId).whereEqualTo("status", "queued").orderBy("sequence").limit(1) }.firstOrNull() ?: return
        val campaign = read("campaigns", campaignId); val at = System.currentTimeMillis(); val nextId = next.id
        val customerId = next.text("customerId"); val phone = next.text("phone")
        if (campaign.text("channel").contains("call")) {
            saveSchedule(CallSchedule(customerId = customerId, phone = phone, runAt = at, campaignId = campaignId, campaignCustomerId = nextId, idempotencyKey = "$campaignId:call:$nextId:$at"))
            if (campaign.text("channel") == "call_sms" && campaign.text("smsTemplate").isNotBlank()) saveSmsSchedule(SmsSchedule(customerId = customerId, phone = phone, runAt = at, message = campaign.text("smsTemplate"), campaignId = campaignId, campaignCustomerId = nextId, idempotencyKey = "$campaignId:sms:$nextId:$at"))
            save("campaign_customers", nextId, mapOf("status" to "call_scheduled"))
        } else if (campaign.text("channel") == "sms" && campaign.text("smsTemplate").isNotBlank()) {
            saveSmsSchedule(SmsSchedule(customerId = customerId, phone = phone, runAt = at, message = campaign.text("smsTemplate"), campaignId = campaignId, campaignCustomerId = nextId, idempotencyKey = "$campaignId:sms:$nextId:$at"))
            save("campaign_customers", nextId, mapOf("status" to "sms_scheduled"))
        }
    }
    private fun scheduleFrom(d: DocumentSnapshot) = CallSchedule(d.id, d.text("customerId"), d.text("phone"), d.long("runAt"), d.text("status"), d.text("note"), d.text("campaignId").ifBlank { null }, d.text("campaignCustomerId").ifBlank { null }, d.text("idempotencyKey").ifBlank { null }, d.int("attempts"))
    private fun smsScheduleFrom(d: DocumentSnapshot) = SmsSchedule(d.id, d.text("customerId"), d.text("phone"), d.long("runAt"), d.text("message"), d.text("status"), d.text("campaignId").ifBlank { null }, d.text("note"), d.text("campaignCustomerId").ifBlank { null }, d.text("idempotencyKey").ifBlank { null }, d.int("attempts"))
    private fun from(d: DocumentSnapshot): VoicxCustomer? = if (!d.exists()) null else VoicxCustomer(d.id, d.text("phone"), d.text("name"), d.text("role"), d.text("company"), d.text("instructions"), d.text("notes"), (d.get("groups") as? List<*>)?.filterIsInstance<String>() ?: emptyList(), d.long("createdAt"), d.long("updatedAt"))
}

internal class SmsApiClient(private val apiKey: String, private val endpoint: String = "https://adasms-standalone.vercel.app/api/sms/send") {
    fun send(phone: String, message: String): Result<String> = runCatching { require(apiKey.isNotBlank()) { "SMS API key is not configured" }; require(phone.matches(Regex("\\+?[0-9]{7,20}"))) { "Invalid phone number" }; require(message.isNotBlank() && message.length <= 1000) { "Invalid SMS message" }; val body = JSONObject().put("phone", phone).put("message", message).toString().toRequestBody("application/json".toMediaType()); val request = Request.Builder().url(endpoint).header("Authorization", "Bearer $apiKey").post(body).build(); SMS_HTTP.newCall(request).execute().use { response -> if (!response.isSuccessful) error("SMS service rejected request (${response.code})"); "SMS sent" } }
    private companion object { val SMS_HTTP: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build() }
}
