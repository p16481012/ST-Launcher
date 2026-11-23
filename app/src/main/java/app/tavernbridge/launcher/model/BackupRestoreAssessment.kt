package app.tavernbridge.launcher.model

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Advisory only: version/date metadata cannot establish data-schema compatibility. */
fun backupRestoreWarnings(backup: BackupArchive, currentVersion: String, nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault()): List<String> = buildList {
    if (currentVersion.isNotBlank() && backup.sillyTavernVersion.isNotBlank() && currentVersion != backup.sillyTavernVersion) {
        add("현재 $currentVersion 버전과 다릅니다. 데이터 형식의 호환성은 보장하지 않습니다.")
    }
    val date = runCatching {
        LocalDateTime.parse(backup.createdAt, DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT)).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()
    when {
        date == null || nowMillis <= 0 -> add("백업 시각을 확인할 수 없습니다. 실제 자료가 최신인지 확인해 주세요.")
        date > nowMillis -> add("백업 시각이 현재보다 미래입니다. 기기 시각과 자료의 최신 여부를 확인해 주세요.")
        nowMillis - date >= 7L * 86_400_000L -> add("생성된 지 ${(nowMillis - date) / 86_400_000L}일 지난 백업입니다. 이후 생긴 대화는 포함되지 않습니다.")
    }
    if (backup.branch == "imported") add("가져온 백업의 표시 시각은 원본 자료의 생성 시각과 다를 수 있습니다.")
}
