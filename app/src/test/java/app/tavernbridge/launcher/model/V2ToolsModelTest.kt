package app.tavernbridge.launcher.model

import org.junit.Assert.*
import org.junit.Test

class V2ToolsModelTest {
    @Test fun errorToggleAndSearchAreCombinedWithoutLosingOrdering() {
        val log = "server ready\n[오류] API 응답 없음\nWARN Slow API\n    at Extension.run()\n200 /api/chat"
        assertEquals(listOf("[오류] API 응답 없음", "WARN Slow API"), visibleLogLines(log, "api", true))
        assertEquals(3, visibleLogLines(log, "", true).size)
        assertEquals(3, visibleLogLines(log, "api", false).size)
        assertTrue(visibleLogLines("", "", false).isEmpty())
        assertFalse(isProblemLog("HTTP 200 /errorless/page"))
    }
    @Test fun batchUndoOnlyUsesConfirmedCurrentReceiptsAndLegacySingleReceiptsWork() {
        val a = "123-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        val b = "124-bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        val c = "125-cccccccc-cccc-cccc-cccc-cccccccccccc"
        assertEquals(listOf(a), TrashUndoGroup.parse(a))
        val selected = TrashUndoGroup.success(TrashUndoGroup.success(listOf(c), a, false), b, true)
        assertEquals(listOf(a,b), selected)
        assertEquals(selected, TrashUndoGroup.success(selected, "", true))
        assertEquals(listOf(a,b), TrashUndoGroup.parse("$a\nnot-a-receipt\n$b\n$a"))
        assertEquals(listOf(c), TrashUndoGroup.success(selected, c, false))
    }
}
