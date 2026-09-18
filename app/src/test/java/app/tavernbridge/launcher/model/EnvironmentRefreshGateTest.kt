package app.tavernbridge.launcher.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentRefreshGateTest {
    @Test
    fun coldStartCanEnterRefreshWithActualDefaultBusyState() {
        val state = LauncherUiState()
        val gate = EnvironmentRefreshGate()

        assertTrue(state.isWorking)
        assertFalse(state.environmentChecked)
        assertTrue(gate.tryStart(appInForeground = true, isWorking = state.isWorking, refreshActive = false))
    }

    @Test
    fun backgroundAttemptsDoNotConsumeFirstForegroundPermission() {
        val state = LauncherUiState()
        val gate = EnvironmentRefreshGate()

        repeat(3) {
            assertFalse(gate.tryStart(appInForeground = false, isWorking = state.isWorking, refreshActive = false))
        }
        assertTrue(gate.tryStart(appInForeground = true, isWorking = state.isWorking, refreshActive = false))
    }

    @Test
    fun firstAcceptedCallImmediatelyBlocksDuplicateBeforeRefreshJobBecomesActive() {
        val state = LauncherUiState()
        val gate = EnvironmentRefreshGate()

        assertTrue(gate.tryStart(appInForeground = true, isWorking = state.isWorking, refreshActive = false))
        repeat(3) {
            assertFalse(gate.tryStart(appInForeground = true, isWorking = state.isWorking, refreshActive = false))
        }
    }

    @Test
    fun activeRefreshCanBeReplacedWithoutReopeningInitialPermission() {
        val state = LauncherUiState()
        val gate = EnvironmentRefreshGate()

        assertTrue(gate.tryStart(appInForeground = true, isWorking = state.isWorking, refreshActive = false))
        assertTrue(gate.tryStart(appInForeground = true, isWorking = state.isWorking, refreshActive = true))
        assertTrue(gate.tryStart(appInForeground = true, isWorking = state.isWorking, refreshActive = true))
        assertFalse(gate.tryStart(appInForeground = true, isWorking = state.isWorking, refreshActive = false))
    }

    @Test
    fun busyBackupAndOtherNonRefreshWorkStayProtectedAfterInitialization() {
        val gate = EnvironmentRefreshGate()
        val initial = LauncherUiState()
        assertTrue(gate.tryStart(appInForeground = true, isWorking = initial.isWorking, refreshActive = false))

        listOf("백업 생성 중", "ZIP에서 바로 복원 중", "SillyTavern 시작 중").forEach { label ->
            val working = initial.copy(environmentChecked = true, isWorking = true, workingLabel = label)
            assertFalse(gate.tryStart(appInForeground = true, isWorking = working.isWorking, refreshActive = false))
        }
    }

    @Test
    fun idleRetryAfterFailedInitialRefreshIsAllowed() {
        val gate = EnvironmentRefreshGate()
        val initial = LauncherUiState()
        assertTrue(gate.tryStart(appInForeground = true, isWorking = initial.isWorking, refreshActive = false))

        val failed = initial.copy(isWorking = false, workingLabel = "", error = "환경 확인 실패")
        assertFalse(failed.environmentChecked)
        assertTrue(gate.tryStart(appInForeground = true, isWorking = failed.isWorking, refreshActive = false))
        assertFalse(gate.tryStart(appInForeground = true, isWorking = true, refreshActive = false))
    }

    @Test
    fun newGateRestoresExactlyOneColdStartPermissionAfterProcessRecreation() {
        val initial = LauncherUiState()
        val previousProcess = EnvironmentRefreshGate()
        assertTrue(previousProcess.tryStart(appInForeground = true, isWorking = initial.isWorking, refreshActive = false))
        assertFalse(previousProcess.tryStart(appInForeground = true, isWorking = initial.isWorking, refreshActive = false))

        val recreatedProcess = EnvironmentRefreshGate()
        assertTrue(recreatedProcess.tryStart(appInForeground = true, isWorking = initial.isWorking, refreshActive = false))
        assertFalse(recreatedProcess.tryStart(appInForeground = true, isWorking = initial.isWorking, refreshActive = false))
    }

    @Test
    fun firstIdleAcceptanceAlsoConsumesInitialPermission() {
        val gate = EnvironmentRefreshGate()
        val idle = LauncherUiState(isWorking = false)

        assertTrue(gate.tryStart(appInForeground = true, isWorking = idle.isWorking, refreshActive = false))
        assertFalse(gate.tryStart(appInForeground = true, isWorking = true, refreshActive = false))
        assertTrue(gate.tryStart(appInForeground = true, isWorking = idle.isWorking, refreshActive = false))
    }

    @Test
    fun backgroundAlwaysRejectsEvenWhenIdleOrRefreshIsActive() {
        val gate = EnvironmentRefreshGate()

        assertFalse(gate.tryStart(appInForeground = false, isWorking = false, refreshActive = false))
        assertFalse(gate.tryStart(appInForeground = false, isWorking = true, refreshActive = true))
        assertTrue(gate.tryStart(appInForeground = true, isWorking = LauncherUiState().isWorking, refreshActive = false))
        assertFalse(gate.tryStart(appInForeground = false, isWorking = false, refreshActive = true))
    }
}
