package app.tavernbridge.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalProgressJournalTest {
    @Test
    fun wholeOperationStartDoesNotResetWithTheNextPhase() {
        var now = 10_000L
        val values = mutableListOf<WorkProgress>()
        val journal = LocalProgressJournal("diagnose", values::add, { now })
        journal.phase("기기 검사", "권한 확인")
        now = 25_000L
        journal.phase("Termux 검사", "응답 확인")
        assertEquals(10_000L, values.last().operationStartedAtMillis)
        assertEquals(25_000L, values.last().phaseStartedAtMillis)
    }

    @Test
    fun itemCountsAdvanceOnlyWhenWorkCompletes() {
        val values = mutableListOf<WorkProgress>()
        var now = 10_000L
        val journal = LocalProgressJournal("diagnose", values::add, { now })
        journal.phase("검사", "로컬 검사", 2)
        assertEquals(0, values.last().measuredPercent)
        assertEquals(0L, values.last().activityAtMillis)
        now = 12_000L
        journal.item("권한 확인", "시스템 응답 대기")
        assertEquals(0L, values.last().completedFiles)
        assertEquals(0L, values.last().activityAtMillis)
        journal.completedItem("권한 확인")
        assertEquals(50, values.last().measuredPercent)
        assertEquals(12_000L, values.last().activityAtMillis)
        journal.completedItem("설치 확인")
        assertEquals(100, values.last().measuredPercent)
        assertEquals("running", values.last().status)
    }

    @Test
    fun unknownTotalNeverFabricatesPercentageAndNextPhaseResetsCounts() {
        val values = mutableListOf<WorkProgress>()
        val journal = LocalProgressJournal("connect-manager", values::add, { 10_000L })
        journal.phase("준비", "스크립트 준비")
        journal.completedItem("스크립트 준비")
        assertNull(values.last().measuredPercent)
        assertEquals(1L, values.last().completedFiles)
        journal.phase("응답 확인", "Termux 응답 대기")
        assertEquals(0L, values.last().completedFiles)
        assertEquals(0L, values.last().activityAtMillis)
        assertTrue(values.last().logText.contains("완료 · 스크립트 준비"))
    }

    @Test
    fun journalBoundsHistoryAndRedactsSecrets() {
        val values = mutableListOf<WorkProgress>()
        val journal = LocalProgressJournal("diagnose", values::add, { 10_000L })
        journal.phase("진단", "api_key=test-secret")
        assertFalse(values.last().logText.contains("test-secret"))
        repeat(30) { journal.completedItem("항목 $it") }
        assertEquals(12, values.last().logText.lines().size)
        assertEquals(30L, values.last().completedFiles)
    }
}
