package app.tavernbridge.launcher.model

/** History, an unscoped exit number or a different operation must never authorize a Termux-wide upgrade. */
internal fun LauncherUiState.canOfferRuntimeSync(failureAt: Long): Boolean {
    val result = lastOperationResult ?: return false
    return runtimeSyncFailureAt == failureAt && result.completedAtMillis == failureAt && !result.succeeded &&
        result.operation == "repair" && result.errorCode == "NODE_LINKER_FAILED" &&
        section == MainSection.LOGS && toolPage == ToolPage.REPAIR &&
        !isWorking && !fileBrowserMutating && backupTransfer == null && encryptedBackup == null && termuxSetup.verified &&
        environment.run { sillyTavernInstalled && managerConnected && termuxInstalled && commandPermissionGranted &&
            !processRunning && !operationActive && !recoveryPending }
}

enum class RetryAction {
    NONE,
    REFRESH,
    CONNECT_MANAGER,
    INSTALL_RELEASE,
    INSTALL_STAGING,
    START,
    STOP,
    RESTART,
    CHECK_UPDATE,
    BACKUP,
    REPAIR,
    DIAGNOSE,
    SETTINGS_DIAGNOSE,
}

data class OperationResultSummary(
    val operation: String,
    val title: String,
    val detail: String,
    val succeeded: Boolean,
    val errorCode: String = "",
    val completedAt: String,
    val completedAtMillis: Long,
    val durationSeconds: Long,
    val retryAction: RetryAction = RetryAction.NONE,
    val backupRequest: BackupRequest? = null,
) {
    val canRetry: Boolean
        get() = !succeeded && retryAction != RetryAction.NONE
}
