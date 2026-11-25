package app.tavernbridge.launcher.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.theme.SillyTavernLauncherTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StopToContinueUiTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val live = LauncherUiState(isWorking = false, environment = EnvironmentStatus(termuxInstalled = true,
        commandPermissionGranted = true, managerConnected = true, sillyTavernInstalled = true, processRunning = true),
        termuxSetup = TermuxSetupState(TermuxSetupStatus.READY))
    private val origin = mutableStateOf("one.zip")
    private val answer = CompletableDeferred<Boolean>()
    private var calls = 0
    private var opened = 0

    private fun show() = ui.setContent {
        SillyTavernLauncherTheme(AppTheme.LIGHT, DisplayPreferences(reducedMotion = true)) {
            val verify: suspend (Long) -> Boolean = { calls++; answer.await() }
            CompositionLocalProvider(LocalVerifiedStop provides verify) {
                val resume = rememberStopToContinue(live, origin.value)
                var confirm by remember { mutableStateOf(false) }
                Button(enabled = resume != null, onClick = { resume?.invoke("백업 복원") { opened++; confirm = true } }) {
                    Text("복원 선택")
                }
                if (confirm) Text("복원 최종 확인")
            }
        }
    }
    private fun ask() = ui.onNodeWithText("복원 선택").performClick()
    private fun stop() = ui.onNodeWithText("서버 끄고 이어가기").performClick()

    @Test fun onlyVerifiedStopOpensOriginalConfirmationOnce() {
        show(); ask(); ui.onNodeWithText("취소").performClick()
        ui.runOnIdle { assertEquals(0, calls) }
        ask(); stop()
        ui.onNodeWithText("복원 최종 확인").assertDoesNotExist()
        ui.runOnIdle { assertEquals(1, calls); answer.complete(true) }
        ui.onNodeWithText("복원 최종 확인").assertExists()
        ui.runOnIdle { assertEquals(1, opened) }
    }
    @Test fun failureDoesNotOpenOriginalConfirmation() {
        show(); ask(); stop()
        ui.runOnIdle { answer.complete(false) }
        ui.onNodeWithText("복원 최종 확인").assertDoesNotExist()
        ui.runOnIdle { assertEquals(0, opened) }
    }
    @Test fun leavingSelectedArchiveDropsEvenSuccessfulInFlightStop() {
        show(); ask(); stop()
        ui.runOnIdle { origin.value = "two.zip" }; ui.waitForIdle()
        ui.runOnIdle { answer.complete(true) }
        ui.onNodeWithText("복원 최종 확인").assertDoesNotExist()
        ui.runOnIdle { assertEquals(0, opened) }
    }
}
