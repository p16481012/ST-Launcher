package app.tavernbridge.launcher.data

import android.content.Context
import app.tavernbridge.launcher.model.BackupArchive
import app.tavernbridge.launcher.model.BackupInterval
import app.tavernbridge.launcher.model.BackupReminderState
import app.tavernbridge.launcher.model.BackupRequest
import app.tavernbridge.launcher.model.WorkProgress

/** Only dates, a request UUID and user preferences; no paths, ZIP contents or credentials. */
internal class BackupReminderStore(context: Context) {
    private val preferences = context.getSharedPreferences("backup_reminder", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): BackupReminderState = runCatching {
        BackupReminderState(
            enabled = preferences.getBoolean("enabled", true),
            interval = BackupInterval.entries.firstOrNull { it.name == preferences.getString("interval", null) } ?: BackupInterval.WEEKLY,
            lastBackupAtMillis = preferences.getLong("last_backup_at", 0L).coerceAtLeast(0L),
            lastBackupRequestId = preferences.getString("last_request_id", "").orEmpty(),
            snoozedUntilMillis = preferences.getLong("snoozed_until", 0L).coerceAtLeast(0L),
        )
    }.getOrDefault(BackupReminderState())

    @Synchronized
    fun setEnabled(enabled: Boolean): BackupReminderState = save(load().copy(enabled = enabled, snoozedUntilMillis = 0L))

    @Synchronized
    fun setInterval(interval: BackupInterval): BackupReminderState = save(load().copy(interval = interval, snoozedUntilMillis = 0L))

    @Synchronized
    fun snooze(nowMillis: Long = System.currentTimeMillis()): BackupReminderState = save(load().snooze(nowMillis))

    @Synchronized
    fun recordSuccessful(request: BackupRequest, completedAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): BackupReminderState =
        save(load().recordSuccessful(request, completedAtMillis, nowMillis))

    @Synchronized
    fun recordRecovered(request: BackupRequest?, progress: WorkProgress?, nowMillis: Long = System.currentTimeMillis()): BackupReminderState =
        save(load().recordRecovered(request, progress, nowMillis))

    @Synchronized
    fun observeArchives(backups: List<BackupArchive>, nowMillis: Long = System.currentTimeMillis()): BackupReminderState =
        save(load().observeArchives(backups, nowMillis))

    private fun save(state: BackupReminderState): BackupReminderState {
        preferences.edit()
            .putBoolean("enabled", state.enabled)
            .putString("interval", state.interval.name)
            .putLong("last_backup_at", state.lastBackupAtMillis)
            .putString("last_request_id", state.lastBackupRequestId)
            .putLong("snoozed_until", state.snoozedUntilMillis)
            .apply()
        return state
    }
}
