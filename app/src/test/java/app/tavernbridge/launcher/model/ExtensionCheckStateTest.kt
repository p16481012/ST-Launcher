package app.tavernbridge.launcher.model

import org.junit.Assert.*
import org.junit.Test

class ExtensionCheckStateTest {
    private val item = ManagedExtension("x", "local", "Example", "1.0", "user", false, "clean",
        "a".repeat(40), true, "", "owner/example", "main")

    @Test fun noUnverifiedEntryAppearsCurrentAndFailedChecksAreVisible() {
        assertEquals("미확인 1개", extensionCheckSummary(listOf(item)))
        assertEquals("원격과 동일 1개 · 미확인 1개", extensionCheckSummary(listOf(item.copy(remoteAhead = 0),
            item.copy(checkIssue = ExtensionCheckIssue.RATE_LIMIT))))
        assertEquals("GitHub 요청 제한", item.copy(checkIssue = ExtensionCheckIssue.RATE_LIMIT).checkLabel())
        assertEquals("업데이트 1개", extensionCheckSummary(listOf(item.copy(remoteAhead = 2, remoteVersion = "1.0"))))
    }

    @Test fun changingLocalShaRepositoryOrBranchInvalidatesPreviousCheck() {
        assertTrue(item.sameCheckTarget(item.copy(remoteAhead = 0)))
        assertFalse(item.sameCheckTarget(item.copy(commit = "c".repeat(40))))
        assertFalse(item.sameCheckTarget(item.copy(gitBranch = "dev")))
        assertFalse(item.sameCheckTarget(item.copy(gitHubRepository = "owner/fork")))
    }

    @Test fun noChangeIsNotReportedAsAnUpdateAndMissingOrAmbiguousReceiptsStayUnknown() {
        assertEquals("already-current", extensionUpdateReceipt("extension_result=already-current\nextra=1\n"))
        assertEquals("updated", extensionUpdateReceipt("progress\nextension_result=updated\n"))
        assertNull(extensionUpdateReceipt("exit_code=0"))
        assertNull(extensionUpdateReceipt("extension_result=already-current\nextension_result=updated"))
    }
}
