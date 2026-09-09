package app.tavernbridge.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFormattingTest {
    @Test
    fun `one sentence is not split into separate lines`() {
        val formatted = formatUiText(
            "필요한 항목만 골라 검증된 ZIP으로 Download 폴더에 저장합니다.",
            keepWordsTogether = true,
        )

        assertFalse(formatted.contains('\n'))
        assertTrue(formatted.contains('\u2060'))
    }

    @Test
    fun `next sentence starts on a new line`() {
        val formatted = formatUiText(
            "서버는 이 기기에서만 열립니다. 외부 네트워크에는 노출되지 않습니다.",
            keepWordsTogether = true,
        )

        assertEquals(
            "서버는 이 기기에서만 열립니다.\n외부 네트워크에는 노출되지 않습니다.",
            formatted.replace("\u2060", ""),
        )
    }
}
