package app.tavernbridge.launcher.termux

import android.app.Activity

internal object LauncherErrorCode {
    private val protocolLine = Regex("""^error_code=([A-Z0-9_]+)$""")

    fun resolve(stdout: String, stderr: String, exitCode: Int, androidErrorCode: Int): String {
        return sequenceOf(stderr, stdout)
            .flatMap { it.lineSequence() }
            .mapNotNull { protocolLine.matchEntire(it.trim())?.groupValues?.get(1) }
            .firstOrNull()
            ?: fallback(exitCode, androidErrorCode)
    }

    fun withoutProtocolLines(text: String): String = text.lineSequence()
        .filterNot { protocolLine.matches(it.trim()) }
        .joinToString("\n")
        .trim()

    private fun fallback(exitCode: Int, androidErrorCode: Int): String = when (exitCode) {
        2 -> "UNSUPPORTED_BRANCH"
        3 -> "INSTALL_PATH_EXISTS"
        4 -> "INSTALLATION_NOT_FOUND"
        5 -> "SERVER_START_FAILED"
        6 -> "FILE_NOT_FOUND"
        7 -> "BACKUP_STORAGE_UNAVAILABLE"
        8 -> "INSTALLATION_INCOMPLETE"
        9 -> "LOCAL_CHANGES_PRESENT"
        10 -> "SERVER_MUST_STOP"
        11 -> "OPERATION_ALREADY_RUNNING"
        12 -> "DEPENDENCY_SETUP_FAILED"
        14 -> "PACKAGE_INDEX_FAILED"
        15 -> "TERMUX_TOOL_INSTALL_FAILED"
        16 -> "ESBUILD_INSTALL_FAILED"
        17 -> "GIT_DOWNLOAD_FAILED"
        18 -> "NETWORK_FETCH_FAILED"
        19 -> "BACKUP_INTEGRITY_FAILED"
        20 -> "ZIP_DAMAGED"
        21 -> "ZIP_UNSAFE_PATH"
        22 -> "ZIP_SYMLINK_REJECTED"
        23 -> "BACKUP_FORMAT_UNSUPPORTED"
        24 -> "BACKUP_CONTENT_INVALID"
        25 -> "RESTORE_ROLLBACK_REQUIRED"
        26 -> "RESTORE_DEPENDENCY_FAILED"
        27 -> "RESTORE_VERIFICATION_FAILED"
        28 -> "BACKUP_DELETE_FAILED"
        29 -> "REQUIREMENT_NOT_MET"
        30 -> "INSUFFICIENT_STORAGE"
        31 -> "UPDATE_GIT_FAILED"
        32 -> "UPDATE_PACKAGES_FAILED"
        33 -> "UPDATE_SERVER_FAILED"
        34 -> "UPDATE_HISTORY_DIVERGED"
        35 -> "SAFETY_BACKUP_NO_SPACE"
        36 -> "SAFETY_BACKUP_FAILED"
        37 -> "SAFETY_BACKUP_SOURCE_CHANGED"
        40 -> "FILE_EDIT_TOO_LARGE"
        41 -> "FILE_NOT_TEXT"
        42 -> "FILE_EDIT_CONFLICT"
        43 -> "FILE_ALREADY_EXISTS"
        44 -> "FILE_PROTECTED_PATH"
        45 -> "FILE_OPERATION_FAILED"
        50 -> "INSTALL_IMPORT_INVALID_PATH"
        51 -> "INSTALL_IMPORT_OVERLAP"
        52 -> "INSTALL_IMPORT_INVALID_INSTALLATION"
        53 -> "INSTALL_IMPORT_UNSAFE_ENTRY"
        54 -> "INSTALL_IMPORT_NO_SPACE"
        55 -> "INSTALL_IMPORT_COPY_FAILED"
        56 -> "NODE_VERSION_UNSUPPORTED"
        57 -> "NODE_VERSION_CHECK_FAILED"
        64 -> "INVALID_ARGUMENT"
        130 -> "OPERATION_CANCELLED"
        else -> if (androidErrorCode != Activity.RESULT_OK) "TERMUX_COMMAND_FAILED" else "UNKNOWN_COMMAND_FAILURE"
    }
}
