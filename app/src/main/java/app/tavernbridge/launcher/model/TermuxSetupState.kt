package app.tavernbridge.launcher.model

/** Verified independently of Android's RUN_COMMAND permission and server network settings. */
enum class TermuxSetupStatus {
    UNKNOWN,
    CHECKING,
    TERMUX_MISSING,
    PERMISSION_REQUIRED,
    EXTERNAL_APPS_DISABLED,
    UNVERIFIED,
    READY,
}

data class TermuxSetupState(
    val status: TermuxSetupStatus = TermuxSetupStatus.UNKNOWN,
    val detail: String = "",
) {
    val verified: Boolean get() = status == TermuxSetupStatus.READY
}

/** A remembered page or a granted Android permission is not proof of a working connection. */
fun setupEligibleStep(environment: EnvironmentStatus, setup: TermuxSetupState): Int = when {
    !environment.termuxInstalled -> 1
    !environment.commandPermissionGranted -> 2
    !setup.verified -> 3
    !environment.managerConnected -> 4
    else -> 5
}

/** Losing command access must not make a known installation appear deleted. */
fun EnvironmentStatus.withTermuxSetup(base: EnvironmentStatus, setup: TermuxSetupState): EnvironmentStatus = copy(
    termuxInstalled = base.termuxInstalled,
    commandPermissionGranted = base.commandPermissionGranted,
    termuxBatteryUnrestricted = base.termuxBatteryUnrestricted,
    managerConnected = managerConnected && base.termuxInstalled && base.commandPermissionGranted && setup.verified,
)
