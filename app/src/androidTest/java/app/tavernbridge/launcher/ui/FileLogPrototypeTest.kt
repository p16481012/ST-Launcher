package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.theme.SillyTavernLauncherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FileLogPrototypeTest {
    @get:Rule val ui = createComposeRule()
    @Test fun backHighlightsOnlyTheLastTappedOriginAndNeverOnOrdinaryRecomposition() {
        val links = ReturnAnchors()
        val initial = LauncherUiState()
        val log = initial.visit(MainSection.LOGS, ToolPage.LOGS)
        links.route(initial.navigation, 1)
        links.tap("home-action:로그", 2)
        links.route(log.navigation, 3)
        assertEquals(null, links.returning)
        links.route(log.previousScreen().navigation, 4)
        assertEquals("home-action:로그", links.returning)
        links.clearHighlight("home-action:로그")
        links.route(initial.navigation, 5)
        assertEquals(null, links.returning)
    }
    private fun entry(name: String) = TavernFileEntry(name, name, false, 100, "", false, "a".repeat(64))
    private fun files(names: List<String>, onTrash: (List<TavernFileEntry>) -> Unit = {}) {
        val state = LauncherUiState(fileBrowserOpen = true, isWorking = false, environment = EnvironmentStatus(managerConnected = true),
            fileBrowserEntries = names.map(::entry))
        ui.setContent {
            SillyTavernLauncherTheme(AppTheme.LIGHT, DisplayPreferences(reducedMotion = true)) {
                TavernFileManagerDialog(state, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {},
                    {}, {}, {}, onTrash, {}, null, {}, embedded = true)
            }
        }
    }

    @Test fun longPressSelectsAndOnlyMultipleItemsNeedConfirmation() {
        val moved = mutableListOf<List<String>>()
        files(listOf("alpha.txt", "beta.txt")) { moved.add(it.map(TavernFileEntry::name)) }
        fun choose() = ui.onNodeWithText("alpha.txt").performSemanticsAction(SemanticsActions.OnLongClick)
        choose()
        ui.onNodeWithText("1개 선택").assertExists()
        ui.onNodeWithText("이 폴더에서 찾기").assertDoesNotExist()
        ui.onNodeWithText("beta.txt").performClick()
        ui.onNodeWithText("2개 휴지통으로 이동").performClick()
        ui.onNodeWithText("2개 항목을 휴지통으로 옮길까요?").assertExists()
        ui.onNodeWithText("취소").performClick()
        ui.onNodeWithText("beta.txt").performClick()
        ui.onNodeWithText("1개 휴지통으로 이동").performClick()
        ui.runOnIdle { assertEquals(listOf(listOf("alpha.txt")), moved) }
        choose()
        ui.onNodeWithText("alpha.txt").performClick()
        ui.onNodeWithText("이 폴더에서 찾기").assertExists()
    }

    @Test fun emptyFolderKeepsCreateButNotSelect() {
        files(emptyList())
        ui.onNodeWithText("빈 폴더예요").assertExists()
        ui.onNodeWithText("선택").assertIsNotEnabled()
        ui.onNodeWithText("새로 만들기").assertIsEnabled()
    }

    @Test fun logAwayFromBottomDoesNotJumpAndErrorFilterIsImmediate() {
        fun raw(server: IntRange, extra: String) = "===== SillyTavern server.log =====\n" +
            server.joinToString("\n") { "ordinary item $it" } + extra + "\n\n===== SillyTavern access.log =====\nGET /"
        val log = mutableStateOf(raw(1..100, ""))
        ui.setContent {
            SillyTavernLauncherTheme(AppTheme.LIGHT, DisplayPreferences(reducedMotion = true)) {
                LogViewer(log.value, "SERVER", Modifier.fillMaxSize())
            }
        }
        ui.onNodeWithContentDescription("맨 아래로").assertDoesNotExist()
        ui.onNodeWithTag("live-log-viewport").performTouchInput { swipeDown() }
        ui.onNodeWithContentDescription("맨 아래로").assertExists()
        ui.runOnIdle { log.value = raw(2..100, "\nWARN incoming") }
        ui.onNodeWithContentDescription("맨 아래로").assertExists().performClick()
        ui.onNodeWithContentDescription("맨 아래로").assertDoesNotExist()
        ui.onNodeWithText("오류만").performClick()
        ui.onNodeWithText("WARN incoming").assertExists()
        ui.onNodeWithText("ordinary item", substring = true).assertDoesNotExist()
    }

    @Test fun anotherFileCanBeSelectedWhileTabletPreviewIsPending() {
        val selected = mutableListOf<String>()
        val state = mutableStateOf(LauncherUiState(fileBrowserOpen = true, isWorking = false, environment = EnvironmentStatus(managerConnected = true),
            fileBrowserEntries = listOf(entry("a.txt"), entry("b.txt"))))
        ui.setContent {
            SillyTavernLauncherTheme(AppTheme.LIGHT, DisplayPreferences(reducedMotion = true)) {
                TavernFileManagerDialog(state.value, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {},
                    { path -> selected += path; state.value = state.value.copy(pendingFilePreviewPath = path) },
                    null, {}, embedded = true, sideBySide = true)
            }
        }
        ui.onNodeWithText("a.txt").performClick()
        ui.onNodeWithText("b.txt").performClick()
        ui.runOnIdle { assertEquals(listOf("a.txt", "b.txt"), selected) }
    }
}
