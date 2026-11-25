package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.theme.SillyTavernLauncherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChatRecoveryUiTest {
    @get:Rule val ui = createComposeRule()
    private val row = ChatDamage("data/me/chats/A/old.jsonl","bad_tail",2,1,500,"r","copy",4,99,"b","verified")
    private val actions = mutableListOf<String>()
    private fun show(row:ChatDamage=this.row, running:Boolean=false){
        val report=DiagnosticReport("2026-11-26 10:00:00",emptyList()).withChat(ChatScanPage(1,0,0,"",listOf(row)))
        val state=mutableStateOf(LauncherUiState(isWorking=false,diagnosticReport=report,
            environment=EnvironmentStatus(termuxInstalled=true,commandPermissionGranted=true,managerConnected=true,sillyTavernInstalled=true,processRunning=running),
            termuxSetup=TermuxSetupState(TermuxSetupStatus.READY)))
        ui.setContent { SillyTavernLauncherTheme(AppTheme.LIGHT,DisplayPreferences(reducedMotion=true)) {
            val stop:suspend (Long)->Boolean = { actions+="stop"; state.value=state.value.copy(environment=state.value.environment.copy(processRunning=false));true }
            CompositionLocalProvider(LocalVerifiedStop provides stop) {
                ChatRecoveryDetail(state.value,{ actions+="scan" },{_,mode->actions+=mode},{actions+="undo"},Modifier.fillMaxSize())
            }
        } }
    }
    @Test fun verifiedRowsOfferBulkAndRequireExplicitConfirmationAfterStopping(){
        show(running=true)
        ui.onNodeWithText("사본으로 모두 되찾기 (1)").performClick()
        ui.onNodeWithText("서버 끄고 이어가기").performClick()
        ui.onNodeWithText("확인된 1개 되찾기").assertExists()
        ui.runOnIdle{assertEquals(listOf("stop"),actions)}
        ui.onNodeWithText("확인된 1개 되찾기").performClick()
        ui.runOnIdle{assertEquals(listOf("stop","restore-backup"),actions)}
    }
    @Test fun uncertainMatchCanOnlyMakeNewFile(){
        show(row.copy(match="candidate"))
        ui.onNodeWithText("사본으로 모두 되찾기 (1)").assertDoesNotExist()
        ui.onNodeWithText(row.label).performClick()
        ui.onNodeWithText("사본을 새 파일로 저장").performClick()
        ui.runOnIdle{assertEquals(listOf("candidate-copy"),actions)}
    }
    @Test fun noBackupTruncatedTailCanBeLeftAlone(){
        show(row.copy(match="none",backup="",backupRevision=""))
        ui.onNodeWithText(row.label).performClick()
        ui.onNodeWithText("잘린 마지막 줄만 정리").assertExists()
        ui.onNodeWithText("그대로 두기").performClick()
        ui.onNodeWithText("이번 결과에 남은 채팅이 없어요").assertExists()
        ui.runOnIdle{assertEquals(emptyList<String>(),actions)}
    }
}
