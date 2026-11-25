package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.model.AppTheme
import app.tavernbridge.launcher.model.DisplayPreferences
import app.tavernbridge.launcher.ui.theme.SillyTavernLauncherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ActionAffordanceTest {
    @get:Rule val ui = createComposeRule()
    @Test fun commonShapesShowRealLabelsAndDispatchOnlyTheirOwnActions() {
        val events = mutableListOf<String>()
        ui.setContent {
            SillyTavernLauncherTheme(AppTheme.LIGHT, DisplayPreferences(reducedMotion = true)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("안전 사본", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                            V2TopBarAction("모두 선택", { events += "select" })
                        }
                        V2NavigationRow("저장 공간 보기", { events += "settings" }, description = "Android 설정 앱이 열려요.")
                        V2LinkAction("전체 6개", { events += "more" })
                        V2OutlineAction("목록 다시 확인", { events += "check" }, Modifier.fillMaxWidth())
                        V2SheetPrimaryButton("백업 시작") { events += "backup" }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            V2DialogAction(onClick = { events += "cancel" }, tone = V2DialogTone.Quiet) { Text("취소") }
                            V2DangerAction("영구 삭제", { events += "delete" })
                        }
                    }
                }
            }
        }
        ui.captureManagementScreen("action-affordances", "목록 다시 확인")
        val labels = listOf("모두 선택", "저장 공간 보기", "전체 6개", "목록 다시 확인", "백업 시작", "취소", "영구 삭제")
        labels.forEach { ui.onNodeWithText(it).performScrollTo().assertHasClickAction().performClick() }
        ui.runOnIdle { assertEquals(listOf("select", "settings", "more", "check", "backup", "cancel", "delete"), events) }
    }
}
