package app.tavernbridge.launcher.model

internal enum class RecoveredOperationStatus {
    SUCCESS,
    FAILURE,
    INTERRUPTED,
}

internal fun recoveredOperationStatus(status: String?): RecoveredOperationStatus = when (status) {
    "success" -> RecoveredOperationStatus.SUCCESS
    "error", "cancelled" -> RecoveredOperationStatus.FAILURE
    else -> RecoveredOperationStatus.INTERRUPTED
}

internal fun shouldRestoreUpdateServer(
    wasRunning: Boolean,
    environment: EnvironmentStatus,
    rollbackFailed: Boolean,
): Boolean = wasRunning && !environment.operationActive && !environment.processRunning && !rollbackFailed

internal fun retryActionAfterFailure(errorCode: String, requested: RetryAction): RetryAction =
    if (errorCode == "TERMUX_TIMEOUT") RetryAction.REFRESH else requested

/** A callback deadline is not an operation failure while the backend is still active. */
internal fun LauncherUiState.reconnectingAfterTimeout(
    activeEnvironment: EnvironmentStatus,
    operation: String,
): LauncherUiState {
    require(activeEnvironment.operationActive)
    return copy(
        environment = activeEnvironment,
        isWorking = true,
        workingLabel = "진행 중인 작업에 다시 연결 중",
        error = null,
        message = "Termux 응답 대기 시간이 지났지만 작업은 계속 실행 중입니다. 완료 여부를 다시 확인하고 있습니다.",
        // The lost callback cannot provide a new structured report. Do not leave an old report looking current.
        updatePreflight = if (operation == "update-preflight") null else updatePreflight,
        updateAvailable = if (operation == "update-preflight") null else updateAvailable,
        latestVersion = if (operation == "update-preflight") "" else latestVersion,
        diagnosticReport = if (operation == "diagnose") null else diagnosticReport,
    )
}
