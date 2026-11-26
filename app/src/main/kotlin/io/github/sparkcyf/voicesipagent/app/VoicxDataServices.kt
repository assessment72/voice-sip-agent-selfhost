package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import org.bson.Document
import org.bson.BsonRegularExpression
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
    fun saveSchedule(schedule: CallSchedule): String {
        val id = schedule.id?.takeIf { ObjectId.isValid(it) }?.let(::ObjectId) ?: ObjectId()
        store.schedules().replaceOne(Document("_id", id), Document("customerId", schedule.customerId)
            .append("phone", schedule.phone).append("runAt", Date(schedule.runAt))
            .append("status", schedule.status).append("note", schedule.note), com.mongodb.client.model.ReplaceOptions().upsert(true))
        return id.toHexString()
    }
    fun listSchedules(limit: Int = 20): List<CallSchedule> = store.schedules().find().sort(Document("runAt", 1)).limit(limit)
        .map { CallSchedule(it.getObjectId("_id")?.toHexString(), it.getString("customerId").orEmpty(), it.getString("phone").orEmpty(), it.getDate("runAt")?.time ?: 0L, it.getString("status").orEmpty(), it.getString("note").orEmpty()) }
        .toList()
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
