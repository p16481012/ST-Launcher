package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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

class UpdateManagementContentTest {
    @get:Rule val ui = createComposeRule()
    private val report = UpdatePreflight("staging", "a1234567", "b1234567", "1.19.1", "1.19.1",
        true, 4, "v24.22.3", ">= 20", true, 8_000_000_000, 800_000_000, true, listOf("config-test.yaml"),
        true, List(4) { UpdateCommit("abf011${it}", "변경 ${it + 1}") }, true)
    private val base = LauncherUiState(isWorking = false, environmentChecked = true,
        environment = EnvironmentStatus(managerConnected = true, commandPermissionGranted = true,
            sillyTavernInstalled = true, sillyTavernVersion = "1.19.1", branch = SillyBranch.STAGING),
        updatePreflight = report, autoBackupBeforeUpdate = true,
        lastUpdateRecord = UpdateRecord("2026-11-24 12:14", "staging", "111cccc1", "222dddd8", "", "", "", "rolled_back"))
    private val state = mutableStateOf(base)
    private val actions = mutableListOf<String>()

    private fun show() {
        ui.setContent { SillyTavernLauncherTheme(AppTheme.LIGHT, DisplayPreferences(reducedMotion = true)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text("업데이트", style = MaterialTheme.typography.titleMedium)
                    UpdateManagementContent(state.value, Modifier.fillMaxWidth().weight(1f), { actions += "check" },
                        { actions += "backup:$it"; state.value = state.value.copy(autoBackupBeforeUpdate = it) },
                        { actions += "update" }, { actions += "installation" }, { actions += "versions" },
                        { actions += "backups" }, { actions += "changes" })
                }
            }
        } }
    }

    @Test fun sameVersionWithNewCommitsShowsAllChecksAndRoutesWithoutExpandingOptions() {
        show()
        ui.onNodeWithText("같은 버전에 새 커밋이 있어요").assertIsDisplayed()
        ui.onNodeWithText("4개 모두").performClick()
        ui.onNodeWithText("업데이트 전에 자동 백업").performScrollTo().performClick()
        ui.onNodeWithText("비밀 설정은 제외해요.", substring = true).assertExists()
        ui.onNodeWithText("a123456 → b123456").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("v24.22.3 / 필요 >= 20").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("업데이트 공간").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("config-test.yaml").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("업데이트 안전 검사").performScrollTo().performClick()
        ui.onNodeWithText("이전 버전 선택·복귀").performScrollTo().performClick()
        ui.onNodeWithText("설치 정보·가져오기·브랜치").performScrollTo().performClick()
        ui.onNodeWithText("업데이트 실패 후 이전 상태로 롤백됨").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("111cccc → 222dddd").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("업데이트 전 ZIP 백업 확인·복원").performScrollTo().performClick()
        ui.onNode(hasText("업데이트") and hasClickAction()).performClick()
        ui.runOnIdle { assertEquals(listOf("changes","backup:false","check","versions","installation","backups","update"), actions) }
    }

    @Test fun latestAndRunningKeepsSafetyDataAndPreviousVersionReasonVisible() {
        state.value = base.copy(updatePreflight = report.copy(updateAvailable = false,
            currentCommit = "a1234567", targetCommit = "a1234567", commitsBehind = 0, modifiedFiles = emptyList()),
            environment = base.environment.copy(processRunning = true))
        show()
        ui.onNodeWithText("최신 버전이에요").assertIsDisplayed()
        ui.onNodeWithText("최신 상태").assertIsNotEnabled()
        captureModalWindowSnapshot("management-update")
        ui.onNodeWithText("a123456 → a123456").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("업데이트 안전 검사").performScrollTo().assertIsEnabled()
        ui.onNodeWithText("이전 버전 선택·복귀").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithText("서버를 종료한 뒤 선택할 수 있어요.").assertExists()
        ui.onNodeWithText("추가 옵션 접기").assertDoesNotExist()
    }

    @Test fun preflightAndApplyRemainSeparateAndFailedCheckCannotWrite() {
        state.value = base.copy(updatePreflight = null)
        show()
        ui.onNodeWithText("업데이트 안전 검사").performClick()
        ui.runOnIdle { state.value = base.copy(updatePreflight = report.copy(nodeCompatible = false,
            spaceReady = false, backupStorageReady = false)) }
        ui.onNode(hasText("업데이트") and hasClickAction()).assertIsNotEnabled()
        ui.onNodeWithText("백업 저장공간 연결").performScrollTo().performClick()
        ui.runOnIdle { assertEquals(listOf("check", "backups"), actions) }
    }
}
