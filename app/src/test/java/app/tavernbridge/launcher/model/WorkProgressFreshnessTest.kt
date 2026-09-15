package app.tavernbridge.launcher.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkProgressFreshnessTest {
    private fun progress(operation: String, phaseStartedAt: Long) = WorkProgress(
        0, "복사", "", operation = operation, phaseStartedAtMillis = phaseStartedAt,
    )

    @Test
    fun rejectsPreviousRunOfSameOperationDuringStagingTransition() {
        assertFalse(progress("import-backup", 99_000L).belongsToStartedOperation("import-backup", 100_700L))
        assertFalse(progress("import-st-file", 99_000L).belongsToStartedOperation("import-st-file", 100_700L))
    }

    @Test
    fun acceptsShellTimestampRoundedDownWithinCurrentSecond() {
        assertTrue(progress("import-backup", 100_000L).belongsToStartedOperation("import-backup", 100_700L))
        assertTrue(progress("import-st-file", 100_999L).belongsToStartedOperation("import-st-file", 100_700L))
    }

    @Test
    fun rejectsUnknownTimestampAndUnrelatedOperation() {
        assertFalse(progress("import-backup", 0L).belongsToStartedOperation("import-backup", 100_000L))
        assertFalse(progress("backup", 101_000L).belongsToStartedOperation("import-backup", 100_000L))
        assertFalse(progress("import-backup", 100_000L).belongsToStartedOperation("import-backup", 0L))
    }

    @Test
    fun acceptsOnlyKnownCompositeWorkflowSteps() {
        listOf("stop", "backup", "update", "start").forEach {
            assertTrue(progress(it, 100_000L).belongsToStartedOperation("update", 100_000L))
        }
        listOf("stop", "start").forEach {
            assertTrue(progress(it, 100_000L).belongsToStartedOperation("restart", 100_000L))
            assertTrue(progress(it, 100_000L).belongsToStartedOperation("switch-branch", 100_000L))
        }
        assertFalse(progress("restore", 100_000L).belongsToStartedOperation("update", 100_000L))
        assertFalse(progress("backup", 100_000L).belongsToStartedOperation("restart", 100_000L))
    }

    @Test
    fun batchBackupDeletionMatchesItsUiOperationName() {
        assertTrue(progress("delete-backups", 100_000L).belongsToStartedOperation("delete-backup", 100_000L))
    }
}
