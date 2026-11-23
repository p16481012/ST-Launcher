package app.tavernbridge.launcher.model

/** Call only after a command has been accepted, never while merely validating a click. */
internal fun LauncherUiState.closeOperationDialogsForResult(): LauncherUiState {
    if (selectiveRestore == null && previousVersions == null && !extensionManagerOpen &&
        !safetyInventory.open && backupCleanupPreview == null) return this
    // Dialog windows sit above the Scaffold Snackbar. Keeping a selection dialog
    // alive would hide the terminal error when the working overlay disappears.
    return copy(
        section = MainSection.HOME,
        selectiveRestore = null,
        previousVersions = null,
        extensionManagerOpen = false,
        extensionLoading = false,
        safetyInventory = safetyInventory.copy(open = false),
        backupCleanupPreview = null,
    )
}
