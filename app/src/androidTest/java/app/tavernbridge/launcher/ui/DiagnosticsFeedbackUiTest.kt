package app.tavernbridge.launcher.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

    private val report = DiagnosticReport("${java.time.LocalDate.now()} 14:05:06", listOf(
        DiagnosticItem("battery", "Termux 배터리 최적화", "최적화 대상", status = DiagnosticStatus.WARNING),
        DiagnosticItem("git_changes", "수정된 설치 파일", "1개", detail = "server.js", status = DiagnosticStatus.WARNING),
        DiagnosticItem("termux_storage", "저장 공간", "7.5 GB 남음", status = DiagnosticStatus.OK),
        DiagnosticItem("termux", "Termux 연결", "정상", status = DiagnosticStatus.OK),
        DiagnosticItem("node", "Node.js", "v24.22.3", status = DiagnosticStatus.OK),
        DiagnosticItem("v", "앱 버전", "0.3.2", status = DiagnosticStatus.INFO),
    ) + (1..12).map { DiagnosticItem("ok-$it", "추가 검사 $it", "정상", status = DiagnosticStatus.OK) })

    private val links = mutableListOf<String>()
    private fun launch(report: DiagnosticReport?) {
        val vm = compose.runOnIdle { ViewModelProvider(compose.activity)[LauncherViewModel::class.java] }
        val uri = object : androidx.compose.ui.platform.UriHandler { override fun openUri(uri: String) { links.add(uri) } }
        compose.setContent {
            val route by vm.state.collectAsState()
            CompositionLocalProvider(androidx.compose.ui.platform.LocalUriHandler provides uri) {
                LauncherScreen(route.copy(section = MainSection.LOGS, toolPage = ToolPage.DIAGNOSTICS, diagnosticReport = report,
                    isWorking = false, environmentChecked = true, previousInstallationKnown = true,
                    displayPreferences = DisplayPreferences(reducedMotion = true), theme = AppTheme.LIGHT,
                    termuxSetup = TermuxSetupState(TermuxSetupStatus.READY), environment = EnvironmentStatus(
                        termuxInstalled = true, commandPermissionGranted = true, managerConnected = true, sillyTavernInstalled = true)), vm)
            }
        }
    }

    @Test fun diagnosticWelcomeShowsTheFourChecksAndOneAction() {
        launch(null)
        compose.onNodeWithText("아직 진단하지 않았어요").assertIsDisplayed()
        listOf("저장 공간", "Termux 연결과 권한", "Node.js·SillyTavern 실행 환경", "배터리·백그라운드 제한").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onNodeWithText("보고서 복사").assertDoesNotExist()
        compose.onNodeWithText("Termux가 갑자기 종료될 때").assertDoesNotExist()
        compose.onNodeWithText("진단 시작").assertIsEnabled()
        compose.captureManagementScreen("review-diagnostic-empty", "진단 시작")
    }

    @Test fun diagnosisOnlyRunsOnStartOrExplicitRecheck() {
        val vm = compose.runOnIdle { ViewModelProvider(compose.activity)[LauncherViewModel::class.java] }
        var runs = 0
        val result = androidx.compose.runtime.mutableStateOf<DiagnosticReport?>(null)
        compose.setContent {
            SillyTavernLauncherTheme(AppTheme.LIGHT) {
                DiagnosticDetail(LauncherUiState(isWorking = false, diagnosticReport = result.value), vm, Modifier) {
                    runs++; result.value = report
                }
            }
        }
        compose.runOnIdle { org.junit.Assert.assertEquals(0, runs) }
        compose.onNodeWithText("진단 시작").performClick()
        compose.onNodeWithText("다시 진단").assertIsEnabled()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, runs) }
        compose.onNodeWithText("다시 진단").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(2, runs) }
    }

    @Test fun everyCountedCheckHasARowAndVersionMetadataIsSeparate() {
        launch(report)
        compose.onNodeWithText("17개 중 15개 정상").assertIsDisplayed()
        compose.onNodeWithText("오늘 14:05 진단").assertIsDisplayed()
        compose.onNodeWithText("보고서 복사").assertIsEnabled()
        compose.onNodeWithText("다시 진단").assertIsEnabled()
        compose.onNodeWithText("저장 공간").assertIsDisplayed()
        compose.onNodeWithText("Termux 연결").assertIsDisplayed()
        compose.onNodeWithText("Node.js").assertIsDisplayed()
        compose.captureManagementScreen("review-diagnostic-result", "다시 진단")
        compose.onNodeWithText("추가 검사 12").assertDoesNotExist()
        compose.onNodeWithText("15개 모두").performScrollTo().performClick()
        compose.onNodeWithText("추가 검사 12").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("접기").performScrollTo().performClick()
        compose.onNodeWithText("추가 검사 12").assertDoesNotExist()
        compose.onNodeWithText("참고 1개 · 검사 수에서 제외").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("앱 버전").performScrollTo().assertIsDisplayed()
    }

    @Test fun acknowledgingAReportImmediatelyClearsOnlyItsUnreadMarker() {
        val vm = compose.runOnIdle { ViewModelProvider(compose.activity)[LauncherViewModel::class.java] }
        val screen = androidx.compose.runtime.mutableStateOf(LauncherUiState(isWorking = false).withDiagnosticReport(report))
        compose.setContent {
            SillyTavernLauncherTheme(AppTheme.LIGHT) {
                DiagnosticDetail(screen.value, vm, Modifier, onAcknowledge = { current ->
                    screen.value = screen.value.copy(acknowledgedDiagnosticKeys = current.coveredKeys)
                })
            }
        }
        compose.onNodeWithText("이번 결과 확인했어요").performScrollTo().performClick()
        compose.onNodeWithText("이번 결과 확인했어요").assertDoesNotExist()
        compose.onNodeWithText("Termux 배터리 최적화").assertExists()
        compose.runOnIdle { org.junit.Assert.assertFalse(MainSection.LOGS in screen.value.tabAttention()) }
    }

    @SdkSuppress(minSdkVersion = 31)
    @Test fun diagnosticSolutionsOnlyOfferActionsForTheSelectedIssue() {
        launch(report)
        compose.onNodeWithText("Termux 배터리 최적화").performScrollTo().performClick()
        compose.onNodeWithText("배터리 설정 열기").assertIsDisplayed()
        compose.onNodeWithText("Termux 앱 정보 열기").assertIsDisplayed()
        compose.onNodeWithText("Android 12 이상 도움말").assertIsDisplayed()
        compose.onNodeWithText("외부 브라우저로 열려요").assertIsDisplayed()
        compose.waitForIdle()
        captureModalWindowSnapshot("review-diagnostic-solution")
        compose.onNodeWithText("Android 12 이상 도움말").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(listOf(TermuxProcessHelp.OFFICIAL_HELP_URL), links) }
        compose.onNodeWithText("확인").performClick()
        compose.onNodeWithText("수정된 설치 파일").performScrollTo().performClick()
        compose.onNodeWithText("배터리 설정 열기").assertDoesNotExist()
        compose.onNodeWithText("업데이트 화면 열기").assertIsDisplayed()
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
