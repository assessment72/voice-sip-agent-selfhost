package io.github.sparkcyf.voicesipagent.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class HostedCallJob(val id: String, val phoneNumber: String, val expiresAt: Long)

/**
 * Minimal HTTPS-only device client. Redirects are disabled so the bearer token can never be
 * forwarded to a different origin. Response bodies and credentials are intentionally not logged.
 */
class HostedCallApiClient(
    baseUrl: String,
    private val deviceToken: String,
) {
    private val base = baseUrl.trim().trimEnd('/')

    init {
        require(base.startsWith("https://", ignoreCase = true)) { "Hosted API requires HTTPS" }
        require(deviceToken.isNotBlank()) { "Device token is required" }
    }

    fun nextJob(waitSeconds: Int = 20): HostedCallJob? {
        require(waitSeconds in 0..25)
        val json = request("GET", "/v1/device/jobs/next?wait=$waitSeconds", null)
        val job = json.optJSONObject("job") ?: return null
        val id = job.optString("id")
        val number = job.optString("phoneNumber")
        val expiry = job.optLong("expiresAt", 0L)
        if (id.isBlank() || number.isBlank() || expiry <= 0L) return null
        return HostedCallJob(id, number, expiry)
    }

    fun updateState(callId: String, state: String, reasonCode: String? = null) {
        require(state in setOf("ringing_cellular", "cellular_answered", "calling_sip", "bridged", "failed", "ended"))
        val body = JSONObject().put("state", state).apply {
            if (!reasonCode.isNullOrBlank()) put("reasonCode", reasonCode.take(64))
        }
        request("POST", "/v1/device/calls/$callId/state", body)
    }

    private fun request(method: String, path: String, body: JSONObject?): JSONObject {
        val url = URL(base + path)
        require(url.protocol.equals("https", ignoreCase = true)) { "Hosted API requires HTTPS" }
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $deviceToken")
            setRequestProperty("Accept", "application/json")
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
