package app.tavernbridge.launcher.model

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class ReplacementProtectionTest {
    @Test fun backupFinishesBeforeReplacement() = runBlocking {
        val events = mutableListOf<String>()
        val result = protectBeforeReplacement(true, { events += "backup" }) { events += "replace"; 7 }
        assertEquals(listOf("backup", "replace"), events)
        assertEquals(7, result)
    }
    @Test fun failedBackupNeverReplacesAnything() = runBlocking {
        var replaced = false
        try {
            protectBeforeReplacement(true, { error("no space") }) { replaced = true }
            fail("backup failure swallowed")
        } catch (expected: IllegalStateException) { assertEquals("no space", expected.message) }
        assertFalse(replaced)
    }
    @Test fun optingOutDoesNotRunBackup() = runBlocking {
        assertEquals("done", protectBeforeReplacement(false, { error("unexpected backup") }) { "done" })
    }
    @Test fun cancellationBetweenBackupAndReplacementKeepsOriginal() = runBlocking {
        val control = OperationCancellationControl()
        var replaced = false
        try {
            withContext(control) {
                protectBeforeReplacement(true, { control.requestCancellation() }) { replaced = true }
            }
            fail("cancellation ignored")
        } catch (_: UserOperationCancelled) { }
        assertFalse(replaced)
    }
    @Test fun replacementFailureDoesNotUndoCompletedBackup() = runBlocking {
        var backedUp = false
        try {
            protectBeforeReplacement(true, { backedUp = true }) { error("replacement failed") }
        } catch (_: IllegalStateException) { }
        assertTrue(backedUp)
    }
    @Test fun sourceChoiceIsExplicitAndIndependentOfDestinationBackup() {
        assertEquals("keep", InstallationImportMode.COPY.argument)
        assertEquals("move", InstallationImportMode.MOVE.argument)
    }
    @Test fun detachedBackupCompletionDoesNotClaimReplacementCompletion() {
        listOf("restore", "import-backup", "import-install").forEach {
            assertTrue(recoveredSubstepNotice(it, "backup", "success").orEmpty().contains("후속 단계는 자동으로 진행하지 않았습니다"))
            assertNull(recoveredSubstepNotice(it, it, "success"))
        }
    }
}
