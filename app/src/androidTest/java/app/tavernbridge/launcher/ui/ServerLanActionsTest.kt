package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.theme.SillyTavernLauncherTheme
import app.tavernbridge.launcher.ui.theme.LocalLauncherColors
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
        var hidden = false
        compose.setContent {
            var editor by remember { mutableStateOf("") }
            var explanation by remember { mutableStateOf("") }
            var completed by remember { mutableStateOf<OperationResultSummary?>(null) }
            var applied by remember { mutableStateOf(false) }
            SillyTavernLauncherTheme(AppTheme.LIGHT) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (editor.isEmpty()) {
                        ServerLanRows(running.copy(environment = running.environment.copy(externalAccessEnabled = applied)), { editor = it }, {}, { explanation = it })
                        Text(explanation)
                    } else LanSecuritySettingsPage(
                        LanSecuritySnapshot(false, LanWhitelist.loopbacks, false, false), onDismiss = { editor = ""; hidden = true },
                        onConfirm = { change ->
                            assertTrue(change.listen); change.close(); ++calls
                            if (calls > 1) {
                                completed = OperationResultSummary("save-lan-security", "", "", true, completedAt = "now", completedAtMillis = 1, durationSeconds = 0)
                                applied = true
                            }; calls > 1
                        }, requestedListen = editor == "toggle-listen", running = true, result = completed)
                 }
            }
        }
        compose.onNodeWithText("외부 접속").performClick()
        compose.onNodeWithText("저장할 때 잠시", substring = true).assertExists()
        compose.onNodeWithText("접속 주소").assertDoesNotExist()
        compose.onNodeWithText("같은 Wi-Fi에서 접속 허용").performClick()
        compose.onNodeWithText("다른 기기 접속 허용").assertIsOn()
        compose.onNodeWithText("적용하고 재시작").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("접속 허용 범위와 인증 변경 내용을 확인했습니다.", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("적용하고 재시작").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("바뀌는 내용 확인").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithText("돌아가기").performClick()
        compose.onNodeWithText("적용하고 재시작").performScrollTo().performClick()
        compose.onNodeWithText("확인하고 적용").performClick()
        compose.onNodeWithText("지금은 적용할 수 없어요.", substring = true).assertExists()
        compose.onNodeWithText("적용하고 재시작").performScrollTo().performClick()
        compose.onNodeWithText("확인하고 적용").performClick()
        compose.runOnIdle { assertEquals(2, calls); assertTrue(hidden) }
        compose.onNodeWithText("같은 Wi-Fi에서 접속 허용").assertIsOn()
        compose.onNodeWithText("접속 주소").assertExists()
    }

    @Test fun portValidatesOnImeAndSavesOnlyTheCorrectedValue() {
        var saved = 0
        compose.setContent {
            SillyTavernLauncherTheme(AppTheme.LIGHT) {
                var showing by remember { mutableStateOf(true) }
                if (showing) ServerPortDialog(8000, 8000, true, true, { showing = false }) { saved = it; showing = false }
            }
        }
        val input = compose.onNode(hasSetTextAction())
        input.assertIsFocused().performTextReplacement("80")
        compose.onNodeWithText("저장하고 재시작").assertIsNotEnabled()
        compose.onNodeWithText("1024–65535 사이로 입력해 주세요.").assertDoesNotExist()
        input.performImeAction()
        compose.onNodeWithText("1024–65535 사이로 입력해 주세요.").assertExists()
        input.performTextReplacement("8123")
        compose.onNodeWithText("1024–65535 사이로 입력해 주세요.").assertDoesNotExist()
        compose.onNodeWithText("config.yaml은 8000", substring = true).assertExists()
        input.performImeAction()
        compose.runOnIdle { assertEquals(8123, saved) }
    }

    @Test fun themeAndTextAnimateInPlaceButSnapWhenMotionIsReduced() {
        compose.mainClock.autoAdvance = false
        var dark by mutableStateOf(false)
        var display by mutableStateOf(DisplayPreferences(reducedMotion = false))
        var observedCanvas = androidx.compose.ui.graphics.Color.Unspecified
        var observedScale = 0f
        var starts = 0
        compose.setContent {
            SillyTavernLauncherTheme(if (dark) AppTheme.DARK else AppTheme.LIGHT, display) {
                val canvas = LocalLauncherColors.current.canvas
                val scale = LocalDensity.current.fontScale
                SideEffect { observedCanvas = canvas; observedScale = scale }
                DisposableEffect(Unit) { starts++; onDispose {} }
            }
        }
        compose.waitForIdle()
        val systemScale = android.provider.Settings.Global.getFloat(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val initialCanvas = observedCanvas
        val initialScale = observedScale
        compose.runOnIdle { dark = true; display = display.copy(textSize = LauncherTextSize.LARGE) }
        compose.mainClock.advanceTimeBy(96)
        val middleCanvas = observedCanvas
        val middleScale = observedScale
        compose.mainClock.advanceTimeBy(320)
        val darkCanvas = observedCanvas
        assertTrue(initialCanvas != darkCanvas)
        assertTrue(observedScale > initialScale)
        if (systemScale == 1f) {
            assertTrue(initialCanvas != middleCanvas && middleCanvas != darkCanvas)
            assertTrue(middleScale > initialScale && observedScale > middleScale)
        }
        compose.runOnIdle { dark = false; display = DisplayPreferences(reducedMotion = true) }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(initialCanvas, observedCanvas); assertEquals(initialScale, observedScale, .001f); assertEquals(1, starts) }
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
