package app.tavernbridge.launcher.data

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class DataLibraryProtocolTest {
    private fun enc(value: String) = Base64.getEncoder().encodeToString(value.toByteArray())
    private val revision = "f".repeat(64)
    private fun row(path: String, label: String, protected: Boolean = false) =
        listOf("item",enc(path),enc(label),"1234","2","400",revision,if(protected) "1" else "0","234",revision).joinToString("\t")

    @Test fun readsProfileRelativePathsLabelsAndPagingWithoutInventingMissingData() {
        val page = LibraryProtocol.page(row("characters/겨울.png","겨울",true) + "\nnext_cursor=${enc("겨울")}")
        assertEquals("겨울",page.items.single().label)
        assertEquals("characters/겨울.png",page.items.single().path)
        assertEquals(1234L,page.items.single().bytes)
        assertTrue(page.items.single().protected)
        assertEquals(234L,page.items.single().fileBytes)
        assertEquals(revision,page.items.single().artRevision)
        assertEquals(enc("겨울"),page.next)
        assertTrue(runCatching { LibraryProtocol.page(row("characters/Test.png","Test")) }.isFailure)
        assertTrue(runCatching { LibraryProtocol.page(row("../private","private")+"\nnext_cursor=") }.isFailure)
    }
    @Test fun acceptsOnlyOneOfTheThreeMatchingJsonLibraries() {
        assertNull(LibraryProtocol.jsonLibrary(mapOf("context" to "BEGIN_OBJECT","instruct" to "BEGIN_OBJECT","prompts" to "BEGIN_ARRAY")))
        assertNull(LibraryProtocol.jsonLibrary(mapOf("name" to "STRING","data" to "BEGIN_OBJECT")))
        assertNull(LibraryProtocol.jsonLibrary(mapOf("temp" to "NUMBER","rep_pen" to "NUMBER")))
        assertNull(LibraryProtocol.jsonLibrary(mapOf("temperature" to "NUMBER","repetition_penalty" to "NUMBER")))
        assertNull(LibraryProtocol.jsonLibrary(mapOf("prompts" to "BEGIN_ARRAY","qrList" to "BEGIN_ARRAY")))
        assertEquals("presets",LibraryProtocol.jsonLibrary(mapOf("prompts" to "BEGIN_ARRAY")))
        assertEquals("themes",LibraryProtocol.jsonLibrary(mapOf("main_text_color" to "STRING")))
        assertEquals("quickreplies",LibraryProtocol.jsonLibrary(mapOf("qrList" to "BEGIN_ARRAY")))
        assertEquals("quickreplies",LibraryProtocol.jsonLibrary(mapOf("quickReplySlots" to "BEGIN_ARRAY")))
    }
    @Test fun groupReceiptsMustBeKnownShapeAndAreShownNewestFirst() {
        val a = "0".repeat(8)+"-0000-0000-0000-000000000001"
        val b = "0".repeat(8)+"-0000-0000-0000-000000000002"
        assertEquals(listOf(b,a),LibraryProtocol.groups("group\t$a\t100\t5\ngroup\t$b\t200\t8").map { it.id })
        assertTrue(runCatching { LibraryProtocol.groups("group\t../unsafe\t100\t1") }.isFailure)
    }
    @Test fun backgroundPreflightChecksActualSignature() {
        assertEquals("jpg",LibraryProtocol.picture(byteArrayOf(-1,-40,-1,0)))
        assertNull(LibraryProtocol.picture("picture.png".toByteArray()))
        assertEquals("gif",LibraryProtocol.picture("GIF89a1234567".toByteArray()))
        assertNull(LibraryProtocol.picture("RIFF1234WAVE".toByteArray()))
    }
}
