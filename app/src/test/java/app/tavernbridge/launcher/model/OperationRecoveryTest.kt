package app.tavernbridge.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationRecoveryTest {
    @Test
    fun onlyExplicitSuccessRestoresACompletedOperation() {
        assertEquals(RecoveredOperationStatus.SUCCESS, recoveredOperationStatus("success"))
        assertEquals(RecoveredOperationStatus.FAILURE, recoveredOperationStatus("error"))
        assertEquals(RecoveredOperationStatus.FAILURE, recoveredOperationStatus("cancelled"))
    }

    @Test
    fun missingOrStaleProgressIsInterruptedNotSuccessful() {
        listOf(null, "", "running", "unknown").forEach { status ->
            assertEquals(RecoveredOperationStatus.INTERRUPTED, recoveredOperationStatus(status))
        }
    }

    @Test
    fun stoppedServerCanBeRestoredAfterAnUpdatePreparationFailure() {
        assertTrue(shouldRestoreUpdateServer(true, EnvironmentStatus(), rollbackFailed = false))
    }

    @Test
    fun timeoutMustNotStartServerDuringAnActiveUpdate() {
        assertFalse(
            shouldRestoreUpdateServer(true, EnvironmentStatus(operationActive = true), rollbackFailed = false),
        )
    }

    @Test
    fun failedRollbackMustNotLaunchAnUnsafeInstallation() {
        assertFalse(shouldRestoreUpdateServer(true, EnvironmentStatus(), rollbackFailed = true))
    }

    @Test
    fun recoveryDoesNotDuplicateServerOrStartAPreviouslyStoppedServer() {
        assertFalse(
            shouldRestoreUpdateServer(true, EnvironmentStatus(processRunning = true), rollbackFailed = false),
        )
        assertFalse(shouldRestoreUpdateServer(false, EnvironmentStatus(), rollbackFailed = false))
    }

    @Test
    fun timeoutRequiresStatusRefreshInsteadOfRepeatingTheOperation() {
        RetryAction.entries.forEach { originalAction ->
            assertEquals(RetryAction.REFRESH, retryActionAfterFailure("TERMUX_TIMEOUT", originalAction))
        }
    }

    @Test
    fun ordinaryFailuresKeepTheirAllowedRetryAction() {
        assertEquals(RetryAction.START, retryActionAfterFailure("SERVER_START_FAILED", RetryAction.START))
        assertEquals(RetryAction.NONE, retryActionAfterFailure("RESTORE_NO_SPACE", RetryAction.NONE))
    }
}
