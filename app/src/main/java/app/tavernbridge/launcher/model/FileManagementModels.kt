package app.tavernbridge.launcher.model

data class ExistingInstallation(
    val sourcePath: String,
    val version: String,
    val sizeBytes: Long,
    val destinationPath: String,
    val sameInstallation: Boolean,
)

/** Revision is an opaque backend token used to avoid overwriting concurrent changes. */
data class TavernTextFile(
    val relativePath: String,
    val content: String,
    val revision: String,
)

fun validTavernEntryName(name: String): Boolean =
    name.isNotBlank() && name != "." && name != ".." &&
        name.none { it == '/' || it == '\\' || it.isISOControl() }

fun LauncherUiState.canModifyTavernFiles(): Boolean =
    environment.managerConnected && !isWorking && !environment.operationActive &&
        !environment.processRunning && !fileBrowserLoading && !fileBrowserMutating
