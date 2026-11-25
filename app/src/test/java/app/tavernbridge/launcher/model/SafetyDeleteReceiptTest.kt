package app.tavernbridge.launcher.model

import org.junit.Assert.*
import org.junit.Test

class SafetyDeleteReceiptTest {
    @Test fun onlyConfirmedChildResponsesAdvanceTheLowerBound() {
        val start = SafetyDeleteReceipt(3)
        assertEquals(0, start.confirmed)
        val afterOne = start.acknowledge()
        val restarted = afterOne.summary(1500, "date")
        assertFalse(restarted.succeeded)
        assertFalse(restarted.canRetry)
        assertTrue(restarted.detail.contains("3개 중 최소 1개"))
        assertTrue(restarted.isUnfinishedDeleteStep("safety-delete", 1000))
        assertFalse(restarted.isUnfinishedDeleteStep("backup", 1000))
        assertFalse(restarted.isUnfinishedDeleteStep("safety-delete", 2000))
        assertFalse(restarted.copy(succeeded = true).isUnfinishedDeleteStep("safety-delete", 1000))
        val childSuccess = restarted.copy(operation = "safety-delete", detail = "삭제 성공", succeeded = true, errorCode = "")
        assertFalse(afterOne.finish(childSuccess).succeeded)
        assertFalse(afterOne.finish(childSuccess.copy(succeeded = false)).canRetry)
        assertTrue(afterOne.finish(childSuccess.copy(succeeded = false)).detail.contains("최소 1개"))
        val complete = afterOne.acknowledge().acknowledge()
        assertTrue(complete.summary(2000, "date").succeeded)
        assertEquals("", complete.summary(2000, "date").errorCode)
        assertTrue(complete.notice.contains("3개 전부"))
        assertFalse(complete.notice.contains("나머지"))
        assertTrue(complete.finish(childSuccess).succeeded)
        assertEquals("삭제 성공", complete.finish(childSuccess).detail)
    }

    @Test fun invalidCountValuesAndOverAcknowledgementAreRejected() {
        for ((n,m) in listOf(1 to 0, 1001 to 1, 2 to -1, 2 to 3))
            assertThrows(IllegalArgumentException::class.java) { SafetyDeleteReceipt(n, m) }
        assertThrows(IllegalArgumentException::class.java) { SafetyDeleteReceipt(2, 2).acknowledge() }
    }
}
