package app.tavernbridge.launcher.ui

internal fun v2DaysSince(last: Long, now: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String? {
    if (last <= 0 || last > now) return null
    fun day(millis: Long) = java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val days = java.time.temporal.ChronoUnit.DAYS.between(day(last), day(now))
    return if (days == 0L) "오늘" else "${days}일 전"
}

// Only show recognized completed operations; raw console output, paths and secrets never enter the dashboard.
private val activityLine = Regex("""^\[\d{4}-\d{2}-\d{2} (\d{2}:\d{2}):\d{2}] \[([a-z-]+)] 작업 종료 · 상태=(성공|실패|중단)(?: ·.*)?$""")
private val activityNames = mapOf("start" to "서버 시작", "stop" to "서버 종료", "restart" to "서버 재시작",
    "backup" to "백업", "restore" to "복원", "import-backup" to "외부 백업 복원", "restore-selected" to "선택 복원",
    "update" to "업데이트", "update-preflight" to "업데이트 확인", "install" to "설치")

internal fun v2RecentActivity(history: String): List<Pair<String, String>> =
    history.lineSequence().mapNotNull { line ->
        val (time, op, outcome) = activityLine.matchEntire(line.trim())?.destructured ?: return@mapNotNull null
        val name = activityNames[op] ?: return@mapNotNull null
        time to "$name ${if (outcome == "성공") "완료" else outcome}"
    }.toList().takeLast(5).asReversed()
