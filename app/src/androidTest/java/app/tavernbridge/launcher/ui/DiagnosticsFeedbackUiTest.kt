package app.tavernbridge.launcher.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import app.tavernbridge.launcher.LauncherViewModel
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.theme.SillyTavernLauncherTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticsFeedbackUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun everyCountedCheckHasARowAndVersionMetadataIsSeparate() {
        val report = DiagnosticReport("now", listOf(
            DiagnosticItem("git", "Git 검사", "정상", status = DiagnosticStatus.OK),
            DiagnosticItem("npm", "npm 검사", "확인 필요", status = DiagnosticStatus.WARNING),
            DiagnosticItem("v", "앱 버전", "0.3.2", status = DiagnosticStatus.INFO),
        ))
        val vm = compose.runOnIdle { ViewModelProvider(compose.activity)[LauncherViewModel::class.java] }
        compose.setContent {
            SillyTavernLauncherTheme(AppTheme.LIGHT) {
                V2ToolsScreen(LauncherUiState(toolPage = ToolPage.DIAGNOSTICS, diagnosticReport = report),
                    vm, {}, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("검사 결과 2개").assertIsDisplayed()
        compose.onNodeWithText("npm 검사").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Git 검사").performScrollTo().assertIsDisplayed() // No expand action.
        compose.onNodeWithText("참고 1개 · 검사 수에서 제외").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("앱 버전").performScrollTo().assertIsDisplayed()
    }

    @SdkSuppress(minSdkVersion = 31)
    @Test fun android12ProcessHelpDoesNotReportADeviceWarning() {
        val vm = compose.runOnIdle { ViewModelProvider(compose.activity)[LauncherViewModel::class.java] }
        compose.setContent {
            SillyTavernLauncherTheme(AppTheme.LIGHT) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    V2BackgroundSettings(LauncherUiState(environment = EnvironmentStatus(termuxInstalled = true),
                        backgroundStatus = BackgroundStatus(termuxBatteryUnrestricted = true), backupStorageReady = true), vm)
                }
            }
        }
        compose.onNodeWithText("하위 프로세스 제한").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("확인 필요").assertDoesNotExist()
        compose.onNodeWithText("하위 프로세스 제한").performClick()
        compose.onNodeWithText("실제 하위 프로세스 제한 설정을 검사한 결과는 아니에요.", substring = true)
            .performScrollTo().assertIsDisplayed()
    }
}
