package app.tavernbridge.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    private fun savedResult(operation: String, succeeded: Boolean = true) = OperationResultSummary(
        operation = operation,
        title = operation,
        detail = "saved result",
        succeeded = succeeded,
        completedAt = "2026-10-04 12:00:00",
        completedAtMillis = 1L,
        durationSeconds = 1L,
    )

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

    @Test fun onlyExplicitPrerequisiteFailuresRequireUserAction() {
        val actionable = setOf(TermuxSetupStatus.TERMUX_MISSING, TermuxSetupStatus.PERMISSION_REQUIRED,
            TermuxSetupStatus.EXTERNAL_APPS_DISABLED)
        TermuxSetupStatus.entries.forEach { status ->
            assertEquals(status in actionable, TermuxSetupState(status).requiresUserAction)
        }
    }

    @Test fun coldStartAccessCheckStaysHomeUntilInstallationIsInspected() {
        val checking = LauncherUiState().beginTermuxSetupCheck(connected)
        assertEquals(MainSection.HOME, checking.section)
        assertFalse(checking.environmentChecked)
        assertFalse(checking.environment.sillyTavernInstalled)
        assertTrue(checking.environment.termuxInstalled)
        assertTrue(checking.environment.commandPermissionGranted)
        assertEquals(TermuxSetupStatus.CHECKING, checking.termuxSetup.status)
        assertFalse(checking.isWorking)
        assertEquals("", checking.workingLabel)
        assertNull(checking.workProgress)

        val checked = checking.finishTermuxSetupCheck(connected, ready)
        assertEquals(MainSection.HOME, checked.section)
        assertFalse(checked.environmentChecked)
    }

    @Test fun existingVerifiedCheckPreservesScreenAndInstallationKnowledge() {
        val checking = LauncherUiState(
            environment = connected,
            environmentChecked = true,
            section = MainSection.LOGS,
            termuxSetup = ready,
            workProgress = WorkProgress(50, "old phase", "old detail"),
        ).beginTermuxSetupCheck(connected.copy(termuxBatteryUnrestricted = true))
        assertTrue(checking.environmentChecked)
        assertEquals(MainSection.LOGS, checking.section)
        assertEquals(ready, checking.termuxSetup)
        assertTrue(checking.environment.termuxBatteryUnrestricted)
        assertTrue(checking.environment.sillyTavernInstalled)
        assertEquals("existing-commit", checking.environment.commit)
        assertNull(checking.workProgress)
    }

    @Test fun revokedPermissionCannotRetainReadyDuringCheck() {
        val checking = LauncherUiState(environment = connected, termuxSetup = ready)
            .beginTermuxSetupCheck(connected.copy(commandPermissionGranted = false))
        assertFalse(checking.environment.commandPermissionGranted)
        assertEquals(TermuxSetupStatus.CHECKING, checking.termuxSetup.status)
    }

    @Test fun timeoutDoesNotCompleteInspectionOrRouteToFirstSetup() {
        val checking = LauncherUiState().beginTermuxSetupCheck(connected)
        val checked = checking.finishTermuxSetupCheck(connected,
            TermuxSetupState(TermuxSetupStatus.UNVERIFIED))
        assertFalse(checked.environmentChecked)
        assertFalse(checked.termuxSetup.requiresUserAction)
        assertEquals(MainSection.HOME, checked.section)
    }

    @Test fun failureBeforeCheckBeginsClearsInitialBusyStateForRetry() {
        val checked = LauncherUiState(workProgress = WorkProgress(0, "checking", "pending"))
            .finishTermuxSetupCheck(EnvironmentStatus(), TermuxSetupState(TermuxSetupStatus.UNVERIFIED))
        assertFalse(checked.isWorking)
        assertEquals("", checked.workingLabel)
        assertNull(checked.workProgress)
        assertFalse(checked.environmentChecked)
        assertEquals(MainSection.HOME, checked.section)
        val refreshGate = EnvironmentRefreshGate()
        assertTrue(refreshGate.tryStart(appInForeground = true, isWorking = true, refreshActive = false))
        assertTrue(refreshGate.tryStart(appInForeground = true, isWorking = checked.isWorking, refreshActive = false))
    }

    @Test fun uncertainRecheckPreservesExistingMetadataAndSelectedScreen() {
        val checked = LauncherUiState(
            environment = connected,
            environmentChecked = true,
            section = MainSection.SETTINGS,
            termuxSetup = ready,
            termuxWakeBlocked = true,
            error = "old error",
        ).beginTermuxSetupCheck(connected).finishTermuxSetupCheck(connected,
            TermuxSetupState(TermuxSetupStatus.UNVERIFIED))
        assertFalse(checked.environmentChecked)
        assertEquals(MainSection.SETTINGS, checked.section)
        assertTrue(checked.environment.sillyTavernInstalled)
        assertTrue(checked.environment.processRunning)
        assertEquals("existing-commit", checked.environment.commit)
        assertFalse(checked.environment.managerConnected)
        assertFalse(checked.termuxWakeBlocked)
        assertNull(checked.error)
    }

    @Test fun existingInstallationPrerequisiteFailuresStayOnReconnectionWithoutClaimingInspectionCompleted() {
        listOf(TermuxSetupStatus.TERMUX_MISSING, TermuxSetupStatus.PERMISSION_REQUIRED,
            TermuxSetupStatus.EXTERNAL_APPS_DISABLED).forEach { status ->
            val base = connected.copy(
                termuxInstalled = status != TermuxSetupStatus.TERMUX_MISSING,
                commandPermissionGranted = status != TermuxSetupStatus.PERMISSION_REQUIRED,
            )
            val checked = LauncherUiState(environment = connected, environmentChecked = true,
                termuxSetup = ready, section = MainSection.LOGS).beginTermuxSetupCheck(base)
                .finishTermuxSetupCheck(base, TermuxSetupState(status))
            assertTrue(checked.termuxSetup.requiresUserAction)
            assertFalse(checked.shouldShowSetupGuide)
            assertTrue(checked.hasInstallationHistory)
            assertTrue(checked.previousInstallationKnown)
            assertEquals(MainSection.LOGS, checked.section)
            assertFalse(checked.environmentChecked)
            assertTrue(checked.environment.sillyTavernInstalled)
            assertEquals("existing-commit", checked.environment.commit)
            assertFalse(checked.environment.managerConnected)
            assertFalse(checked.termuxSetup.verified)
        }
    }

    @Test fun newUserExplicitPrerequisiteFailuresStillShowSetupGuide() {
        listOf(TermuxSetupStatus.TERMUX_MISSING, TermuxSetupStatus.PERMISSION_REQUIRED,
            TermuxSetupStatus.EXTERNAL_APPS_DISABLED).forEach { status ->
            val base = EnvironmentStatus(
                termuxInstalled = status != TermuxSetupStatus.TERMUX_MISSING,
                commandPermissionGranted = status != TermuxSetupStatus.PERMISSION_REQUIRED,
            )
            val checked = LauncherUiState().beginTermuxSetupCheck(base)
                .finishTermuxSetupCheck(base, TermuxSetupState(status))
            assertTrue(checked.shouldShowSetupGuide)
            assertFalse(checked.hasInstallationHistory)
            assertEquals(MainSection.SETUP, checked.section)
            assertFalse(checked.environmentChecked)
            assertFalse(checked.environment.sillyTavernInstalled)
            assertFalse(checked.environment.managerConnected)
            assertFalse(checked.termuxSetup.verified)
        }
    }

    @Test fun persistedInstallationHintSelectsReconnectionWithoutAuthorizingCommands() {
        val checked = LauncherUiState(previousInstallationKnown = true)
            .beginTermuxSetupCheck(connected)
            .finishTermuxSetupCheck(connected, TermuxSetupState(TermuxSetupStatus.EXTERNAL_APPS_DISABLED))
        assertTrue(checked.hasInstallationHistory)
        assertFalse(checked.shouldShowSetupGuide)
        assertEquals(MainSection.HOME, checked.section)
        assertFalse(checked.environmentChecked)
        assertFalse(checked.environment.sillyTavernInstalled)
        assertFalse(checked.environment.managerConnected)
        assertFalse(checked.termuxSetup.verified)
        assertEquals(3, setupEligibleStep(checked.environment, checked.termuxSetup))
    }

    @Test fun legacySuccessfulStartSelectsReconnectionAndLatchesHistory() {
        val legacy = LauncherUiState(lastOperationResult = savedResult("start"))
        assertTrue(legacy.hasInstallationHistory)
        val checked = legacy.beginTermuxSetupCheck(connected)
            .finishTermuxSetupCheck(connected, TermuxSetupState(TermuxSetupStatus.EXTERNAL_APPS_DISABLED))
        assertTrue(checked.previousInstallationKnown)
        assertTrue(checked.hasInstallationHistory)
        assertFalse(checked.shouldShowSetupGuide)
        assertEquals(MainSection.HOME, checked.section)
        assertFalse(checked.environment.sillyTavernInstalled)
        assertFalse(checked.environment.managerConnected)
        assertFalse(checked.termuxSetup.verified)
    }

    @Test fun failedRefreshReplacingLegacySummaryDoesNotLoseLatchedInstallationHistory() {
        val failedCheck = LauncherUiState(lastOperationResult = savedResult("start"))
            .finishTermuxSetupCheck(connected, TermuxSetupState(TermuxSetupStatus.UNVERIFIED))
            .copy(lastOperationResult = savedResult("refresh", succeeded = false))
        assertFalse(failedCheck.lastOperationResult.indicatesExistingInstallation())
        val checked = failedCheck.beginTermuxSetupCheck(connected)
            .finishTermuxSetupCheck(connected, TermuxSetupState(TermuxSetupStatus.EXTERNAL_APPS_DISABLED))
        assertTrue(checked.previousInstallationKnown)
        assertTrue(checked.hasInstallationHistory)
        assertFalse(checked.shouldShowSetupGuide)
        assertEquals(MainSection.HOME, checked.section)
        assertFalse(checked.environmentChecked)
        assertFalse(checked.environment.sillyTavernInstalled)
        assertFalse(checked.environment.managerConnected)
        assertFalse(checked.termuxSetup.verified)
    }

    @Test fun failedOrUnrelatedLegacyOperationsDoNotFabricateInstallationHistory() {
        for (result in listOf(null, savedResult("start", succeeded = false),
            savedResult("refresh"), savedResult("connect-manager"))) {
            assertFalse(result.indicatesExistingInstallation())
            val checked = LauncherUiState(lastOperationResult = result)
                .finishTermuxSetupCheck(connected, TermuxSetupState(TermuxSetupStatus.EXTERNAL_APPS_DISABLED))
            assertFalse(checked.hasInstallationHistory)
            assertTrue(checked.shouldShowSetupGuide)
            assertFalse(checked.environment.managerConnected)
            assertFalse(checked.termuxSetup.verified)
        }
    }

    @Test fun successfulRetryOfExistingInstallationReturnsHomeFromGuide() {
        val waiting = LauncherUiState(environment = connected, environmentChecked = true, section = MainSection.SETUP)
            .finishTermuxSetupCheck(connected.copy(commandPermissionGranted = false),
                TermuxSetupState(TermuxSetupStatus.PERMISSION_REQUIRED))
        val readyToInspect = waiting.beginTermuxSetupCheck(connected).finishTermuxSetupCheck(connected, ready)
        assertFalse(readyToInspect.environmentChecked)
        assertEquals(MainSection.HOME, sectionAfterEnvironmentCheck(connected,
            firstCheck = !readyToInspect.environmentChecked,
            requestedSection = readyToInspect.section, currentSection = readyToInspect.section))
    }

    @Test fun confirmedAbsentInstallationGoesToSetup() {
        assertEquals(MainSection.SETUP, sectionAfterEnvironmentCheck(
            connected.copy(sillyTavernInstalled = false), firstCheck = true,
            requestedSection = MainSection.HOME, currentSection = MainSection.HOME))
    }

    @Test fun successfulManagementRefreshKeepsSelectedSection() {
        val checked = LauncherUiState(environment = connected, environmentChecked = true,
            termuxSetup = ready, section = MainSection.SETUP)
            .beginTermuxSetupCheck(connected).finishTermuxSetupCheck(connected, ready)
        assertTrue(checked.environmentChecked)
        assertEquals(MainSection.SETUP, sectionAfterEnvironmentCheck(connected,
            firstCheck = !checked.environmentChecked,
            requestedSection = checked.section, currentSection = checked.section))
    }

    @Test fun firstSuccessfulInspectionPreservesOtherRequestedScreens() {
        assertEquals(MainSection.LOGS, sectionAfterEnvironmentCheck(connected, firstCheck = true,
            requestedSection = MainSection.LOGS, currentSection = MainSection.SETUP))
    }
}
