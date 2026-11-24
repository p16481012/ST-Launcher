package app.tavernbridge.launcher.model

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BackupReminderTest {
    @Test fun chosenIntervalStartsExactlyAtItsBoundary() {
        val day = 86_400_000L
        val created = 100L * day
        for (interval in BackupInterval.entries) {
            val state = BackupReminderState(lastBackupAtMillis = created, interval = interval)
            assertNull(state.notice(created + interval.days * day - 1, true))
            assertEquals(interval.days, state.notice(created + interval.days * day, true)?.ageDays)
        }
    }
    private val now = Instant.parse("2026-11-23T10:00:00Z").toEpochMilli()
    private val day = 86_400_000L
    private val request = BackupRequest("50bd0d3a-9540-4a33-b81d-1aa37e447f00", setOf(BackupCategory.USER_DATA), false, emptySet())

    @Test fun initialHistoryIsUnknownRatherThanNoBackupExists() {
        val notice = BackupReminderState().notice(now, installed = true)
        assertNotNull(notice)
        assertNull(notice?.ageDays)
        assertEquals(0L, notice?.lastBackupAtMillis)
        assertNull(BackupReminderState().notice(now, installed = false))
    }

    @Test fun reminderStartsExactlySevenDaysAfterKnownCreation() {
        val state = BackupReminderState(lastBackupAtMillis = now - 7 * day)
        assertEquals(7L, state.notice(now, true)?.ageDays)
        assertNull(state.notice(now - 1L, true))
        assertNull(state.copy(enabled = false).notice(now, true))
    }

    @Test fun onlyFullUserDataScopeClearsTheReminder() {
        val original = BackupReminderState(lastBackupAtMillis = now - 20 * day)
        listOf(BackupCategory.CONFIG, BackupCategory.EXTENSIONS, BackupCategory.CUSTOM).forEach { category ->
            val partial = request.copy(categories = setOf(category), customFolders = if (category == BackupCategory.CUSTOM) setOf("chats") else emptySet())
            assertEquals(original, original.recordSuccessful(partial, now, now))
        }
        listOf(BackupCategory.USER_DATA, BackupCategory.FULL).forEach { category ->
            val updated = original.recordSuccessful(request.copy(categories = setOf(category)), now, now)
            assertEquals(now, updated.lastBackupAtMillis)
            assertNull(updated.notice(now, true))
        }
    }

    @Test fun recoveredCompletionUsesBackendTimeAndMatchingRequest() {
        val original = BackupReminderState()
        val progress = WorkProgress(100, "완료", "", status = "success", operation = "backup",
            finishedAtMillis = now - 10 * day, backupRequestId = request.id)
        val updated = original.recordRecovered(request, progress, now)
        assertEquals(10L, updated.notice(now, true)?.ageDays)
        listOf("running", "error", "cancelled").forEach { status ->
            assertEquals(original, original.recordRecovered(request, progress.copy(status = status), now))
        }
        assertEquals(original, original.recordRecovered(null, progress, now))
        assertEquals(original, original.recordRecovered(request, progress.copy(backupRequestId = "unknown"), now))
        assertEquals(original, original.recordRecovered(request, progress.copy(operation = "import-backup"), now))
        assertEquals(original, original.recordRecovered(request, progress.copy(finishedAtMillis = 0L), now))
    }

    @Test fun repeatedCallbackDoesNotPretendAnOldBackupWasCreatedAgain() {
        val saved = BackupReminderState().recordSuccessful(request, now - 10 * day, now)
        assertEquals(saved, saved.recordSuccessful(request, now, now))
        assertEquals(saved, saved.recordSuccessful(request.newAttempt(), now - 11 * day, now))
    }

    @Test fun invalidRequestAndInvalidTimesNeverRefreshHistory() {
        val original = BackupReminderState()
        assertEquals(original, original.recordSuccessful(request.copy(id = "bad"), now, now))
        listOf(0L, -1L, now + 1L, Long.MAX_VALUE).forEach {
            assertEquals(original, original.recordSuccessful(request, it, now))
        }
        assertNull(original.notice(0L, true))
        assertNull(original.notice(-1L, true))
    }

    @Test fun observationUsesArchiveCreationNotListingOrImportTime() {
        val archive = archive("2026-11-01 10:00:00")
        val state = BackupReminderState().observeArchives(listOf(archive), now, ZoneOffset.UTC)
        assertEquals(22L, state.notice(now, true)?.ageDays)
        val imported = archive.copy(createdAt = "2026-11-23 10:00:00", branch = "imported")
        assertEquals(state, state.observeArchives(listOf(imported), now, ZoneOffset.UTC))
        assertEquals(state, state.observeArchives(emptyList(), now, ZoneOffset.UTC))
    }

    @Test fun malformedFutureAndPartialArchivesDoNotSuppressReminder() {
        val original = BackupReminderState()
        val archives = listOf(
            archive("2026-02-30 10:00:00"), archive("2026-11-24 10:00:00"), archive("unknown"),
            archive("2026-11-23 10:00:00").copy(categories = setOf(BackupCategory.CONFIG)),
        )
        assertEquals(original, original.observeArchives(archives, now, ZoneOffset.UTC))
    }

    @Test fun snoozeExpiresAndDoesNotHideAfterLargeClockRollback() {
        val original = BackupReminderState()
        val state = original.snooze(now)
        assertNull(state.notice(now, true))
        assertNull(state.notice(now + 7 * day - 1L, true))
        assertNotNull(state.notice(now + 7 * day, true))
        assertNotNull(state.notice(now - day, true))
        assertEquals(original, original.snooze(0L))
        assertNotNull(BackupReminderState(lastBackupAtMillis = now + day).notice(now, true))
    }

    @Test fun freshConfirmedBackupCanRepairARecordFromTheFuture() {
        val future = BackupReminderState(lastBackupAtMillis = now + 30 * day)
        assertEquals(now, future.recordSuccessful(request, now, now).lastBackupAtMillis)
    }

    private fun archive(date: String) = BackupArchive(
        fileName = "SillyTavern-Launcher-20261101-100000.zip", sizeBytes = 500L, createdAt = date,
        categories = setOf(BackupCategory.USER_DATA), customFolders = emptySet(), includesSecrets = false,
        sillyTavernVersion = "1.18.0", branch = "release",
    )
}
