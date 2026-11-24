package app.tavernbridge.launcher.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.theme.SillyTavernLauncherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real Compose layouts on device. Fixtures cannot call Termux, use the network, or alter user preferences. */
@RunWith(AndroidJUnit4::class)
class V2VisualTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val wide get() = context.resources.configuration.screenWidthDp >= 720
    private val prefs = DisplayPreferences(reducedMotion = true)
    private val fixture = LauncherUiState(isWorking = false, environmentChecked = true, displayPreferences = prefs,
        environment = EnvironmentStatus(termuxInstalled = true, commandPermissionGranted = true, managerConnected = true,
            sillyTavernInstalled = true, branch = SillyBranch.RELEASE, sillyTavernVersion = "1.13.2",
            processRunning = true, serverReachable = true), backupReminder = BackupReminderState(enabled = false))

    private fun capture(name: String) {
        compose.waitForIdle()
        val output = File(context.filesDir, "v2-visual").also { check(it.isDirectory || it.mkdirs()) }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
        File(output, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test fun homeUsesAppropriateLayoutAndReadyAction() {
        var opens = 0
        compose.setContent { SillyTavernLauncherTheme(AppTheme.LIGHT, prefs) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                V2HomeDashboard(fixture, onOpen = { opens++ }, onStart = {}, onStop = {}, onRestart = {}, onBackups = {},
                    onUpdate = {}, onLogs = {}, onHistory = {}, onReadHistory = {}, onRefresh = {}, onRetry = {}, onSnooze = {})
            }
        } }
        compose.onNodeWithText("대화 열기").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, opens) }
        if (wide) compose.onNodeWithText("최근 활동").assertExists()
        capture("home")
    }

    @Test fun setupDoesNotProbeUntilExplicitlyRequested() {
        var probes = 0
        compose.setContent { SillyTavernLauncherTheme(AppTheme.LIGHT, prefs) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                SetupScreen(fixture.copy(environment = EnvironmentStatus(termuxInstalled = true, commandPermissionGranted = true)),
                    setupCommand = "준비 명령 미리보기", onDownloadTermux = {}, onOpenTermux = {}, onRequestPermission = {},
                    onOpenPermissionSettings = {}, onVerifyTermux = { probes++ }, onConfirmFirstConnection = { probes++ },
                    onConnect = { probes++ }, onSelectBranch = {}, onInstall = {}, onSwitchBranch = {}, onBackup = {}, onCopied = {},
                    onPickInstallation = {}, onInspectInstallation = {}, onImportBackup = {}, onLeave = {})
            }
        } }
        compose.runOnIdle { assertEquals(0, probes) }
        capture("setup-step-3")
        compose.onNodeWithText("실행했어요 · 연결 확인").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, probes) }
    }

    @Test fun settingsUsesMasterDetailOrStackAndCanChangeSize() {
        var shown = prefs
        compose.setContent {
            var display by remember { mutableStateOf(prefs) }
            SillyTavernLauncherTheme(AppTheme.LIGHT, display) {
                val state = fixture.copy(displayPreferences = display)
                V2SettingsLayout(state) { page, roomy ->
                    check(page == SettingsPage.DISPLAY)
                    V2DisplaySettings(state, {}, { display = it; shown = it }, roomy)
                }
            }
        }
        compose.onNodeWithText("설정").assertIsDisplayed()
        if (!wide) {
            compose.onNodeWithText("글자 크기").assertDoesNotExist()
            capture("settings-index")
            compose.onNodeWithText("화면").performClick()
        }
        compose.onNodeWithText("글자 크기").assertIsDisplayed()
        capture("settings-display")
        compose.onNodeWithText("크게").performClick()
        compose.runOnIdle { assertEquals(LauncherTextSize.LARGE, shown.textSize) }
        if (!wide) compose.onNodeWithContentDescription("설정 목록으로").performClick()
        compose.onNodeWithText("설정").assertIsDisplayed()
    }
}
