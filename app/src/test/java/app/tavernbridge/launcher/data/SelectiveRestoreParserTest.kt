package app.tavernbridge.launcher.data

import app.tavernbridge.launcher.model.SelectiveRestoreChoice
import app.tavernbridge.launcher.model.SelectiveRestoreConflict
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class SelectiveRestoreParserTest {
    private val revision = "a".repeat(64)
    private val path = "data/default-user/characters/Alice.png"
    private fun b64(value: String) = Base64.getEncoder().encodeToString(value.toByteArray())
    private fun output() = "selection_format=1\narchive_revision=$revision\ninstallation_revision=$revision\nnext_cursor=-1\ncandidate_count=1\n" +
        "restore_item\tcharacter\t${b64(path)}\t123\t1\t$revision\t0\t1\t${b64("default-user")}\t${b64("Alice.png")}\n"

    @Test fun parsesBoundedItemAndDefaultsToSkip() {
        val page = SelectiveRestoreParser.parse(output())
        assertEquals(path, page.items.single().relativePath)
        assertNull(page.nextCursor)
        val encoded = SelectiveRestoreParser.encodeChoices(listOf(SelectiveRestoreChoice(page.items.single())))
        assertTrue(String(Base64.getDecoder().decode(encoded)).contains("\"policy\":\"skip\""))
        val replace = SelectiveRestoreParser.encodeChoices(listOf(SelectiveRestoreChoice(page.items.single(), SelectiveRestoreConflict.REPLACE)))
        assertTrue(String(Base64.getDecoder().decode(replace)).contains("\"policy\":\"replace\""))
    }

    @Test fun rejectsMalformedPagesAndUnauthorizedPaths() {
        listOf(
            output() + "next_cursor=0\n",
            output().replace(b64(path), b64("data/default-user/secrets.json")),
            output().replace(b64(path), b64("data/../characters/Alice.png")),
            output().replace("\t$revision\t0", "\tnone\t0"),
            output().replace("\t0\t1\t", "\t1\t1\t"),
            output().replace(b64("Alice.png"), b64("other.png")),
            output() + "x".repeat(40960),
        ).forEach { bad -> assertThrows(IllegalArgumentException::class.java) { SelectiveRestoreParser.parse(bad) } }
    }

    @Test fun selectionLimitsAndProfileGuardAreRepeated() {
        val item = SelectiveRestoreParser.parse(output()).items.single()
        assertThrows(IllegalArgumentException::class.java) { SelectiveRestoreParser.encodeChoices(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { SelectiveRestoreParser.encodeChoices(List(2) { SelectiveRestoreChoice(item) }) }
        assertThrows(IllegalArgumentException::class.java) { SelectiveRestoreParser.encodeChoices(listOf(SelectiveRestoreChoice(item.copy(profileExists = false)))) }
        val quoted = item.copy(relativePath = "data/default-user/characters/A\"lice.png")
        val json = String(Base64.getDecoder().decode(SelectiveRestoreParser.encodeChoices(listOf(SelectiveRestoreChoice(quoted)))))
        assertTrue(json.contains("A\\\"lice.png"))
    }
}
