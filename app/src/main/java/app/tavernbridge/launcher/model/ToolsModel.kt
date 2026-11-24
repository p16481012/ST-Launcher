package app.tavernbridge.launcher.model

enum class ToolPage(val label: String) {
    INDEX("도구"), UPDATE("업데이트"), EXTENSIONS("확장 프로그램"), FILES("파일"),
    DIAGNOSTICS("환경 진단"), LOGS("서버 로그"), REPAIR("점검·복구"), RESET("SillyTavern 초기화")
}

private val logSeverity = Regex("""(?i)(?:\b(?:err(?:or)?|warn(?:ing)?|fatal|exception|failed|panic)\b|\b\w+(?:Exception|Error):|(?:오류|경고|실패)|^\s*at\s+\S)""")
internal fun isProblemLog(line: String) = logSeverity.containsMatchIn(line)
internal fun visibleLogLines(log: String, query: String, problemsOnly: Boolean): List<String> =
    if (log.isBlank()) emptyList() else log.lineSequence()
        .filter { (!problemsOnly || isProblemLog(it)) && (query.isBlank() || it.contains(query, ignoreCase = true)) }.toList()
