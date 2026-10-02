package app.tavernbridge.launcher.data

import app.tavernbridge.launcher.model.TermuxSetupState
import app.tavernbridge.launcher.model.TermuxSetupStatus
import app.tavernbridge.launcher.termux.TermuxCommandResult

/** No Android calls or manager/bootstrap commands: readiness needs its own proof. */
internal object TermuxSetupProbe {
    fun prerequisites(installed: Boolean, permissionGranted: Boolean): TermuxSetupState? = when {
        !installed -> TermuxSetupState(TermuxSetupStatus.TERMUX_MISSING, "Termux를 설치하고 한 번 실행해 주세요.")
        !permissionGranted -> TermuxSetupState(
            TermuxSetupStatus.PERMISSION_REQUIRED,
            "Android 설정에서 런처의 ‘Termux 환경에서 명령 실행’ 권한을 허용해 주세요.",
        )
        else -> null
    }

    fun command(marker: String): String {
        require(marker.matches(Regex("[A-Za-z0-9:_-]+")))
        return "printf '%s' '$marker'"
    }

    fun classify(expectedCallbackId: String, marker: String, result: TermuxCommandResult): TermuxSetupState {
        if (result.callbackId != expectedCallbackId) return unverified()
        if (result.isSuccess && !result.stdoutTruncated && result.stdout == marker) {
            return TermuxSetupState(TermuxSetupStatus.READY, "Termux 외부 명령 실행과 응답을 확인했습니다.")
        }
        if (!result.isSuccess && externalAppsDisabled(result.stderr + "\n" + result.errorMessage)) {
            return disabled()
        }
        return unverified()
    }

    fun failure(message: String?): TermuxSetupState =
        if (!message.orEmpty().contains("[TERMUX_TIMEOUT]") && externalAppsDisabled(message.orEmpty())) disabled()
        else unverified()

    fun externalAppsDisabled(message: String): Boolean {
        if (message.contains("[TERMUX_TIMEOUT]")) return false
        val detail = message.lowercase().replace(Regex("['\"`]"), "")
        if (!detail.contains("allow-external-apps")) return false
        return Regex("allow-external-apps\\s*[:=]\\s*false").containsMatchIn(detail) ||
            detail.contains("not set to true") || detail.contains("not set to the value true") ||
            detail.contains("not enabled") || detail.contains("is disabled") ||
            (Regex("\\b(require|requires|required|must)\\b").containsMatchIn(detail) &&
                Regex("set to(?: the value)? true").containsMatchIn(detail))
    }

    private fun disabled() = TermuxSetupState(
        TermuxSetupStatus.EXTERNAL_APPS_DISABLED,
        "설정 명령이 아직 적용되지 않았어요. 위 버튼으로 복사한 뒤 Termux에서 붙여넣고 Enter를 눌러 주세요.",
    )

    private fun unverified() = TermuxSetupState(
        TermuxSetupStatus.UNVERIFIED,
        "Termux에서 답이 오지 않았어요. Termux를 한 번 열고 돌아온 뒤 다시 확인해 주세요.",
    )
}
