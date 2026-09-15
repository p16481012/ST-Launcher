package app.tavernbridge.launcher.ui

import app.tavernbridge.launcher.model.WorkProgress
import app.tavernbridge.launcher.security.redactSensitiveText
import java.util.Locale

internal enum class ProgressActivityState {
    WAITING_FOR_RESPONSE,
    RESPONSE_STALE,
    NO_RECENT_ACTIVITY,
    ACTIVE,
}

internal fun WorkProgress.activityState(nowMillis: Long): ProgressActivityState = when {
    heartbeatAtMillis <= 0L -> ProgressActivityState.WAITING_FOR_RESPONSE
    (nowMillis - heartbeatAtMillis).coerceAtLeast(0L) >= 30_000L -> ProgressActivityState.RESPONSE_STALE
    activityAtMillis <= 0L || (nowMillis - activityAtMillis).coerceAtLeast(0L) >= 30_000L ->
        ProgressActivityState.NO_RECENT_ACTIVITY
    else -> ProgressActivityState.ACTIVE
}

internal fun formatProgressDuration(seconds: Long): String {
    val value = seconds.coerceAtLeast(0L)
    return if (value < 60L) "${value}초" else "${value / 60L}분 ${value % 60L}초"
}

internal fun formatProgressBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L)
    if (value < 1_024L) return "$value B"
    val (divisor, unit) = when {
        value < 1_048_576L -> 1_024.0 to "KiB"
        value < 1_073_741_824L -> 1_048_576.0 to "MiB"
        value < 1_099_511_627_776L -> 1_073_741_824.0 to "GiB"
        else -> 1_099_511_627_776.0 to "TiB"
    }
    return String.format(Locale.KOREA, "%.1f %s", value / divisor, unit)
}

/** Names come from both Termux and untrusted Android document providers, never file contents. */
internal fun formatProgressItem(item: String): String = redactSensitiveText(
    item.take(4_096).filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() },
    maxLength = 1_024,
)

/** Only operation log output is supplied here; the file editor's content must never be passed in. */
internal fun formatRecentProgressLog(logText: String): String = logText.takeLast(16_384)
    .lineSequence()
    .map { line ->
        line.replace(Regex("\u001B\\[[0-?]*[ -/]*[@-~]"), "")
            .replace('\t', ' ')
            .filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
            .trim()
    }
    .filter { line ->
        line.isNotBlank() && !line.matches(Regex("[=*_─━-]{3,}")) &&
            !(line.startsWith("===") && line.endsWith("==="))
    }
    .map { redactSensitiveText(it, maxLength = 400) }
    .toList()
    .takeLast(5)
    .joinToString("\n")
