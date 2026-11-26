package app.tavernbridge.launcher.termux

import android.app.Activity

data class TermuxCommandResult(
    val callbackId: String,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val errorCode: Int,
    val errorMessage: String,
    val stdoutOriginalLength: Int = -1,
) {
    val isSuccess: Boolean get() = errorCode == Activity.RESULT_OK && exitCode == 0
    val stdoutTruncated: Boolean get() = stdoutOriginalLength > stdout.length

    /** Only the explicit Start action calls this. A running process is not proof of an HTTP-ready start. */
    internal fun shouldAutoOpenAfterStart(enabled: Boolean, foreground: Boolean, cancelled: Boolean): Boolean {
        if (!enabled || !foreground || cancelled || !isSuccess || stdoutTruncated) return false
        val lines = stdout.lineSequence()
        return lines.any { it == "started=1" } && lines.none { it == "pending=1" || it == "already_running=1" }
    }

    val stableErrorCode: String
        get() = LauncherErrorCode.resolve(stdout, stderr, exitCode, errorCode)

    // 71 alone is not unique across helpers; do not infer consent eligibility from diagnostic text.
    internal val isRepairLinkerFailure: Boolean
        get() = errorCode == Activity.RESULT_OK && exitCode == 71 && stableErrorCode == "NODE_LINKER_FAILED"

    /** Keep the original machine-readable failure first; the batch count belongs to the detail. */
    internal fun inFailedExtensionBatch(completed: Int, total: Int, item: String): TermuxCommandResult {
        require(!isSuccess && completed in 0 until total)
        val name = item.replace('\n', ' ').replace('\r', ' ').take(160)
        return copy(stderr = "error_code=$stableErrorCode\n확장 ${completed}/${total}개 완료 · $name\n" +
            readableError().substringAfter('\n'))
    }

    fun readableError(): String {
        val detail = LauncherErrorCode.withoutProtocolLines(stderr)
            .ifBlank { errorMessage.trim() }
            .ifBlank { "명령 실행에 실패했습니다. 종료 코드: $exitCode, Termux 오류 코드: $errorCode" }
        return if (isSuccess) detail else "[$stableErrorCode]\n$detail"
    }
}
