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
)
