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
        name.toByteArray(Charsets.UTF_8).size <= 255 &&
        name.none { it == '/' || it == '\\' || it.isISOControl() }

data class TavernTrashEntry(val id: String, val relativePath: String, val isDirectory: Boolean)
data class TavernTrashPage(val entries: List<TavernTrashEntry>, val next: String, val unavailable: Int)
internal val tavernTrashId = Regex("[0-9]{1,16}-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")

/** Suggest an alternate sibling name but never rename automatically or overwrite the existing item. */
internal fun availableTavernName(name: String, occupied: Set<String>, directory: Boolean): String? {
    if (!validTavernEntryName(name) || name !in occupied) return null
    val dot = if (directory) -1 else name.lastIndexOf('.')
    val suffix = if (dot > 0) name.substring(dot) else ""
    val stem = name.removeSuffix(suffix)
    return (1..occupied.size + 1).asSequence().map { index ->
        val tail = " ($index)$suffix"
        var base = stem
        while (base.isNotEmpty() && (base + tail).toByteArray(Charsets.UTF_8).size > 255) {
            base = base.dropLast(Character.charCount(base.codePointBefore(base.length)))
        }
        base + tail
    }.firstOrNull { validTavernEntryName(it) && it !in occupied }
}

fun LauncherUiState.canModifyTavernFiles(): Boolean =
    environment.managerConnected && !isWorking && !environment.operationActive && !environment.recoveryPending &&
        !environment.processRunning && !fileBrowserLoading && !fileBrowserMutating

internal fun LauncherUiState.canUndoLibraryWithoutStoppingServer(): Boolean =
    environmentChecked && termuxSetup.verified && canModifyTavernFiles() && environment.sillyTavernInstalled &&
        !environment.serverReachable && environment.extensionRecovery == "none" && !termuxWakeBlocked &&
        !cancellationRequested && backupTransfer == null && encryptedBackup == null
