package app.tavernbridge.launcher.ui

import app.tavernbridge.launcher.model.BackupArchive
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class V2BackupPresentationTest {
    private val now = LocalDate.of(2026, 11, 24)
    private fun archive(time: String) = BackupArchive("real-backup.zip", 512, time, emptySet(), emptySet(), false, "")

    @Test fun noArchivesDoesNotConflateUnconnectedCheckingAndEmpty() {
        assertEquals("백업 저장공간을\n연결해 주세요", backupOverviewHeading(null, true, false, now))
        assertEquals("백업 목록을\n확인하고 있어요", backupOverviewHeading(null, false, true, now))
        assertEquals("첫 백업을\n만들어 보세요", backupOverviewHeading(null, true, true, now))
    }

    @Test fun usesCalendarDaysNotTwentyFourHourPeriods() {
        assertEquals("어제 마지막으로\n백업했어요", backupOverviewHeading(archive("2026-11-23 23:59:58"), true, true, now))
        assertEquals("3일 전에 마지막으로\n백업했어요", backupOverviewHeading(archive("2026-11-21 11:20:00"), true, true, now))
    }

    @Test fun malformedOrFutureMetadataIsNotPresentedAsARecentBackup() {
        for (raw in listOf("", "2026-02-31 12:00:00", "2030-01-01 00:00:00")) {
            assertEquals("저장된 백업을\n확인하세요", backupOverviewHeading(archive(raw), true, true, now))
        }
        assertEquals("real-backup.zip", archiveHeading(archive("")))
    }

    @Test fun realDateIsFormattedForListAndDetail() {
        val backup = archive("2026-11-21 14:02:00")
        assertEquals("11월 21일 토", archiveHeading(backup))
        assertEquals("11월 21일 토요일 14:02", archiveHeading(backup, detail = true))
    }
}
