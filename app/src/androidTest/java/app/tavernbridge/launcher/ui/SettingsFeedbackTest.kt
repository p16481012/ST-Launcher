package app.tavernbridge.launcher.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tavernbridge.launcher.data.LauncherReleaseClient
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.theme.SillyTavernLauncherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsFeedbackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun previewEditsRealTextAndResetsToExampleWhenCleared() {
        compose.setContent { SillyTavernLauncherTheme(AppTheme.LIGHT) { AppearanceTextPreview() } }
        compose.onNodeWithText("미리보기 문장 바꾸기").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("내 폰에서 읽어 볼 문장")
        compose.onNodeWithText("적용").performClick()
        compose.onNodeWithText("내 폰에서 읽어 볼 문장").assertIsDisplayed()
        compose.onNodeWithText("미리보기 문장 바꾸기").performClick()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNodeWithText("적용").performClick()
        compose.onNodeWithText(DEFAULT_SAMPLE).assertIsDisplayed()
        compose.onNodeWithText("미리보기 문장 바꾸기").performClick()
        compose.onNodeWithText(SAMPLE_HINT).assertIsDisplayed()
    }

    @Test fun updateButtonsDoNotInstallOrLaunchTheOtherAction() {
        var checks = 0
        var url = ""
        val busy = mutableStateOf(true)
        compose.setContent {
            SillyTavernLauncherTheme(AppTheme.LIGHT) {
                CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) { url = uri }
                }) { UpdateInformationCard(LauncherUiState(isWorking = busy.value)) { checks++ } }
            }
        }
        compose.onNodeWithText("업데이트 정보 확인").assertDoesNotExist()
        compose.onNodeWithText("진행 중인 작업이 끝나면 업데이트 정보를 확인할 수 있어요.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, checks); busy.value = false }
        compose.onNodeWithText("업데이트 정보 확인").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, checks); assertEquals("", url) }
        compose.onNodeWithText("GitHub 릴리스 열기").performClick()
        compose.runOnIdle { assertEquals(1, checks); assertEquals(LauncherReleaseClient.RELEASE_URL, url) }
    }
}
