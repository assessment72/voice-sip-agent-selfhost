package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random

/** Persisted state for one customer's ten-stage outreach sequence. */
data class CustomerOutreachSequence(
    val id: String,
    val customerId: String,
    val phone: String,
    val emailRecipient: String,
    val firstAt: Long,
    val stage: Int,
    val minDays: Int,
    val maxDays: Int,
    val status: String,
    val runAt: Long,
    val claimedAt: Long = 0L,
    val createdAt: Long,
    val updatedAt: Long,
    val emailTaskId: String = "",
    val expectedRecipient: String = "",
    val expectedSubject: String = "",
    val expectedBody: String = "",
    val waMessageId: String = "",
    val lastError: String = "",
)

/** Pure validation and interval policy shared by the store and local unit tests. */
object CustomerOutreachSequencePolicy {
    const val MIN_INTERVAL_DAYS = 3
    const val MAX_INTERVAL_DAYS = 7
    const val STAGE_COUNT = 10
    const val CLAIM_TIMEOUT_MS = 30L * 60L * 1000L
    const val DAY_MS = 24L * 60L * 60L * 1000L

    fun isValidStart(
        firstAt: Long,
        now: Long,
        minDays: Int,
        maxDays: Int,
        hasConsentedWhatsApp: Boolean,
        hasConsentedEmail: Boolean,
    ): Boolean = firstAt > now &&
        minDays in MIN_INTERVAL_DAYS..MAX_INTERVAL_DAYS &&
        maxDays in MIN_INTERVAL_DAYS..MAX_INTERVAL_DAYS &&
        minDays <= maxDays &&
        (hasConsentedWhatsApp || hasConsentedEmail)

    fun randomDelayDays(minDays: Int, maxDays: Int, random: Random): Int {
        require(minDays in MIN_INTERVAL_DAYS..MAX_INTERVAL_DAYS) { "minDays must be between 3 and 7" }
        require(maxDays in MIN_INTERVAL_DAYS..MAX_INTERVAL_DAYS) { "maxDays must be between 3 and 7" }
        require(minDays <= maxDays) { "minDays must not exceed maxDays" }
        return random.nextInt(minDays, maxDays + 1)
    }

    fun nextRunAt(completedAt: Long, delayDays: Int): Long {
        require(delayDays in MIN_INTERVAL_DAYS..MAX_INTERVAL_DAYS) { "delayDays must be between 3 and 7" }
        return completedAt + delayDays * DAY_MS
    }

    fun isStaleRunning(claimedAt: Long, now: Long): Boolean =
        claimedAt > 0L && claimedAt <= now - CLAIM_TIMEOUT_MS
}

/**
 * Local document-store persistence for outreach templates and sequence state. This class never
 * accepts or persists service credentials. Sequence errors are stored only after redaction.
 */
