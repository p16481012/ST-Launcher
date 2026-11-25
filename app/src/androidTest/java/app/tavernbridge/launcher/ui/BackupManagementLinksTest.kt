package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CompletableDeferred
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.theme.SillyTavernLauncherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupManagementLinksTest {
    @get:Rule val ui = createComposeRule()

    @Test fun sameNameReplacementDuringStopNeverOpensRestoreForTheWrongArchive() {
        val original = BackupArchive(fileName = "backup.zip", sizeBytes = 100, createdAt = "", categories = emptySet(),
            customFolders = emptySet(), includesSecrets = false, sillyTavernVersion = "", revision = "a".repeat(64))
        val running = EnvironmentStatus(termuxInstalled = true, commandPermissionGranted = true, managerConnected = true,
            sillyTavernInstalled = true, processRunning = true)
        val model = mutableStateOf(LauncherUiState(isWorking = false, environmentChecked = true, section = MainSection.BACKUPS,
            termuxSetup = TermuxSetupState(TermuxSetupStatus.READY), environment = running,
            backupsLoaded = true, backupStorageReady = true, backups = listOf(original)))
        val firstStop = CompletableDeferred<Boolean>()
        var checks = 0
        val opened = mutableListOf<String>()
        val verify: suspend (Long) -> Boolean = {
            checks++
            if (checks == 1) firstStop.await() else {
                model.value = model.value.copy(environment = running.copy(processRunning = false))
                true
            }
        }
        ui.setContent { SillyTavernLauncherTheme(AppTheme.LIGHT, DisplayPreferences(reducedMotion = true)) {
            CompositionLocalProvider(LocalVerifiedStop provides verify) {
                V2BackupScreen(model.value, onCreateBackup = { _, _, _ -> }, onRefresh = {}, onReminderChange = {},
                    onTransfer = { _, _ -> }, onOpenFolder = {}, onSafety = {}, onScan = {}, onPreviewCleanup = { _, _ -> },
                    onSaveAnnotation = { _, _, _ -> }, onEncrypted = {}, onSelective = { opened += it.revision },
                    onImport = {}, storageSetupCommand = "", onTermux = {}, onCopied = {},
                    onRestore = { opened += "full:" + it.revision }, onDelete = {}, requestedArchive = original.fileName,
                    onArchiveOpened = {}, onArchiveShortcutBack = {})
            }
        } }
        ui.onNodeWithText("이 백업으로 복원").performClick()
        ui.onNodeWithText("서버 끄고 이어가기").performClick()
        ui.runOnIdle {
            assertEquals(1, checks)
            model.value = model.value.copy(backups = listOf(original.copy(revision = "b".repeat(64))))
        }
        ui.waitForIdle()
        ui.runOnIdle { firstStop.complete(true) }
        ui.runOnIdle { assertEquals(emptyList<String>(), opened) }
        ui.onNodeWithText("일부만 복원").performClick()
        ui.onNodeWithText("서버 끄고 이어가기").performClick()
        ui.runOnIdle { assertEquals(listOf("b".repeat(64)), opened) }
    }

    @Test fun overviewNamesTheActionsAndOpensTheRightEncryptionMode() {
        val prefs = DisplayPreferences(reducedMotion = true)
        val state = LauncherUiState(isWorking = false, environmentChecked = true, section = MainSection.BACKUPS,
            termuxSetup = TermuxSetupState(TermuxSetupStatus.READY),
            environment = EnvironmentStatus(termuxInstalled = true, commandPermissionGranted = true,
                managerConnected = true, sillyTavernInstalled = true),
            backupsLoaded = true, backupStorageReady = true, backupReminder = BackupReminderState(enabled = false),
            // On a wide screen the real overview shows its latest archive in the second pane.
            backups = listOf(BackupArchive(fileName = "SillyTavern-20261124-165300.zip", sizeBytes = 83_886_080L,
                createdAt = "2026-11-24 16:53:00", categories = setOf(BackupCategory.USER_DATA, BackupCategory.CONFIG),
                customFolders = emptySet(), includesSecrets = false, sillyTavernVersion = "1.19.1",
                characterFiles = 12, chatFiles = 34)))
        var openedFolder = 0
        var openedSafety = 0
        var reminder: Boolean? = null
        val modes = mutableListOf<EncryptedBackupMode>()
        ui.setContent {
            SillyTavernLauncherTheme(AppTheme.LIGHT, prefs) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
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
        ui.onNodeWithText("Download 폴더 열기").performScrollTo()
        if (InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.screenWidthDp >= 840) {
            ui.onNodeWithText("이 백업으로 복원").assertIsDisplayed()
        }
        ui.captureManagementScreen("management-backup", "Download 폴더 열기")
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
