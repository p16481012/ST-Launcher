package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.theme.SillyTavernLauncherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    @Test fun searchClearIconIsOnlyShownWithTextAndClearsTheField() {
        val query = mutableStateOf("")
        ui.setContent { SillyTavernLauncherTheme(AppTheme.LIGHT) {
            FileLogSearchField(query.value, { query.value = it }, "이 폴더에서 찾기")
        } }
        ui.onNodeWithContentDescription("검색어 지우기").assertDoesNotExist()
        ui.onNode(hasSetTextAction()).performTextInput("notes")
        ui.onNodeWithContentDescription("검색어 지우기").assertHasClickAction().performClick()
        ui.onNodeWithContentDescription("검색어 지우기").assertDoesNotExist()
        ui.runOnIdle { assertEquals("", query.value) }
    }

    @Test fun folderOverflowOpensActionsWithoutNavigatingIntoIt() {
        val visits = mutableListOf<String>()
        val state = LauncherUiState(fileBrowserOpen = true, isWorking = false,
            environment = EnvironmentStatus(managerConnected = true),
            fileBrowserEntries = listOf(entry("data").copy(isDirectory = true)))
        ui.setContent { SillyTavernLauncherTheme(AppTheme.LIGHT) {
            TavernFileManagerDialog(state, { visits += it }, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {},
                {}, {}, {}, {}, {}, null, {}, embedded = true)
        } }
        ui.onNodeWithContentDescription("data 작업").performClick()
        ui.onNodeWithText("이름 변경").assertIsDisplayed()
        ui.runOnIdle { assertEquals(emptyList<String>(), visits) }
    }

    @Test fun createConflictNamesTheExistingTypeAndOffersANewNameBeforeSubmitting() {
        val created = mutableListOf<String>()
        val state = LauncherUiState(fileBrowserOpen = true, isWorking = false, environment = EnvironmentStatus(managerConnected = true),
            fileBrowserEntries = listOf(entry("notes").copy(isDirectory = true)))
        ui.setContent { SillyTavernLauncherTheme(AppTheme.LIGHT) {
            TavernFileManagerDialog(state, {}, {}, {}, {}, {}, {}, {}, { created += it }, { _, _ -> }, {},
                {}, {}, {}, {}, {}, null, {}, embedded = true)
        } }
        ui.onNodeWithText("새로 만들기").performClick()
        ui.onNodeWithText("새 파일").performClick()
        ui.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("notes")
        ui.onNodeWithText("같은 이름의 폴더가 있어요.", substring = true).assertExists()
        ui.onNodeWithText("확인").assertIsNotEnabled()
        ui.onNodeWithText("제안 이름 사용: notes (1)").performClick()
        ui.onNodeWithText("확인").performClick()
        ui.runOnIdle { assertEquals(listOf("notes (1)"), created) }
    }

    @Test fun trashHasVisibleEntryPointAndRestoresTheSelectedReceiptNotJustLastUndo() {
        val captured = mutableListOf<String>()
        val id = "1234567890123-11111111-2222-3333-4444-555555555555"
        val state = LauncherUiState(fileBrowserOpen = true, isWorking = false, environment = EnvironmentStatus(managerConnected = true))
        ui.setContent { SillyTavernLauncherTheme(AppTheme.LIGHT) {
            // The real embedded route receives Scaffold's safe-area padding.
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                TavernFileManagerDialog(state, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, null, {},
                embedded = true, readTrash = { TavernTrashPage(listOf(TavernTrashEntry(id, "data/기록.txt", false)), "", 0) },
                onRestoreTrash = { captured += it })
            }
        } }
        ui.onNodeWithText("휴지통").performClick()
        ui.onNodeWithText("SillyTavern/data/기록.txt").assertExists()
        ui.captureManagementScreen("files-trash", "기록.txt")
        ui.onNodeWithContentDescription("data/기록.txt 작업").performClick()
        ui.onNodeWithText("원래 위치로 복원").assertHasClickAction()
        ui.runOnIdle { assertEquals(emptyList<String>(), captured) }
        ui.onNodeWithText("원래 위치로 복원").performClick()
        ui.runOnIdle { assertEquals(listOf(id), captured) }
        ui.onNodeWithContentDescription("파일 목록으로 돌아가기").performClick()
        ui.onNodeWithText("빈 폴더예요").assertExists()
    }

    @Test fun navigatingToFolderNeverShowsALinearBarAndSlowReadsShowInlineStatus() {
        val state = mutableStateOf(LauncherUiState(fileBrowserOpen = true, isWorking = false,
            environment = EnvironmentStatus(managerConnected = true),
            fileBrowserEntries = listOf(entry("data").copy(isDirectory = true), entry("config.yaml"))))
        ui.setContent { SillyTavernLauncherTheme(AppTheme.LIGHT, DisplayPreferences(reducedMotion = true)) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            TavernFileManagerDialog(state.value, { path -> state.value = state.value.copy(fileBrowserPath = path,
                fileBrowserEntries = emptyList(), fileBrowserLoading = true) }, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {},
                {}, {}, {}, {}, {}, null, {}, embedded = true)
            }
        } }
        ui.onNodeWithText("새로 만들기").assertHasClickAction().assertIsDisplayed()
        ui.onNodeWithText("가져오기").assertHasClickAction().assertIsDisplayed()
        ui.onNodeWithText("선택").assertHasClickAction().assertIsDisplayed()
        ui.onNodeWithContentDescription("data 작업").assertHasClickAction().assertIsDisplayed()
        ui.onNodeWithContentDescription("config.yaml 작업").assertHasClickAction().assertIsDisplayed()
        ui.captureManagementScreen("files-browser", "새로 만들기")
        ui.mainClock.autoAdvance = false
        ui.onNodeWithText("data").performClick()
        ui.mainClock.advanceTimeBy(128)
        ui.onNodeWithText("목록을 불러오는 중…").assertDoesNotExist()
        ui.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
        ui.mainClock.advanceTimeBy(500)
        ui.onNodeWithText("목록을 불러오는 중…").assertExists()
        ui.runOnIdle { state.value = state.value.copy(fileBrowserLoading = false) }
        ui.mainClock.advanceTimeBy(32)
        ui.onNodeWithText("목록을 불러오는 중…").assertDoesNotExist()
        ui.mainClock.autoAdvance = true
    }
    private fun files(names: List<String>, textSize: LauncherTextSize = LauncherTextSize.NORMAL,
        onTrash: (List<TavernFileEntry>) -> Unit = {}) {
        val state = LauncherUiState(fileBrowserOpen = true, isWorking = false, environment = EnvironmentStatus(managerConnected = true),
            fileBrowserEntries = names.map(::entry))
        ui.setContent {
            SillyTavernLauncherTheme(AppTheme.LIGHT, DisplayPreferences(reducedMotion = true, textSize = textSize)) {
                TavernFileManagerDialog(state, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {},
                    {}, {}, {}, onTrash, {}, null, {}, embedded = true)
            }
        }
    }

    @Test fun sortTextHasEndInsetAndStillOpensAtLargestLauncherTextSize() {
        files(emptyList(), textSize = LauncherTextSize.MAXIMUM)
        val field = ui.onNode(hasSetTextAction()).getUnclippedBoundsInRoot()
        val label = ui.onNodeWithText("최근 수정순", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("Sort label needs visible space inside the outline", field.right - label.right >= 15.dp)
        val action = ui.onNode(hasText("최근 수정순") and hasClickAction())
        val target = action.getUnclippedBoundsInRoot()
        assertTrue(target.bottom - target.top >= 48.dp && target.right - target.left >= 48.dp)
        action.performClick()
        ui.onNodeWithText("현재 폴더 정렬").assertIsDisplayed()
        ui.onNodeWithText("이름순").performClick()
        ui.onNodeWithText("현재 폴더 정렬").assertDoesNotExist()
        ui.onNode(hasText("이름순") and hasClickAction()).assertIsDisplayed()
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
        ui.onNodeWithText("선택").assertDoesNotExist()
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

    @Test fun phoneSheetAutomaticallyReadsExtensionlessTextButNeverOpensASecret() {
        val requested = mutableListOf<String>()
        val document = mutableStateOf<TavernFilePreview?>(null)
        val state = LauncherUiState(fileBrowserOpen = true, isWorking = false, environment = EnvironmentStatus(managerConnected = true),
            fileBrowserEntries = listOf(entry("README"), entry("secrets.json").copy(sensitive = true)))
        ui.setContent {
            SillyTavernLauncherTheme(AppTheme.LIGHT, DisplayPreferences(reducedMotion = true)) {
                TavernFileManagerDialog(state, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {},
                    { path -> requested += path; document.value = TavernFilePreview(path, 5, text = "Hello") },
                    document.value, { document.value = null }, embedded = true)
            }
        }
        ui.onNodeWithText("README").performClick()
        ui.onNodeWithText("Hello").assertExists()
        ui.onNodeWithText("내용 미리보기").assertDoesNotExist()
        ui.onNodeWithContentDescription("시트 닫기").performClick()
        ui.onNodeWithText("secrets.json").performClick()
        ui.onNodeWithText("민감한 파일 내용은 자동으로 표시하지 않아요.").assertExists()
        ui.onNodeWithText("Hello").assertDoesNotExist()
        ui.runOnIdle { assertEquals(listOf("README"), requested) }
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
