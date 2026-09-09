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
