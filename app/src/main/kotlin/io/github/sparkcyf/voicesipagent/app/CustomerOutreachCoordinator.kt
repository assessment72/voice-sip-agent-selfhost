package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the persisted ten-stage outreach flow independently of SIP/call scheduling. External
 * outcomes that cannot be proven are quarantined for human review; they are never retried blindly.
 */
internal class CustomerOutreachCoordinator(
    context: Context,
    private val customers: VoicxCustomerRepository,
    private val settings: () -> GatewaySettings,
    private val logger: (String) -> Unit = AppLog::i,
) : AutoCloseable {
    private val store = CustomerOutreachStore(context.applicationContext)
    private val running = AtomicBoolean(false)

    fun loadTemplates(): List<CustomerOutreachTemplate> = store.loadTemplates()

    fun saveTemplate(template: CustomerOutreachTemplate) = store.saveTemplate(template)

    fun start(customerId: String, firstAt: Long, minDays: Int, maxDays: Int): CustomerOutreachSequence {
        val customer = customers.findById(customerId) ?: error("العميل غير موجود")
        require(!customers.isPhoneOptedOut(customer.phone)) { "العميل طلب إيقاف التواصل؛ لن يبدأ التسلسل" }
        val recipient = preferredEmail(customer)
        return store.startSequence(
            customerId = customerId,
            phone = customer.phone,
            emailRecipient = recipient,
            whatsappLinked = customer.phoneHasWhatsApp,
            whatsappMarketingConsent = customer.whatsappContactConsent,
            emailMarketingConsent = customer.emailContactConsent,
            firstAt = firstAt,
            minDays = minDays,
            maxDays = maxDays,
        )
    }

    fun stop(sequenceId: String): Boolean = store.stop(sequenceId)

    fun listSequences(): List<CustomerOutreachSequence> = store.listSequences()

    /** One bounded pass: poll at most one pending Manus task and claim at most one due stage. */
    fun runOnePass() {
        if (!running.compareAndSet(false, true)) return
        try {
            pollOneWaitingEmail()
            val due = store.claimDue() ?: return
            submitStage(due)
        } catch (error: Exception) {
            logger("customer outreach worker pass failed: ${error.javaClass.simpleName}")
        } finally {
            running.set(false)
        }
    }

    private fun submitStage(sequence: CustomerOutreachSequence) {
        var customer = eligibleCustomerNow(sequence)
            ?: return review(sequence, "Customer opt-out, channel consent, or contact data changed; no new channel was sent.")

        val template = store.loadTemplates().firstOrNull { it.stage == sequence.stage }
            ?: return review(sequence, "Outreach template for this stage is missing.")
        val whatsAppEnabled = sequence.phone.isNotBlank()
        val emailEnabled = sequence.emailRecipient.isNotBlank()
        if (!whatsAppEnabled && !emailEnabled) {
            return review(sequence, "No consented channel is available; no message was sent.")
        }

        val currentSettings = settings()
        var waMessageId: String? = null
        var whatsappSkipped: String? = null
        if (whatsAppEnabled) {
            customer = eligibleCustomerNow(sequence)
                ?: return review(sequence, "Customer opt-out, channel consent, or contact data changed before WhatsApp submission.")
            if (currentSettings.whatsappAccessToken.isBlank() || currentSettings.whatsappPhoneNumberId.isBlank()) {
                whatsappSkipped = "WhatsApp credentials are not configured"
            } else if (template.whatsappTemplateName.isBlank()) {
                whatsappSkipped = "Approved Meta template name is missing for this stage"
            } else {
                try {
                    val submission = WhatsAppCloudApiClient(
                        accessToken = currentSettings.whatsappAccessToken,
                        phoneNumberId = currentSettings.whatsappPhoneNumberId,
                        graphVersion = currentSettings.whatsappGraphVersion,
                    ).sendApprovedTemplate(
                        recipient = sequence.phone,
                        templateName = template.whatsappTemplateName,
                        languageCode = template.whatsappLanguage.ifBlank { currentSettings.whatsappTemplateLanguage },
                        positionalBodyVariables = listOf(
                            customer.name.trim().ifBlank { "عميلنا الكريم" },
                            customer.company.trim().ifBlank { "مؤسستكم" },
                        ),
                    )
                    waMessageId = submission.messageId
                        ?: return review(sequence, "Meta accepted no message ID; delivery outcome is unknown. Do not retry automatically.")
                    store.recordChannelSubmission(sequence.id, sequence.stage, "whatsapp", "accepted", waMessageId)
                    // Accepted by Meta is not a delivery receipt. This flow records provider acceptance;
                    // delivery status webhooks are not available to a phone-only app deployment.
                } catch (error: Exception) {
                    return review(sequence, "WhatsApp submission outcome is unknown or rejected (${error.javaClass.simpleName}); verify before retrying.")
                }
            }
        }
        whatsappSkipped?.let { reason ->
            store.recordChannelSubmission(sequence.id, sequence.stage, "whatsapp", "skipped_configuration", reason)
            logger("outreach stage ${sequence.stage}: WhatsApp skipped because $reason")
        }

        if (emailEnabled) {
            customer = eligibleCustomerNow(sequence)
                ?: return review(sequence, "Customer opt-out, channel consent, or contact data changed before email submission.")
            if (currentSettings.manusApiKey.isBlank() || currentSettings.manusGmailConnectorId.isBlank()) {
                return review(sequence, "Manus/Gmail is not configured; verify any accepted channel before retrying.")
            }
            val subject = CustomerOutreachTemplates.safeSubject(template.emailSubject, customer.name, customer.company)
            val body = CustomerOutreachTemplates.render(template.emailBody, customer.name, customer.company).trim()
            if (subject.isBlank() || body.isBlank()) {
                return review(sequence, "Email template subject/body is empty; no email task was submitted.")
            }
            try {
                val task = ManusV2Client(currentSettings.manusApiKey, currentSettings.manusGmailConnectorId)
                    .submitEmailTask(sequence.emailRecipient, subject, body)
                store.markWaitingForEmail(
                    sequenceId = sequence.id,
                    taskId = task.taskId,
                    expectedRecipient = sequence.emailRecipient,
                    expectedSubject = subject,
                    expectedBody = body,
                    waMessageId = waMessageId,
                )
                store.recordChannelSubmission(sequence.id, sequence.stage, "email", "manus_task_submitted", task.taskId)
                logger("outreach stage ${sequence.stage} submitted to Manus; task completion is pending")
            } catch (error: Exception) {
                review(sequence, "Manus email-task submission failed or is unknown (${error.javaClass.simpleName}); verify before retrying.")
            }
        } else {
            if (whatsAppEnabled && waMessageId == null) {
                return review(sequence, "WhatsApp was not submitted because its configuration is incomplete; no eligible email channel was available.")
            }
            store.completeStage(sequence.id, sequence.stage)
            logger("outreach stage ${sequence.stage} accepted by WhatsApp provider")
        }
    }

    private fun pollOneWaitingEmail() {
        val sequence = store.listSequences().firstOrNull { it.status == "waiting_email" } ?: return
        if (System.currentTimeMillis() - sequence.updatedAt > EMAIL_REVIEW_AFTER_MS) {
            review(sequence, "Manus task did not reach a verifiable result before the local deadline; check Manus/Gmail before retrying.")
            return
        }
        val customer = customers.findById(sequence.customerId)
            ?: return review(sequence, "Customer record no longer exists; pending email requires review.")
        val optedOut = runCatching {
            customers.isPhoneOptedOut(sequence.phone) || customers.isPhoneOptedOut(customer.phone)
        }.getOrDefault(true)
        if (optedOut || !isStillEligible(sequence, customer)) {
            return review(sequence, "Customer opt-out, channel consent, or contact data changed while email was pending.")
        }
        val currentSettings = settings()
        if (currentSettings.manusApiKey.isBlank()) {
            return review(sequence, "Manus API key is no longer configured while email task is pending.")
        }
        try {
            val client = ManusV2Client(currentSettings.manusApiKey, currentSettings.manusGmailConnectorId)
            val detail = client.getTaskDetail(sequence.emailTaskId)
            when (detail.status.lowercase()) {
                "waiting" -> {
                    val review = client.confirmExactPendingGmailAction(
                        taskId = sequence.emailTaskId,
                        expectedRecipient = sequence.expectedRecipient,
                        expectedSubject = sequence.expectedSubject,
                        expectedContent = sequence.expectedBody,
                        isAlreadyConfirmed = { eventId ->
                            store.hasChannelOutcome(sequence.id, sequence.stage, "email", "gmail_confirmation_attempted", eventId) ||
                                store.hasChannelOutcome(sequence.id, sequence.stage, "email", "exact_gmail_action_confirmed", eventId)
                        },
                        beforeConfirmation = { eventId ->
                            store.recordChannelSubmission(sequence.id, sequence.stage, "email", "gmail_confirmation_attempted", eventId)
                        },
                    )
                    if (review.status == GmailActionReview.Status.CONFIRMATION_SUBMITTED) {
                        store.recordChannelSubmission(sequence.id, sequence.stage, "email", "exact_gmail_action_confirmed", review.eventId.orEmpty())
                        logger("exact Gmail action confirmation submitted for outreach stage ${sequence.stage}")
                    }
                    // A missing or mismatched event is intentionally left pending for manual review;
                    // it is never confirmed on a guessed recipient or altered content.
                }
                "stopped" -> {
                    if (detail.hasRunningBackgroundJobs != false) return
                    val result = client.getStructuredEmailResult(sequence.emailTaskId)
                        ?: return review(sequence, "Manus task stopped without a successful structured email result; verify in Gmail.")
                    if (!result.sent || result.recipient != sequence.expectedRecipient ||
                        result.subject != sequence.expectedSubject || result.messageId.isBlank()) {
                        return review(sequence, "Manus structured result did not prove the exact requested email was sent; verify in Gmail.")
                    }
                    store.recordChannelSubmission(sequence.id, sequence.stage, "email", "sent", result.messageId)
                    val next = store.completeStage(sequence.id, sequence.stage)
                    logger(if (next?.status == "completed") "customer outreach sequence completed" else "customer outreach stage ${sequence.stage} verified")
                }
                "error", "failed" -> review(sequence, "Manus reports an email task error; inspect the task before any retry.")
                else -> Unit // running or an unknown state remains pending until the bounded deadline
            }
        } catch (error: Exception) {
            // Transient polling failure does not cause another send. The fixed deadline eventually
            // quarantines this task so a person can reconcile the Gmail and Manus records.
            logger("Manus task status check failed: ${error.javaClass.simpleName}")
        }
    }

    private fun isStillEligible(sequence: CustomerOutreachSequence, customer: VoicxCustomer): Boolean {
        val emailEligible = sequence.emailRecipient.isBlank() ||
            (customer.emailContactConsent && sequence.emailRecipient == preferredEmail(customer))
        val whatsappEligible = sequence.phone.isBlank() ||
            (customer.phoneHasWhatsApp && customer.whatsappContactConsent && sequence.phone == customer.phone)
        return emailEligible && whatsappEligible
    }

    /** Re-read consent and the current contact record immediately before each external channel call. */
    private fun eligibleCustomerNow(sequence: CustomerOutreachSequence): VoicxCustomer? {
        return try {
            val customer = customers.findById(sequence.customerId) ?: return null
            val blocked = customers.isPhoneOptedOut(sequence.phone) || customers.isPhoneOptedOut(customer.phone)
            customer.takeUnless { blocked || !isStillEligible(sequence, customer) }
        } catch (_: Exception) {
            null // Fail closed if local consent or suppression state cannot be read.
        }
    }

    private fun preferredEmail(customer: VoicxCustomer): String =
        customer.workEmail.trim().ifBlank { customer.personalEmail.trim() }

    private fun review(sequence: CustomerOutreachSequence, reason: String) {
        store.markNeedsReview(sequence.id, reason)
        logger("outreach sequence ${sequence.id} needs review: ${reason.take(220)}")
    }

    override fun close() = store.close()

    private companion object {
        const val EMAIL_REVIEW_AFTER_MS = 6L * 60L * 60L * 1000L
    }
}
