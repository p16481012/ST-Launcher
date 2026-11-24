package app.tavernbridge.launcher.data

import app.tavernbridge.launcher.model.EphemeralBasicAuthCredentials
import app.tavernbridge.launcher.model.LanSecurityChange
import app.tavernbridge.launcher.model.LanWhitelist
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanSecurityPayloadTest {
    @Test fun disableExposureDoesNotSendCachedAuthOrWhitelist() {
        LanSecurityChange.disableExposure().use { change ->
            assertEquals("""{"protocol":2,"action":"disable-listen"}""", LanSecurityPayload.encode(change).toString(Charsets.UTF_8))
        }
    }
    @Test fun escapedCredentialsCannotInjectExtraJsonFields() {
        val credentials = EphemeralBasicAuthCredentials.create("user\"test", "비밀\\\"pass123")!!
        LanSecurityChange(true, LanWhitelist.loopbacks, true, credentials).use { change ->
            val payload = LanSecurityPayload.encode(change)
            val json = payload.toString(Charsets.US_ASCII)
            assertTrue(json.contains("\"protocol\":1"))
            assertTrue(json.contains("user\\\"test"))
            assertTrue(json.contains("\\ube44\\ubc00"))
            assertTrue(payload.size <= 16384)
            payload.fill(0)
        }
    }

    @Test fun legacyWhitelistIsKeptWithoutSendingCredentialsWhenDisabling() {
        LanSecurityChange(false, null, false, null).use { change ->
            val payload = LanSecurityPayload.encode(change)
            val json = payload.toString(Charsets.US_ASCII)
            assertTrue(json.contains("\"allowedNetworks\":null"))
            assertTrue(json.contains("\"credentials\":null"))
            assertFalse(json.contains("password"))
            payload.fill(0)
        }
    }

    @Test fun publicOrMalformedEntriesCannotBeSerialized() {
        LanSecurityChange(true, listOf("*"), false, null).use { change ->
            assertTrue(runCatching { LanSecurityPayload.encode(change) }.isFailure)
        }
    }

    @Test fun aPasswordMutatedToWhitespaceCannotBeSerialized() {
        val credentials = EphemeralBasicAuthCredentials.create("user", "valid-password")!!
        credentials.password.fill(' ')
        LanSecurityChange(true, LanWhitelist.loopbacks, true, credentials).use { change ->
            assertTrue(runCatching { LanSecurityPayload.encode(change) }.isFailure)
        }
    }
}
