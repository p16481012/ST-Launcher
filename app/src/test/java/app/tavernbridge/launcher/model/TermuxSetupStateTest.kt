package app.tavernbridge.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxSetupStateTest {
    private val connected = EnvironmentStatus(
        termuxInstalled = true,
        commandPermissionGranted = true,
        managerConnected = true,
        sillyTavernInstalled = true,
        processRunning = true,
        commit = "existing-commit",
    )
    private val ready = TermuxSetupState(TermuxSetupStatus.READY)

    @Test fun missingTermuxCannotBeSkippedByCachedCompletion() {
        assertEquals(1, setupEligibleStep(connected.copy(termuxInstalled = false), ready))
    }

    @Test fun permissionMustBeGrantedBeforeCommandInstructions() {
        assertEquals(2, setupEligibleStep(connected.copy(commandPermissionGranted = false), ready))
    }

    @Test fun permissionAndManagerHistoryDoNotProveCurrentAccess() {
        TermuxSetupStatus.entries.filter { it != TermuxSetupStatus.READY }.forEach {
            val unverified = TermuxSetupState(it)
            assertFalse(unverified.verified)
            assertEquals(3, setupEligibleStep(connected, unverified))
        }
    }

    @Test fun verifiedTermuxWithoutManagerGoesToConnectionStep() {
        assertEquals(4, setupEligibleStep(connected.copy(managerConnected = false), ready))
    }

    @Test fun onlySuccessfulCurrentConnectionUnlocksInstallation() {
        assertTrue(ready.verified)
        assertEquals(5, setupEligibleStep(connected, ready))
    }

    @Test fun revokedPermissionLocksManagerWithoutErasingKnownInstallation() {
        val updated = connected.withTermuxSetup(
            EnvironmentStatus(termuxInstalled = true),
            TermuxSetupState(TermuxSetupStatus.PERMISSION_REQUIRED),
        )
        assertFalse(updated.commandPermissionGranted)
        assertFalse(updated.managerConnected)
        assertTrue(updated.sillyTavernInstalled)
        assertTrue(updated.processRunning)
        assertEquals("existing-commit", updated.commit)
        assertEquals(2, setupEligibleStep(updated, ready))
    }

    @Test fun timeoutDoesNotEraseInstallationOrImplyDisabledSetting() {
        val check = TermuxSetupState(TermuxSetupStatus.UNVERIFIED, "아직 연결을 확인하지 못했어요.")
        val updated = connected.withTermuxSetup(connected, check)
        assertFalse(updated.managerConnected)
        assertTrue(updated.sillyTavernInstalled)
        assertEquals(3, setupEligibleStep(updated, check))
        assertEquals(TermuxSetupStatus.UNVERIFIED, check.status)
    }

    @Test fun checkingDoesNotRetainStalePermissionForStepEligibility() {
        assertEquals(2, setupEligibleStep(connected.copy(commandPermissionGranted = false),
            TermuxSetupState(TermuxSetupStatus.CHECKING)))
    }

    @Test fun permissionRevokedDuringSuccessfulProbeStillLocksManager() {
        val updated = connected.withTermuxSetup(connected.copy(commandPermissionGranted = false), ready)
        assertFalse(updated.managerConnected)
        assertEquals(2, setupEligibleStep(updated, ready))
    }

    @Test fun coldStartNeverRestoresVerifiedConnectionFromPreferences() {
        val state = LauncherUiState()
        assertFalse(state.termuxSetup.verified)
        assertEquals(TermuxSetupStatus.UNKNOWN, state.termuxSetup.status)
        assertEquals(1, setupEligibleStep(state.environment, state.termuxSetup))
    }
}
