package app.tavernbridge.launcher.ui

import android.graphics.Bitmap

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.theme.SillyTavernLauncherTheme
import java.util.Calendar
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReviewSafetyScreenTest {
    @get:Rule val ui = createComposeRule()
    private fun at(month: Int, day: Int, hour: Int, minute: Int) = Calendar.getInstance().apply {
        set(2026, month - 1, day, hour, minute, 0)
    }.timeInMillis
    private val a = SafetyInventoryItem(SafetyItemKind.ARCHIVE, "a", "a1", "before-forced-update-20260724-175045.tar.gz",
        2_700_000_000L, at(7,24,17,54))
    private val b = a.copy(id = "b", revision = "b1", displayName = "before-forced-update-20260918-145400.tar.gz", modifiedAtMillis = at(9,18,14,54))
    private val cfg = SafetyInventoryItem(SafetyItemKind.CONFIG, "cfg", "c1", "before-network-change.yaml", 3200, at(9,9,13,46))
    private val file = SafetyInventoryItem(SafetyItemKind.TRASH, "f", "f1", "characters/Alice.png", 1_400_000, at(11,24,21,10),
        originalRelativePath = "characters/Alice.png", restorable = true)
    private val folder = file.copy(id = "folder", revision = "dir1", displayName = "chats/Alice", originalRelativePath = "chats/Alice",
        sizeBytes = null, isDirectory = true)
    private val model = mutableStateOf(SafetyInventoryUiState(open = true, items = listOf(a,b,cfg,file,folder), protectedEntries = 1, hasLoaded = true))
    private val events = mutableListOf<String>()
    private val running = mutableStateOf(false)
    // PixelCopy the *new* dialog Window surface, not the display (which may still present the sheet).
    private fun captureDrawnDeleteDialog() {
        ui.waitForIdle()
        val inDialog = hasAnyAncestor(isDialog())
        listOf("영구 삭제할까요?", "취소", "영구 삭제").forEach { label ->
            ui.onNode(hasText(label) and inDialog).assertIsDisplayed()
        }
        val surface = ui.onNodeWithTag("v2-dialog-surface", useUnmergedTree = true)
        surface.assertIsDisplayed()
        val bitmap = surface.captureToImage().asAndroidBitmap()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "v2-visual")
        check(dir.isDirectory || dir.mkdirs())
        try {
            check(bitmap.width > 0 && bitmap.height > 0)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val firstVisible = pixels.first { it ushr 24 > 0 }
            check(pixels.any { it ushr 24 > 0 && it != firstVisible }) { "Empty dialog capture" }
            File(dir, "review-safety-delete.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }
    private fun show() = ui.setContent {
        SillyTavernLauncherTheme(AppTheme.LIGHT, DisplayPreferences(reducedMotion = true)) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    SafetyInventoryScreen(model.value, serverRunning = running.value, busy = false,
                        onClose = { events += "back" }, onRefresh = { events += "refresh" }, onMore = { events += "more" },
                        onAction = { action, row -> events += "$action:${row.id}" },
                        onDeleteMany = { rows -> events += rows.joinToString(",") { it.id } },
                        onRepair = { events += "repair" }, onNotice = { events += "notice" })
                }
                V2TabBar(MainSection.BACKUPS) {}
            }
        }
    }
    @Test fun safetyListSheetAndConfirmationUseRealCapabilitiesAndHumanReadableLabels() {
        show()
        ui.captureManagementScreen("review-safety-list", "업데이트·삭제 때 보관한 자료예요")
        ui.onNodeWithText(a.displayName).assertDoesNotExist()
        ui.onAllNodesWithText("업데이트 전 보호 사본").onFirst().performClick()
        ui.onNodeWithText(a.displayName).assertIsDisplayed()
        ui.onNodeWithText("원래 위치로 복원").assertDoesNotExist()
        ui.onNodeWithText("Download로 사본 내보내기").assertIsDisplayed()
        captureModalWindowSnapshot("review-safety-actions")
        ui.onNodeWithText("영구 삭제").performClick()
        ui.onNodeWithText("영구 삭제할까요?").assertIsDisplayed()
        captureDrawnDeleteDialog()
        ui.onNodeWithText("취소").performClick()
        ui.runOnIdle { assertTrue(events.isEmpty()) }
        ui.onAllNodesWithText("업데이트 전 보호 사본").onFirst().performClick()
        ui.onNodeWithText("Download로 사본 내보내기").performClick()
        ui.onNodeWithText("내보내기").performClick()
        ui.runOnIdle { assertEquals(listOf("export:a"), events) }
    }
    @Test fun selectedBytesExcludeNothingSilentlyAndChangedArchiveIsNeverDeleted() {
        show()
        ui.onNodeWithContentDescription("항목 선택").performClick()
        ui.onAllNodesWithText("업데이트 전 보호 사본")[0].performClick()
        ui.onAllNodesWithText("업데이트 전 보호 사본")[1].performClick()
        val del = "영구 삭제 (2) · 5.4 GB"
        ui.onNodeWithText(del).assertIsDisplayed()
        ui.captureManagementScreen("review-safety-selection", del)
        ui.onNodeWithText(del).performClick()
        ui.runOnIdle { model.value = model.value.copy(items = listOf(a.copy(revision = "new"), b, cfg, file, folder)) }
        ui.onNodeWithText("영구 삭제할까요?").assertDoesNotExist()
        ui.runOnIdle { assertEquals(listOf("notice"), events) }
        ui.onNodeWithContentDescription("항목 선택").performClick()
        ui.onNodeWithText("모두 선택").performClick()
        ui.onNodeWithText("영구 삭제 (5) · 전체 용량 미확인").performClick()
        ui.onNodeWithText("영구 삭제").performClick()
        ui.runOnIdle { assertEquals("a,b,cfg,f,folder", events.last()) }
    }
    @Test fun lockedListExplainsAndOpensRepairOnlyOnExplicitTap() {
        model.value = model.value.copy(mutationBlocked = true)
        show()
        ui.captureManagementScreen("review-safety-locked", "복구가 끝나지 않은 자료가 있어요")
        ui.onNodeWithContentDescription("항목 선택").assertDoesNotExist()
        ui.onAllNodesWithText("업데이트 전 보호 사본").onFirst().performClick()
        ui.onNodeWithText("영구 삭제").assertDoesNotExist()
        ui.onNodeWithText("점검·복구").performClick()
        ui.runOnIdle { assertEquals(listOf("notice","repair"), events) }
    }
    @Test fun trashRestorationRequiresExplicitAgreementAndRechecksServerGate() {
        show()
        ui.onNodeWithTag("safety-inventory-items").performScrollToNode(hasText(file.originalRelativePath))
        ui.onNodeWithText(file.originalRelativePath).performClick()
        ui.onNodeWithText("Download로 사본 내보내기").assertDoesNotExist()
        ui.onNodeWithText("원래 위치로 복원").performClick()
        ui.runOnIdle { assertTrue(events.isEmpty()); running.value = true }
        ui.onNodeWithText("복원").assertIsNotEnabled()
        ui.runOnIdle { running.value = false }
        ui.onNodeWithText("복원").performClick()
        ui.runOnIdle { assertEquals(listOf("restore:f"), events) }
    }
}
