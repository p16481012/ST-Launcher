package app.tavernbridge.launcher.termux

import android.app.Activity

/** Decodes callback fields without assuming that an absent errmsg means a missing result. */
internal object TermuxResultDecoder {
    val keys = listOf("stdout", "stderr", "exitCode", "err", "errmsg", "stdout_original_length")

    fun decode(
        callbackId: String,
        bundle: Map<String, Any?>?,
        legacyExtras: Map<String, Any?> = emptyMap(),
    ): TermuxCommandResult {
        // A nested payload is authoritative. Never assemble a successful result from
        // partial nested status and unrelated legacy fields on the callback intent.
        val fields = bundle ?: legacyExtras
        fun string(key: String): String? = fields[key] as? String
        fun integer(key: String): Int? = fields[key] as? Int

        val exitCode = integer("exitCode")
        val errorCode = integer("err")
        val errorMessage = string("errmsg").orEmpty().ifBlank {
            if (errorCode == null || (errorCode == Activity.RESULT_OK && exitCode == null)) {
                "Termux 실행 결과에 종료 상태 정보가 없어 성공 여부를 확인할 수 없습니다. 작업 기록을 확인해 주세요."
            } else ""
        }
        return TermuxCommandResult(
            callbackId = callbackId,
            stdout = string("stdout").orEmpty(),
            stderr = string("stderr").orEmpty(),
            exitCode = exitCode ?: -1,
            errorCode = errorCode ?: 0,
            errorMessage = errorMessage,
            stdoutOriginalLength = string("stdout_original_length")?.toIntOrNull() ?: -1,
        )
    }
}
