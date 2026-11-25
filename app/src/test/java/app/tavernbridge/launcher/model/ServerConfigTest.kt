package app.tavernbridge.launcher.model

import org.junit.Assert.*
import org.junit.Test

class ServerConfigTest {
    private val protocol = buildString {
        appendLine("config_revision=" + "a".repeat(64)); appendLine("st_version=1.19.1")
        ServerSetting.entries.forEach { k -> appendLine("key\t" + k.key + when(k) {
            ServerSetting.THUMB -> "\t1\tfalse\ttrue\t1"
            ServerSetting.COPIES -> "\t1\t388\t-1\t1"
            else -> "\t0\tnull\tnull\t0"
        }) }
    }
    @Test fun retainsCustomQuotaButDoesNotOfferItAsAnEditablePreset() {
        val config = ServerConfig.parse(protocol)
        assertEquals(mapOf(ServerSetting.THUMB to "false", ServerSetting.COPIES to "388"),config.values)
        assertFalse(ServerSetting.COPIES.accepts("388")); assertTrue(ServerSetting.COPIES.accepts("-1"))
    }
    @Test fun partialDuplicateAndInvalidResponsesAreRejected() {
        listOf(protocol.substringBefore("key\tenableCorsProxy"), protocol + "key\tenableCorsProxy\t0\tnull\tnull\t0",
            protocol.replace("false\ttrue","not-bool\ttrue"), protocol.replace("a".repeat(64),"bad")).forEach {
            assertTrue(runCatching { ServerConfig.parse(it) }.isFailure)
        }
    }
}
