package app.tavernbridge.launcher.model

import app.tavernbridge.launcher.ui.tabAttention
import org.junit.Assert.*
import org.junit.Test

class DiagnosticAttentionTest {
    private fun row(id: String, status: DiagnosticStatus, value: String = "") = DiagnosticItem(id, id, value, status = status)
    private fun report(vararg rows: DiagnosticItem) = DiagnosticReport("today", rows.toList())
    private fun LauncherUiState.read() = copy(acknowledgedDiagnosticKeys = acknowledgedDiagnosticKeys + diagnosticReport!!.coveredKeys)

    @Test fun rereadOnlyAfterNewWorseOrRecurrentFinding() {
        val issue = row("termux_storage", DiagnosticStatus.WARNING, "860 MB")
        val first = LauncherUiState().withDiagnosticReport(report(issue))
        assertTrue(first.hasUnreadDiagnostics())
        val read = first.read().withDiagnosticReport(report(issue.copy(value = "864 MB")).copy(checkedAt = "tomorrow"))
        assertFalse(read.hasUnreadDiagnostics())
        assertEquals(1, read.diagnosticReport!!.warningCount)
        assertTrue(read.withDiagnosticReport(report(issue.copy(status = DiagnosticStatus.ERROR))).hasUnreadDiagnostics())
        assertTrue(read.withDiagnosticReport(report(issue, row("npm", DiagnosticStatus.ERROR))).hasUnreadDiagnostics())
        assertTrue(read.withDiagnosticReport(report(issue.copy(status = DiagnosticStatus.OK))).withDiagnosticReport(report(issue)).hasUnreadDiagnostics())
        // A limited Android-only report did not inspect this Termux item at all.
        assertFalse(read.withDiagnosticReport(report(row("battery", DiagnosticStatus.OK))).withDiagnosticReport(report(issue)).hasUnreadDiagnostics())
    }

    @Test fun acknowledgementDoesNotMuteOtherTabsOrRecoveryAndUpdate() {
        val issue = row("npm", DiagnosticStatus.ERROR)
        val read = LauncherUiState().withDiagnosticReport(report(issue)).read()
        assertFalse(MainSection.LOGS in read.tabAttention())
        assertTrue(MainSection.LOGS in read.copy(updateAvailable = true).tabAttention())
        val emergency = read.copy(environment = read.environment.copy(recoveryPending = true, portListening = true))
        assertTrue(MainSection.LOGS in emergency.tabAttention())
        assertTrue(MainSection.HOME in emergency.tabAttention())
        assertFalse(read.withDiagnosticReport(report(issue.copy(status = DiagnosticStatus.WARNING))).hasUnreadDiagnostics())
    }
}
