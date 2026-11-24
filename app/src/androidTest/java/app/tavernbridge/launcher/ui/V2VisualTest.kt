package app.tavernbridge.launcher.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
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
import kotlinx.coroutines.launch

/** Real Compose layouts on device. Fixtures cannot call Termux, use the network, or alter user preferences. */
@RunWith(AndroidJUnit4::class)
class V2VisualTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val wide get() = context.resources.configuration.screenWidthDp >= 840
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

    @Composable private fun ScreenChrome(section: MainSection, content: @Composable () -> Unit) {
        BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (chooseLauncherNavigation(maxWidth.value) == LauncherNavigationMode.RAIL) {
                Row(Modifier.fillMaxSize()) {
                    LauncherNavigationRail(section, {}, { it.icon() })
                    Box(Modifier.weight(1f).fillMaxHeight()) { content() }
                }
            } else Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                V2TabBar(section) {}
            }
        }
    }

    @Test fun homeUsesAppropriateLayoutAndReadyAction() {
        var opens = 0
        compose.setContent { SillyTavernLauncherTheme(AppTheme.LIGHT, prefs) {
            ScreenChrome(MainSection.HOME) {
                V2HomeDashboard(fixture, onOpen = { opens++ }, onStart = {}, onStop = {}, onRestart = {}, onBackups = {},
                    onUpdate = {}, onLogs = {}, onHistory = {}, onReadHistory = {}, onRefresh = {}, onRetry = {}, onSnooze = {}, onOpenArchive = {})
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
                ScreenChrome(MainSection.SETTINGS) {
                    V2SettingsLayout(state) { page, roomy ->
                        check(page == SettingsPage.DISPLAY)
                        V2DisplaySettings(state, {}, { display = it; shown = it }, roomy)
                    }
                }
            }
        }
        compose.onAllNodesWithText("설정").onFirst().assertIsDisplayed()
        if (!wide) {
            compose.onNodeWithText("글자 크기").assertDoesNotExist()
            capture("settings-index")
            compose.onNodeWithText("화면").performClick()
        }
        compose.onNodeWithText("글자 크기").assertIsDisplayed()
        capture("settings-display")
        compose.onNodeWithText("크게").performClick()
        compose.runOnIdle { assertEquals(LauncherTextSize.LARGE, shown.textSize) }
        if (!wide) compose.onNodeWithContentDescription("뒤로").performClick()
        compose.onAllNodesWithText("설정").onFirst().assertIsDisplayed()
    }

    @Test fun displayChoicesSurviveAStoreReopen() {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val store = app.tavernbridge.launcher.data.ThemePreferences(testContext)
        val saved = prefs.copy(textSize = LauncherTextSize.LARGE, accent = LauncherAccent.WINE, highContrast = true)
        store.saveTheme(AppTheme.DARK)
        store.saveDisplay(saved)
        val reopened = app.tavernbridge.launcher.data.ThemePreferences(testContext)
        assertEquals(AppTheme.DARK, reopened.loadTheme())
        assertEquals(saved, reopened.loadDisplay())
    }

    @Test fun tappingExternalAccessWhileServerRunsExplainsWithoutChangingIt() {
        compose.setContent { SillyTavernLauncherTheme(AppTheme.LIGHT, prefs) {
            V2HomeDashboard(fixture, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("외부 접속").performScrollTo().performClick()
        compose.onNodeWithText("실행 중인 서버가 잠시 멈췄다가 다시 시작해요.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("확인").performClick()
        compose.onNodeWithContentDescription("외부 접속 설명").performClick()
        compose.onNodeWithText("현재 외부 접속은 꺼져 있어요.", substring = true).assertIsDisplayed()
    }

    @Test fun noticeReplacesActionsAndDismissDoesNotUndo() {
        lateinit var host: androidx.compose.material3.SnackbarHostState
        var undo = 0
        compose.setContent {
            host = remember { androidx.compose.material3.SnackbarHostState() }
            val scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalV2NoticeHost provides host) {
                Column {
                    V2BottomActions {
                        androidx.compose.material3.Button({ scope.launch {
                            if (showV2Notice(host, "휴지통으로 옮겼어요.", "실행 취소") == androidx.compose.material3.SnackbarResult.ActionPerformed) undo++
                        } }) { androidx.compose.material3.Text("휴지통") }
                    }
                    V2NoticeHost(host)
                }
            }
        }
        compose.onNodeWithText("휴지통").performClick()
        compose.onNodeWithText("휴지통").assertDoesNotExist()
        compose.onNodeWithText("닫기").performClick()
        compose.onNodeWithText("휴지통").assertIsDisplayed().performClick()
        compose.onNodeWithText("실행 취소").performClick()
        compose.runOnIdle { assertEquals(1, undo) }
    }

    @Test fun recentBackupShortcutOpensTheChosenArchiveAndReturnsHome() {
        val first = BackupArchive("new.zip", 2048, "2026-11-21 14:02:00",
            setOf(BackupCategory.USER_DATA), emptySet(), false, "1.2.3")
        val second = first.copy(fileName = "old.zip", createdAt = "2026-11-19 11:12:00", sillyTavernVersion = "1.0.9")
        val roomy = wide
        val state = fixture.copy(backups = listOf(first, second), backupsLoaded = true, backupStorageReady = true)
        compose.setContent {
            var home by remember { mutableStateOf(roomy) }
            var request by remember { mutableStateOf(if (roomy) null else second.fileName) }
            SillyTavernLauncherTheme(AppTheme.LIGHT, prefs) {
                if (home) V2HomeDashboard(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                    onOpenArchive = { request = it; home = false })
                else V2BackupScreen(state, { _, _, _ -> }, {}, {}, { _, _ -> }, {}, {}, {}, { _, _ -> }, { _, _, _ -> },
                    {}, {}, {}, "", {}, {}, {}, {}, request, { request = null }, { home = true })
            }
        }
        if (roomy) compose.onNodeWithText(second.createdAt).performScrollTo().performClick()
        compose.onNodeWithText("SillyTavern 1.0.9", substring = true).assertExists()
        compose.onNodeWithContentDescription("뒤로").performClick()
        compose.onNodeWithText("대화 열기").assertExists()
    }

    @Test fun captureRealScreensAcrossEveryPalette() {
        var selectedTheme by mutableStateOf(AppTheme.LIGHT)
        var selectedAccent by mutableStateOf(LauncherAccent.AMBER)
        var contrast by mutableStateOf(false)
        var section by mutableStateOf(MainSection.HOME)
        val archive = BackupArchive("SillyTavern-Backup-20261121.zip", 2048, "2026-11-21 14:02:00",
            setOf(BackupCategory.USER_DATA), emptySet(), false, "1.13.2", branch = "release")
        compose.setContent {
            val display = prefs.copy(accent = selectedAccent, highContrast = contrast)
            val state = fixture.copy(theme = selectedTheme, displayPreferences = display, termuxSetup = TermuxSetupState(TermuxSetupStatus.READY),
                backups = listOf(archive), backupsLoaded = true, backupStorageReady = true)
            SillyTavernLauncherTheme(selectedTheme, display) {
                ScreenChrome(section) {
                    when (section) {
                        MainSection.HOME -> V2HomeDashboard(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
                        MainSection.SETTINGS -> V2SettingsLayout(state) { page, roomy ->
                            check(page == SettingsPage.DISPLAY)
                            V2DisplaySettings(state, { selectedTheme = it }, { selectedAccent = it.accent; contrast = it.highContrast }, roomy)
                        }
                        MainSection.BACKUPS -> V2BackupScreen(state = state, onCreateBackup = { _,_,_ -> }, onRefresh = {},
                            onReminderChange = {}, onTransfer = { _,_ -> }, onOpenFolder = {}, onSafety = {}, onScan = {},
                            onPreviewCleanup = { _,_ -> }, onSaveAnnotation = { _,_,_ -> }, onEncrypted = {}, onSelective = {},
                            onImport = {}, storageSetupCommand = "", onTermux = {}, onCopied = {}, onRestore = {}, onDelete = {},
                            requestedArchive = null, onArchiveOpened = {}, onArchiveShortcutBack = {})
                        else -> error("Unexpected screen")
                    }
                }
            }
        }
        for (screen in listOf(MainSection.HOME, MainSection.SETTINGS, MainSection.BACKUPS))
            for (brightness in listOf(AppTheme.LIGHT, AppTheme.DARK))
                for (high in listOf(false, true))
                    for (accent in LauncherAccent.entries) {
                        compose.runOnIdle { section = screen; selectedTheme = brightness; selectedAccent = accent; contrast = high }
                        if (screen == MainSection.SETTINGS && !wide &&
                            compose.onAllNodesWithText("글자 크기").fetchSemanticsNodes().isEmpty())
                            compose.onNodeWithText("화면").performClick()
                        if (screen == MainSection.SETTINGS) compose.onNodeWithText("글자 크기").assertIsDisplayed()
                        val heading = when (screen) {
                            MainSection.HOME -> "SillyTavern"
                            MainSection.SETTINGS -> "글자 크기"
                            else -> if (wide) "백업 목록" else "최근"
                        }
                        val glyph = compose.onNodeWithText(heading).captureToImage().asAndroidBitmap()
                        val pixels = IntArray(glyph.width * glyph.height)
                        glyph.getPixels(pixels, 0, glyph.width, 0, 0, glyph.width, glyph.height)
                        val ink = app.tavernbridge.launcher.ui.theme.launcherColors(brightness == AppTheme.DARK, accent, high).ink.toArgb()
                        fun nearInk(color: Int) = listOf(0, 8, 16).all { shift ->
                            kotlin.math.abs((color ushr shift and 255) - (ink ushr shift and 255)) <= 16
                        }
                        assertTrue("Missing heading ink: $screen $brightness $high $accent", pixels.count(::nearInk) >= 6)
                        capture("palette-${screen.name.lowercase()}-${brightness.name.lowercase()}-${if (high) "high" else "normal"}-${accent.name.lowercase()}")
                    }
    }
}
