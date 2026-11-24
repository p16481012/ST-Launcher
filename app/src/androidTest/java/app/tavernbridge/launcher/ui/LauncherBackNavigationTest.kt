package app.tavernbridge.launcher.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tavernbridge.launcher.LauncherViewModel
import app.tavernbridge.launcher.model.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real launcher and navigation callbacks; only the rendered device status is a fixture.
 * The view model is not connected to Termux and does not refresh or start any command. */
@RunWith(AndroidJUnit4::class)
class LauncherBackNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun launchExistingInstallation(): LauncherViewModel {
        val vm = compose.activityRule.scenario.let {
            lateinit var reference: LauncherViewModel
            it.onActivity { activity -> reference = ViewModelProvider(activity)[LauncherViewModel::class.java] }
            reference
        }
        compose.setContent {
            val route by vm.state.collectAsState()
            LauncherScreen(route.copy(isWorking = false, environmentChecked = true,
                termuxSetup = TermuxSetupState(TermuxSetupStatus.READY), previousInstallationKnown = true,
                environment = EnvironmentStatus(termuxInstalled = true, commandPermissionGranted = true,
                    managerConnected = true, sillyTavernInstalled = true, processRunning = true, serverReachable = true)), vm)
        }
        return vm
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
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle {
            assertEquals(MainSection.LOGS, vm.state.value.section)
            assertEquals(ToolPage.INDEX, vm.state.value.toolPage)
        }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("대화 열기").assertIsDisplayed()
    }

    @Test fun settingsShortcutReturnsToTheSameDetailRatherThanItsIndex() {
        val vm = launchExistingInstallation()
        compose.onNode(hasText("설정") and hasClickAction()).performClick()
        compose.onNode(hasText("앱 정보") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("바로가기 · 앱 관리").performScrollTo().performClick()
        compose.onNodeWithText("확장 프로그램 관리").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ToolPage.EXTENSIONS, vm.state.value.toolPage) }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("바로가기 · 앱 관리").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(MainSection.SETTINGS, vm.state.value.section) }
    }
}
