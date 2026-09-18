package app.tavernbridge.launcher.model

import app.tavernbridge.launcher.security.redactSensitiveText

/**
 * Bounded log snapshots for one top-level UI operation. Call [clear] before the next operation.
 * Pass original producer snapshots to [merge], never a previously merged UI snapshot.
 */
internal class ProgressLogHistory(private val maxLength: Int = 40_000) {
    private data class Generation(val operation: String, val startedAtMillis: Long)

    private var generation: Generation? = null
    private var previousLogs = ""
    private var currentLog = ""

    init {
        require(maxLength in 1..40_000)
    }

    fun clear() {
        generation = null
        previousLogs = ""
        currentLog = ""
    }

    fun merge(progress: WorkProgress): WorkProgress {
        val next = Generation(progress.operation, progress.operationStartedAtMillis)
        if (next != generation) {
            previousLogs = bounded(join(previousLogs, currentLog))
            currentLog = ""
            generation = next
        }
        if (progress.logText.isNotBlank()) {
            // This is a complete bounded snapshot, not a new chunk. Replace it:
            // server output may grow before an unchanged operation-log suffix,
            // and the producer may remove old lines when its tail limit is hit.
            // Phase changes alone do not start a new generation.
            currentLog = bounded(progress.logText)
        }
        return progress.copy(logText = bounded(join(previousLogs, currentLog)))
    }

    private fun bounded(text: String): String =
        // Redact before truncation, even though each producer already redacts.
        // A tail cut must never retain a secret after dropping its field name.
        redactSensitiveText(text, maxLength = null).takeLast(maxLength).trim()

    private fun join(previous: String, current: String): String = when {
        previous.isEmpty() -> current
        current.isEmpty() -> previous
        else -> "$previous\n$current"
    }
}
