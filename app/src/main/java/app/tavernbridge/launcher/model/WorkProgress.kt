package app.tavernbridge.launcher.model

data class WorkProgress(
    val percent: Int,
    val phase: String,
    val detail: String,
    val status: String = "running",
    val operation: String = "idle",
    val errorCode: String = "",
    val finishedAtMillis: Long = 0L,
    val logText: String = "",
    val progressMode: String = "indeterminate",
    val completedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val completedFiles: Long = 0L,
    val totalFiles: Long = 0L,
    val currentItem: String = "",
    val heartbeatAtMillis: Long = 0L,
    val activityAtMillis: Long = 0L,
    val phaseStartedAtMillis: Long = 0L,
) {
    /** Measured progress within the current phase, never the old phase-weighted percentage. */
    val measuredPercent: Int?
        get() = when {
            status == "success" && (progressMode == "complete" || percent == 100) -> 100
            progressMode == "bytes" && totalBytes > 0L -> ratioPercent(completedBytes, totalBytes)
            progressMode == "files" && totalFiles > 0L -> ratioPercent(completedFiles, totalFiles)
            else -> null
        }

    private fun ratioPercent(completed: Long, total: Long): Int =
        (completed.coerceIn(0L, total).toDouble() / total.toDouble() * 100.0).toInt()
}

/** New commands must not briefly replay the preceding command's saved progress file. */
internal fun WorkProgress.belongsToStartedOperation(expectedOperation: String, startedAtMillis: Long): Boolean {
    if (phaseStartedAtMillis <= 0L || startedAtMillis <= 0L) return false
    // Shell timestamps have second precision; local Android transfer timestamps have millisecond precision.
    if (phaseStartedAtMillis < startedAtMillis / 1_000L * 1_000L) return false
    return operation == expectedOperation || when (expectedOperation) {
        "restart" -> operation in setOf("stop", "start")
        "update" -> operation in setOf("stop", "backup", "start")
        "switch-branch" -> operation in setOf("stop", "start")
        "delete-backup" -> operation == "delete-backups"
        else -> false
    }
}