class CustomerOutreachStore(
    context: Context,
    private val random: Random = Random.Default,
    private val now: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val db = LocalDatabase(context.applicationContext)

    /** Load all ten templates in stage order, inserting any missing default stage first. */
    fun loadTemplates(): List<CustomerOutreachTemplate> = db.transaction {
        CustomerOutreachTemplates.defaults.forEach { default ->
            val key = default.stage.toString()
            if (db.read(TEMPLATES_COLLECTION, key) == null) {
                db.put(TEMPLATES_COLLECTION, key, default.toValues())
            }
        }
        db.all(TEMPLATES_COLLECTION)
            .mapNotNull { doc -> doc.toTemplateOrNull() }
            .sortedBy(CustomerOutreachTemplate::stage)
    }

    /** Save or replace a template for one of the ten supported stages. */
    fun saveTemplate(template: CustomerOutreachTemplate) {
        require(template.stage in 1..CustomerOutreachSequencePolicy.STAGE_COUNT) {
            "Template stage must be between 1 and 10"
        }
        db.put(TEMPLATES_COLLECTION, template.stage.toString(), template.toValues())
    }

    /**
     * Start an eligible sequence. Channel booleans must represent both availability/linkage and
     * explicit marketing consent; the email recipient and phone are persisted only for eligible
     * channels. Throws if policy fails or the customer already has a non-terminal sequence.
     */
    fun startSequence(
        customerId: String,
        phone: String,
        emailRecipient: String?,
        whatsappLinked: Boolean,
        whatsappMarketingConsent: Boolean,
        emailMarketingConsent: Boolean,
        firstAt: Long,
        minDays: Int,
        maxDays: Int,
    ): CustomerOutreachSequence {
        val currentTime = now()
        val validEmail = emailRecipient?.trim()?.takeIf(::isValidEmail).orEmpty()
        val hasWhatsApp = phone.isNotBlank() && whatsappLinked && whatsappMarketingConsent
        val hasEmail = validEmail.isNotBlank() && emailMarketingConsent
        require(
            CustomerOutreachSequencePolicy.isValidStart(
                firstAt = firstAt,
                now = currentTime,
                minDays = minDays,
                maxDays = maxDays,
                hasConsentedWhatsApp = hasWhatsApp,
                hasConsentedEmail = hasEmail,
            ),
        ) { "Invalid outreach start time, interval, or consented channel" }
        require(customerId.isNotBlank()) { "customerId is required" }

        return db.transaction {
            val alreadyActive = db.all(SEQUENCES_COLLECTION).any { document ->
                document.text("customerId") == customerId && document.text("status") in ACTIVE_STATUSES
            }
            check(!alreadyActive) { "An active outreach sequence already exists for this customer" }

            val sequence = CustomerOutreachSequence(
                id = UUID.randomUUID().toString(),
                customerId = customerId,
                phone = phone.trim().takeIf { hasWhatsApp }.orEmpty(),
                emailRecipient = validEmail.takeIf { hasEmail }.orEmpty(),
                firstAt = firstAt,
                stage = 1,
                minDays = minDays,
                maxDays = maxDays,
                status = STATUS_SCHEDULED,
                runAt = firstAt,
                createdAt = currentTime,
                updatedAt = currentTime,
            )
            db.put(SEQUENCES_COLLECTION, sequence.id, sequence.toValues())
            sequence
        }
    }

    /** Atomically mark the oldest due scheduled sequence running and return its claimed snapshot. */
    fun claimDue(): CustomerOutreachSequence? {
        val currentTime = now()
        return db.transaction {
            db.all(SEQUENCES_COLLECTION)
                .filter { it.text("status") == STATUS_RUNNING }
                .filter { CustomerOutreachSequencePolicy.isStaleRunning(it.long("claimedAt"), currentTime) }
                .forEach { stale ->
                    db.patch(
                        SEQUENCES_COLLECTION,
                        stale.id,
                        mapOf(
                            "status" to STATUS_NEEDS_REVIEW,
                            "lastError" to "Previous worker claim expired; verify external delivery before retrying.",
                            "updatedAt" to currentTime,
                        ),
                    )
                }

            val due = db.all(SEQUENCES_COLLECTION)
                .asSequence()
                .filter { it.text("status") == STATUS_SCHEDULED && it.long("runAt") <= currentTime }
                .minWithOrNull(compareBy<LocalDocument> { it.long("runAt") }.thenBy { it.long("createdAt") })
                ?: return@transaction null

            db.patch(
                SEQUENCES_COLLECTION,
                due.id,
                mapOf("status" to STATUS_RUNNING, "claimedAt" to currentTime, "updatedAt" to currentTime),
            )
            db.read(SEQUENCES_COLLECTION, due.id)?.toSequenceOrNull()
        }
    }

    /**
     * Record an asynchronously submitted email task and any WhatsApp outcome. A waiting email is
     * deliberately not reclaimed by claimDue; the caller must verify task completion before advancing.
     */
    fun markWaitingForEmail(
        sequenceId: String,
        taskId: String,
        expectedRecipient: String,
        expectedSubject: String,
        expectedBody: String,
        waMessageId: String? = null,
        error: String? = null,
    ): Boolean = updateIfStatus(sequenceId, setOf(STATUS_RUNNING, STATUS_WAITING_EMAIL)) { currentTime ->
        mapOf(
            "status" to STATUS_WAITING_EMAIL,
            "emailTaskId" to taskId,
            "expectedRecipient" to expectedRecipient,
            "expectedSubject" to expectedSubject,
            "expectedBody" to expectedBody,
            "waMessageId" to waMessageId.orEmpty(),
            "lastError" to safeError(error),
            "updatedAt" to currentTime,
        )
    }

    /**
     * Complete the claimed stage only after every applicable channel succeeded. Stages 1–9 are
     * scheduled after an inclusive random delay; stage 10 terminates the sequence.
     */
    fun completeStage(sequenceId: String, currentStage: Int): CustomerOutreachSequence? {
        require(currentStage in 1..CustomerOutreachSequencePolicy.STAGE_COUNT) { "Invalid outreach stage" }
        val currentTime = now()
        return db.transaction {
            val document = db.read(SEQUENCES_COLLECTION, sequenceId) ?: return@transaction null
            val sequence = document.toSequenceOrNull() ?: return@transaction null
            if (sequence.stage != currentStage || sequence.status !in setOf(STATUS_RUNNING, STATUS_WAITING_EMAIL)) {
                return@transaction null
            }
            if (currentStage == CustomerOutreachSequencePolicy.STAGE_COUNT) {
                db.patch(
                    SEQUENCES_COLLECTION,
                    sequenceId,
                    mapOf("status" to STATUS_COMPLETED, "claimedAt" to 0L, "updatedAt" to currentTime),
                )
            } else {
                val delayDays = CustomerOutreachSequencePolicy.randomDelayDays(sequence.minDays, sequence.maxDays, random)
                db.patch(
                    SEQUENCES_COLLECTION,
                    sequenceId,
                    mapOf(
                        "stage" to currentStage + 1,
                        "status" to STATUS_SCHEDULED,
                        "runAt" to CustomerOutreachSequencePolicy.nextRunAt(currentTime, delayDays),
                        "claimedAt" to 0L,
                        "emailTaskId" to "",
                        "expectedRecipient" to "",
                        "expectedSubject" to "",
                        "expectedBody" to "",
                        "waMessageId" to "",
                        "lastError" to "",
                        "updatedAt" to currentTime,
                    ),
                )
            }
            db.read(SEQUENCES_COLLECTION, sequenceId)?.toSequenceOrNull()
        }
    }

    /** Move an uncertain external outcome out of the pollable queue for manual reconciliation. */
    fun markNeedsReview(sequenceId: String, error: String): Boolean = updateIfStatus(
        sequenceId,
        setOf(STATUS_SCHEDULED, STATUS_RUNNING, STATUS_WAITING_EMAIL, STATUS_NEEDS_REVIEW),
    ) { currentTime ->
        mapOf("status" to STATUS_NEEDS_REVIEW, "lastError" to safeError(error), "updatedAt" to currentTime)
    }

    /** Persist a minimal channel audit without message bodies, addresses, or credentials. */
    fun recordChannelSubmission(
        sequenceId: String,
        stage: Int,
        channel: String,
        outcome: String,
        externalId: String,
    ) {
        require(stage in 1..CustomerOutreachSequencePolicy.STAGE_COUNT) { "Invalid outreach stage" }
        require(channel in setOf("email", "whatsapp")) { "Invalid outreach channel" }
        require(outcome.isNotBlank()) { "Channel outcome is required" }
        db.put(
            EVENTS_COLLECTION,
            UUID.randomUUID().toString(),
            mapOf(
                "sequenceId" to sequenceId,
                "stage" to stage,
                "channel" to channel,
                "outcome" to outcome.take(80),
                "externalId" to externalId.take(200),
                "createdAt" to now(),
            ),
        )
    }

    /** Detect an already-confirmed external action before retrying a confirmation request. */
    fun hasChannelOutcome(
        sequenceId: String,
        stage: Int,
        channel: String,
        outcome: String,
        externalId: String,
    ): Boolean = db.all(EVENTS_COLLECTION).any { event ->
        event.text("sequenceId") == sequenceId &&
            event.int("stage") == stage &&
            event.text("channel") == channel &&
            event.text("outcome") == outcome &&
            event.text("externalId") == externalId
    }

    /** Cancel work that has not started; in-flight or email-waiting work is quarantined for review. */
    fun stop(sequenceId: String): Boolean = db.transaction {
        val document = db.read(SEQUENCES_COLLECTION, sequenceId) ?: return@transaction false
        val status = document.text("status")
        val target = when (status) {
            STATUS_SCHEDULED -> STATUS_CANCELLED
            STATUS_RUNNING, STATUS_WAITING_EMAIL -> STATUS_NEEDS_REVIEW
            else -> return@transaction false
        }
        db.patch(
            SEQUENCES_COLLECTION,
            sequenceId,
            mapOf(
                "status" to target,
                "lastError" to if (target == STATUS_NEEDS_REVIEW) "Stopped while an external outcome may be pending; verify before retrying." else "",
                "updatedAt" to now(),
            ),
        )
        true
    }

    /** Return sequence records newest first, breaking timestamp ties by descending ID. */
    fun listSequences(): List<CustomerOutreachSequence> = db.all(SEQUENCES_COLLECTION)
        .mapNotNull { document -> document.toSequenceOrNull() }
        .sortedWith(compareByDescending<CustomerOutreachSequence> { it.createdAt }.thenByDescending { it.id })

    override fun close() = db.close()

    private fun updateIfStatus(
        sequenceId: String,
        allowedStatuses: Set<String>,
        values: (Long) -> Map<String, Any?>,
    ): Boolean = db.transaction {
        val current = db.read(SEQUENCES_COLLECTION, sequenceId) ?: return@transaction false
        if (current.text("status") !in allowedStatuses) return@transaction false
        db.patch(SEQUENCES_COLLECTION, sequenceId, values(now()))
        true
    }

    private fun CustomerOutreachTemplate.toValues(): Map<String, Any?> = mapOf(
        "stage" to stage,
        "title" to title,
        "emailSubject" to emailSubject,
        "emailBody" to emailBody,
        "whatsappTemplateName" to whatsappTemplateName,
        "whatsappLanguage" to whatsappLanguage,
    )

    private fun LocalDocument.toTemplateOrNull(): CustomerOutreachTemplate? {
        val stage = int("stage").takeIf { it in 1..CustomerOutreachSequencePolicy.STAGE_COUNT } ?: return null
        return CustomerOutreachTemplate(
            stage = stage,
            title = text("title"),
            emailSubject = text("emailSubject"),
            emailBody = text("emailBody"),
            whatsappTemplateName = text("whatsappTemplateName"),
            whatsappLanguage = text("whatsappLanguage").ifBlank { "ar" },
        )
    }

    private fun CustomerOutreachSequence.toValues(): Map<String, Any?> = mapOf(
        "customerId" to customerId,
        "phone" to phone,
        "emailRecipient" to emailRecipient,
        "firstAt" to firstAt,
        "stage" to stage,
        "minDays" to minDays,
        "maxDays" to maxDays,
        "status" to status,
        "runAt" to runAt,
        "claimedAt" to claimedAt,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt,
        "emailTaskId" to emailTaskId,
        "expectedRecipient" to expectedRecipient,
        "expectedSubject" to expectedSubject,
        "expectedBody" to expectedBody,
        "waMessageId" to waMessageId,
        "lastError" to lastError,
    )

    private fun LocalDocument.toSequenceOrNull(): CustomerOutreachSequence? {
        val stage = int("stage").takeIf { it in 1..CustomerOutreachSequencePolicy.STAGE_COUNT } ?: return null
        return CustomerOutreachSequence(
            id = id,
            customerId = text("customerId"),
            phone = text("phone"),
            emailRecipient = text("emailRecipient"),
            firstAt = long("firstAt"),
            stage = stage,
            minDays = int("minDays"),
            maxDays = int("maxDays"),
            status = text("status"),
            runAt = long("runAt"),
            claimedAt = long("claimedAt"),
            createdAt = long("createdAt"),
            updatedAt = long("updatedAt"),
            emailTaskId = text("emailTaskId"),
            expectedRecipient = text("expectedRecipient"),
            expectedSubject = text("expectedSubject"),
            expectedBody = text("expectedBody"),
            waMessageId = text("waMessageId"),
            lastError = text("lastError"),
        )
    }

    private fun isValidEmail(value: String): Boolean = EMAIL_PATTERN.matches(value)

    private fun safeError(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return value
            .replace(SECRET_ASSIGNMENT, "$1[REDACTED]")
            .replace(BEARER_SECRET, "Bearer [REDACTED]")
            .take(MAX_ERROR_LENGTH)
    }

    private companion object {
        const val TEMPLATES_COLLECTION = "outreach_templates"
        const val SEQUENCES_COLLECTION = "customer_outreach_sequences"
        const val EVENTS_COLLECTION = "customer_outreach_events"
        const val STATUS_SCHEDULED = "scheduled"
        const val STATUS_RUNNING = "running"
        const val STATUS_WAITING_EMAIL = "waiting_email"
        const val STATUS_NEEDS_REVIEW = "needs_review"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_CANCELLED = "cancelled"
        const val MAX_ERROR_LENGTH = 500
        val ACTIVE_STATUSES = setOf(STATUS_SCHEDULED, STATUS_RUNNING, STATUS_WAITING_EMAIL, STATUS_NEEDS_REVIEW)
        val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
        val SECRET_ASSIGNMENT = Regex("(?i)(api[ _-]?key|access[ _-]?token|authorization)(\\s*[:=]\\s*)[^\\s,;]+")
        val BEARER_SECRET = Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*")
    }
}
