package app.tavernbridge.launcher.data

import org.junit.Assert.*
import org.junit.Test

class LibraryActionNamesTest {
    private fun row(path: String) = LibraryItem(path, path.substringAfterLast('/'), 1, 0, 0, "1".repeat(64), false)

    @Test fun onlyTheFiveSupportedProfileFoldersAreMutable() {
        listOf("backgrounds/노을.webp", "worlds/책.json", "OpenAI Settings/대화.json",
            "QuickReplies/대답.json", "themes/연두.json").forEach { assertTrue(it, row(it).isManagedAsset()) }
        listOf("characters/a.png", "chats/a/chat.jsonl", "settings.json", "../a.json", "themes/sub/a.json",
            "themes/.hidden.json", "themes/test.png").forEach { assertFalse(it, row(it).isManagedAsset()) }
        assertFalse(row("themes/test.json").copy(revision="").isManagedAsset())
    }

    @Test fun keepExtensionAndRejectUnsafeOrOversizedStemsWithoutTruncation() {
        val asset = row("backgrounds/밤하늘.png")
        for (bad in listOf("밤하늘", "", ".hidden", "a.", " 앞", "뒤 ", "../폴더", "a:b", "a\\b", "a\n", "가".repeat(80)))
            assertNotNull(bad, assetNameError(asset,bad))
        assertNull(assetNameError(asset,"초여름 밤 (2)"))
        assertNull(assetNameError(asset,"가".repeat(78)))
        assertEquals("한", assetStem("\u1112\u1161\u11ab"))
    }

    @Test fun cardsOnlyUseTheDedicatedLinkPreservingOperations() {
        val card = row("characters/노을 (2).png")
        assertTrue(card.isCardAsset())
        listOf("nul", "LPT5.backup", "a\u0085b").forEach { assertNotNull(it, assetNameError(card,it)) }
        assertNull(assetNameError(card,"Nully"))
        assertFalse(card.isManagedAsset())
        listOf("backgrounds/a.png", "characters/a.json", "characters/.a.png", "characters/sub/a.png",
            "characters/../a.png", "characters/a\\b.png", "characters/a\nb.png").forEach {
            assertFalse(it, row(it).isCardAsset())
        }
        assertFalse(card.copy(revision = "").isCardAsset())
    }
}
