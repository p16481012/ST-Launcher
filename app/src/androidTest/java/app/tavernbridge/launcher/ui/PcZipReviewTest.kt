package app.tavernbridge.launcher.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.tavernbridge.launcher.data.*
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.theme.SillyTavernLauncherTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class PcZipReviewTest {
    @get:Rule val ui = createComposeRule()
    @Test fun selectingAnArchiveNeverStopsServerOrReplacesPhoneFiles() {
        val actions = mutableListOf<Pair<Boolean,Boolean>>()
        val preview = PcZipPreview("user_data",true,"a".repeat(64),"b".repeat(64),
            mapOf("pc" to "work"),listOf("work"),listOf(PcZipCategory("worlds",1,2)),1,2,100,1,300,0,null)
        ui.setContent { SillyTavernLauncherTheme(AppTheme.LIGHT,DisplayPreferences(reducedMotion=true)) {
            PcZipReviewScreen(preview,true,{}, {_,_->}) { replace, backup -> actions += replace to backup }
        } }
        ui.onNodeWithText("다음").performClick()
        ui.onNodeWithText("새 파일 1개를 더할까요?").assertExists()
        ui.onNodeWithText("계속하면 서버를 잠깐 끄고, 작업이 끝나면 다시 켜요.").assertExists()
        ui.runOnIdle { assertTrue(actions.isEmpty()) }
        ui.onNodeWithText("백업 후 합치기").performClick()
        ui.runOnIdle { assertEquals(listOf(false to true), actions) }
    }
}
