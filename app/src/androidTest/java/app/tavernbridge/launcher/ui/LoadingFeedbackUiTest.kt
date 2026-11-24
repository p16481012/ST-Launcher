package app.tavernbridge.launcher.ui

import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.components.OperationResultCard
import app.tavernbridge.launcher.ui.theme.SillyTavernLauncherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LoadingFeedbackUiTest {
    @get:Rule val ui = createComposeRule()

    @Test fun checkingAndResultCardsShareScreenGuttersAndContentAlignment() {
        val state = LauncherUiState(isWorking = false, termuxSetup = TermuxSetupState(TermuxSetupStatus.CHECKING))
        val result = OperationResultSummary("extension-update", "확장 업데이트", "확장 업데이트 작업을 완료했어요.",
            true, completedAt = "2026-11-24", completedAtMillis = 0L, durationSeconds = 2)
        ui.mainClock.autoAdvance = false
        ui.setContent { SillyTavernLauncherTheme(AppTheme.DARK, DisplayPreferences(reducedMotion = true)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().testTag("host").padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    EnvironmentCheckingCard(state, {}, {}, {}, "", {}, Modifier.testTag("checking"))
                    OperationResultCard(result, {}, Modifier.testTag("result"))
                }
            }
        } }
        ui.onNodeWithTag("checking").assertDoesNotExist()
        ui.mainClock.advanceTimeBy(PROGRESS_REVEAL_DELAY_MS + 96)
        val host = ui.onNodeWithTag("host").getUnclippedBoundsInRoot()
        val check = ui.onNodeWithTag("checking").getUnclippedBoundsInRoot()
        val done = ui.onNodeWithTag("result").getUnclippedBoundsInRoot()
        assertEquals(host.left.value + 20f, check.left.value, 1f)
        assertEquals(host.right.value - 20f, check.right.value, 1f)
        assertEquals(done.left, check.left)
        assertEquals(done.right, check.right)
        assertEquals(ui.onNodeWithText(result.detail).getUnclippedBoundsInRoot().left,
            ui.onNodeWithText("확인이 끝날 때까지 설치 화면으로 전환하지 않습니다.").getUnclippedBoundsInRoot().left)
        captureModalWindowSnapshot("home-install-check")
    }

    @Test fun briefReadNeverFlashesAndLongReadShowsUntilCompletion() {
        val request = mutableStateOf<Int?>(null)
        ui.mainClock.autoAdvance = false
        ui.setContent {
            if (rememberProgressVisible(request.value != null, request.value)) Text("진행 표시")
        }
        fun tick(ms: Long) {
            ui.mainClock.advanceTimeBy(ms)
            ui.waitForIdle()
        }
        fun start(id: Int?) {
            ui.runOnUiThread { request.value = id }
            tick(32)
        }
        val progress = ui.onNodeWithText("진행 표시")
        start(1)
        tick(160)
        progress.assertDoesNotExist()
        start(null)
        tick(PROGRESS_REVEAL_DELAY_MS + 64)
        progress.assertDoesNotExist()
        start(2)
        tick(PROGRESS_REVEAL_DELAY_MS + 64)
        progress.assertExists()
        start(3) // The second request's timer does not reveal a new short read.
        progress.assertDoesNotExist()
        tick(PROGRESS_REVEAL_DELAY_MS + 64)
        progress.assertExists()
        start(null)
        progress.assertDoesNotExist()
    }
}
