package app.tavernbridge.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportTest {
    @Test fun credentialsAreHiddenBeforeRenderingReportDetails() {
        val source = DiagnosticItem("mirror", "미러", "https://user:password@example.test/a",
            detail = "Authorization: Bearer example-secret-value", status = DiagnosticStatus.WARNING)
        val visible = source.safeForDisplay()
        assertTrue(!visible.value.contains("password") && !visible.detail.contains("example-secret-value"))
        assertEquals(DiagnosticStatus.WARNING, visible.status)
    }

    @Test fun settingsReportOnlyRunsExternalChecksAfterAllPrerequisites() {
        val connected = LauncherUiState(termuxSetup = TermuxSetupState(TermuxSetupStatus.READY),
            environment = EnvironmentStatus(termuxInstalled = true, commandPermissionGranted = true, managerConnected = true))
        assertTrue(connected.readyForTermuxDiagnostic())
        assertFalse(connected.copy(termuxSetup = TermuxSetupState()).readyForTermuxDiagnostic())
        assertFalse(connected.copy(environment = connected.environment.copy(commandPermissionGranted = false)).readyForTermuxDiagnostic())
        assertFalse(connected.copy(environment = connected.environment.copy(termuxInstalled = false)).readyForTermuxDiagnostic())
        assertFalse(connected.copy(environment = connected.environment.copy(managerConnected = false)).readyForTermuxDiagnostic())
    }

    private fun item(id: String, status: DiagnosticStatus) = DiagnosticItem(id, id, id, status = status)

    @Test fun groupRowsAndCopiedSummaryCountActualResultsNotMetadata() {
        val report = DiagnosticReport("today", listOf(item("version", DiagnosticStatus.INFO),
            item("first", DiagnosticStatus.OK), item("error", DiagnosticStatus.ERROR),
            item("skipped", DiagnosticStatus.INFO), item("warning", DiagnosticStatus.WARNING),
            item("second", DiagnosticStatus.OK)), mapOf("build" to "5"))
        assertEquals(4, report.checkedCount)
        assertEquals(listOf("first", "second"), report.rows(DiagnosticStatus.OK).map { it.id })
        assertEquals(listOf("version", "skipped"), report.rows(DiagnosticStatus.INFO).map { it.id })
        assertEquals(2, report.infoCount)
        assertEquals("확인 필요", report.indexLabel)
        assertTrue(report.copyText().contains("검사 결과 4개 · 정상 2개 · 확인 필요 1개 · 오류 1개"))
        assertTrue(report.copyText().contains("참고 2개 (검사 수에서 제외)"))
    }

    @Test fun partialAndInfoOnlyReportsNeverInventPassedChecks() {
        val metadata = DiagnosticReport("now", listOf(item("version", DiagnosticStatus.INFO)))
        val partial = metadata.copy(items = metadata.items + item("missing_permission", DiagnosticStatus.ERROR))
        assertEquals(0, metadata.checkedCount)
        assertEquals("판정 없음", metadata.indexLabel)
        assertEquals("판정한 검사 없음", metadata.copy(items = emptyList()).checkTitle)
        assertEquals("검사 결과 1개", partial.checkTitle)
        assertEquals("정상", metadata.copy(items = metadata.items + item("ok", DiagnosticStatus.OK)).indexLabel)
    }

    @Test
    fun `copied report masks every free text field without truncating the report`() {
        val report = DiagnosticReport(
            checkedAt = "now",
            items = listOf(
                DiagnosticItem("first", "ordinary", "ok", "normal detail\n".repeat(100), DiagnosticStatus.OK),
                DiagnosticItem("last", "network", "Authorization: Basic dXNlcjpleGFtcGxl", status = DiagnosticStatus.WARNING),
            ),
            copyOnlyDetails = mapOf("support" to "api_key_mistral: privateSecret\ncustom_openai_api_key=fixture-private\nsilly_tavern_secret=fixture-private-again"),
        )
        val copied = report.copyText()
        assertTrue(copied.length > 600)
        assertTrue(copied.contains("network"))
        assertFalse(copied.contains("dXNlcjpleGFtcGxl"))
        assertFalse(copied.contains("privateSecret"))
        assertFalse(copied.contains("fixture-private"))
    }

    @Test
    fun `copy-only details are excluded from visible items but included in copied text`() {
        val report = DiagnosticReport(
            checkedAt = "2026-08-07 12:00:00",
            items = listOf(
                DiagnosticItem(
                    id = "launcher_version",
                    title = "런처 버전",
                    value = "0.1.0",
                    status = DiagnosticStatus.INFO,
                ),
            ),
            copyOnlyDetails = linkedMapOf(
                "빌드 코드" to "40",
                "관리 스크립트" to "5",
            ),
        )

        assertEquals(listOf("0.1.0"), report.items.map { it.value })
        assertTrue(report.copyText().contains("빌드 코드: 40"))
        assertTrue(report.copyText().contains("관리 스크립트: 5"))
    }
}
