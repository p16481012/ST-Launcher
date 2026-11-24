package app.tavernbridge.launcher.ui

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tavernbridge.launcher.model.AppTheme
import app.tavernbridge.launcher.model.DisplayPreferences
import app.tavernbridge.launcher.ui.theme.SillyTavernLauncherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkSheetGestureTest {
    @get:Rule val compose = createComposeRule()
    private var cancels = 0
    private var folds = 0

    private fun showWork(reduced: Boolean) {
        compose.setContent {
            var open by remember { mutableStateOf(true) }
            val started = remember { System.currentTimeMillis() }
            SillyTavernLauncherTheme(AppTheme.LIGHT, DisplayPreferences(reducedMotion = reduced)) {
                if (open) WorkingOverlay("백업 중", null, started, false, { cancels++ }, { folds++; open = false })
                else WorkingMiniBar("백업 중", null) { open = true }
            }
        }
    }

    @Test fun tapOnlyHandleFoldsAndMiniBarReopens() {
        showWork(reduced = false)
        // onNode also requires exactly one match: a second arrow-button would fail the test.
        compose.onNodeWithContentDescription("진행창 접기").assertHasClickAction().performTouchInput { click() }
        compose.onNodeWithContentDescription("진행창 펼치기").performClick()
        compose.onNodeWithContentDescription("진행창 접기").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, folds); assertEquals(0, cancels) }
    }

    @Test fun reducedMotionHandleStillSupportsSwipeAndAccessibilityClick() {
        showWork(reduced = true)
        compose.onNodeWithContentDescription("진행창 접기").performTouchInput { swipeDown(startY = 1f, endY = bottom - 1f) }
        compose.onNodeWithContentDescription("진행창 펼치기").performClick()
        compose.onNodeWithContentDescription("진행창 접기").assertHasClickAction().performClick()
        compose.onNodeWithContentDescription("진행창 펼치기").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, folds); assertEquals(0, cancels) }
    }

    @Test fun draggingStandardSheetDownFoldsWithoutCancelling() {
        showWork(reduced = false)
        val handle = compose.onNodeWithContentDescription("진행창 접기")
        // Swipe toward the bottom of the actual sheet, not just across its 4dp painted line.
        val end = compose.onNodeWithText("기록 보기").fetchSemanticsNode().boundsInRoot.center.y
        val distance = end - handle.fetchSemanticsNode().boundsInRoot.center.y
        handle.performTouchInput { swipe(center, center + Offset(0f, distance), durationMillis = 140) }
        compose.onNodeWithContentDescription("진행창 펼치기").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, folds); assertEquals(0, cancels) }
    }
}
