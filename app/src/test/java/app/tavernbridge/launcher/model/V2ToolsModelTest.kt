package app.tavernbridge.launcher.model

import org.junit.Assert.*
import org.junit.Test

class V2ToolsModelTest {
    @Test fun extensionRowsUseRemoteVersionsOrCommitCountsAndPutDisabledLast() {
        val a = ManagedExtension("a","r","Alpha","1.0","user",false,"clean","b",true,"")
        val b = a.copy(id="b", name="Beta", remoteAhead=2, remoteVersion="1.2")
        val c = a.copy(id="c", name="Aardvark", disabled=true, canUpdate=false)
        assertEquals(listOf(b,a,c), extensionDisplayOrder(listOf(c,a,b)))
        assertEquals("1.0 → 1.2", extensionVersionLabel(b))
        assertEquals("1.0 · 새 커밋 2개", extensionVersionLabel(b.copy(remoteVersion=null)))
        assertEquals("1.0 · 새 커밋 2개", extensionVersionLabel(b.copy(remoteVersion="1.0")))
    }

    @Test fun highlightsOnlyActualTimestampPrefixAndFormatsKnownFileDates() {
        assertEquals("[14:55:00]".length, logTimestampLength("[14:55:00] ready"))
        assertEquals("2026-11-24T14:55:00Z".length, logTimestampLength("2026-11-24T14:55:00Z ready"))
        assertEquals(0, logTimestampLength("ready at 14:55:00"))
        assertEquals("11/23 21:40", fileListDate("2026-11-23 21:40:08"))
        assertEquals("미확인", fileListDate("미확인"))
    }
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
