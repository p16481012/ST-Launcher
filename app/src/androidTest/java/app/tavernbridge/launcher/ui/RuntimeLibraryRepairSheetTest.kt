package app.tavernbridge.launcher.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.theme.SillyTavernLauncherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RuntimeLibraryRepairSheetTest {
    @get:Rule val ui = createComposeRule()
    private val at = 812L
    private val failed = OperationResultSummary("repair", "점검·복구", "libicui18n.so.79 not found", false,
        errorCode = "NODE_LINKER_FAILED", completedAt = "now", completedAtMillis = at, durationSeconds = 1,
        retryAction = RetryAction.REPAIR)
    private val state = mutableStateOf(LauncherUiState(section = MainSection.LOGS, toolPage = ToolPage.REPAIR,
        environment = EnvironmentStatus(termuxInstalled = true, commandPermissionGranted = true, managerConnected = true,
            sillyTavernInstalled = true), termuxSetup = TermuxSetupState(TermuxSetupStatus.READY), environmentChecked = true,
        isWorking = false, lastOperationResult = failed, runtimeSyncFailureAt = at))
    private val open = mutableStateOf(true)
    private val syncs = mutableListOf<Long>()
    private var retries = 0

    private fun show(keepOpenAfterSync: Boolean = false) {
        ui.setContent { SillyTavernLauncherTheme(AppTheme.LIGHT, DisplayPreferences(reducedMotion = true)) {
            if (open.value) RuntimeLibraryRepairSheet(state.value, at, onDismiss = { open.value = false },
                onRetry = { retries++; open.value = false }, onHistory = { open.value = false },
                onSync = { syncs += it; if (!keepOpenAfterSync) open.value = false })
        } }
    }

    @Test fun inspectionAndCancelNeverUpgradeAndOnlyExplicitConfirmationRunsOnce() {
        // Keep the sheet mounted while dispatch is pending to exercise an immediate second tap.
        show(keepOpenAfterSync = true)
        val option = ui.onNodeWithText("Termux 실행 도구 갱신하기")
        val confirm = ui.onNodeWithText("동의하고 갱신·재검사")
        confirm.assertDoesNotExist()
        option.assertIsEnabled().performClick()
        ui.onNodeWithText("Termux 패키지 전체를 갱신할까요?").assertIsDisplayed()
        captureModalWindowSnapshot("repair-runtime-consent")
        ui.runOnIdle { assertEquals(emptyList<Long>(), syncs); assertEquals(0, retries) }
        ui.onNodeWithText("취소").performClick()
        confirm.assertDoesNotExist()
        option.assertIsDisplayed()
        ui.runOnIdle { assertEquals(emptyList<Long>(), syncs) }
        option.performClick()
        confirm.assertIsEnabled().performClick()
        confirm.assertDoesNotExist()
        ui.onNodeWithText("Termux 패키지 갱신을 요청했어요.").assertIsDisplayed()
        ui.runOnIdle { assertEquals(listOf(at), syncs); assertEquals(0, retries) }
    }

    @Test fun dismissingTheConfirmationClosesTheSheetWithoutDispatching() {
        show()
        ui.onNodeWithText("Termux 실행 도구 갱신하기").performClick()
        ui.onNodeWithContentDescription("시트 닫기").performClick()
        ui.onNodeWithText("동의하고 갱신·재검사").assertDoesNotExist()
        ui.onNodeWithText("Termux 실행 도구 갱신하기").assertDoesNotExist()
        ui.runOnIdle { assertEquals(emptyList<Long>(), syncs); assertEquals(0, retries) }
    }

    @Test fun ordinaryRetryNeverEnablesThePackageUpgradeMode() {
        show()
        ui.onNodeWithText("전체 갱신 없이 다시 시도").performClick()
        ui.onNodeWithText("동의하고 갱신·재검사").assertDoesNotExist()
        ui.runOnIdle { assertEquals(1, retries); assertEquals(emptyList<Long>(), syncs) }
    }

    @Test fun unrelatedResultsCannotOfferOrInheritPendingConsent() {
        state.value = state.value.copy(lastOperationResult = failed.copy(errorCode = "NODE_RUNTIME_FAILED"))
        show()
        val option = ui.onNodeWithText("Termux 실행 도구 갱신하기")
        val confirm = ui.onNodeWithText("동의하고 갱신·재검사")
        option.assertDoesNotExist()
        ui.runOnIdle { state.value = state.value.copy(lastOperationResult = failed) }
        option.performClick()
        confirm.assertIsDisplayed()
        ui.runOnIdle { state.value = state.value.copy(lastOperationResult = failed.copy(operation = "backup")) }
        confirm.assertDoesNotExist()
        option.assertDoesNotExist()
        ui.runOnIdle { assertEquals(emptyList<Long>(), syncs); assertEquals(0, retries) }
    }

    @Test fun technicalDetailsAreOptionalAndDoNotRunRepair() {
        show()
        ui.onNodeWithText(failed.detail).assertDoesNotExist()
        ui.onNodeWithText(failed.errorCode).assertDoesNotExist()
        ui.onNodeWithText("기술 정보 보기").performClick()
        ui.onNodeWithText(failed.detail).assertIsDisplayed()
        ui.onNodeWithText(failed.errorCode).assertIsDisplayed()
        ui.onNodeWithText("기술 정보 접기").performClick()
        ui.onNodeWithText(failed.detail).assertDoesNotExist()
        ui.runOnIdle { assertEquals(emptyList<Long>(), syncs); assertEquals(0, retries) }
    }
}
