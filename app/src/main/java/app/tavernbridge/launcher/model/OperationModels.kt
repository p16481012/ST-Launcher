package app.tavernbridge.launcher.model

enum class RetryAction {
    NONE,
    REFRESH,
    CONNECT_MANAGER,
    INSTALL_RELEASE,
    INSTALL_STAGING,
    START,
    STOP,
    RESTART,
    CHECK_UPDATE,
    BACKUP,
    REPAIR,
    DIAGNOSE,
}

data class OperationResultSummary(
    val operation: String,
    val title: String,
    val detail: String,
    val succeeded: Boolean,
    val errorCode: String = "",
    val completedAt: String,
    val completedAtMillis: Long,
    val durationSeconds: Long,
    val retryAction: RetryAction = RetryAction.NONE,
) {
    val canRetry: Boolean
        get() = !succeeded && retryAction != RetryAction.NONE
}
