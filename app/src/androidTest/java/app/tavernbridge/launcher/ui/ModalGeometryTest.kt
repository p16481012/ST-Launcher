package app.tavernbridge.launcher.ui

import android.graphics.Bitmap
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import app.tavernbridge.launcher.LauncherViewModel
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.theme.SillyTavernLauncherTheme
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Capturing onRoot() would omit the other Android window or fail with two roots:
// capture the actual composited display, including scrim, corners, gutters and underlying page.
internal fun captureModalWindowSnapshot(name: String) {
    val inst = InstrumentationRegistry.getInstrumentation()
    inst.waitForIdleSync()
    val dir = File(inst.targetContext.filesDir, "v2-visual").apply { check(isDirectory || mkdirs()) }
    val image = checkNotNull(inst.uiAutomation.takeScreenshot()) { "Cannot capture $name" }
    try {
        File(dir, "$name.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    } finally { image.recycle() }
}

@SdkSuppress(minSdkVersion = 30)
@RunWith(AndroidJUnit4::class)
class ModalGeometryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun launcher(): LauncherViewModel {
        val vm = compose.runOnIdle { ViewModelProvider(compose.activity)[LauncherViewModel::class.java] }
        compose.setContent {
            val route by vm.state.collectAsState()
            LauncherScreen(route.copy(isWorking = false, environmentChecked = true, previousInstallationKnown = true,
                backupsLoaded = true, backupStorageReady = true, theme = AppTheme.LIGHT,
                displayPreferences = DisplayPreferences(reducedMotion = true), termuxSetup = TermuxSetupState(TermuxSetupStatus.READY),
                environment = EnvironmentStatus(termuxInstalled = true, commandPermissionGranted = true, managerConnected = true,
                    sillyTavernInstalled = true, branch = SillyBranch.RELEASE, sillyTavernVersion = "1.13.2")), vm)
        }
        return vm
    }

    private val density get() = compose.activity.resources.displayMetrics.density
    private fun safeWidth(): Float {
        val metrics = compose.activity.windowManager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        return (metrics.bounds.width() - insets.left - insets.right) / density
    }
    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
    private fun record(name: String, prefix: String) {
        val box = bounds("v2-$prefix-surface")
        val body = bounds("v2-$prefix-body")
        val actions = bounds(if (prefix == "dialog") "v2-dialog-actions" else "v2-sheet-footer")
        assertTrue("$name footer overlaps the scroll area: $body / $actions", body.bottom.value <= actions.top.value + 1f)
        assertTrue("$name footer outside the surface: $box / $actions", actions.bottom.value <= box.bottom.value + 1f)
        assertTrue("$name footer inside top edge: $box / $actions", actions.top.value >= box.top.value)
        val dir = File(compose.activity.filesDir, "v2-visual").apply { check(isDirectory || mkdirs()) }
        File(dir, "modal-geometry.tsv").appendText(
            "$name\tviewportWidth=" + safeWidth() + "\twidth=" + box.width.value + "\theight=" + box.height.value +
                "\tbodyHeight=" + body.height.value + "\tactionsHeight=" + actions.height.value + "\n",
        )
        captureModalWindowSnapshot(name)
    }

    @Test fun realResetConfirmationHasFigmaGuttersAndFitsItsContent() {
        val vm = launcher()
        compose.runOnIdle { vm.selectTool(ToolPage.RESET) }
        compose.onNodeWithText("초기화").assertIsEnabled().performClick()
        compose.onNodeWithText("SillyTavern을 초기화할까요?").assertIsDisplayed()
        val surface = bounds("v2-dialog-surface")
        assertEquals("Centered dialog must not inherit Android's narrower default window", (safeWidth() - 48f).coerceAtMost(560f),
            surface.width.value, 2f)
        if (safeWidth() in 355f..365f) {
            // Figma C5: 312×248 at 360dp. Allow additional lines of real backup/data warnings, not an empty tall panel.
            assertTrue("C5 too tall: $surface", surface.height.value <= 312f)
        }
        compose.onNodeWithText("취소").assertIsDisplayed()
        record("modal-confirm", "dialog")
        compose.onNodeWithText("취소").performClick()
        compose.onNodeWithTag("v2-dialog-surface", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals("", vm.state.value.dispatchedOperation) }
    }

    @Test fun actualExplanationAndBackupSheetsUseTheWindowWidthAndPinnedActions() {
        val vm = launcher()
        compose.onNodeWithText("외부 접속").performScrollTo().performClick()
        compose.onNodeWithText("서버 연결 설정 열기").assertIsDisplayed()
        val expected = safeWidth().coerceAtMost(640f)
        val info = bounds("v2-sheet-surface")
        assertEquals(expected, info.width.value, 2f)
        val nav = compose.activity.windowManager.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom / density
        if (safeWidth() in 355f..365f) assertTrue("Short C3 explanation consumed too much space: $info", info.height.value - nav <= 324f)
        record("modal-info", "sheet")
        compose.onNodeWithText("닫기").performClick()
        compose.runOnIdle { vm.openBackups() }
        compose.onNodeWithText("지금 백업").assertIsEnabled().performClick()
        compose.onNodeWithText("백업 시작").assertIsDisplayed()
        val scope = bounds("v2-sheet-surface")
        assertEquals(expected, scope.width.value, 2f)
        if (safeWidth() in 355f..365f) assertTrue("C4 scope didn't fit its normal content: $scope", scope.height.value - nav <= 510f)
        record("modal-backup", "sheet")
        val fixed = bounds("v2-sheet-footer")
        compose.onNodeWithText("직접 선택", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("백업 시작").assertIsDisplayed()
        val expanded = bounds("v2-sheet-footer")
        assertEquals("Expanded options must not push the action out of the window", fixed.bottom.value, expanded.bottom.value, 2f)
        compose.runOnIdle { assertEquals("", vm.state.value.dispatchedOperation) }
    }

    @Test fun longBodyScrollsInShortRealDialogWindowAtMaximumTextSizeAndBothActionsStayVisible() {
        var window: Window? = null
        var confirmed = 0
        var cancelled = 0
        compose.setContent {
            SillyTavernLauncherTheme(AppTheme.LIGHT, DisplayPreferences(textSize = LauncherTextSize.MAXIMUM, reducedMotion = true)) {
                V2AlertDialog(onDismissRequest = { cancelled++ },
                    title = {
                        val root = LocalView.current
                        SideEffect { window = (root.parent as? DialogWindowProvider)?.window }
                        Text("복원 항목 확인")
                    },
                    text = { Column { for (i in 1..32) Text("복원할 항목 $i — 원본 파일과 설정을 확인합니다.") } },
                    confirmButton = { TextButton(onClick = { confirmed++ }) { Text("확인") } },
                    dismissButton = { TextButton(onClick = { cancelled++ }) { Text("취소") } })
            }
        }
        compose.onNodeWithText("복원 항목 확인").assertIsDisplayed()
        val heightPx = (352f * density).roundToInt()
        compose.runOnIdle { checkNotNull(window).setLayout(WindowManager.LayoutParams.MATCH_PARENT, heightPx) }
        compose.waitUntil(5_000) { (window?.decorView?.height ?: 0) in 1..heightPx + 2 }
        val fixed = bounds("v2-dialog-actions")
        assertTrue("Dialog ignored the real 352dp window constraint", bounds("v2-dialog-surface").height.value <= 353f)
        compose.onNodeWithText("복원할 항목 32", substring = true).performScrollTo().assertIsDisplayed()
        assertEquals(fixed, bounds("v2-dialog-actions"))
        compose.onNodeWithText("확인").assertIsDisplayed().performClick()
        compose.onNodeWithText("취소").assertIsDisplayed()
        record("modal-long", "dialog")
        compose.onNodeWithText("취소").performClick()
        compose.runOnIdle { assertEquals(1, confirmed); assertEquals(1, cancelled) }
    }
}
