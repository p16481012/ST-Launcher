package app.tavernbridge.launcher.model

internal enum class BackgroundAssessment(val label: String) {
    READY("정상"), ATTENTION("확인 필요"), UNKNOWN("확인 전"), NOT_INSTALLED("Termux 미설치"),
}

/** An Android version is not a probe of the device's process limits. Wake lock is opt-in. */
internal fun LauncherUiState.backgroundAssessment(): BackgroundAssessment = when {
    !environment.termuxInstalled && environmentChecked -> BackgroundAssessment.NOT_INSTALLED
    !environment.termuxInstalled -> BackgroundAssessment.UNKNOWN
    backgroundStatus.termuxBatteryUnrestricted == false || backupStorageReady == false -> BackgroundAssessment.ATTENTION
    backgroundStatus.termuxBatteryUnrestricted == null || backupStorageReady == null -> BackgroundAssessment.UNKNOWN
    else -> BackgroundAssessment.READY
}
