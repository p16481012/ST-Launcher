package app.tavernbridge.launcher.ui

import android.app.Activity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.tavernbridge.launcher.data.DataLibraryAccess
import app.tavernbridge.launcher.data.LibraryWrite
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.termux.TermuxCommandResult
import app.tavernbridge.launcher.ui.theme.SillyTavernLauncherTheme
import java.util.Base64
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DataLibraryFlowTest {
    @get:Rule val ui = createComposeRule()
    private fun enc(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())
    private val writes = mutableListOf<LibraryWrite>()
    private val calls = mutableListOf<String>()
    private fun launch() {
        val library = DataLibraryAccess(InstrumentationRegistry.getInstrumentation().targetContext,{ listOf("default-user") }) { args,_ ->
            calls += args
            val output = if(args.startsWith("library-list")) {
                listOf("item",enc("chats/남은 대화"),enc("남은 대화"),"1000","5","100", "1".repeat(64),"0","-").joinToString("\t") +
                    "\nunexamined=0\nnext_cursor="
            } else "unexamined=2\nnext_cursor="
            TermuxCommandResult("",output,"",0,Activity.RESULT_OK,"")
        }
        ui.setContent {
            SillyTavernLauncherTheme(AppTheme.LIGHT,DisplayPreferences(reducedMotion=true)) {
                DataLibraryScreen(LauncherUiState(isWorking=false), library, { writes += it }, {})
            }
        }
    }
    @Test fun hubNeverRunsDeepChecksAndOrphanTrashNeedsSelectionAndConfirmation() {
        launch()
        ui.onNodeWithText("카드 없이 남은 채팅").performScrollTo().performClick()
        ui.onNodeWithText("남은 대화").assertExists()
        ui.onNodeWithText("1개 휴지통으로").assertDoesNotExist()
        ui.onNodeWithText("선택").performClick()
        ui.onNodeWithText("남은 대화").performClick()
        ui.onNodeWithText("1개 휴지통으로").performClick()
        ui.onNodeWithText("1개를 휴지통으로 옮길까요?").assertExists()
        ui.runOnIdle { assertTrue(writes.isEmpty()); assertTrue(calls.all { it.startsWith("library-list") }) }
        ui.onNodeWithText("휴지통으로 옮기기").performClick()
        ui.runOnIdle { assertEquals("library-trash",writes.single().operation);assertTrue(writes.single().pause) }
    }
    @Test fun duplicatesIsAnExplicitScanAndIncompleteCandidatesAreVisible() {
        launch()
        ui.onNodeWithText("중복 카드 찾기").performScrollTo().performClick()
        ui.onNodeWithText("확인 못 한 카드 2개는 정리 후보에 넣지 않았어요.").assertExists()
        ui.runOnIdle { assertEquals(1,calls.size);assertTrue(calls.single().startsWith("library-duplicates")) }
    }
}
