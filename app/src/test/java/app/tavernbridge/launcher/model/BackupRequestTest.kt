package app.tavernbridge.launcher.model

import app.tavernbridge.launcher.data.WorkProgressParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRequestTest {
    private val id = "50bd0d3a-9540-4a33-b81d-1aa37e447f00"
    private fun request() = BackupRequest(id, setOf(BackupCategory.CUSTOM, BackupCategory.CONFIG), false,
        setOf("chats", "User Avatars", "group chats"))

    @Test fun scopeRoundTripsWithoutChangingFoldersOrSecretPolicy() {
        listOf(request(), BackupRequest(id, setOf(BackupCategory.USER_DATA), true, emptySet()),
            BackupRequest(id, setOf(BackupCategory.FULL), false, emptySet()),
            BackupRequest(id, setOf(BackupCategory.FULL), true, emptySet()),
            BackupRequest(id, setOf(BackupCategory.EXTENSIONS), false, emptySet())).forEach {
            assertEquals(it, BackupRequestCodec.decode(BackupRequestCodec.encode(it)))
        }
    }

    @Test fun retriedRequestGetsNewIdentityButKeepsAllSelections() {
        val next = request().newAttempt()
        assertNotEquals(request().id, next.id)
        assertEquals(request(), next.copy(id = id))
        assertEquals(next, BackupRequestCodec.decode(BackupRequestCodec.encode(next)))
    }

    @Test fun oldMalformedOrUnsupportedRecordsDoNotBecomeFullBackups() {
        listOf(null, "", "full", "2|$id|full|0|", "1|invalid|full|0|", "1|$id|bad|0|",
            "1|$id|full,config|0|", "1|$id|config,config|0|", "1|$id|config|1|",
            "1|$id|custom|0|", "1|$id|custom,config|0|", "1|$id|full|3|", "1|$id|custom|0|!!",
            "1|$id|full|0|Y2hhdHM", "1|$id|custom|0|Li4").forEach {
            assertNull("Unexpected request for $it", BackupRequestCodec.decode(it))
        }
    }

    @Test fun processDeathRecoveryRequiresExactBackendRequestIdentity() {
        val saved = BackupRequestCodec.decode(BackupRequestCodec.encode(request()))
        val progress = WorkProgress(0, "ZIP 생성", "", operation = "backup", backupRequestId = id)
        assertEquals(request(), BackupRequestCodec.matching(saved, progress))
        assertNull(BackupRequestCodec.matching(null, progress))
        assertNull(BackupRequestCodec.matching(saved, null))
        assertNull(BackupRequestCodec.matching(saved, progress.copy(backupRequestId = "")))
        assertNull(BackupRequestCodec.matching(saved, progress.copy(backupRequestId = request().newAttempt().id)))
        assertNull(BackupRequestCodec.matching(saved, progress.copy(operation = "restore")))
    }

    @Test fun backendIdentitySurvivesProgressParsing() {
        val progress = WorkProgressParser.parse("operation=backup\nstatus=error\nbackup_request_id=$id\nerror_code=BACKUP_CREATE_FAILED\n")
        assertEquals(request(), BackupRequestCodec.matching(request(), progress))
    }

    @Test fun recoveredFailureKeepsActualZipReasonAndHidesSecrets() {
        val raw = "api_key=private-key-value\nzip I/O error: Permission denied\n백업 대상 파일을 읽지 못했습니다."
        val detail = backupFailureDetail("작업이 중단되었습니다. 로그를 확인해 주세요.", raw)
        assertTrue(detail.contains("Permission denied"))
        assertTrue(detail.contains("백업 대상 파일을 읽지 못했습니다"))
        assertFalse(detail.contains("private-key-value"))
        val log = backupFailureLog("실패", raw)
        assertTrue(log.contains("api_key=[숨김]"))
        assertFalse(log.contains("private-key-value"))
    }

    @Test fun redactionHappensBeforeTailLimitAndLatestErrorIsKept() {
        val secret = "z".repeat(8_000)
        val detail = backupFailureDetail("실패", "token=$secret\n" + "압축 진행\n".repeat(1_000) + "zip error: changed source")
        assertFalse(detail.contains("zzzz"))
        assertTrue(detail.endsWith("zip error: changed source"))
        assertTrue(detail.length < 6_000)
    }

    @Test fun emptyLogRetainsActionableDirectFailure() {
        assertEquals("[BACKUP_NO_SPACE]\n공간이 부족합니다.",
            backupFailureDetail("[BACKUP_NO_SPACE]\n공간이 부족합니다.", ""))
    }

    @Test fun cleanupChatterCannotDisplaceZipExitAndFilesystemCause() {
        val log = "zip_exit_code=14\nzip I/O error: No space left on device\n" +
            "임시 파일 정리 중\n".repeat(400) + "작업 종료 · 상태=실패"
        val detail = backupFailureDetail("[BACKUP_CREATE_FAILED]\n백업 생성에 실패했습니다.", log)
        assertTrue(detail.contains("BACKUP_CREATE_FAILED"))
        assertTrue(detail.contains("zip_exit_code=14"))
        assertTrue(detail.contains("No space left on device"))
        assertTrue(backupFailureLog("실패", log).contains("No space left on device"))
        assertTrue(detail.length < 2_000)
    }

    @Test fun successfulBackupSurvivesFailedFollowUpDoctor() = runBlocking {
        val result = checkCompletedBackup("existing environment") { throw IllegalStateException("doctor timeout") }
        assertEquals("existing environment", result.value)
        assertTrue(result.refreshFailed)
    }

    @Test fun successfulFollowUpUsesFreshEnvironment() = runBlocking {
        val result = checkCompletedBackup("old") { "fresh" }
        assertEquals("fresh", result.value)
        assertFalse(result.refreshFailed)
    }

    @Test fun cancellationIsNotSwallowedByCompletionCheck() = runBlocking {
        var cancelled = false
        try { checkCompletedBackup("old") { throw CancellationException("cancel") } }
        catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }
}
