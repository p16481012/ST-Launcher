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
    @Test fun mergeShowsPersonaAndConflictScopeBeforeOfferingReplacementInventory() {
        val preview = PcZipPreview("user_data",true,"a".repeat(64),"b".repeat(64),
            mapOf("pc" to "work"),listOf("work"),listOf(PcZipCategory("avatars",1,0)),1,1,100,1,300,1,null,
            relations=mapOf("groups" to 1),exclusions=mapOf("settings" to 1))
        var requests=0
        var avatarRequests=0
        ui.setContent { SillyTavernLauncherTheme(AppTheme.LIGHT,DisplayPreferences(reducedMotion=true)) {
            PcZipReviewScreen(preview,false,{}, {_,_->}, checkImpact={ requests++ }, toggleAvatars={ avatarRequests++ }) {_,_-> fail("Unexpected write") }
        } }
        ui.onNodeWithText("페르소나 그림 1개만 가져와요.",substring=true).performScrollTo().assertExists()
        ui.onNodeWithText("페르소나 그림 포함").performClick()
        ui.onNodeWithText("(그룹 1개)",substring=true).assertExists()
        ui.onNodeWithText("합치지 않는 항목").performScrollTo().performClick()
        ui.onNodeWithText("사용자 설정").assertExists()
        ui.onNodeWithText("ZIP에 담긴 범위로 교체").performScrollTo().performClick()
        ui.onNodeWithText("교체 전 휴대폰 자료 확인").performScrollTo().performClick()
        ui.runOnIdle { assertEquals(1,requests); assertEquals(1,avatarRequests) }
    }
    @Test fun selectingAnArchiveNeverStopsServerOrReplacesPhoneFiles() {
        val actions = mutableListOf<Pair<Boolean,Boolean>>()
        val preview = PcZipPreview("user_data",true,"a".repeat(64),"b".repeat(64),
            mapOf("pc" to "work"),listOf("work"),listOf(PcZipCategory("worlds",1,2)),1,2,100,1,300,0,null)
        ui.setContent { SillyTavernLauncherTheme(AppTheme.LIGHT,DisplayPreferences(reducedMotion=true)) {
            PcZipReviewScreen(preview,true,{}, {_,_->}) { replace, backup -> actions += replace to backup }
        } }
        ui.onNodeWithText("다음").performClick()
        ui.onNodeWithText("새 파일 1개를 더할까요?").assertExists()
        ui.onNodeWithText("서버를 끄고 작업해요. ZIP이 크면 오래 걸릴 수 있고, 작업이 끝나면 다시 켜요.").assertExists()
        ui.runOnIdle { assertTrue(actions.isEmpty()) }
        ui.onNodeWithText("백업 후 합치기").performClick()
        ui.runOnIdle { assertEquals(listOf(false to true), actions) }
    }
}
