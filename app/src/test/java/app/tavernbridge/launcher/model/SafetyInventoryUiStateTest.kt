package app.tavernbridge.launcher.model

import org.junit.Assert.*
import org.junit.Test

class SafetyInventoryUiStateTest {
    @Test fun neverInventsEmptyOrPartialGroupTotals() {
        val start = SafetyInventoryUiState(open = true)
        val more = SafetyInventoryPage(emptyList(), 2, false, false, 0)
        assertNull(start.totalFor(SafetyItemKind.TRASH))
        assertNull(start.accept(more, false).totalFor(SafetyItemKind.TRASH))
        val snapshot = start.accept(more.copy(totals = mapOf(SafetyItemKind.TRASH to 5)), false)
        assertEquals(5, snapshot.accept(more, true).totalFor(SafetyItemKind.TRASH))
        assertNull(snapshot.accept(more.copy(truncated = true), true).totalFor(SafetyItemKind.TRASH))
        assertEquals(0, start.accept(more.copy(nextCursor = null), false).totalFor(SafetyItemKind.TRASH))
    }
    private val first = SafetyInventoryItem(SafetyItemKind.CONFIG, "id1", "a".repeat(64), "config", 42, 1)
    private val second = first.copy(id = "id2", displayName = "second")
    private fun page(items: List<SafetyInventoryItem>) = SafetyInventoryPage(items, 2, false, false, 0)

    @Test fun refreshReplacesStaleEntriesAndPreservesOpenState() {
        val after = SafetyInventoryUiState(open = true, loading = true, items = listOf(first), error = "old")
            .accept(page(listOf(second)), append = false)
        assertEquals(listOf(second), after.items)
        assertTrue(after.open)
        assertFalse(after.loading)
        assertEquals("", after.error)
    }

    @Test fun pagesDoNotDuplicateItemsOrForgetRecoveryLock() {
        val after = SafetyInventoryUiState(items = listOf(first)).accept(
            page(listOf(first, second)).copy(mutationBlocked = true, protectedEntries = 3), append = true)
        assertEquals(listOf(first, second), after.items)
        assertTrue(after.mutationBlocked)
        assertEquals(3, after.protectedEntries)
    }

    @Test fun resetBackupCompletionNeverClaimsResetCompleted() {
        assertTrue(recoveredSubstepNotice("reset-installation", "backup", "success").orEmpty()
            .contains("후속 단계는 자동으로 진행하지 않았습니다"))
        assertNull(recoveredSubstepNotice("reset-installation", "backup", "running"))
        val request = BackupRequest("50bd0d3a-9540-4a33-b81d-1aa37e447f00", setOf(BackupCategory.FULL), true, emptySet(), "reset-installation")
        assertEquals(request, BackupRequestCodec.decode(BackupRequestCodec.encode(request)))
        assertEquals("", request.newAttempt().parentOperation)
    }
}
