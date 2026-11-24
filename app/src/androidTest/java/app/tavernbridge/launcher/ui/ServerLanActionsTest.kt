package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.theme.SillyTavernLauncherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerLanActionsTest {
    @get:Rule val compose = createComposeRule()
    private val running = LauncherUiState(isWorking = false, environmentChecked = true,
        termuxSetup = TermuxSetupState(TermuxSetupStatus.READY),
        environment = EnvironmentStatus(termuxInstalled = true, managerConnected = true, commandPermissionGranted = true,
            sillyTavernInstalled = true, processRunning = true, serverReachable = true))

    @Test fun runningServerAllowsOpeningAndSavingFromLabelAfterAcknowledgement() {
        var calls = 0
        compose.setContent {
            var editor by remember { mutableStateOf("") }
            var explanation by remember { mutableStateOf("") }
            SillyTavernLauncherTheme(AppTheme.LIGHT) {
                Column {
                    ServerLanRows(running, { editor = it }, {}, { explanation = it })
                    Text(explanation)
                }
                if (editor.isNotEmpty()) LANSecuritySettingsDialog(
                    LanSecuritySnapshot(false, LanWhitelist.loopbacks, false, false), onDismiss = { editor = "" },
                    onConfirm = { change -> assertTrue(change.listen); change.close(); ++calls; calls > 1 },
                    requestedListen = editor == "toggle-listen")
            }
        }
        compose.onNodeWithText("외부 접속").performClick()
        compose.onNodeWithText("저장할 때 잠시", substring = true).assertExists()
        compose.onNodeWithText("접속 주소").performClick()
        compose.onNodeWithText("그 뒤 다른 기기", substring = true).assertExists()
        compose.onNodeWithText("같은 Wi-Fi에서 접속 허용").performClick()
        compose.onNodeWithText("다른 기기 접속 허용").assertIsOn()
        compose.onNodeWithText("확인하고 적용").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("접속 허용 범위와 인증 변경 내용을 확인했습니다.", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("확인하고 적용").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("지금은 적용할 수 없어요.", substring = true).assertExists()
        compose.onNodeWithText("확인하고 적용").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, calls) }
    }

    @Test fun busyServerExplainsBlockedActionWithoutOpeningAnEditor() {
        compose.setContent {
            var reason by remember { mutableStateOf("") }
            SillyTavernLauncherTheme(AppTheme.LIGHT) {
                Column {
                    ServerLanRows(running.copy(isWorking = true), { error("Unexpected editor") }, {}, { reason = it })
                    Text("선택 결과: $reason")
                }
            }
        }
        compose.onNodeWithText("같은 Wi-Fi에서 접속 허용").performClick()
        compose.onNodeWithText("선택 결과: 진행 중인 작업이 끝나면", substring = true).assertExists()
    }
}
