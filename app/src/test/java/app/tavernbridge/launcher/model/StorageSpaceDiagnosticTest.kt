package app.tavernbridge.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageSpaceDiagnosticTest {
    @Test fun readFailureIsNotReportedAsZeroOrOutOfSpace() {
        fun item(raw: String) = storageDiagnosticItem("termux_storage", "Termux 저장 공간", raw)
        for (bad in listOf("", "-1", "invalid", "1.2e+10")) {
            assertEquals("미확인", item(bad).value)
            assertEquals(DiagnosticStatus.WARNING, item(bad).status)
        }
        assertEquals("0 B", item("0").value)
        assertEquals(DiagnosticStatus.ERROR, item("0").status)
        assertEquals(DiagnosticStatus.OK, item("126419751936").status)
    }

    @Test fun runningServerCanReadResetCountsButActiveWorkCannot() {
        val env = EnvironmentStatus(managerConnected = true, sillyTavernInstalled = true, processRunning = true)
        val live = LauncherUiState(isWorking = false, environment = env)
        assertTrue(live.canInspectResetImpact())
        assertFalse(live.copy(isWorking = true).canInspectResetImpact())
        assertFalse(live.copy(environment = env.copy(operationActive = true)).canInspectResetImpact())
        assertFalse(live.copy(environment = env.copy(managerConnected = false)).canInspectResetImpact())
    }
}
