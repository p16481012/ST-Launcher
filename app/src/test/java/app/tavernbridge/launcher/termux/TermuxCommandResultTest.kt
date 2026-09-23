package app.tavernbridge.launcher.termux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxCommandResultTest {
    @Test
    fun `backup failures preserve actionable codes instead of network failure`() {
        mapOf(58 to "BACKUP_NO_SPACE", 59 to "BACKUP_CREATE_FAILED", 60 to "BACKUP_SOURCE_CHANGED")
            .forEach { (exitCode, code) ->
                val result = TermuxCommandResult("test", "", "백업 작업의 원본 오류입니다.", exitCode, -1, "")
                assertFalse(result.isSuccess)
                assertTrue(result.readableError().startsWith("[$code]"))
                assertTrue(result.readableError().contains("원본 오류"))
                assertFalse(result.readableError().contains("NETWORK_FETCH_FAILED"))
            }
    }

    @Test
    fun `runtime validation and interrupted restore retain actionable error codes`() {
        mapOf(25 to "RESTORE_ROLLBACK_REQUIRED", 56 to "NODE_VERSION_UNSUPPORTED", 57 to "NODE_VERSION_CHECK_FAILED")
            .forEach { (exitCode, code) ->
                val result = TermuxCommandResult("test", "", "원본과 보호사본은 유지했습니다.", exitCode, -1, "")
                assertFalse(result.isSuccess)
                assertTrue(result.readableError().startsWith("[$code]"))
                assertTrue(result.readableError().contains("보호사본"))
            }
    }

    @Test
    fun `safety backup failures never masquerade as branch or command errors`() {
        mapOf(35 to "SAFETY_BACKUP_NO_SPACE", 36 to "SAFETY_BACKUP_FAILED", 37 to "SAFETY_BACKUP_SOURCE_CHANGED")
            .forEach { (exitCode, code) ->
                val result = TermuxCommandResult("test", "", "안전 백업에 실패했습니다.", exitCode, -1, "")
                assertTrue(result.readableError().startsWith("[$code]"))
            }
    }

    @Test
    fun `truncated protocol output is distinguishable from complete or legacy output`() {
        val result = TermuxCommandResult("test", "tail", "", 0, -1, "")
        assertFalse(result.stdoutTruncated)
        assertFalse(result.copy(stdoutOriginalLength = 4).stdoutTruncated)
        assertTrue(result.copy(stdoutOriginalLength = 100_000).stdoutTruncated)
    }

    @Test
    fun `file conflict and installation overlap retain typed errors`() {
        val result = TermuxCommandResult("test", "", "", 42, -1, "")
        assertTrue(result.readableError().startsWith("[FILE_EDIT_CONFLICT]"))
        assertTrue(result.copy(exitCode = 51).readableError().startsWith("[INSTALL_IMPORT_OVERLAP]"))
    }

    @Test
    fun `android result ok minus one and shell exit zero is success`() {
        val result = TermuxCommandResult(
            callbackId = "test",
            stdout = "ok",
            stderr = "",
            exitCode = 0,
            errorCode = -1,
            errorMessage = "",
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun `shell failures include stable support code`() {
        val result = TermuxCommandResult(
            callbackId = "test",
            stdout = "",
            stderr = "저장 공간이 부족합니다.",
            exitCode = 30,
            errorCode = -1,
            errorMessage = "",
        )

        assertFalse(result.isSuccess)
        assertTrue(result.readableError().startsWith("[INSUFFICIENT_STORAGE]"))
    }

    @Test
    fun `missing result bundle is not success`() {
        val result = TermuxCommandResult(
            callbackId = "test",
            stdout = "",
            stderr = "",
            exitCode = -1,
            errorCode = 0,
            errorMessage = "Termux 실행 결과 Bundle을 읽지 못했습니다.",
        )

        assertFalse(result.isSuccess)
        assertTrue(result.readableError().contains("Bundle"))
    }

    @Test
    fun `manager error code overrides numeric fallback and is hidden from detail`() {
        val result = TermuxCommandResult(
            callbackId = "test",
            stdout = "",
            stderr = "복원 공간이 부족합니다.\nerror_code=RESTORE_NO_SPACE",
            exitCode = 29,
            errorCode = -1,
            errorMessage = "",
        )

        assertTrue(result.readableError().startsWith("[RESTORE_NO_SPACE]"))
        assertFalse(result.readableError().contains("error_code="))
    }
}
