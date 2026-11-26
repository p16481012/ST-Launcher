package app.tavernbridge.launcher.model

/** Process-local monotonic times; a successful HTTP ping never extends the inspection lifetime. */
internal class WarmReturnWindow {
    private var stoppedAt: Long? = null
    fun stopped(now: Long) { stoppedAt = now }
    fun consume(now: Long, inspectedAt: Long?): Boolean {
        val stop = stoppedAt
        stoppedAt = null
        return stop != null && inspectedAt != null &&
            now - stop in 0L..60_000L && now - inspectedAt in 0L..300_000L
    }
}

/** A loopback response is not evidence of a fresh installation, server ownership, or permission. */
internal fun LauncherUiState.allowsWarmProbe(base: EnvironmentStatus): Boolean =
    !isWorking && !cancellationRequested && !termuxWakeBlocked && pendingEntry == null &&
        backupTransfer == null && encryptedBackup == null &&
        environmentChecked && termuxSetup.verified && base.termuxInstalled && base.commandPermissionGranted &&
        base.port == environment.port && environment.sillyTavernInstalled && environment.managerConnected &&
        environment.processRunning && environment.serverReachable && !environment.operationActive &&
        !environment.recoveryPending && environment.extensionRecovery == "none"

// Revalidate after the I/O suspend point, before triggering any Termux command.
internal suspend fun probeWarmServer(ping: suspend () -> Boolean, stillCurrent: () -> Boolean, inspect: () -> Unit) {
    if (!ping() && stillCurrent()) inspect()
}
