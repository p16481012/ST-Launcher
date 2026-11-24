package app.tavernbridge.launcher.ui

import app.tavernbridge.launcher.model.BackupArchive
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

private val archiveDateFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)
private val archiveShortDate = DateTimeFormatter.ofPattern("M월 d일 E", Locale.KOREAN)
private val archiveDetailDate = DateTimeFormatter.ofPattern("M월 d일 EEEE HH:mm", Locale.KOREAN)
private fun BackupArchive.date() = runCatching { LocalDateTime.parse(createdAt, archiveDateFormat) }.getOrNull()
internal fun archiveHeading(archive: BackupArchive, detail: Boolean = false) =
    archive.date()?.format(if (detail) archiveDetailDate else archiveShortDate) ?: archive.createdAt.ifBlank { archive.fileName }

internal fun backupOverviewHeading(latest: BackupArchive?, loaded: Boolean, storageReady: Boolean?, today: LocalDate): String {
    if (storageReady == false) return "백업 저장공간을\n연결해 주세요"
    if (!loaded) return "백업 목록을\n확인하고 있어요"
    if (latest == null) return "첫 백업을\n만들어 보세요"
    val days = latest.date()?.toLocalDate()?.let { ChronoUnit.DAYS.between(it, today) }
    return when {
        days == null || days < 0 -> "저장된 백업을\n확인하세요"
        days == 0L -> "오늘 마지막으로\n백업했어요"
        days == 1L -> "어제 마지막으로\n백업했어요"
        else -> "${days}일 전에 마지막으로\n백업했어요"
    }
}
