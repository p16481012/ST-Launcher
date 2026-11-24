package app.tavernbridge.launcher.model

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** A creation record, not a promise that the ZIP still exists or contains every data item. */
enum class BackupInterval(val label: String, val days: Long) { DAILY("매일", 1), WEEKLY("매주", 7), MONTHLY("매월", 30) }

data class BackupReminderState(
    val enabled: Boolean = true,
    val lastBackupAtMillis: Long = 0L,
    val lastBackupRequestId: String = "",
    val snoozedUntilMillis: Long = 0L,
    val interval: BackupInterval = BackupInterval.WEEKLY,
) {
    fun notice(nowMillis: Long, installed: Boolean): BackupReminderNotice? {
        if (!enabled || !installed || nowMillis <= 0L) return null
        // Do not hide the notice indefinitely after the device clock moves backwards.
        if (snoozedUntilMillis > nowMillis && snoozedUntilMillis - nowMillis <= REMINDER_INTERVAL_MILLIS) return null
        val recorded = lastBackupAtMillis.takeIf { it in 1L..nowMillis }
        val ageDays = recorded?.let { (nowMillis - it) / DAY_MILLIS }
        if (ageDays != null && ageDays < interval.days) return null
        return BackupReminderNotice(ageDays, recorded ?: 0L)
    }

    fun snooze(nowMillis: Long): BackupReminderState = if (nowMillis > 0L) {
        copy(snoozedUntilMillis = nowMillis.coerceAtMost(Long.MAX_VALUE - REMINDER_INTERVAL_MILLIS) + REMINDER_INTERVAL_MILLIS)
    } else this

    /** Call only after the backend has confirmed this backup request succeeded. */
    fun recordSuccessful(request: BackupRequest, completedAtMillis: Long, nowMillis: Long): BackupReminderState {
        if (BackupRequestCodec.decode(BackupRequestCodec.encode(request)) != request ||
            !request.categories.hasCompleteUserData() || request.id == lastBackupRequestId) return this
        return recordTime(completedAtMillis, request.id, nowMillis)
    }

    /** A reopened app must retain the backend completion time, not pretend it backed up today. */
    fun recordRecovered(request: BackupRequest?, progress: WorkProgress?, nowMillis: Long): BackupReminderState {
        if (progress?.status != "success") return this
        val matched = BackupRequestCodec.matching(request, progress) ?: return this
        return recordSuccessful(matched, progress.finishedAtMillis, nowMillis)
    }

    /** Called only for an already requested, successfully loaded list; never starts a storage scan. */
    fun observeArchives(backups: List<BackupArchive>, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): BackupReminderState {
        val latest = backups.asSequence()
            .filter { it.categories.hasCompleteUserData() && it.branch != "imported" }
            // Imported/converted archives can be stamped with today's date despite holding old data.
            .mapNotNull { archive ->
                runCatching { LocalDateTime.parse(archive.createdAt, ARCHIVE_DATE_FORMAT).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
            }
            .filter { it in 1L..nowMillis }
            .maxOrNull() ?: return this
        return recordTime(latest, "", nowMillis)
    }

    private fun recordTime(atMillis: Long, requestId: String, nowMillis: Long): BackupReminderState {
        if (nowMillis <= 0L || atMillis !in 1L..nowMillis) return this
        val current = lastBackupAtMillis.takeIf { it in 1L..nowMillis } ?: 0L
        if (atMillis <= current) return this
        return copy(lastBackupAtMillis = atMillis, lastBackupRequestId = requestId, snoozedUntilMillis = 0L)
    }
}

/** ageDays=null means unknown history, never "the user has no backup". */
data class BackupReminderNotice(val ageDays: Long?, val lastBackupAtMillis: Long)

private fun Set<BackupCategory>.hasCompleteUserData(): Boolean =
    BackupCategory.USER_DATA in this || BackupCategory.FULL in this

private const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
private const val REMINDER_INTERVAL_DAYS = 7L
private const val REMINDER_INTERVAL_MILLIS = REMINDER_INTERVAL_DAYS * DAY_MILLIS
private val ARCHIVE_DATE_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)
