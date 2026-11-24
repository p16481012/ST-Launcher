package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.theme.SillyTavernLauncherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class InstalledToolDetailTest {
    @get:Rule val ui = createComposeRule()
    private val env = EnvironmentStatus(termuxInstalled = true, commandPermissionGranted = true, managerConnected = true,
        sillyTavernInstalled = true, sillyTavernVersion = "1.19.1", nodeVersion = "v24.22.3", branch = SillyBranch.STAGING,
        commit = "c96a1d0c6")
    private val model = mutableStateOf(LauncherUiState(isWorking = false, environment = env,
        termuxSetup = TermuxSetupState(TermuxSetupStatus.READY)))
    private val calls = mutableListOf<String>()
    private fun show() {
        ui.setContent { SillyTavernLauncherTheme(AppTheme.LIGHT, DisplayPreferences(reducedMotion = true)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                V2InstallationDetail(model.value, Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp),
                    { calls += "storage" }, { calls += "inspect:$it" }, { calls += "branch:${it.value}" },
                    { calls += "repair-page" }, { calls += "reset-page" })
            }
        } }
    }
    private fun item(text: String) = ui.onNodeWithText(text).performScrollTo()

    @Test fun allInstalledValuesAndActionsArePresentWithoutOpeningAnOptionsMenu() {
        show()
        listOf("버전", "1.19.1", "브랜치", "설치 위치", "~/SillyTavern", "Node.js", "v24.22.3",
            "커밋", "c96a1d0c6", "기존 설치 가져오기").forEach { ui.onNodeWithText(it).assertExists() }
        ui.onAllNodesWithText("Staging").assertCountEquals(2)
        ui.captureManagementScreen("tool-installation", "설치 정보")
        item("내부 저장소 폴더 선택").assertIsEnabled().performClick()
        item("Termux 폴더 경로 입력").performClick()
        ui.onNode(hasSetTextAction()).performTextReplacement("/storage/emulated/0/Documents/SillyTavern")
        ui.onNodeWithText("검사").performClick()
        item("Release로 변경").performClick()
        item("점검·복구").performClick()
        item("초기화").performClick()
        ui.runOnIdle { assertEquals(listOf("storage", "inspect:/storage/emulated/0/Documents/SillyTavern",
            "branch:release", "repair-page", "reset-page"), calls) }
    }

    @Test fun runningServerRetainsEveryTargetAndExplainsWhyWritesAreUnavailable() {
        model.value = model.value.copy(environment = env.copy(processRunning = true))
        show()
        item("내부 저장소 폴더 선택").assertIsNotEnabled()
        item("Termux 폴더 경로 입력").assertIsNotEnabled()
        item("Release로 변경").assertIsNotEnabled()
        item("서버 종료 후 전환할 수 있어요.").assertIsDisplayed()
        // The repair and reset pages themselves can be inspected; dispatch is guarded in their own flows.
        item("점검·복구").assertIsEnabled().performClick()
        item("초기화").assertIsEnabled().performClick()
        ui.runOnIdle { assertEquals(listOf("repair-page", "reset-page"), calls) }
    }

    @Test fun pathInspectionAlsoHonorsAChangedEnvironmentAndCancelNeverImports() {
        show()
        item("Termux 폴더 경로 입력").performClick()
        ui.onNode(hasSetTextAction()).performTextClearance()
        ui.onNodeWithText("검사").assertIsNotEnabled()
        ui.onNode(hasSetTextAction()).performTextReplacement("~/other")
        ui.runOnIdle { model.value = model.value.copy(environment = env.copy(operationActive = true)) }
        ui.onNodeWithText("검사").assertIsNotEnabled()
        ui.onNodeWithText("취소").performClick()
        ui.runOnIdle { assertEquals(emptyList<String>(), calls) }
    }
}
