package app.tavernbridge.launcher.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanSecuritySettingsTest {
    @Test fun emptyShortAndControlCharactersAreRejected() {
        for ((user, password) in listOf("" to "long-password", "user" to "short", "a:b" to "long-password", "user\n" to "long-password", "user" to "long\npassword")) {
            assertNull(EphemeralBasicAuthCredentials.create(user, password))
        }
        assertNull(EphemeralBasicAuthCredentials.create("x".repeat(129), "long-password"))
        assertNull(EphemeralBasicAuthCredentials.create("user", "x".repeat(1025)))
        assertNull(EphemeralBasicAuthCredentials.create("user", " ".repeat(8)))
        assertNull(EphemeralBasicAuthCredentials.create("user", "\u3000".repeat(8)))
        assertTrue(EphemeralBasicAuthCredentials.valid("user", "   valid password   "))
    }

    @Test fun credentialsNeverAppearInDefaultStringAndCanBeCleared() {
        val credentials = EphemeralBasicAuthCredentials.create("private-user", "secret-password")!!
        val change = LanSecurityChange(true, LanWhitelist.loopbacks, true, credentials)
        assertFalse(credentials.toString().contains("private-user"))
        assertFalse(credentials.toString().contains("secret-password"))
        assertFalse(change.toString().contains("secret-password"))
        change.close()
        assertTrue(credentials.password.all { it == '\u0000' })
        change.close() // idempotent
    }

    @Test(expected = IllegalArgumentException::class)
    fun legacyAllowlistCannotBeKeptWhenEnablingExternalAccess() {
        LanSecurityChange(true, null, false, null)
    }
}
