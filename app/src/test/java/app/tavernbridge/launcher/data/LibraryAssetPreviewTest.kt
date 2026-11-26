package app.tavernbridge.launcher.data

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class LibraryAssetPreviewTest {
    private fun enc(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
    private fun peek(label: String, detail: String = "", excerpt: String = "", color: String = "-") =
        listOf("peek", enc(label), enc(detail), enc(excerpt), color).joinToString("\t")
    private val ended = "preview\t0\t"

    @Test fun preservesUnicodeExcerptsAndArgbIncludingTransparency() {
        val out = listOf(peek("색", "#12345678", color = "78123456"),
            peek("항목", "키워드", "📚".repeat(256)), "preview\t3\t").joinToString("\r\n") + "\r\n"
        val p = LibraryAssetPreview.parse(out)
        assertEquals(0x78123456L, p.entries[0].colorArgb)
        assertEquals("📚".repeat(256), p.entries[1].excerpt)
        assertNull(p.entries[1].colorArgb)
        assertEquals(3, p.omitted)
        assertEquals("", p.notice)
        assertEquals("파일이 커요", LibraryAssetPreview.parse("preview\t0\t" + enc("파일이 커요")).notice)
    }

    @Test fun onlyAcceptsCompleteBoundedSummariesWithSafeFieldTypes() {
        val item = peek("제목")
        val invalid = listOf(item, ended + "\n" + item, "preview\t-1\t", peek(" ") + "\n" + ended,
            List(13) { item }.plus(ended).joinToString("\n"),
            peek("X".repeat(73)) + "\n" + ended, peek("A", excerpt = "📚".repeat(257)) + "\n" + ended,
            peek("A", excerpt = "a\u202eb") + "\n" + ended, peek("A", color = "url(#red)") + "\n" + ended,
            "peek\t/w==\t\t\t-\n" + ended, "peek\tQQ\t\t\t-\n" + ended, "x".repeat(32769))
        invalid.forEachIndexed { index, value ->
            assertTrue("malformed fixture $index", runCatching { LibraryAssetPreview.parse(value) }.isFailure)
        }
    }
}
