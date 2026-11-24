package app.tavernbridge.launcher.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class LoadingFeedbackUiTest {
    @get:Rule val ui = createComposeRule()

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
