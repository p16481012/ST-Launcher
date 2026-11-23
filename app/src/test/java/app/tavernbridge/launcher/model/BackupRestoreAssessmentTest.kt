package app.tavernbridge.launcher.model

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class BackupRestoreAssessmentTest {
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-11-23T12:00:00Z").toEpochMilli()
    private fun archive(date: String = "2026-11-23 11:00:00") = BackupArchive("backup.zip", 1, date,
        setOf(BackupCategory.FULL), emptySet(), true, "1.18.0")

    @Test fun matchingRecentVersionHasNoExtraWarning() {
        assertTrue(backupRestoreWarnings(archive(), "1.18.0", now, zone).isEmpty())
    }
    @Test fun versionDifferenceDoesNotPretendToProveCompatibility() {
        assertTrue(backupRestoreWarnings(archive(), "1.19.0", now, zone).single().contains("보장하지 않습니다"))
    }
    @Test fun oldFutureMalformedAndImportedDatesAreExplicit() {
        assertTrue(backupRestoreWarnings(archive("2026-11-01 12:00:00"), "1.18.0", now, zone).single().contains("22일"))
        assertTrue(backupRestoreWarnings(archive("2026-11-24 12:00:00"), "1.18.0", now, zone).single().contains("미래"))
        assertTrue(backupRestoreWarnings(archive("2026-02-30 12:00:00"), "1.18.0", now, zone).single().contains("확인할 수 없습니다"))
        assertTrue(backupRestoreWarnings(archive().copy(branch = "imported"), "1.18.0", now, zone).single().contains("원본 자료"))
    }
}
