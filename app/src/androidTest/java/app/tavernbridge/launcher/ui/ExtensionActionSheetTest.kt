package app.tavernbridge.launcher.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.theme.SillyTavernLauncherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ExtensionActionSheetTest {
    @get:Rule val ui = createComposeRule()
    private fun extension(i: Int) = ManagedExtension("id-$i", "rev-$i", "확장 $i", "1.0", "user", false,
        "clean", "abc1234", true, "", remoteAhead = 1)

    @Test fun selectingAnAvailableUpdateOpensActionsThenRequiresConfirmation() {
        val actions = mutableListOf<ExtensionAction>()
        val item = extension(1)
        ui.setContent { SillyTavernLauncherTheme(AppTheme.LIGHT, DisplayPreferences(reducedMotion = true)) {
            ExtensionManagerDialog(ExtensionInventory(listOf(item), null, 0), false, false, true, "",
                {}, {}, {}, { _, action -> actions += action }, {}, embedded = true)
        } }
        ui.onNodeWithText("확장 1").performClick()
        ui.onNode(hasText("업데이트") and hasAnyAncestor(isDialog())).assertHasClickAction().performClick()
        ui.onNodeWithText("업데이트할까요?").assertExists()
        ui.runOnIdle { assertEquals(emptyList<ExtensionAction>(), actions) }
        ui.onNode(hasText("업데이트") and hasAnyAncestor(isDialog())).performClick()
        ui.runOnIdle { assertEquals(listOf(ExtensionAction.UPDATE), actions) }
    }

    @Test fun visibleListEndAutomaticallyRequestsEachCursorOnlyOnce() {
        val items = (1..36).map(::extension)
        val pages = mutableStateOf(ExtensionInventory(items, 36, 0))
        val requests = mutableListOf<Int>()
        ui.setContent { SillyTavernLauncherTheme(AppTheme.LIGHT, DisplayPreferences(reducedMotion = true)) {
            ExtensionManagerDialog(pages.value, false, false, true, "", {}, { requests += requireNotNull(pages.value.nextCursor) },
                {}, { _, _ -> }, {}, embedded = true)
        } }
        ui.runOnIdle { assertEquals(emptyList<Int>(), requests) }
        ui.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToIndex(items.size + 1)
        ui.waitForIdle()
        ui.runOnIdle { assertEquals(listOf(36), requests); pages.value = pages.value.copy(recoveryMessage = "changed") }
        ui.runOnIdle { assertEquals(listOf(36), requests); pages.value = ExtensionInventory(items, null, 0) }
        ui.onNodeWithText("확장 목록 더 보기").assertDoesNotExist()
    }
}
