package app.tavernbridge.launcher.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.theme.SillyTavernLauncherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ServerConfigSettingsTest {
    @get:Rule val ui = createComposeRule()
    @Test fun savingIsExplicitAndPluginConfirmationOnlyEditsDraft() {
        val revision = "a".repeat(64)
        val config = ServerConfig(revision,"1.19.1",mapOf(ServerSetting.PLUGINS to "false"))
        var sent: Map<ServerSetting,String>? = null
        ui.setContent {
            var draft by remember { mutableStateOf("") }
            SillyTavernLauncherTheme(AppTheme.LIGHT, DisplayPreferences(reducedMotion = true)) {
                V2ServerConfigSettings(LauncherUiState(serverConfig=config, isWorking=false, environment=EnvironmentStatus(processRunning=true)),
                    draft,{draft=it},{},{},{rev,changes -> assertEquals(revision,rev); sent=changes },{ null })
            }
        }
        ui.onNodeWithText("저장하고 재시작").assertIsNotEnabled()
        ui.onNodeWithText("서버 플러그인").performClick()
        ui.onNodeWithText("서버 플러그인을 켤까요?").assertIsDisplayed()
        ui.onNodeWithText("취소").performClick()
        ui.onNodeWithText("저장하고 재시작").assertIsNotEnabled()
        ui.onNodeWithText("서버 플러그인").performClick()
        ui.onNodeWithText("켜기").performClick()
        ui.onNodeWithText("바뀜").assertExists()
        ui.runOnIdle { assertEquals(null,sent) }
        ui.onNodeWithText("저장하고 재시작").performClick()
        ui.runOnIdle { assertEquals(mapOf(ServerSetting.PLUGINS to "true"),sent) }
    }
}
