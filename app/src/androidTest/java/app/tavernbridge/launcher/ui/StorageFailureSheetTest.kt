package app.tavernbridge.launcher.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.theme.SillyTavernLauncherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StorageFailureSheetTest {
    @get:Rule val ui = createComposeRule()
    private val calls = mutableListOf<String>()
    private val failure = OperationResultSummary("backup", "백업 생성", "disk full", false,
        "BACKUP_NO_SPACE", "now", 10, 1, RetryAction.BACKUP)
    private fun show(advice: StorageFailureAdvice, pending: Boolean = false, canOpenCleanup: Boolean = true) {
        ui.setContent { SillyTavernLauncherTheme(AppTheme.LIGHT, DisplayPreferences(reducedMotion = true)) {
            StorageFailureSheet(failure, advice, pending, canOpenCleanup, onClose = { calls += "close" },
                onStorageCleanup = { calls += "cleanup" },
                onDeviceStorage = { calls += "settings" }, onRetry = { calls += "retry" }, onHistory = { calls += "history" })
        } }
    }

    @Test fun downloadFailureOpensSpaceCleanupWithoutRetryOrDeletingAnything() {
        show(StorageFailureAdvice(SpaceLocation.DOWNLOAD, 200, 30))
        ui.onNodeWithText("검사 당시 필요 200 B · 여유 30 B").assertIsDisplayed()
        ui.onNodeWithText(failure.errorCode).assertDoesNotExist()
        ui.runOnIdle { assertEquals(emptyList<String>(), calls) }
        ui.onNodeWithText("공간 정리").performClick()
        ui.runOnIdle { assertEquals(listOf("cleanup"), calls) }
    }

    @Test fun unknownCountsAndProtectedRecoveryNeverOfferCleanupOnRecoveryData() {
        show(StorageFailureAdvice(SpaceLocation.PRIVATE_SAFETY), pending = true)
        ui.onNodeWithText("실패 시점의 필요 용량과 남은 용량은 확인되지 않았어요.").assertIsDisplayed()
        ui.onNodeWithText("공간 정리").assertDoesNotExist()
        ui.onNodeWithText("휴대폰 저장 공간 열기").performClick()
        ui.onNodeWithText("작업 기록 보기").performClick()
        ui.onNodeWithText("다시 시도").performClick()
        ui.runOnIdle { assertEquals(listOf("settings", "history", "retry"), calls) }
    }

    @Test fun downloadFailureWhenBackupTabCannotOpenOffersOnlyTheDeviceStorageShortcut() {
        show(StorageFailureAdvice(SpaceLocation.DOWNLOAD), canOpenCleanup = false)
        ui.onNodeWithText("공간 정리").assertDoesNotExist()
        ui.onNodeWithText("휴대폰 저장 공간 열기").performClick()
        ui.runOnIdle { assertEquals(listOf("settings"), calls) }
    }
}
