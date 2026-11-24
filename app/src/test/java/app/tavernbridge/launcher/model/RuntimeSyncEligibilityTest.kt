package app.tavernbridge.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSyncEligibilityTest {
    private val at = 410L
    private val failed = OperationResultSummary("repair", "점검·복구", "libicui18n.so.79 not found", false,
        errorCode = "NODE_LINKER_FAILED", completedAt = "now", completedAtMillis = at,
        durationSeconds = 1, retryAction = RetryAction.REPAIR)
    private val env = EnvironmentStatus(termuxInstalled = true, commandPermissionGranted = true,
        managerConnected = true, sillyTavernInstalled = true)
    private val ready = LauncherUiState(section = MainSection.LOGS, toolPage = ToolPage.REPAIR,
        environment = env, environmentChecked = true, termuxSetup = TermuxSetupState(TermuxSetupStatus.READY),
        isWorking = false, lastOperationResult = failed, runtimeSyncFailureAt = at)

    @Test fun onlyTheLiveOrdinaryRepairLinkerFailureCanOfferAPackageUpgrade() {
        assertTrue(ready.canOfferRuntimeSync(at))
        assertFalse(ready.canOfferRuntimeSync(at - 1))
        // After process death the persisted report can still be read, but cannot authorize a global package operation.
        assertFalse(ready.copy(runtimeSyncFailureAt = null).canOfferRuntimeSync(at))
        assertFalse(ready.copy(lastOperationResult = null).canOfferRuntimeSync(at))
        for (other in listOf(failed.copy(completedAtMillis = at + 1), failed.copy(succeeded = true),
            failed.copy(operation = "install"), failed.copy(operation = "update"),
            failed.copy(errorCode = "NODE_RUNTIME_FAILED"), failed.copy(errorCode = "DEPENDENCY_SETUP_FAILED"),
            failed.copy(errorCode = "UNKNOWN_COMMAND_FAILURE"))) {
            assertFalse(other.toString(), ready.copy(lastOperationResult = other).canOfferRuntimeSync(at))
        }
        // A normal retry of this failure still runs normal repair; a result never remembers upgrade consent.
        assertEquals(RetryAction.REPAIR, retryActionAfterFailure(failed.errorCode, RetryAction.REPAIR))
    }

    @Test fun eligibilityIsRecheckedWhenTheScreenOrTheEnvironmentChanges() {
        val transfer = BackupTransferState(BackupArchive("b.zip", 100, "now", emptySet(), emptySet(), false, "1"),
            BackupTransferAction.SHARE)
        for (state in listOf(
            ready.copy(section = MainSection.HOME), ready.copy(toolPage = ToolPage.INDEX),
            ready.copy(isWorking = true), ready.copy(termuxSetup = TermuxSetupState(TermuxSetupStatus.UNKNOWN)),
            ready.copy(fileBrowserMutating = true), ready.copy(backupTransfer = transfer),
            ready.copy(encryptedBackup = EncryptedBackupState("b", EncryptedBackupMode.EXPORT)),
            ready.copy(environment = env.copy(termuxInstalled = false)),
            ready.copy(environment = env.copy(commandPermissionGranted = false)),
            ready.copy(environment = env.copy(managerConnected = false)),
            ready.copy(environment = env.copy(sillyTavernInstalled = false)),
            ready.copy(environment = env.copy(processRunning = true)),
            ready.copy(environment = env.copy(operationActive = true)),
            ready.copy(environment = env.copy(recoveryPending = true)),
        )) assertFalse("Unsafe state allowed a package upgrade: $state", state.canOfferRuntimeSync(at))
    }
}
