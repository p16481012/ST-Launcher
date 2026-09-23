package app.tavernbridge.launcher.data

import android.content.Context
import app.tavernbridge.launcher.model.OperationResultSummary
import app.tavernbridge.launcher.model.RetryAction
import app.tavernbridge.launcher.model.BackupRequestCodec
import app.tavernbridge.launcher.security.redactSensitiveText

class OperationResultStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): OperationResultSummary? {
        if (!preferences.contains(KEY_OPERATION)) return null
        return OperationResultSummary(
            operation = preferences.getString(KEY_OPERATION, "").orEmpty(),
            title = preferences.getString(KEY_TITLE, "").orEmpty(),
            detail = preferences.getString(KEY_DETAIL, "").orEmpty(),
            succeeded = preferences.getBoolean(KEY_SUCCEEDED, false),
            errorCode = preferences.getString(KEY_ERROR_CODE, "").orEmpty(),
            completedAt = preferences.getString(KEY_COMPLETED_AT, "").orEmpty(),
            completedAtMillis = preferences.getLong(KEY_COMPLETED_AT_MILLIS, 0L),
            durationSeconds = preferences.getLong(KEY_DURATION_SECONDS, 0L),
            retryAction = runCatching {
                RetryAction.valueOf(preferences.getString(KEY_RETRY_ACTION, RetryAction.NONE.name).orEmpty())
            }.getOrDefault(RetryAction.NONE),
            backupRequest = BackupRequestCodec.decode(preferences.getString(KEY_BACKUP_REQUEST, null)),
        )
    }

    fun save(summary: OperationResultSummary) {
        preferences.edit()
            .putString(KEY_OPERATION, summary.operation)
            .putString(KEY_TITLE, summary.title)
            .putString(KEY_DETAIL, redactSensitiveText(summary.detail, maxLength = 6_000))
            .putBoolean(KEY_SUCCEEDED, summary.succeeded)
            .putString(KEY_ERROR_CODE, summary.errorCode)
            .putString(KEY_COMPLETED_AT, summary.completedAt)
            .putLong(KEY_COMPLETED_AT_MILLIS, summary.completedAtMillis)
            .putLong(KEY_DURATION_SECONDS, summary.durationSeconds)
            .putString(KEY_RETRY_ACTION, summary.retryAction.name)
            .putString(KEY_BACKUP_REQUEST, summary.backupRequest?.let(BackupRequestCodec::encode))
            .commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "operation_results"
        const val KEY_OPERATION = "operation"
        const val KEY_TITLE = "title"
        const val KEY_DETAIL = "detail"
        const val KEY_SUCCEEDED = "succeeded"
        const val KEY_ERROR_CODE = "error_code"
        const val KEY_COMPLETED_AT = "completed_at"
        const val KEY_COMPLETED_AT_MILLIS = "completed_at_millis"
        const val KEY_DURATION_SECONDS = "duration_seconds"
        const val KEY_RETRY_ACTION = "retry_action"
        const val KEY_BACKUP_REQUEST = "backup_request"
    }
}
