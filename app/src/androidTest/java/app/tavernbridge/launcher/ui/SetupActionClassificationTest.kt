package app.tavernbridge.launcher.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.theme.SillyTavernLauncherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SetupActionClassificationTest {
    @get:Rule val ui = createComposeRule()
    private val prefs = DisplayPreferences(reducedMotion = true)
    private val browsers = listOf(BrowserOption("test.one", "첫 앱"), BrowserOption("test.two", "둘째 앱"))
    private val opened = mutableListOf<Pair<String, Boolean>>()

    private fun picker(options: List<BrowserOption>) {
        ui.setContent { SillyTavernLauncherTheme(AppTheme.LIGHT, prefs) {
            BrowserPickerSheet(options, onClose = {}) { pkg, always -> opened += pkg to always }
        } }
    }

    @Test fun browserRowOpensOnceWithoutSavingByDefault() {
        picker(browsers)
        ui.runOnIdle { assertEquals(emptyList<Pair<String, Boolean>>(), opened) }
        ui.onNodeWithText("둘째 앱").performClick()
        ui.onNodeWithText("첫 앱").performClick()
        ui.runOnIdle { assertEquals(listOf("test.two" to false), opened) }
    }

    @Test fun rememberSwitchDoesNotOpenUntilAnAppIsChosen() {
        picker(browsers)
        ui.onNodeWithText("항상 이 앱으로 열기").performClick()
        ui.runOnIdle { assertEquals(emptyList<Pair<String, Boolean>>(), opened) }
        ui.onNodeWithText("첫 앱").performClick()
        ui.runOnIdle { assertEquals(listOf("test.one" to true), opened) }
    }

    @Test fun noBrowserDoesNotPresentADeadChoice() {
        picker(emptyList())
        ui.onNodeWithText("이 주소를 열 수 있는 앱을 찾지 못했어요.").assertIsDisplayed()
        ui.onNodeWithText("항상 이 앱으로 열기").assertDoesNotExist()
    }

    @Test fun setupImportIsASeparatePageAndBackDoesNotRunInstall() {
        val actions = mutableListOf<String>()
        val state = LauncherUiState(isWorking = false,
            environment = EnvironmentStatus(termuxInstalled = true, commandPermissionGranted = true, managerConnected = true),
            termuxSetup = TermuxSetupState(TermuxSetupStatus.READY))
        ui.setContent { SillyTavernLauncherTheme(AppTheme.LIGHT, prefs) {
            SetupScreen(state, "", onDownloadTermux = {}, onOpenTermux = {}, onRequestPermission = {},
                onOpenPermissionSettings = {}, onVerifyTermux = { actions += "verify" },
                onConfirmFirstConnection = { actions += "connect" }, onConnect = { actions += "connect" },
                onSelectBranch = {}, onInstall = { actions += "install" }, onSwitchBranch = {}, onBackup = {},
                onCopied = {}, onPickInstallation = { actions += "pick" }, onInspectInstallation = { actions += "inspect" },
                onImportBackup = { actions += "zip" }, onLeave = { actions += "leave" })
        } }
        ui.onNodeWithText("다른 폴더의 설치 가져오기").performScrollTo().performClick()
        ui.onNodeWithText("설치 시작").assertDoesNotExist()
        ui.onNodeWithText("Termux 폴더 경로 입력").assertExists()
        ui.onNodeWithContentDescription("이전으로").performClick()
        ui.onNodeWithText("설치 시작").assertExists()
        ui.onNodeWithText("Termux 폴더 경로 입력").assertDoesNotExist()
        ui.runOnIdle { assertEquals(emptyList<String>(), actions) }
    }
}
