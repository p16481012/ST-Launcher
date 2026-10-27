package app.tavernbridge.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationErrorSummaryTest {
    @Test fun recoveredFailureUsesMatchingOperationLogWithStableCodeFirst() {
        val detail = operationFailureSummary("NODE_RUNTIME_FAILED", "Node.js를 실행하지 못했습니다. pkg upgrade 후 다시 시도해 주세요.",
            "패키지 확인 중\n".repeat(20) + "CANNOT LINK EXECUTABLE node: cannot locate symbol missing_runtime\n")
        assertTrue(detail.startsWith("[NODE_RUNTIME_FAILED]\n"))
        assertTrue(detail.contains("missing_runtime"))
        assertTrue(detail.contains("pkg upgrade"))
        assertFalse(detail.contains("패키지 확인 중"))
    }

    @Test fun recoveredFailureWithoutLogRetainsItsDirectReasonAndDoesNotDuplicateCode() {
        assertEquals("[NPM_RUNTIME_FAILED]\nnpm 명령을 찾을 수 없습니다.",
            operationFailureSummary("NPM_RUNTIME_FAILED", "[NPM_RUNTIME_FAILED]\nnpm 명령을 찾을 수 없습니다.", ""))
        assertEquals("작업이 실패했습니다. 로그를 확인해 주세요.", operationFailureSummary("", "", ""))
    }

    @Test fun transactionalRecoveryCodeIsNotReplacedByTheUnderlyingRuntimeCause() {
        listOf("UPDATE_PACKAGES_FAILED", "RESTORE_DEPENDENCY_FAILED").forEach { code ->
            val detail = operationFailureSummary(code, "이전 데이터를 보호했습니다.",
                "패키지 준비\n".repeat(10) + "CANNOT LINK EXECUTABLE node: cannot locate symbol missing_runtime\n")
            assertTrue(detail.startsWith("[$code]\n"))
            assertTrue(detail.contains("missing_runtime"))
        }
    }

    @Test fun actualLoaderFailureSurvivesTheInitialInstallationTranscript() {
        val raw = """
            [DEPENDENCY_SETUP_FAILED]
            Node 모듈 설치에 실패했습니다. 로그를 확인해 주세요.
            [16:57:47] 작업 준비 - 요청한 작업을 안전하게 준비하고 있습니다.
            [16:57:48] 패키지 목록 확인 - 현재 Termux 저장소를 확인하고 있습니다.
            Reading package lists...
            73 packages can be upgraded.
            [16:58:13] Node 모듈 설치 - 필수 패키지를 내려받고 있습니다.
            CANNOT LINK EXECUTABLE "node": cannot locate symbol "_ZNSt6__ndk113__hash_memoryEPKvm" referenced by "/data/data/com.termux/files/usr/bin/node"...
            [16:58:13] Node 패키지 폴더 0개 확인
            [16:58:15] 설치 실패 - Node 모듈 설치에 실패했습니다. 로그를 확인해 주세요.
        """.trimIndent()
        val detail = operationErrorSummary(raw)
        assertTrue(detail.startsWith("[DEPENDENCY_SETUP_FAILED]\n"))
        assertTrue(detail.contains("CANNOT LINK EXECUTABLE"))
        assertTrue(detail.contains("__hash_memory"))
        assertFalse(detail.contains("패키지 목록 확인"))
        assertTrue(detail.length < 1_100)
    }

    @Test fun wrappedLoaderPathIsIncludedButFollowingProgressIsNot() {
        val detail = operationErrorSummary("""
            [NODE_RUNTIME_FAILED]
            Node.js를 실행하지 못했습니다. Termux에서 pkg upgrade를 완료한 뒤 다시 시도해 주세요.
            preparing packages
            CANNOT LINK EXECUTABLE "node": cannot locate symbol "missing" referenced by
            /data/data/com.termux/files/usr/bin/node
            [16:58:15] 작업 실패 - 명령 실행에 실패했습니다.
        """.trimIndent())
        assertTrue(detail.contains("referenced by /data/data/com.termux/files/usr/bin/node"))
        assertTrue(detail.contains("pkg upgrade"))
    }

    @Test fun npmLogFooterDoesNotDisplaceTheActualDownloadFailure() {
        val detail = operationErrorSummary("""
            [DEPENDENCY_SETUP_FAILED]
            Node 모듈 설치에 실패했습니다. 로그를 확인해 주세요.
            package setup
            npm error code ENOTFOUND
            npm error request to https://registry.npmjs.org/yargs failed, reason: getaddrinfo ENOTFOUND registry.npmjs.org
            npm error A complete log of this run can be found in: /tmp/npm-debug.log
            [16:58:15] 설치 실패 - Node 모듈 설치에 실패했습니다. 로그를 확인해 주세요.
        """.trimIndent())
        assertTrue(detail.contains("ENOTFOUND registry.npmjs.org"))
        assertFalse(detail.contains("npm-debug.log"))
    }

    @Test fun aptFailureRemainsVisibleAfterLongPackageOutput() {
        val detail = operationErrorSummary("[TERMUX_PACKAGE_UPGRADE_FAILED]\nTermux에서 pkg upgrade를 완료해 주세요.\n" +
            "Reading package lists...\n".repeat(100) + "E: Failed to fetch https://mirror.test/package.deb Connection timed out\n")
        assertTrue(detail.startsWith("[TERMUX_PACKAGE_UPGRADE_FAILED]"))
        assertTrue(detail.contains("Failed to fetch"))
        assertFalse(detail.contains("Reading package lists"))
    }

    @Test fun veryLongLinesKeepTheirBeginningAndEndWithoutUnboundedUiText() {
        val detail = operationErrorSummary("[NODE_RUNTIME_FAILED]\nNode.js 실행 실패\nloading\n" +
            "CANNOT LINK EXECUTABLE node: " + "long-path/".repeat(100) + " cannot locate symbol UNIQUE_SYMBOL\n" +
            "정리 완료\n")
        assertTrue(detail.contains("CANNOT LINK EXECUTABLE"))
        assertTrue(detail.contains("UNIQUE_SYMBOL"))
        assertTrue(detail.contains(" … "))
        assertTrue(detail.length < 1_100)
    }

    @Test fun secretsAreRedactedBeforeLineSelectionAndTruncation() {
        val secret = "x".repeat(4_000)
        val detail = operationErrorSummary("[DEPENDENCY_SETUP_FAILED]\n설치 실패\n" +
            "starting\nerror token=$secret\n" +
            "npm error ENOTFOUND https://alice:private-pass@registry.test/path?token=private-token\n")
        assertFalse(detail.contains("xxxx"))
        assertFalse(detail.contains("private-pass"))
        assertFalse(detail.contains("private-token"))
        assertTrue(detail.contains("[숨김]"))
    }

    @Test fun shortTermuxPermissionMessageRetainsTheActionablePropertyName() {
        val raw = "[TERMUX_COMMAND_FAILED]\nRunCommandService requires `allow-external-apps` property to be set to `true` in ~/.termux/termux.properties file."
        assertEquals(raw, operationErrorSummary(raw))
    }

    @Test fun shortOrdinaryMessagesAreNotChangedOrInvented() {
        assertEquals("파일 이름을 확인해 주세요.", operationErrorSummary("파일 이름을 확인해 주세요."))
        assertEquals("[TERMUX_TIMEOUT]\n응답을 기다리는 시간이 초과되었습니다.",
            operationErrorSummary("\n[TERMUX_TIMEOUT]\n\n응답을 기다리는 시간이 초과되었습니다.\n"))
    }

    @Test fun unclassifiedFailureUsesRecentTailInsteadOfStartupChatter() {
        val detail = operationErrorSummary("[UNKNOWN_COMMAND_FAILURE]\n명령을 완료하지 못했습니다.\n" +
            "초기화 진행\n".repeat(30) + "작업 도구의 마지막 응답\n작업 대상: missing.json\n")
        assertTrue(detail.contains("마지막 응답"))
        assertTrue(detail.contains("missing.json"))
        assertFalse(detail.contains("초기화 진행"))
    }

    @Test fun cleanupCannotDisplaceTheSpecificFailure() {
        val detail = operationErrorSummary("[BACKUP_CREATE_FAILED]\n백업 생성에 실패했습니다.\n" +
            "zip I/O error: No space left on device\n" + "정리 중\n".repeat(100) + "작업 종료 · 상태=실패\n")
        assertTrue(detail.contains("No space left on device"))
        assertFalse(detail.contains("정리 중"))
    }

    @Test fun backupSpecificSummaryStillKeepsItsCodeAndZipCause() {
        val raw = "[BACKUP_CREATE_FAILED]\n백업 생성에 실패했습니다.\ninitializing\n" +
            "zip I/O error: No space left on device\n작업 종료 · 상태=실패"
        val detail = backupFailureDetail(operationErrorSummary(raw), "zip_exit_code=14\nzip I/O error: No space left on device")
        assertTrue(detail.startsWith("[BACKUP_CREATE_FAILED]"))
        assertTrue(detail.contains("zip_exit_code=14"))
        assertTrue(detail.contains("No space left on device"))
    }

    @Test fun terminalColorCodesCannotObscureTheCodeOrSecretRedaction() {
        val detail = operationErrorSummary("\u001B[31m[NPM_RUNTIME_FAILED]\u001B[0m\nerror token=secret-value")
        assertTrue(detail.startsWith("[NPM_RUNTIME_FAILED]"))
        assertFalse(detail.contains("\u001B"))
        assertFalse(detail.contains("secret-value"))
    }

    @Test fun emptyMessagesHaveAUsefulFallback() {
        listOf(null, "", " \n\t").forEach {
            assertEquals("알 수 없는 오류가 발생했습니다.", operationErrorSummary(it))
        }
    }
}
