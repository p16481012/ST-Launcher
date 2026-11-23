package app.tavernbridge.launcher.model

data class ExistingInstallation(
    val sourcePath: String,
    val version: String,
    val sizeBytes: Long,
    val destinationPath: String,
    val sameInstallation: Boolean,
    val destinationExists: Boolean = false,
)

enum class InstallationImportMode(val argument: String) { COPY("keep"), MOVE("move") }

/** A backup is a prerequisite, not a best-effort side task. Never replace after it fails. */
internal suspend fun <T> protectBeforeReplacement(
    enabled: Boolean,
    backup: suspend () -> Unit,
    replace: suspend () -> T,
): T {
    if (enabled) backup()
    operationCheckpoint()
    return replace()
}

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
    environment.managerConnected && !isWorking && !environment.operationActive && !environment.recoveryPending &&
        !environment.processRunning && !fileBrowserLoading && !fileBrowserMutating
