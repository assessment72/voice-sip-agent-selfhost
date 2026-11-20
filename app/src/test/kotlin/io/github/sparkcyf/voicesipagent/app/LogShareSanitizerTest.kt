package io.github.sparkcyf.voicesipagent.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogShareSanitizerTest {
    @Test fun redactsSettingsSecretsAndKeepsDiagnosticFields() {
        val safe = LogShareSanitizer.sanitize(
            "settings (username=doctor, password=secret123, registrarPassword=admin456, audioMethod=1)",
        )
        assertFalse("secret123" in safe)
        assertFalse("admin456" in safe)
        assertTrue("password=<redacted>" in safe)
        assertTrue("registrarPassword=<redacted>" in safe)
        assertTrue("audioMethod=1" in safe)
    }

    @Test fun redactsAuthorizationAndSipUriCredentials() {
        val safe = LogShareSanitizer.sanitize(
            "Authorization: Bearer abc123; sip:doctor:passw0rd@pbx.example.test",
        )
        assertFalse("abc123" in safe)
        assertFalse("passw0rd" in safe)
        assertTrue("Authorization: Bearer <redacted>" in safe)
        assertTrue("sip:doctor:<redacted>@pbx.example.test" in safe)
    }
}
