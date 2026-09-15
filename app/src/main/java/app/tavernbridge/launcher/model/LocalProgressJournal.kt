package app.tavernbridge.launcher.model

import app.tavernbridge.launcher.security.redactSensitiveText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Callers only mark an item complete after its work returns. No estimated global percentage. */
internal class LocalProgressJournal(
    private val operation: String,
    private val publish: (WorkProgress) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val history = ArrayDeque<String>()
    private var snapshot = WorkProgress(0, "준비 중", "", operation = operation)

    fun phase(title: String, detail: String, totalItems: Long = 0L) {
        val now = clock()
        snapshot = WorkProgress(
            percent = 0, phase = clean(title), detail = clean(detail), operation = operation,
            progressMode = if (totalItems > 0L) "files" else "indeterminate",
            totalFiles = totalItems.coerceAtLeast(0L), phaseStartedAtMillis = now,
            heartbeatAtMillis = now, logText = history.joinToString("\n"),
        )
        record("${snapshot.phase} · ${snapshot.detail}")
    }

    fun item(title: String, detail: String = snapshot.detail) {
        snapshot = snapshot.copy(currentItem = clean(title), detail = clean(detail), heartbeatAtMillis = clock())
        record("${snapshot.currentItem} · ${snapshot.detail}")
    }

    fun completedItem(title: String) {
        val now = clock()
        snapshot = snapshot.copy(
            completedFiles = snapshot.completedFiles + 1L,
            currentItem = clean(title), heartbeatAtMillis = now, activityAtMillis = now,
        )
        record("완료 · ${snapshot.currentItem}")
    }

    private fun record(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(clock()))
        history.addLast("[$timestamp] $message")
        while (history.size > 12) history.removeFirst()
        snapshot = snapshot.copy(logText = history.joinToString("\n"))
        publish(snapshot)
    }

    private fun clean(value: String): String = redactSensitiveText(
        value.filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }, maxLength = 400,
    )
}
