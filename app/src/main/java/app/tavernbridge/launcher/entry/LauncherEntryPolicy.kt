package app.tavernbridge.launcher.entry

enum class LauncherEntryAction {
    START, STOP, OPEN;

    companion object {
        fun parse(value: String?): LauncherEntryAction? = entries.firstOrNull { it.name == value }
    }
}

data class LauncherEntryRequest(val id: String, val action: LauncherEntryAction)

enum class LauncherEntryGate {
    FOREGROUND_REQUIRED, SETUP_REQUIRED, BUSY, RECOVERY_REQUIRED,
    ALREADY_RUNNING, ALREADY_STOPPED, CONFIRM_START, CONFIRM_STOP, CONFIRM_OPEN,
}

/** Evaluate again on confirmation. An external request is never permission to run a command. */
object LauncherEntryPolicy {
    fun evaluate(
        action: LauncherEntryAction,
        foreground: Boolean,
        setupReady: Boolean,
        working: Boolean,
        recoveryPending: Boolean,
        serverRunning: Boolean,
    ): LauncherEntryGate = when {
        !foreground -> LauncherEntryGate.FOREGROUND_REQUIRED
        action == LauncherEntryAction.OPEN -> LauncherEntryGate.CONFIRM_OPEN
        !setupReady -> LauncherEntryGate.SETUP_REQUIRED
        working -> LauncherEntryGate.BUSY
        recoveryPending && action == LauncherEntryAction.START -> LauncherEntryGate.RECOVERY_REQUIRED
        action == LauncherEntryAction.START && serverRunning -> LauncherEntryGate.ALREADY_RUNNING
        action == LauncherEntryAction.STOP && !serverRunning -> LauncherEntryGate.ALREADY_STOPPED
        action == LauncherEntryAction.START -> LauncherEntryGate.CONFIRM_START
        action == LauncherEntryAction.STOP -> LauncherEntryGate.CONFIRM_STOP
        else -> LauncherEntryGate.CONFIRM_OPEN
    }
}
