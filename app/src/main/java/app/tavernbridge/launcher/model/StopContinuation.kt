package app.tavernbridge.launcher.model

/** The gate is deliberately stricter than the normal Stop control. It never unblocks a recovery,
 * an unrelated port listener or another operation, and it is not an authorization to write. */
internal fun LauncherUiState.readyForStopContinuation(running: Boolean): Boolean =
    termuxSetup.verified && environment.termuxInstalled && environment.commandPermissionGranted &&
        environment.managerConnected && environment.sillyTavernInstalled &&
        !isWorking && !environment.operationActive && !environment.recoveryPending &&
        !cancellationRequested && !fileBrowserMutating && backupTransfer == null && encryptedBackup == null &&
        environment.processRunning == running && (running || !environment.serverReachable)

/** Main-thread handshake. Either the stop or the UI cancellation may arrive first. */
internal class AbandonedStopNotice {
    private var abandoned = false
    private var stopped = false
    private var notified = false

    fun invalidate(): Boolean { abandoned = true; return takeNotice() }
    fun verified(): Boolean { stopped = true; return takeNotice() }
    private fun takeNotice(): Boolean = (abandoned && stopped && !notified).also { if (it) notified = true }
}
