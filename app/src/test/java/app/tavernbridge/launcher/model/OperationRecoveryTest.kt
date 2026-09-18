package app.tavernbridge.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
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

    @Test
    fun recoveredAutomaticBackupDoesNotClaimTheWholeUpdateCompleted() {
        val notice = recoveredSubstepNotice("update", "backup", "success").orEmpty()
        assertTrue(notice.contains("현재 백업 단계 완료는 확인"))
        assertTrue(notice.contains("업데이트의 전체 완료는 확인하지 못했으며"))
        assertTrue(notice.contains("후속 단계는 자동으로 진행하지 않았습니다"))
        assertTrue(notice.contains("현재 상태를 확인한 뒤 원래 작업을 다시 실행"))
        assertEquals(RecoveredOperationStatus.SUCCESS, recoveredOperationStatus("success"))
    }

    @Test
    fun completedStopIsOnlyAnIntermediateStepOfCompoundRequests() {
        for ((operation, label) in listOf("update" to "업데이트", "restart" to "재시작", "switch-branch" to "브랜치 전환")) {
            val notice = recoveredSubstepNotice(operation, "stop", "success").orEmpty()
            assertTrue(notice.contains("현재 서버 종료 단계 완료"))
            assertTrue(notice.contains("${label}의 전체 완료는 확인하지 못했으며"))
        }
    }

    @Test
    fun completedStartCanBeTheFinalStepAndDoesNotClaimThatStepsWereSkipped() {
        listOf("update", "restart", "switch-branch").forEach { operation ->
            assertNull(recoveredSubstepNotice(operation, "start", "success"))
        }
    }

    @Test
    fun completeRequestsAndUnknownRecoveryKeepTheOrdinaryResultMessage() {
        assertNull(recoveredSubstepNotice("update", "update", "success"))
        assertNull(recoveredSubstepNotice("backup", "backup", "success"))
        assertNull(recoveredSubstepNotice(null, "backup", "success"))
        assertNull(recoveredSubstepNotice("delete-backup", "delete-backups", "success"))
        assertNull(recoveredSubstepNotice("restart", "backup", "success"))
    }

    @Test
    fun unfinishedOrFailedSubstepsAreNeverDescribedAsCompleted() {
        listOf(null, "running", "error", "cancelled").forEach { status ->
            assertNull(recoveredSubstepNotice("update", "backup", status))
        }
    }

    @Test
    fun activeTimeoutDoesNotCreateAFailedResultOrDiscardProgress() {
        val previousResult = OperationResultSummary("backup", "백업", "완료", true,
            completedAt = "이전 작업", completedAtMillis = 1_000L, durationSeconds = 1L)
        val progress = WorkProgress(0, "파일 복사", "", logText = "[00:01:00] 복사 중")
        val before = LauncherUiState(
            section = MainSection.SETUP,
            workingStartedAtMillis = 2_000L,
            workProgress = progress,
            lastOperationResult = previousResult,
            logs = "기존 서버 로그",
            error = "이전 오류",
        )
        val active = EnvironmentStatus(operationActive = true)
        listOf("start", "restart", "update", "backup", "restore").forEach { operation ->
            val after = before.reconnectingAfterTimeout(active, operation)
            assertTrue(after.isWorking)
            assertEquals(active, after.environment)
            assertNull(after.error)
            assertSame(previousResult, after.lastOperationResult)
            assertSame(progress, after.workProgress)
            assertEquals(2_000L, after.workingStartedAtMillis)
            assertEquals(before.logs, after.logs)
            assertEquals(before.section, after.section)
            assertTrue(after.message.orEmpty().contains("완료 여부를 다시 확인"))
        }
    }

    @Test
    fun preflightTimeoutClearsOnlyTheMissingUpdateResult() {
        val report = DiagnosticReport("이전 진단", emptyList())
        val after = LauncherUiState(
            updatePreflight = previousPreflight(), updateAvailable = true, latestVersion = "old",
            diagnosticReport = report,
        ).reconnectingAfterTimeout(EnvironmentStatus(operationActive = true), "update-preflight")
        assertNull(after.updatePreflight)
        assertNull(after.updateAvailable)
        assertEquals("", after.latestVersion)
        assertSame(report, after.diagnosticReport)
    }

    @Test
    fun diagnosticTimeoutClearsOnlyTheMissingDiagnosticReport() {
        val preflight = previousPreflight()
        val after = LauncherUiState(
            diagnosticReport = DiagnosticReport("이전 진단", emptyList()),
            updatePreflight = preflight, updateAvailable = true,
        ).reconnectingAfterTimeout(EnvironmentStatus(operationActive = true), "diagnose")
        assertNull(after.diagnosticReport)
        assertSame(preflight, after.updatePreflight)
        assertEquals(true, after.updateAvailable)
    }

    @Test
    fun inactiveEnvironmentCannotBePresentedAsAReconnectedOperation() {
        assertThrows(IllegalArgumentException::class.java) {
            LauncherUiState().reconnectingAfterTimeout(EnvironmentStatus(), "update")
        }
    }

    private fun previousPreflight() = UpdatePreflight(
        branch = "release", currentCommit = "old", targetCommit = "new", currentVersion = "old",
        targetVersion = "new", updateAvailable = true, commitsBehind = 1, nodeVersion = "22",
        nodeRequired = "22", nodeCompatible = true, freeBytes = 1_000L, requiredBytes = 100L,
        spaceReady = true, modifiedFiles = emptyList(), backupStorageReady = true,
    )
}
