package app.tavernbridge.launcher.model

import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class BackupRetentionPolicyTest {
    private val now = LocalDateTime.parse("2026-11-24T00:00:00").toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun archive(day: String, bytes: Long = 100) = BackupArchive(
        "SillyTavern-Launcher-${day.replace("-", "")}-000000.zip", bytes, "$day 00:00:00", setOf(BackupCategory.FULL), emptySet(), true, "1.0")

    @Test fun protectsLatestBackupsAndPinsBeforePreview() {
        val backups = listOf(archive("2026-01-01"), archive("2026-02-01"), archive("2026-03-01"), archive("2026-04-01"), archive("2026-05-01"))
        val notes = mapOf(backups.first().fileName to BackupAnnotation(pinned = true))
        val result = BackupRetentionPolicy.preview(backups, notes, nowMillis = now, zone = ZoneOffset.UTC)
        assertEquals(listOf(backups[1]), result.candidates)
        assertEquals(100L, result.reclaimableBytes)
        assertEquals(4, result.protectedCount)
    }

    @Test fun neverSuggestsUnknownDatesRecentOrUnreadablePins() {
        val unknown = archive("2026-01-01").copy(createdAt = "unknown")
        val invalidDate = archive("2026-02-31")
        val recent = archive("2026-11-23")
        val protected = archive("2026-02-01")
        val result = BackupRetentionPolicy.preview(listOf(unknown, invalidDate, recent, protected), mapOf(protected.fileName to BackupAnnotation(trusted = false)), BackupRetentionOptions(1, 30), now, ZoneOffset.UTC)
        assertTrue(result.candidates.isEmpty())
        assertEquals(2, result.unknownDateCount)
    }

    @Test fun postBackupProposalExcludesFullAndUnidentifiedCheckpointsEvenWhenOverCount() {
        val entries = (1..8).map { n -> archive("2026-11-0$n").copy(categories = setOf(BackupCategory.USER_DATA), branch = "release", revision = "a".repeat(64)) }.toMutableList()
        entries[0] = entries[0].copy(categories = setOf(BackupCategory.FULL))
        entries[1] = entries[1].copy(branch = "imported")
        val notes = entries.associate { it.fileName to BackupAnnotation(pinned = it == entries[2], createdRevision = it.revision) }
        assertNull(BackupRetentionPolicy.afterCreation(entries, notes, BackupKeep.UNLIMITED, now, ZoneOffset.UTC))
        assertEquals(listOf(entries[4], entries[3]), BackupRetentionPolicy.afterCreation(entries, notes, BackupKeep.THREE, now, ZoneOffset.UTC)?.candidates)
        assertNull(BackupRetentionPolicy.afterCreation(entries, emptyMap(), BackupKeep.THREE, now, ZoneOffset.UTC))
        val copied = entries[4].copy(revision = "b".repeat(64))
        assertEquals(listOf(entries[3]), BackupRetentionPolicy.afterCreation(entries.map { if (it == entries[4]) copied else it }, notes, BackupKeep.THREE, now, ZoneOffset.UTC)?.candidates)
    }

    @Test fun oldCountAloneCannotDeleteRecentBackup() {
        val backups = listOf(archive("2026-11-21"), archive("2026-11-22"), archive("2026-11-23"))
        assertTrue(BackupRetentionPolicy.preview(backups, emptyMap(), BackupRetentionOptions(1, 30), now, ZoneOffset.UTC).candidates.isEmpty())
    }

    @Test fun selectionMustMatchPreviewAndFreshPinsAndArchiveMetadata() {
        val backups = listOf(archive("2026-01-01"), archive("2026-02-01"))
        val preview = BackupRetentionPolicy.preview(backups, emptyMap(), BackupRetentionOptions(1, 30), now, ZoneOffset.UTC)
        val selected = setOf(backups.first().fileName)
        assertEquals(listOf(backups.first()), BackupRetentionPolicy.selectedForDeletion(preview, selected, backups, emptyMap()))
        assertTrue(runCatching { BackupRetentionPolicy.selectedForDeletion(preview, selected, backups, mapOf(backups.first().fileName to BackupAnnotation(pinned = true))) }.isFailure)
        assertTrue(runCatching { BackupRetentionPolicy.selectedForDeletion(preview, selected, listOf(backups.first().copy(sizeBytes = 101), backups[1]), emptyMap()) }.isFailure)
        assertTrue(runCatching { BackupRetentionPolicy.selectedForDeletion(preview, setOf(backups[1].fileName), backups, emptyMap()) }.isFailure)
        assertTrue(runCatching { BackupRetentionPolicy.selectedForDeletion(preview, setOf("../test.zip"), backups, emptyMap()) }.isFailure)
    }

    @Test fun diskFreeSpaceChangesDoNotInvalidateUnchangedArchive() {
        val backups = listOf(archive("2026-01-01"), archive("2026-02-01"))
        val preview = BackupRetentionPolicy.preview(backups, emptyMap(), BackupRetentionOptions(1, 30), now, ZoneOffset.UTC)
        assertEquals(1, BackupRetentionPolicy.selectedForDeletion(preview, setOf(backups.first().fileName), backups.map { it.copy(availableRestoreBytes = 9999) }, emptyMap()).size)
    }

    @Test fun safeDefaultsRequireAtLeastOneRecentBackupAndBoundNotes() {
        assertTrue(runCatching { BackupRetentionPolicy.preview(emptyList(), emptyMap(), BackupRetentionOptions(0, 30), now) }.isFailure)
        assertEquals(500, BackupRetentionPolicy.normalizeMemo("a".repeat(700)).length)
        assertEquals("safe\ntext", BackupRetentionPolicy.normalizeMemo("safe\u0000\ntext"))
        assertFalse(BackupRetentionPolicy.validFileName("/storage/SillyTavern-Launcher-20260101-000000.zip"))
        assertTrue(BackupRetentionPolicy.validFileName("SillyTavern-Launcher-20260101-000000 (1).zip"))
    }
}
