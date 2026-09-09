package app.tavernbridge.launcher.model

import app.tavernbridge.launcher.security.redactSensitiveText

enum class DiagnosticPanel(val label: String) {
    OVERVIEW("진단 결과"),
    SERVER_LOG("서버 로그"),
    PREVIOUS_SERVER_LOG("이전 서버 로그"),
    WORK_HISTORY("작업 기록"),
}

enum class DiagnosticStatus {
    OK,
    WARNING,
    ERROR,
    INFO,
}

data class DiagnosticItem(
    val id: String,
    val title: String,
    val value: String,
    val detail: String = "",
    val status: DiagnosticStatus,
    val recommendation: String = "",
)

data class DiagnosticReport(
    val checkedAt: String,
    val items: List<DiagnosticItem>,
    val copyOnlyDetails: Map<String, String> = emptyMap(),
) {
    val okCount: Int get() = items.count { it.status == DiagnosticStatus.OK }
    val warningCount: Int get() = items.count { it.status == DiagnosticStatus.WARNING }
    val errorCount: Int get() = items.count { it.status == DiagnosticStatus.ERROR }

    fun copyText(): String = buildString {
        appendLine("실리태번 런처 진단")
        appendLine("검사 시각: $checkedAt")
        copyOnlyDetails.forEach { (label, value) -> appendLine("$label: $value") }
        appendLine("정상 $okCount · 확인 필요 $warningCount · 오류 $errorCount")
        appendLine()
        items.forEach { item ->
            val marker = when (item.status) {
                DiagnosticStatus.OK -> "정상"
                DiagnosticStatus.WARNING -> "확인 필요"
                DiagnosticStatus.ERROR -> "오류"
                DiagnosticStatus.INFO -> "정보"
            }
            appendLine("[$marker] ${item.title}: ${item.value}")
            if (item.detail.isNotBlank()) appendLine("  ${item.detail}")
            if (item.recommendation.isNotBlank()) appendLine("  권장 조치: ${item.recommendation}")
        }
    }.trim().let { redactSensitiveText(it, maxLength = null) }
}
