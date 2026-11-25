package app.tavernbridge.launcher.model

/** The gate is deliberately stricter than the normal Stop control. It never unblocks a recovery,
 * an unrelated port listener or another operation, and it is not an authorization to write. */
internal fun LauncherUiState.readyForStopContinuation(running: Boolean): Boolean =
    termuxSetup.verified && environment.termuxInstalled && environment.commandPermissionGranted &&
        environment.managerConnected && environment.sillyTavernInstalled &&
        !isWorking && !environment.operationActive && !environment.recoveryPending &&
        !cancellationRequested && !fileBrowserMutating && backupTransfer == null && encryptedBackup == null &&
        environment.processRunning == running && (running || !environment.serverReachable)
