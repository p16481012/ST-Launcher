package app.tavernbridge.launcher.model

import app.tavernbridge.launcher.security.redactSensitiveText

enum class DiagnosticPanel(val label: String) {
    OVERVIEW("진단 결과"),
    SERVER_LOG("서버 로그"),
    PREVIOUS_SERVER_LOG("이전 서버 로그"),
    WORK_HISTORY("작업 기록"),
}

enum class DiagnosticStatus(val label: String) {
    OK("정상"),
    WARNING("확인 필요"),
    ERROR("오류"),
    INFO("참고"),
}

data class DiagnosticItem(
    val id: String,
    val title: String,
    val value: String,
    val detail: String = "",
    val status: DiagnosticStatus,
    val recommendation: String = "",
)

internal fun LauncherUiState.readyForTermuxDiagnostic(): Boolean =
    termuxSetup.verified && environment.termuxInstalled && environment.commandPermissionGranted && environment.managerConnected

data class DiagnosticReport(
    val checkedAt: String,
    val items: List<DiagnosticItem>,
    val copyOnlyDetails: Map<String, String> = emptyMap(),
) {
    private val byStatus = items.groupBy { it.status }
    fun rows(status: DiagnosticStatus): List<DiagnosticItem> = byStatus[status].orEmpty()
    val okCount: Int get() = rows(DiagnosticStatus.OK).size
    val warningCount: Int get() = rows(DiagnosticStatus.WARNING).size
    val errorCount: Int get() = rows(DiagnosticStatus.ERROR).size
    val infoCount: Int get() = rows(DiagnosticStatus.INFO).size
    val checkedCount: Int get() = okCount + warningCount + errorCount
    val checkTitle: String get() = if (checkedCount == 0) "판정한 검사 없음" else "검사 결과 ${checkedCount}개"
    val statusSummary: String get() = "정상 ${okCount}개 · 확인 필요 ${warningCount}개 · 오류 ${errorCount}개"
    val indexLabel: String get() = when {
        warningCount + errorCount > 0 -> "확인 필요"
        checkedCount == 0 -> "판정 없음"
        else -> "정상"
    }

    fun copyText(): String = buildString {
        appendLine("실리태번 런처 진단")
        appendLine("검사 시각: $checkedAt")
        copyOnlyDetails.forEach { (label, value) -> appendLine("$label: $value") }
        appendLine("$checkTitle · $statusSummary")
        if (infoCount > 0) appendLine("참고 ${infoCount}개 (검사 수에서 제외)")
        appendLine()
        items.forEach { item ->
            val marker = item.status.label
            appendLine("[$marker] ${item.title}: ${item.value}")
            if (item.detail.isNotBlank()) appendLine("  ${item.detail}")
            if (item.recommendation.isNotBlank()) appendLine("  권장 조치: ${item.recommendation}")
        }
    }.trim().let { redactSensitiveText(it, maxLength = null) }
}
