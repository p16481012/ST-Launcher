package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.theme.SillyTavernLauncherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsActionClassificationTest {
    @get:Rule val ui = createComposeRule()

    @Test fun licenseDetailReturnsToListThenAppInfoWithoutCloseButton() {
        ui.setContent {
            var route by remember { mutableStateOf<String?>(null) }
            SillyTavernLauncherTheme(AppTheme.LIGHT, DisplayPreferences(reducedMotion = true)) {
                V2SettingsLayout(LauncherUiState(), route, { route = null },
                    onNestedBack = { route = settingsParent(route) },
                    nestedDetail = { page ->
                        V2LicenseContent(if (page == ABOUT_LICENSES) null else page.substringAfter('/')) {
                            route = "about-license/$it"
                        }
                    }) { page, _ ->
                    if (page == SettingsPage.ABOUT) SettingsRow("라이선스 보기", onClick = { route = ABOUT_LICENSES })
                }
            }
        }
        ui.onNode(hasText("앱 정보") and hasClickAction()).performScrollTo().performClick()
        ui.onNodeWithText("라이선스 보기").performClick()
        ui.onNodeWithText("IBM-Plex-Mono").performScrollTo().performClick()
        ui.onNodeWithText("닫기").assertDoesNotExist()
        ui.onNodeWithContentDescription("뒤로").performClick()
        ui.onNodeWithText("IBM-Plex-Mono").assertHasClickAction()
        ui.onNodeWithContentDescription("뒤로").performClick()
        ui.onNodeWithText("라이선스 보기").assertHasClickAction()
    }

    @Test fun copyIconCopiesOnlyItsOwnAddress() {
        val copied = mutableListOf<String>()
        val urls = listOf("http://192.168.1.2:8000", "http://192.168.2.2:8000")
        ui.setContent {
            Column { urls.forEach { url -> LanAddressCopyRow(url) { copied += url } } }
        }
        val label = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            .getString(app.tavernbridge.launcher.R.string.lan_copy_address)
        ui.onAllNodesWithContentDescription(label)[1].performClick()
        ui.runOnIdle { assertEquals(listOf(urls[1]), copied) }
    }
}
