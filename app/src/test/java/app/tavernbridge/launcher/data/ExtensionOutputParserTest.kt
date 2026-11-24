package app.tavernbridge.launcher.data

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class ExtensionOutputParserTest {
    private fun b64(value: String) = Base64.getEncoder().encodeToString(value.toByteArray())
    private val header = "extension_inventory=1\nnext_cursor=-1\nskipped_entries=0\nextension_recovery=none\nextension_recovery_message_b64=\n"
    private fun row() = listOf("extension", b64("data/default-user/extensions/test"), "a".repeat(64), b64("Test"), b64("1"),
        b64("사용자 default-user"), "enabled", "clean", "b".repeat(40), "1", "").joinToString("\t")

    @Test fun validListPreservesOpaqueIdentityAndFileState() {
        val inventory = ExtensionOutputParser.parse(header + row())
        assertNull(inventory.nextCursor)
        assertEquals("none", inventory.recoveryStatus)
        assertEquals(1, inventory.items.size)
        assertTrue(inventory.items.single().canUpdate)
        assertFalse(inventory.items.single().disabled)
    }

    @Test fun recoveryNoticeIsNotTreatedAsAHealthyEmptyList() {
        val inventory = ExtensionOutputParser.parse(header.replace("extension_recovery=none", "extension_recovery=blocked")
            .replace("extension_recovery_message_b64=", "extension_recovery_message_b64=${b64("점검 필요")}"))
        assertEquals("blocked", inventory.recoveryStatus)
        assertEquals("점검 필요", inventory.recoveryMessage)
    }

    @Test fun newProtocolEnablesReadOnlyCompareButNeverAssumesAnUpdate() {
        val item = ExtensionOutputParser.parse(header + row() + "\t" + b64("owner/example") + "\t" + b64("fix/ui")).items.single()
        assertNull(item.remoteAhead)
        assertEquals("https://api.github.com/repos/owner/example/compare/${"b".repeat(40)}...fix%2Fui?per_page=1",
            ExtensionVersionClient.compareUrl(item)?.toString())
        assertNull(ExtensionVersionClient.compareUrl(item.copy(canUpdate = false)))
        assertNull(ExtensionVersionClient.compareUrl(item.copy(gitHubRepository = "../example")))
        assertEquals("https://api.github.com/repos/owner/example/contents/manifest.json?ref=fix%2Fui", ExtensionVersionClient.manifestUrl(item)?.toString())
        assertNull(ExtensionVersionClient.manifestUrl(item.copy(gitHubRepository="https://untrusted.invalid/x")))
        assertNull(ExtensionVersionClient.compareUrl(ExtensionOutputParser.parse(header + row()).items.single()))
    }

    @Test fun duplicatedAndMalformedValuesFailClosed() {
        for (output in listOf(header + row() + "\n" + row(), header + "next_cursor=0", header + row().replace("a".repeat(64), "invalid"),
            header.replace("next_cursor=-1", "next_cursor=2001"), header.replace("extension_recovery=none", "extension_recovery=yes"),
            header + row().replace("enabled", "maybe"), "x".repeat(50000))) {
            assertTrue(runCatching { ExtensionOutputParser.parse(output) }.isFailure)
        }
    }
}
