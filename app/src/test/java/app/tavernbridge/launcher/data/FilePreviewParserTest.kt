package app.tavernbridge.launcher.data

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class FilePreviewParserTest {
    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())

    @Test fun truncatedTextIsSeparateFromEditableDocument() {
        val preview = FilePreviewParser.parse("large.txt", "source_bytes=50000\npreview_kind=text\ntruncated=1\ncontent_b64=${b64("front only")}")
        assertTrue(preview.truncated)
        assertEquals("front only", preview.text)
        assertNull(preview.pngBytes)
    }

    @Test fun cardMetadataHasExplicitlyNoImage() {
        val preview = FilePreviewParser.parse("card.png", "source_bytes=100000\npreview_kind=card\nname_b64=${b64("카드")}\ndescription_b64=${b64("설명")}")
        assertEquals("카드", preview.cardName)
        assertEquals("설명", preview.cardDescription)
        assertNull(preview.pngBytes)
        assertNull(preview.text)
    }

    @Test fun malformedDuplicatedOversizedAndBinaryPayloadsAreRejected() {
        val prefix = "source_bytes=50000\npreview_kind=text\ntruncated=1\ncontent_b64="
        for (output in listOf(prefix + "?", prefix + b64("\u0000"), prefix + b64("a".repeat(24577)),
            prefix + b64("a") + "\ntruncated=0", prefix.replace("truncated=1", "truncated=yes") + b64("a"),
            prefix.replace("source_bytes=50000", "source_bytes=0") + b64("longer"))) {
            assertTrue(runCatching { FilePreviewParser.parse("a", output) }.isFailure)
        }
    }

    @Test fun imagePayloadMustMatchSourceAndHaveCanonicalHeader() {
        val png = java.nio.ByteBuffer.allocate(24).apply {
            put(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            putInt(13); put("IHDR".toByteArray()); putInt(1); putInt(1)
        }.array()
        fun output(size: Int, bytes: ByteArray) = "source_bytes=$size\npreview_kind=png\ncontent_b64=${Base64.getEncoder().encodeToString(bytes)}"
        assertNotNull(FilePreviewParser.parse("a.png", output(24, png)).pngBytes)
        assertTrue(runCatching { FilePreviewParser.parse("a.png", output(25, png)) }.isFailure)
        java.nio.ByteBuffer.wrap(png).putInt(8, 14)
        assertTrue(runCatching { FilePreviewParser.parse("a.png", output(24, png)) }.isFailure)
    }
}
