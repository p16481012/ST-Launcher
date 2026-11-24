package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.theme.SillyTavernLauncherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupManagementLinksTest {
    @get:Rule val ui = createComposeRule()

    @Test fun overviewNamesTheActionsAndOpensTheRightEncryptionMode() {
        val prefs = DisplayPreferences(reducedMotion = true)
        val state = LauncherUiState(isWorking = false, environmentChecked = true, section = MainSection.BACKUPS,
            termuxSetup = TermuxSetupState(TermuxSetupStatus.READY),
            environment = EnvironmentStatus(termuxInstalled = true, commandPermissionGranted = true,
                managerConnected = true, sillyTavernInstalled = true),
            backupsLoaded = true, backupStorageReady = true, backupReminder = BackupReminderState(enabled = false))
        var openedFolder = 0
        var openedSafety = 0
        var reminder: Boolean? = null
        val modes = mutableListOf<EncryptedBackupMode>()
        ui.setContent {
            SillyTavernLauncherTheme(AppTheme.LIGHT, prefs) {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Box(Modifier.widthIn(max = 400.dp).fillMaxHeight()) {
                        V2BackupScreen(state, onCreateBackup = { _, _, _ -> }, onRefresh = {},
                            onReminderChange = { reminder = it }, onTransfer = { _, _ -> },
                            onOpenFolder = { openedFolder++ }, onSafety = { openedSafety++ }, onScan = {},
                            onPreviewCleanup = { _, _ -> }, onSaveAnnotation = { _, _, _ -> },
                            onEncrypted = { modes.add(it) }, onSelective = {}, onImport = {}, storageSetupCommand = "",
                            onTermux = {}, onCopied = {}, onRestore = {}, onDelete = {}, requestedArchive = null,
                            onArchiveOpened = {}, onArchiveShortcutBack = {})
                    }
                }
            }
        }
        val overview = ui.onNodeWithTag("backup-overview-list")
        overview.performScrollToNode(hasText("안전 사본·휴지통"))
        ui.onNodeWithText("안전 사본·휴지통").performScrollTo().performClick()
        overview.performScrollToKey("backup-storage-actions")
        ui.waitForIdle()
        captureModalWindowSnapshot("management-backup")
        ui.onNodeWithText("Download 폴더 열기").performScrollTo().performClick()
        ui.onNodeWithText("백업 알림").performScrollTo().performClick()
        ui.onNodeWithText("암호화 백업").performScrollTo().performClick()
        ui.onNodeWithText("ZIP 암호화 저장").performScrollTo().performClick()
        ui.onNodeWithText("암호화 백업 열기·복원").performScrollTo().performClick()
        ui.onNodeWithContentDescription("뒤로").performClick()
        ui.onNodeWithText("지금 백업").assertIsDisplayed()
        ui.runOnIdle {
            assertEquals(1, openedFolder)
            assertEquals(1, openedSafety)
            assertEquals(true, reminder)
            assertEquals(listOf(EncryptedBackupMode.EXPORT, EncryptedBackupMode.RESTORE), modes)
        }
    }
}
