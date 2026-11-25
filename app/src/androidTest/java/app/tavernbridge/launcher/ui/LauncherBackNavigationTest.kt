package app.tavernbridge.launcher.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tavernbridge.launcher.LauncherViewModel
import app.tavernbridge.launcher.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real launcher and navigation callbacks; only the rendered device status is a fixture.
 * The view model is not connected to Termux and does not refresh or start any command. */
@RunWith(AndroidJUnit4::class)
class LauncherBackNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun launchExistingInstallation(readyBackupStorage: Boolean? = null): LauncherViewModel {
        val vm = compose.activityRule.scenario.let {
            lateinit var reference: LauncherViewModel
            it.onActivity { activity -> reference = ViewModelProvider(activity)[LauncherViewModel::class.java] }
            reference
        }
        compose.setContent {
            val route by vm.state.collectAsState()
            LauncherScreen(route.copy(isWorking = false, environmentChecked = true,
                backupsLoaded = readyBackupStorage == true, backupStorageReady = readyBackupStorage,
                termuxSetup = TermuxSetupState(TermuxSetupStatus.READY), previousInstallationKnown = true,
                environment = EnvironmentStatus(termuxInstalled = true, commandPermissionGranted = true,
                    managerConnected = true, sillyTavernInstalled = true, processRunning = true, serverReachable = true)), vm)
        }
        return vm
    }

    @Test fun realBackupHostCanOpenChooseAndDismissWithoutDispatching() {
        val vm = launchExistingInstallation(readyBackupStorage = true)
        compose.runOnIdle { vm.openBackups() }
        compose.onNodeWithText("지금 백업").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("백업 범위").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("전체 백업").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithContentDescription("시트 닫기").performClick()
        compose.onNodeWithText("지금 백업").assertIsDisplayed()
        compose.runOnIdle { assertEquals("", vm.state.value.dispatchedOperation) }
    }

    @Test fun aboutReportHasVisibleBackAndInstallationShortcutIgnoresPreviousBackupPanel() {
        val vm = launchExistingInstallation(readyBackupStorage = true)
        compose.runOnIdle { vm.openBackups() }
        compose.onNode(hasText("설정") and hasClickAction()).performClick()
        compose.onNode(hasText("앱 정보") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("진단 보고서 만들기").performScrollTo().performClick()
        compose.onNodeWithText("진단 보고서").assertIsDisplayed()
        compose.runOnIdle { assertEquals(MainSection.SETTINGS, vm.state.value.section) }
        compose.onNodeWithContentDescription("뒤로").performClick()
        compose.onNodeWithText("설치 관리 열기").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(MainSection.LOGS, vm.state.value.section)
            assertEquals(ToolPage.INSTALLATION, vm.state.value.toolPage)
        }
        compose.onNodeWithContentDescription("뒤로").performClick()
        compose.onNodeWithText("설치 관리 열기").performScrollTo().assertIsDisplayed()
    }

    @Test fun installedManagementActionsAreRealDestinationsAndBackReturnsToTheirOrigin() {
        val vm = launchExistingInstallation(readyBackupStorage = true)
        compose.onNode(hasText("도구") and hasClickAction()).performClick()
        fun openTool(label: String) = compose.onNode(hasText(label) and hasClickAction() and
            hasAnyAncestor(hasTestTag("tools-index"))).performScrollTo().performClick()
        openTool("설치 관리")
        compose.onNodeWithText("내부 저장소 폴더 선택").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("백업·복원").assertDoesNotExist()
        compose.runOnIdle { assertEquals(ToolPage.INSTALLATION, vm.state.value.toolPage) }
        val split = compose.onAllNodesWithTag("tools-index").fetchSemanticsNodes().isNotEmpty()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        if (split) {
            compose.runOnIdle { assertEquals(MainSection.HOME, vm.state.value.section) }
            compose.onNode(hasText("도구") and hasClickAction()).performClick()
        }
        openTool("업데이트")
        compose.onNodeWithText("설치 정보·가져오기·브랜치").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ToolPage.INSTALLATION, vm.state.value.toolPage) }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(ToolPage.UPDATE, vm.state.value.toolPage) }
        compose.onNodeWithText("이전 버전 선택·복귀").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("서버를 끄고 이어갈까요?").assertIsDisplayed()
        compose.onNodeWithText("취소").performClick()
        compose.runOnIdle { assertEquals("", vm.state.value.dispatchedOperation) }
        compose.onNode(hasText("백업") and hasClickAction()).performClick()
        compose.onNodeWithText("지금 백업").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        // The bottom tab is a root switch, not an Update shortcut; Back must not replay old tabs.
        compose.runOnIdle {
            assertEquals(MainSection.HOME, vm.state.value.section)
            assertEquals(ToolPage.INDEX, vm.state.value.toolPage)
            assertTrue(vm.state.value.navigation.previous.isEmpty())
        }
    }

    @Test fun externalAccessShortcutReturnsToHomeWithEitherBackControl() {
        val vm = launchExistingInstallation()
        for (hardwareBack in listOf(false, true)) {
            compose.onNodeWithText("외부 접속").performScrollTo().performClick()
            compose.onNodeWithText("서버 연결 설정 열기").performClick()
            compose.onNodeWithText("포트").performScrollTo().assertIsDisplayed()
            compose.runOnIdle { assertEquals(MainSection.SETTINGS, vm.state.value.section) }
            if (hardwareBack) compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            else compose.onNodeWithContentDescription("뒤로").performClick()
            compose.runOnIdle { assertEquals(MainSection.HOME, vm.state.value.section) }
            compose.onNodeWithText("대화 열기").assertExists()
        }
    }

    @Test fun homeLogReturnsHomeAndToolsLogReturnsTools() {
        val vm = launchExistingInstallation()
        compose.onNodeWithText("로그").performClick()
        compose.runOnIdle { assertEquals(ToolPage.LOGS, vm.state.value.toolPage) }
        compose.onNodeWithContentDescription("뒤로").performClick()
        compose.onNodeWithText("대화 열기").assertIsDisplayed()
        compose.onNodeWithText("로그").performClick()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("대화 열기").assertIsDisplayed()
        compose.onNode(hasText("도구") and hasClickAction()).performClick()
        compose.onNode(hasText("서버 로그") and hasClickAction()).performScrollTo().performClick()
        val split = compose.onAllNodesWithTag("tools-index").fetchSemanticsNodes().isNotEmpty()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        if (!split) {
            compose.runOnIdle {
                assertEquals(MainSection.LOGS, vm.state.value.section)
                assertEquals(ToolPage.INDEX, vm.state.value.toolPage)
                compose.activity.onBackPressedDispatcher.onBackPressed()
            }
        }
        compose.runOnIdle { assertEquals(MainSection.HOME, vm.state.value.section) }
        compose.onNodeWithText("대화 열기").assertIsDisplayed()
    }

    @Test fun settingsShortcutReturnsToTheSameDetailRatherThanItsIndex() {
        val vm = launchExistingInstallation()
        compose.onNode(hasText("설정") and hasClickAction()).performClick()
        compose.onNode(hasText("앱 정보") and hasClickAction()).performScrollTo().performClick()
        val entry = compose.onNodeWithText("바로가기 · 앱 관리").performScrollTo()
        val pageRowWidth = entry.getUnclippedBoundsInRoot().width.value
        entry.performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("홈 화면 바로가기").assertIsDisplayed()
        captureModalWindowSnapshot("modal-app-management")
        val systemSettings = compose.onNodeWithText("런처 앱 상세 설정 열기").performScrollTo().assertIsDisplayed()
        assertEquals("Management must use the same content width as its parent settings page",
            pageRowWidth, systemSettings.getUnclippedBoundsInRoot().width.value, 2f)
        // This is a settings destination, not a modal: Back returns to the exact About screen.
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("홈 화면 바로가기").assertDoesNotExist()
        compose.onNodeWithText("바로가기 · 앱 관리").performScrollTo().performClick()
        compose.onNodeWithText("확장 프로그램 관리").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ToolPage.EXTENSIONS, vm.state.value.toolPage) }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("홈 화면 바로가기").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(MainSection.SETTINGS, vm.state.value.section) }
    }
}
