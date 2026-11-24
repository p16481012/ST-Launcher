package app.tavernbridge.launcher.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** These are Compose screens, not separate Android windows. Capture their drawn root, not System UI. */
internal fun ComposeTestRule.captureManagementScreen(name: String, visibleText: String) {
    waitForIdle()
    onNodeWithText(visibleText).assertIsDisplayed()
    val bitmap = onRoot().captureToImage().asAndroidBitmap()
    val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "v2-visual")
    check(dir.isDirectory || dir.mkdirs())
    File(dir, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
}
