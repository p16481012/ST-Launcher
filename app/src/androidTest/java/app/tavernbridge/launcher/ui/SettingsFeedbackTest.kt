package app.tavernbridge.launcher.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
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
        compose.setContent { SillyTavernLauncherTheme(AppTheme.LIGHT) { AppearanceTextPreview("세이지") } }
        compose.onNodeWithText("내 문장으로 미리보기").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("내 폰에서 읽어 볼 문장")
        compose.onNodeWithText("입력 마치기").performClick()
        compose.onNodeWithText("내 폰에서 읽어 볼 문장").assertIsDisplayed()
        compose.onNodeWithText("내 문장으로 미리보기").performClick()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNodeWithText("비 오는 오후", substring = true).assertIsDisplayed()
    }

    @Test fun updateButtonsDoNotInstallOrLaunchTheOtherAction() {
        var checks = 0
        var url = ""
        compose.setContent {
            SillyTavernLauncherTheme(AppTheme.LIGHT) {
                CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) { url = uri }
                }) { UpdateInformationCard(LauncherUiState()) { checks++ } }
            }
        }
        compose.onNodeWithText("업데이트 정보 확인").performClick()
        compose.runOnIdle { assertEquals(1, checks); assertEquals("", url) }
        compose.onNodeWithText("GitHub 릴리스 열기").performClick()
        compose.runOnIdle { assertEquals(1, checks); assertEquals(LauncherReleaseClient.RELEASE_URL, url) }
    }
}
