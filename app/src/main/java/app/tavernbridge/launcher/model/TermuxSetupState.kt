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
    val requiresUserAction: Boolean get() = status == TermuxSetupStatus.TERMUX_MISSING ||
        status == TermuxSetupStatus.PERMISSION_REQUIRED || status == TermuxSetupStatus.EXTERNAL_APPS_DISABLED
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

/** Checking access does not establish whether an installation exists. */
fun LauncherUiState.beginTermuxSetupCheck(base: EnvironmentStatus): LauncherUiState = copy(
    environment = environment.copy(
        termuxInstalled = base.termuxInstalled,
        commandPermissionGranted = base.commandPermissionGranted,
        termuxBatteryUnrestricted = base.termuxBatteryUnrestricted,
    ),
    termuxSetup = if (termuxSetup.verified && base.termuxInstalled && base.commandPermissionGranted)
        termuxSetup else TermuxSetupState(TermuxSetupStatus.CHECKING),
    isWorking = false,
    workingLabel = "",
    workProgress = null,
)

/** An uncertain callback requires another check, not first-time installation instructions. */
fun LauncherUiState.finishTermuxSetupCheck(
    base: EnvironmentStatus,
    check: TermuxSetupState,
): LauncherUiState = copy(
    environment = environment.withTermuxSetup(base, check),
    termuxSetup = check,
    environmentChecked = environmentChecked && check.verified,
    section = if (check.requiresUserAction) MainSection.SETUP else section,
    isWorking = false,
    workingLabel = "",
    workProgress = null,
    termuxWakeBlocked = false,
    error = if (!check.verified) null else error,
)

/** Only a completed environment inspection may route an absent installation to setup. */
fun sectionAfterEnvironmentCheck(
    environment: EnvironmentStatus,
    firstCheck: Boolean,
    requestedSection: MainSection,
    currentSection: MainSection,
): MainSection = when {
    !environment.sillyTavernInstalled -> MainSection.SETUP
    firstCheck -> if (requestedSection == MainSection.SETUP) MainSection.HOME else requestedSection
    else -> currentSection
}
