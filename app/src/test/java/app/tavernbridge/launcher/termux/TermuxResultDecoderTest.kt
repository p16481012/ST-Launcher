package app.tavernbridge.launcher.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxResultDecoderTest {
    @Test fun successWithoutErrorMessageDoesNotInventAVersionError() {
        val result = decode(mapOf("exitCode" to 0, "err" to -1, "stdout" to "done"))
        assertTrue(result.isSuccess)
        assertEquals("", result.errorMessage)
        assertEquals("done", result.stdout)
    }

    @Test fun failedShellWithoutErrorMessageUsesActualExitCode() {
        val result = decode(mapOf("exitCode" to 100, "err" to -1))
        assertFalse(result.isSuccess)
        assertEquals("", result.errorMessage)
        assertTrue(result.readableError().contains("종료 코드: 100"))
        assertFalse(result.readableError().contains("0.109"))
        assertFalse(result.readableError().contains("Bundle"))
    }

    @Test fun explicitArchiveFailurePreservesTheUnderlyingPackageError() {
        val result = decode(mapOf("exitCode" to 65, "err" to -1,
            "stderr" to "압축 도구 설치 실패\nE: Failed to fetch package.deb\nerror_code=ARCHIVE_TOOL_INSTALL_FAILED"))
        assertEquals("ARCHIVE_TOOL_INSTALL_FAILED", result.stableErrorCode)
        assertTrue(result.readableError().contains("Failed to fetch package.deb"))
        assertFalse(result.readableError().contains("error_code="))
        assertFalse(result.readableError().contains("0.109"))
        assertEquals("ARCHIVE_TOOL_INSTALL_FAILED", result.copy(stderr = "").stableErrorCode)
    }

    @Test fun internalTermuxFailureWithoutShellExitPreservesItsExplanation() {
        val result = decode(mapOf("err" to 1, "errmsg" to "External apps disabled"))
        assertFalse(result.isSuccess)
        assertEquals("External apps disabled", result.errorMessage)
        assertEquals("TERMUX_COMMAND_FAILED", result.stableErrorCode)
    }

    @Test fun missingAndIncompleteMetadataNeverBecomeSuccess() {
        listOf(null, emptyMap(), mapOf("stdout" to "done"), mapOf("exitCode" to 0),
            mapOf("err" to -1)).forEach { fields ->
            val result = decode(fields)
            assertFalse(result.isSuccess)
            assertTrue(result.errorMessage.contains("종료 상태 정보"))
            assertFalse(result.errorMessage.contains("0.109"))
        }
    }

    @Test fun malformedNumericFieldsNeverBecomeSuccess() {
        listOf(mapOf("exitCode" to "0", "err" to -1),
            mapOf("exitCode" to 0, "err" to "-1")).forEach { fields ->
            assertFalse(decode(fields).isSuccess)
        }
    }

    @Test fun legacyFlatResultsStillWork() {
        val result = TermuxResultDecoder.decode("legacy", null,
            mapOf("exitCode" to 0, "err" to -1, "stdout" to "ok"))
        assertTrue(result.isSuccess)
        assertEquals("legacy", result.callbackId)
        assertEquals("", result.errorMessage)
    }

    @Test fun nestedFieldsTakePriorityOverConflictingLegacyFields() {
        val result = TermuxResultDecoder.decode("mixed", mapOf("stdout" to "nested", "err" to -1, "exitCode" to 65),
            mapOf("stdout" to "legacy", "exitCode" to 0, "errmsg" to "legacy message"))
        assertFalse(result.isSuccess)
        assertEquals("nested", result.stdout)
        assertEquals(65, result.exitCode)
        assertEquals("", result.errorMessage)
    }

    @Test fun partialNestedPayloadCannotBeCombinedWithLegacyFieldsToInventSuccess() {
        listOf(emptyMap(), mapOf("err" to -1), mapOf("exitCode" to 0)).forEach { fields ->
            val result = TermuxResultDecoder.decode("mixed", fields, mapOf("exitCode" to 0, "err" to -1))
            assertFalse(result.isSuccess)
            assertTrue(result.errorMessage.contains("종료 상태 정보"))
        }
    }

    @Test fun emptyErrorMessageIsValidAndOutputTruncationMetadataSurvives() {
        val result = decode(mapOf("exitCode" to 1, "err" to -1, "errmsg" to "", "stdout" to "tail",
            "stdout_original_length" to "5000"))
        assertEquals("", result.errorMessage)
        assertTrue(result.stdoutTruncated)
    }

    @Test fun diagnosticRecommendationsDistinguishIndexAndArchiveFailures() {
        assertTrue(LauncherErrorCode.recommendation("PACKAGE_INDEX_FAILED").contains("패키지 목록"))
        assertTrue(LauncherErrorCode.recommendation("ARCHIVE_TOOL_INSTALL_FAILED").contains("apt-get install zip unzip"))
        assertEquals("", LauncherErrorCode.recommendation(""))
        assertTrue(LauncherErrorCode.recommendation("UNKNOWN_COMMAND_FAILURE").contains("오류 코드와 작업명"))
    }

    private fun decode(fields: Map<String, Any?>?) = TermuxResultDecoder.decode("test", fields)
}
