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
): Boolean = wasRunning && !environment.operationActive && !environment.processRunning &&
    !environment.recoveryPending && !rollbackFailed

internal fun retryActionAfterFailure(errorCode: String, requested: RetryAction): RetryAction =
    if (errorCode == "TERMUX_TIMEOUT") RetryAction.REFRESH else requested

/** Finishing a detached child command does not prove that its caller ran the remaining steps. */
internal fun recoveredSubstepNotice(requestedOperation: String?, completedOperation: String, status: String?): String? {
    if (status != "success" || requestedOperation == completedOperation) return null
    val requestedLabel = when (requestedOperation) {
        "update" -> "업데이트"
        "restart" -> "재시작"
        "switch-branch" -> "브랜치 전환"
        else -> return null
    }
    val completedLabel = when (completedOperation) {
        "stop" -> "서버 종료"
        "backup" -> if (requestedOperation == "update") "백업" else return null
        else -> return null
    }
    return "현재 $completedLabel 단계 완료는 확인했습니다. 응답 지연으로 요청한 ${requestedLabel}의 전체 완료는 확인하지 못했으며 후속 단계는 자동으로 진행하지 않았습니다. 현재 상태를 확인한 뒤 원래 작업을 다시 실행해 주세요."
}

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
