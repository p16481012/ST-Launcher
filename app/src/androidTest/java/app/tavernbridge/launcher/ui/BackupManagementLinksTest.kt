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
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.theme.SillyTavernLauncherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    private val archive = BackupArchive("SillyTavern-Launcher-20261124-165300.zip", 2048, "2026-11-24 16:53:00",
        setOf(BackupCategory.USER_DATA), emptySet(), false, "1.19.1", revision = "a".repeat(64))
    private val ready = LauncherUiState(isWorking = false, environmentChecked = true, section = MainSection.BACKUPS,
        environment = EnvironmentStatus(termuxInstalled = true, commandPermissionGranted = true, managerConnected = true, sillyTavernInstalled = true),
        termuxSetup = TermuxSetupState(TermuxSetupStatus.READY), backupsLoaded = true, backupStorageReady = true, backups = listOf(archive))

    @Test fun phoneOverflowOrTabletQuietActionsTransferOnlyTheChosenArchive() {
        val transfers = mutableListOf<Pair<String, BackupTransferAction>>()
        var deletes = 0
        ui.setContent { SillyTavernLauncherTheme(AppTheme.LIGHT, DisplayPreferences(reducedMotion = true)) {
            V2BackupScreen(ready.copy(backupAnnotations = mapOf(archive.fileName to BackupAnnotation(pinned = true))),
                { _, _, _ -> }, {}, {}, { item, action -> transfers += item.fileName to action }, {}, {}, {}, { _, _ -> },
                { _, _, _ -> }, {}, {}, {}, "", {}, {}, {}, { deletes++ }, archive.fileName, {}, {})
        } }
        val phone = ui.onAllNodesWithContentDescription("백업 파일 동작").fetchSemanticsNodes().isNotEmpty()
        fun select(label: String) {
            if (phone) {
                ui.onNodeWithText(label).assertDoesNotExist()
                ui.onNodeWithContentDescription("백업 파일 동작").performClick()
            }
            ui.onNodeWithText("삭제").assertDoesNotExist()
            ui.onNodeWithText(label).performClick()
        }
        select("공유")
        select("다른 위치에 저장")
        ui.runOnIdle {
            assertEquals(listOf(archive.fileName to BackupTransferAction.SHARE, archive.fileName to BackupTransferAction.EXPORT), transfers)
            assertEquals(0, deletes)
        }
    }

    @Test fun restoreLoadsNextPageOnlyAtTheEndAndDoesNotLoopAfterAnError() {
        val rows = (1..45).map {
            SelectiveRestoreItem(SelectiveRestoreKind.CHARACTER, "profile/characters/$it.png", 256, false,
                "", false, true, "profile", "$it.png")
        }
        val firstPage = SelectiveRestorePage("rev", "install", rows, 45, 46)
        val model = mutableStateOf(SelectiveRestoreUiState("backup.zip", page = firstPage))
        var loads = 0
        var retries = 0
        ui.setContent { SillyTavernLauncherTheme(AppTheme.LIGHT, DisplayPreferences(reducedMotion = true)) {
            SelectiveRestoreDialog(model.value, true, onMore = {
                loads++
                model.value = model.value.copy(error = "목록 응답 없음")
            }, onRefresh = { retries++ }, onRestore = { _, _ -> }, onClose = {})
        } }
        ui.runOnIdle { assertEquals(0, loads) }
        ui.onNodeWithTag("restore-candidate-list").performScrollToKey(BackupPageEnd)
        ui.onNodeWithTag("restore-candidate-list").performScrollToNode(hasText("목록 다시 확인"))
        ui.onNodeWithText("목록 다시 확인").performClick()
        ui.runOnIdle { assertEquals(1, loads); assertEquals(1, retries) }
    }

    @Test fun openingSpaceScansOnceAndCleanupRequiresSelectionAndConfirmation() {
        var scans = 0
        val preview = mutableStateOf<BackupRetentionPreview?>(null)
        var deletions = 0
        ui.setContent { SillyTavernLauncherTheme(AppTheme.LIGHT, DisplayPreferences(reducedMotion = true)) {
            StorageManagementScreen(ready, null, { scans++ }, { keep, days ->
                assertEquals(3, keep); assertEquals(30, days)
                preview.value = BackupRetentionPreview(listOf(archive), 0, 0, archive.sizeBytes)
            })
            preview.value?.let { BackupCleanupScreen(it, { preview.value = null }, { deletions++ }) }
        } }
        ui.onNodeWithText("저장 공간 확인").assertDoesNotExist()
        ui.onNodeWithText("정리 후보 미리보기").performScrollTo().performClick()
        ui.onNodeWithText("영구 삭제").assertDoesNotExist()
        ui.onNodeWithText("선택한 1개 삭제").assertDoesNotExist()
        ui.onNode(isToggleable()).performClick()
        ui.onNodeWithText("선택한 1개 삭제").performClick()
        ui.onNodeWithText("취소").performClick()
        ui.onNodeWithContentDescription("뒤로").performClick()
        ui.runOnIdle { assertEquals(1, scans); assertEquals(0, deletions) }
    }

    @Test fun spaceShortcutRestoresTheSameArchiveButExternalShortcutReturnsToItsCaller() {
        var appBack = 0
        var scans = 0
        val requestedArchive = mutableStateOf<String?>(archive.fileName)
        val requestSpace = mutableStateOf(false)
        val localOrigin = mutableStateOf(true)
        ui.setContent { SillyTavernLauncherTheme(AppTheme.LIGHT, DisplayPreferences(reducedMotion = true)) {
            V2BackupScreen(ready, { _, _, _ -> }, {}, {}, { _, _ -> }, {}, {}, { scans++ }, { _, _ -> },
                { _, _, _ -> }, {}, {}, {}, "", {}, {}, {}, {}, requestedArchive.value,
                onArchiveOpened = { requestedArchive.value = null }, onArchiveShortcutBack = { appBack++ },
                requestSpace = requestSpace.value, onSpaceOpened = { requestSpace.value = false },
                spaceShortcutFromBackup = localOrigin.value)
        } }
        ui.onNodeWithText("SillyTavern 1.19.1", substring = true).assertExists()
        ui.runOnIdle { requestSpace.value = true }
        ui.onNodeWithText("오래된 백업 정리").assertExists()
        // A second request while already in cleanup must not overwrite its return destination.
        ui.runOnIdle { requestSpace.value = true }
        ui.onNodeWithContentDescription("뒤로").performClick()
        ui.onNodeWithText("SillyTavern 1.19.1", substring = true).assertExists()
        ui.runOnIdle { assertEquals(0, appBack); assertEquals(1, scans); localOrigin.value = false; requestSpace.value = true }
        ui.onNodeWithText("오래된 백업 정리").assertExists()
        ui.onNodeWithContentDescription("뒤로").performClick()
        ui.runOnIdle { assertEquals(1, appBack); assertEquals(2, scans) }
    }

    @Test fun selectAllAndExitRespectPinnedArchivesWithoutDeleting() {
        val protected = archive.copy(fileName = "SillyTavern-Launcher-20261124-165301.zip", revision = "b".repeat(64))
        var deletes = 0
        ui.setContent { SillyTavernLauncherTheme(AppTheme.LIGHT, DisplayPreferences(reducedMotion = true)) {
            V2BackupScreen(ready.copy(backups = listOf(archive, protected), backupAnnotations = mapOf(protected.fileName to BackupAnnotation(pinned = true))),
                { _, _, _ -> }, {}, {}, { _, _ -> }, {}, {}, {}, { _, _ -> }, { _, _, _ -> }, {}, {}, {}, "", {}, {}, {}, { deletes++ }, null, {}, {})
        } }
        val overview = ui.onNodeWithTag("backup-overview-list")
        val dual = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.screenWidthDp >= 840
        val link = if (dual) "전체 관리" else "전체 2개"
        overview.performScrollToNode(hasText(link))
        ui.onNodeWithText(link).performClick()
        ui.onNodeWithContentDescription("항목 선택").performClick()
        ui.onNodeWithText("모두 선택").performClick()
        ui.onNodeWithText("선택 항목 1개 삭제").assertExists()
        ui.onNodeWithText("선택 해제").performClick()
        ui.onNodeWithText("선택 항목 1개 삭제").assertDoesNotExist()
        ui.onNodeWithContentDescription("선택 종료").performClick()
        ui.onNodeWithContentDescription("항목 선택").assertExists()
        ui.runOnIdle { assertEquals(0, deletes) }
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
        var refreshed = 0
        var reminder: Boolean? = null
        val modes = mutableListOf<EncryptedBackupMode>()
        ui.setContent {
            SillyTavernLauncherTheme(AppTheme.LIGHT, prefs) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        V2BackupScreen(state, onCreateBackup = { _, _, _ -> }, onRefresh = { refreshed++ },
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
        val wide = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.screenWidthDp >= 840
        val header = ui.onNode(hasText(if (wide) "백업 목록" else "최근") and hasAnyAncestor(hasTestTag("backup-overview-list")))
        val refresh = ui.onNodeWithContentDescription("백업 목록 새로고침")
        val bounds = refresh.assertIsDisplayed().assertHasClickAction().getUnclippedBoundsInRoot()
        val heading = header.getUnclippedBoundsInRoot()
        assertTrue(bounds.right - bounds.left >= 48.dp && bounds.bottom - bounds.top >= 48.dp)
        assertTrue("Refresh belongs beside the backup list heading", heading.top >= bounds.top && heading.bottom <= bounds.bottom)
        ui.runOnIdle { assertEquals(1, refreshed) } // Opening performs the initial read.
        refresh.performClick()
        ui.runOnIdle { assertEquals(2, refreshed); assertEquals(0, openedSafety) }
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
