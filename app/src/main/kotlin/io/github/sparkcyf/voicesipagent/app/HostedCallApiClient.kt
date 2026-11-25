package io.github.sparkcyf.voicesipagent.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class HostedCallJob(val id: String, val phoneNumber: String, val expiresAt: Long)
data class HostedDeviceRegistration(val deviceId: String, val organizationId: String, val deviceToken: String)
data class HostedSmsResult(val smsId: String, val status: String, val phoneMasked: String)

/**
 * HTTPS-only client for one-time enrollment and per-device control-plane traffic.
 * The enrollment token is sent only in the enrollment body; after enrollment, all device routes
 * use the unique server-issued bearer token. Redirects are disabled to prevent credential leakage.
 */
class HostedCallApiClient(
    baseUrl: String,
    private val deviceToken: String = "",
) {
    private val base = baseUrl.trim().trimEnd('/')

    init {
        require(base.startsWith("https://", ignoreCase = true)) { "Hosted API requires HTTPS" }
    }

    fun enroll(
        enrollmentToken: String,
        externalDeviceId: String,
        displayName: String,
        appId: String,
        appVersion: String,
        androidVersion: String,
        manufacturer: String,
        model: String,
    ): HostedDeviceRegistration {
        require(enrollmentToken.isNotBlank())
        val body = JSONObject()
            .put("enrollmentToken", enrollmentToken)
            .put("externalDeviceId", externalDeviceId)
            .put("displayName", displayName)
            .put("appId", appId)
            .put("appVersion", appVersion)
            .put("androidVersion", androidVersion)
            .put("manufacturer", manufacturer)
            .put("model", model)
        val json = request("POST", "/v1/device/enroll", body)
        val id = json.optString("deviceId")
        val organizationId = json.optString("organizationId")
        val token = json.optString("deviceToken")
        require(id.isNotBlank() && organizationId.isNotBlank() && token.isNotBlank()) {
            "Enrollment response was incomplete"
        }
        return HostedDeviceRegistration(id, organizationId, token)
    }

    fun nextJob(waitSeconds: Int = 20): HostedCallJob? {
        requireDeviceToken()
        require(waitSeconds in 0..25)
        val json = request("GET", "/v1/device/call-jobs/next?wait=$waitSeconds", null)
        val job = json.optJSONObject("job") ?: return null
        val id = job.optString("id")
        val number = job.optString("phoneNumber")
        val expiry = job.optLong("expiresAt", 0L)
        if (id.isBlank() || number.isBlank() || expiry <= 0L) return null
        return HostedCallJob(id, number, expiry)
    }

    fun updateState(callId: String, state: String, reasonCode: String? = null) {
        requireDeviceToken()
        require(state in setOf("ringing_cellular", "cellular_answered", "calling_sip", "bridged", "failed", "ended"))
        val body = JSONObject().put("state", state).apply {
            if (!reasonCode.isNullOrBlank()) put("reasonCode", reasonCode.take(64))
        }
        request("POST", "/v1/device/tenant-calls/$callId/state", body)
    }

    fun heartbeat(
        batteryLevel: Int?,
        networkType: String,
        internetAvailable: Boolean,
        sipStatus: String,
        simStatus: String,
        agentStatus: String,
        activeCall: Boolean,
    ) {
        requireDeviceToken()
        val body = JSONObject()
            .put("networkType", networkType)
            .put("internetAvailable", internetAvailable)
            .put("sipStatus", sipStatus)
            .put("simStatus", simStatus)
            .put("agentStatus", agentStatus)
            .put("activeCall", activeCall)
        if (batteryLevel != null) body.put("batteryLevel", batteryLevel)
        request("POST", "/v1/device/heartbeat", body)
    }

    fun reportEvent(eventType: String, severity: String, eventCode: String) {
        requireDeviceToken()
        require(eventType.length in 1..64)
        require(severity in setOf("debug", "info", "warning", "error", "critical"))
        require(eventCode.length in 1..80)
        val body = JSONObject()
            .put("eventType", eventType)
            .put("severity", severity)
            .put("eventCode", eventCode)
        request("POST", "/v1/device/events", body)
    }

    /**
     * Requests an SMS through the hosted backend. The provider key stays on Vercel.
     * The backend rejects this call unless this device is explicitly allowlisted.
     */
    fun sendSms(phone: String, message: String, consentConfirmed: Boolean, idempotencyKey: String): HostedSmsResult {
        requireDeviceToken()
        require(consentConfirmed) { "Recipient consent is required" }
        require(phone.length in 3..32) { "Invalid phone number" }
        require(message.isNotBlank() && message.length <= 1000) { "SMS message must be 1-1000 characters" }
        require(idempotencyKey.length in 16..128) { "Invalid SMS idempotency key" }
        val body = JSONObject()
            .put("phone", phone)
            .put("message", message)
            .put("consentConfirmed", true)
        val json = request(
            "POST",
            "/v1/device/sms/send",
            body,
            mapOf("Idempotency-Key" to idempotencyKey),
        )
        val id = json.optString("smsId")
        val status = json.optString("status")
        require(id.isNotBlank() && status.isNotBlank()) { "SMS response was incomplete" }
        return HostedSmsResult(id, status, json.optString("phoneMasked"))
    }

    private fun requireDeviceToken() {
        require(deviceToken.isNotBlank()) { "Device is not enrolled" }
    }

    private fun request(method: String, path: String, body: JSONObject?, extraHeaders: Map<String, String> = emptyMap()): JSONObject {
        val url = URL(base + path)
        require(url.protocol.equals("https", ignoreCase = true)) { "Hosted API requires HTTPS" }
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            if (deviceToken.isNotBlank()) setRequestProperty("Authorization", "Bearer $deviceToken")
            setRequestProperty("Accept", "application/json")
            extraHeaders.forEach { (name, value) -> setRequestProperty(name, value) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("Hosted API returned HTTP $code")
            val raw = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return if (raw.isBlank()) JSONObject() else JSONObject(raw)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 28_000
    }
}
