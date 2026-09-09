package app.tavernbridge.launcher.termux

import android.app.Activity

data class TermuxCommandResult(
    val callbackId: String,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val errorCode: Int,
    val errorMessage: String,
) {
    val isSuccess: Boolean get() = errorCode == Activity.RESULT_OK && exitCode == 0

    val stableErrorCode: String
        get() = LauncherErrorCode.resolve(stdout, stderr, exitCode, errorCode)

    fun readableError(): String {
        val detail = LauncherErrorCode.withoutProtocolLines(stderr)
            .ifBlank { errorMessage.trim() }
            .ifBlank { "명령 실행에 실패했습니다. 종료 코드: $exitCode, Termux 오류 코드: $errorCode" }
        return if (isSuccess) detail else "[$stableErrorCode]\n$detail"
    }
}
