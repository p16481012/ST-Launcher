package app.tavernbridge.launcher.model

import app.tavernbridge.launcher.security.redactSensitiveText

/** Recovery uses only the operation's matching progress log, never a previous server log. */
internal fun operationFailureSummary(errorCode: String, detail: String, logText: String): String {
    val code = "[$errorCode]".takeIf { stableCode.matches(it) }
    val headline = detail.removePrefix(code.orEmpty()).trim()
        .ifBlank { "작업이 실패했습니다. 로그를 확인해 주세요." }
    return operationErrorSummary(listOfNotNull(code, headline, logText.takeIf { it.isNotBlank() }).joinToString("\n"))
}

/** Keep the failure, not the beginning of a long command transcript, in compact error UI. */
internal fun operationErrorSummary(message: String?): String {
    // Redact before selecting lines or shortening them: a secret may cross either boundary.
    val lines = redactSensitiveText(message.orEmpty().replace(ansiEscape, ""), maxLength = null)
        .lineSequence().filter { it.isNotBlank() }.toList()
    if (lines.isEmpty()) return "알 수 없는 오류가 발생했습니다."
    val code = lines.first().trim().takeIf { stableCode.matches(it) }
    val body = if (code == null) lines else lines.drop(1)
    val headline = body.firstOrNull()?.trim()
    if (body.size <= 3) return (listOfNotNull(code) + body.map(String::trim))
        .joinToString("\n", transform = ::compactErrorLine)

    val ranked = body.mapIndexedNotNull { index, line ->
        val score = causeScore(line)
        if (score == 0) null else index to score
    }
    val selected = ranked.sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenByDescending { it.first })
        .take(2).map { it.first }.sorted()
    val causes = if (selected.isEmpty()) body.takeLast(2).map(String::trim) else selected.map { index ->
        val line = body[index].trim()
        val next = body.getOrNull(index + 1)
        // Shell output may wrap a linker path onto a separate line. Keep that context,
        // but do not attach a following progress line or a second error to the first.
        if (next != null && causeScore(next) == 0 && !progressLine.containsMatchIn(next.trim()) &&
            (next.firstOrNull()?.isWhitespace() == true || next.trim().startsWith("/") ||
                next.trim().startsWith("\"/") || next.trim().startsWith("'/"))) {
            "$line ${next.trim()}"
        } else line
    }
    return (listOfNotNull(code, headline) + causes).distinct()
        .joinToString("\n", transform = ::compactErrorLine)
}

private fun compactErrorLine(line: String): String =
    if (line.length <= 260) line else "${line.take(145)} … ${line.takeLast(110)}"

private fun causeScore(line: String): Int = when {
    routineFailure.containsMatchIn(line) -> 0
    runtimeFailure.containsMatchIn(line) -> 3
    specificFailure.containsMatchIn(line) -> 2
    generalFailure.containsMatchIn(line) -> 1
    else -> 0
}

private val stableCode = Regex("\\[[A-Z][A-Z0-9_]+]")
private val ansiEscape = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
private val progressLine = Regex("^\\[\\d{2}:\\d{2}:\\d{2}]|^error_code=|^\\[\\d{4}-\\d{2}-\\d{2}")
private val routineFailure = Regex(
    "작업 종료 · 상태=|작업이 중단되었습니다\\. 로그를 확인|Node 모듈 설치에 실패했습니다\\. 로그를 확인|" +
        "작업 실패 - 명령 실행에 실패했습니다|" +
        "(?:a complete log|debug log|full report).*?(?:found|see|available)|^error_code=",
    RegexOption.IGNORE_CASE,
)
private val runtimeFailure = Regex(
    "CANNOT LINK EXECUTABLE|cannot locate symbol|library .+ not found|no space left on device|permission denied",
    RegexOption.IGNORE_CASE,
)
private val specificFailure = Regex(
    "\\bE(?:NOSPC|ACCES|PERM|IO|NOENT|NOTFOUND|AI_AGAIN|CONNRESET|CONNREFUSED|TIMEDOUT|RESOLVE|TARGET|INTEGRITY)\\b|" +
        "unable to (?:resolve|locate|fetch)|failed to fetch|could not (?:resolve|connect)|" +
        "no matching version|certificate.*(?:expired|invalid|verify)|" +
        "allow-external-apps.*(?:true|false|disabled)|zip I/O error|zip_exit_code",
    RegexOption.IGNORE_CASE,
)
private val generalFailure = Regex(
    "\\b(?:error|fatal|exception)\\b|^E:|실패|부족|찾을 수 없|실행할 수 없|설치되지 않았",
    RegexOption.IGNORE_CASE,
)
