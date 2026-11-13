package io.github.sparkcyf.voicesipagent.telephony

import android.telecom.PhoneAccountHandle
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Stable app-local preference key for a Telecom account; it is not a SIM subscription ID. */
object PhoneAccountKeys {
    fun encode(handle: PhoneAccountHandle): String {
        val id = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(handle.id.toByteArray(StandardCharsets.UTF_8))
        return "${handle.componentName.flattenToString()}|$id"
    }
}
