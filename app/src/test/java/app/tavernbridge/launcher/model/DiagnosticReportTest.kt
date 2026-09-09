package app.tavernbridge.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportTest {
    @Test
    fun `copied report masks every free text field without truncating the report`() {
        val report = DiagnosticReport(
            checkedAt = "now",
            items = listOf(
                DiagnosticItem("first", "ordinary", "ok", "normal detail\n".repeat(100), DiagnosticStatus.OK),
                DiagnosticItem("last", "network", "Authorization: Basic dXNlcjpleGFtcGxl", status = DiagnosticStatus.WARNING),
            ),
            copyOnlyDetails = mapOf("support" to "api_key_mistral: privateSecret"),
        )
        val copied = report.copyText()
        assertTrue(copied.length > 600)
        assertTrue(copied.contains("network"))
        assertFalse(copied.contains("dXNlcjpleGFtcGxl"))
        assertFalse(copied.contains("privateSecret"))
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
