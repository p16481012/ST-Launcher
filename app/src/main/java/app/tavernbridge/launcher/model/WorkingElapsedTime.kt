package app.tavernbridge.launcher.model

/** A known top-level start wins over timestamps from later steps of a compound operation. */
internal fun resolveWorkingStartedAtMillis(
    localStartedAtMillis: Long,
    reportedStartedAtMillis: Long,
    fallbackStartedAtMillis: Long,
): Long = localStartedAtMillis.takeIf { it > 0L }
    ?: reportedStartedAtMillis.takeIf { it > 0L }
    ?: fallbackStartedAtMillis.coerceAtLeast(0L)

internal fun workingElapsedLabel(startedAtMillis: Long, nowMillis: Long): String {
    if (startedAtMillis <= 0L) return "경과 시간 확인 중"
    val seconds = if (nowMillis > startedAtMillis) (nowMillis - startedAtMillis) / 1_000L else 0L
    return when {
        seconds >= 3_600L -> "${seconds / 3_600L}시간 ${seconds / 60L % 60L}분 ${seconds % 60L}초 경과"
        seconds >= 60L -> "${seconds / 60L}분 ${seconds % 60L}초 경과"
        else -> "${seconds}초 경과"
    }
}
