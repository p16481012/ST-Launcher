package app.tavernbridge.launcher.data

import app.tavernbridge.launcher.security.redactSensitiveText

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactionTest {
    @Test
    fun redactsBearerAuthorizationHeaders() {
        val redacted = redactSensitiveText("Authorization: Bearer sk-live-secret-token")

        assertFalse(redacted.contains("sk-live-secret-token"))
        assertEquals("Authorization: [숨김]", redacted)
    }

    @Test
    fun redactsJsonAndQueryStringSecrets() {
        val redacted = redactSensitiveText(
            """{"api_key":"sk-json-secret","url":"https://example.test?access_token=query-secret-value&model=x"}""",
        )

        assertFalse(redacted.contains("sk-json-secret"))
        assertFalse(redacted.contains("query-secret-value"))
    }

    @Test
    fun redactsStandaloneKnownTokenShapes() {
        val redacted = redactSensitiveText("provider rejected hf_abcdefghijklmnopqrstuvwxyz")

        assertFalse(redacted.contains("hf_abcdefghijklmnopqrstuvwxyz"))
    }

    @Test
    fun leavesOrdinaryErrorsReadable() {
        assertEquals(
            "Connection failed: upstream returned 503",
            redactSensitiveText("Connection failed: upstream returned 503"),
        )
    }

    @Test
    fun redactsEntireBasicAndDigestAuthorizationValues() {
        assertEquals("Authorization: [숨김]", redactSensitiveText("Authorization: Basic dXNlcjpleGFtcGxl"))
        val digest = "Authorization: Digest username=someone, nonce=privateNonce, response=privateResponse"
        assertEquals("Authorization: [숨김]", redactSensitiveText(digest))
    }

    @Test
    fun redactsQuotedWhitespaceEscapesAndUnfinishedValues() {
        assertEquals("password=[숨김]", redactSensitiveText("password=\"two words remain\""))
        assertEquals("password=[숨김]", redactSensitiveText("""password='two \'quoted\' words'"""))
        assertEquals("password=[숨김]", redactSensitiveText("password=\"unfinished secret words"))
    }

    @Test
    fun redactsProviderSpecificKeysAndPreservesOtherFields() {
        val raw = """{"api_key_mistral":"provider-secret", "openai_api_key":"other-secret", "model":"ordinary"}"""
        val safe = redactSensitiveText(raw)
        assertFalse(safe.contains("provider-secret"))
        assertFalse(safe.contains("other-secret"))
        assertTrue(safe.contains("ordinary"))
    }

    @Test
    fun redactsJsonAuthorizationAndUrlCredentials() {
        val safe = redactSensitiveText("""{"authorization":"Basic dXNlcjpleGFtcGxl", "url":"https://user:private@example.test/path"}""")
        assertFalse(safe.contains("dXNlcjpleGFtcGxl"))
        assertFalse(safe.contains("user:private"))
        assertTrue(safe.contains("example.test/path"))
        assertEquals(safe, redactSensitiveText(safe))
    }

    @Test
    fun fullReportModeDoesNotTruncateAfterRedaction() {
        val raw = "ordinary status\n".repeat(100) + "api_key_mistral: privateSecret\nlast item"
        val safe = redactSensitiveText(raw, maxLength = null)
        assertTrue(safe.length > 600)
        assertTrue(safe.endsWith("last item"))
        assertFalse(safe.contains("privateSecret"))
        assertEquals(600, redactSensitiveText(raw).length)
    }

    @Test
    fun repeatedRedactionDoesNotCorruptAlreadyHiddenValues() {
        val once = redactSensitiveText("api_key_mistral=providerSecret\npassword=\"two words\"\nAuthorization: Basic dXNlcjpleGFtcGxl")
        assertEquals(once, redactSensitiveText(once))
    }
}
