package app.tavernbridge.launcher.ui

import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.tavernbridge.launcher.model.AppTheme
import app.tavernbridge.launcher.share.*
import app.tavernbridge.launcher.ui.theme.SillyTavernLauncherTheme
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareCardImportTest {
    @get:Rule val ui = createComposeRule()

    @Test fun previewRequiresUserActionAndOffersActualCollisionName() {
        val card = SharedCard(File("Alice.png"), "Alice.png", "Alice", "작가", "", null)
        val state = CardScreenState(step = ShareStep.READY, incoming = listOf(IncomingCard("Alice",card)),
            users = listOf("default-user","guest"), user = "default-user", serverRunning = true,
            plans = listOf(true to "Alice (2).png"))
        var submitted = false; var selected = ""; var skip = false
        ui.setContent { SillyTavernLauncherTheme(AppTheme.LIGHT) {
            ShareImportScreen(state, {}, { selected=it }, { _,value -> skip=value }, { submitted=true }, {}, {}, {})
        } }
        ui.onNodeWithText("Alice (2).png").assertExists()
        ui.onNodeWithText("사용자").performClick()
        ui.onNodeWithText("guest").performClick()
        ui.runOnIdle { assertEquals("guest",selected); assertFalse(submitted) }
        ui.onNodeWithText("넣지 않기").performClick()
        ui.runOnIdle { assertTrue(skip) }
        ui.onNodeWithText("가져오기").performClick()
        ui.runOnIdle { assertTrue(submitted) }
    }

    @Test fun metadataAcceptsBothKeywordLengthsAndPrefersV3() {
        fun make(vararg entries: Pair<String,String>): File {
            val stream=ByteArrayOutputStream()
            val out=DataOutputStream(stream)
            out.write(byteArrayOf(0x89.toByte(),80,78,71,13,10,26,10))
            fun chunk(kind:String,body:ByteArray) {
                val t=kind.toByteArray(Charsets.US_ASCII)
                out.writeInt(body.size);out.write(t);out.write(body)
                out.writeInt(CRC32().apply { update(t);update(body) }.value.toInt())
            }
            chunk("IHDR",byteArrayOf(0,0,0,1,0,0,0,1,8,2,0,0,0))
            entries.forEach { (key,json) -> chunk("tEXt",key.toByteArray()+byteArrayOf(0)+Base64.getEncoder().encode(json.toByteArray())) }
            chunk("IDAT",byteArrayOf(0));chunk("IEND",byteArrayOf())
            return File.createTempFile("card-parser-",".png",InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
                .apply { writeBytes(stream.toByteArray()) }
        }
        val v2="chara" to """{"data":{"name":"Alice","creator":"artist"}}"""
        val v3="ccv3" to """{"data":{"name":"V3","creator":"artist"}}"""
        for ((file, expected) in listOf(make(v2) to "Alice", make(v2,v3) to "V3")) {
            try { assertEquals(expected to "artist", readPngCard(file)) } finally { file.delete() }
        }
        val noName = make("chara" to """{"data":{}}""")
        try { assertTrue(runCatching { readPngCard(noName) }.isFailure) } finally { noName.delete() }
    }
}

@RunWith(AndroidJUnit4::class)
class ShareCardIntentTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun realSendRejectsNoReadGrantAndConfirmationReturnsToSender() {
        val intent = Intent(ctx,ShareImportActivity::class.java).setAction(Intent.ACTION_SEND).setType("image/png")
            .putExtra(Intent.EXTRA_STREAM,Uri.parse("content://untrusted.shared/no-grant")).apply {
                // Do not allow Android to synthesize a matching ClipData grant for this invalid fixture.
                clipData = android.content.ClipData.newPlainText("fixture", "no-grant")
            }
        ActivityScenario.launch<ShareImportActivity>(intent).use {
            ui.onNodeWithText("파일 읽기 권한이 없어요. 원래 앱에서 다시 공유해 주세요.").assertExists()
            ui.onNodeWithText("확인").performClick()
            ui.waitUntil(5_000) { it.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
        }
    }

    @Test fun realMultipleRejectsMoreThanTwentyBeforeReadingOrContactingTermux() {
        val uris = ArrayList<Parcelable>((0..20).map { Uri.parse("content://untrusted.shared/" + it) })
        val intent = Intent(ctx,ShareImportActivity::class.java).setAction(Intent.ACTION_SEND_MULTIPLE).setType("image/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris)
        ActivityScenario.launch<ShareImportActivity>(intent).use {
            ui.onNodeWithText("한 번에 20개까지 넣을 수 있어요.").assertExists()
        }
    }

    @Test fun unrelatedClipGrantCannotOpenUnsharedExtraStream() {
        val intent = Intent(ctx,ShareImportActivity::class.java).setAction(Intent.ACTION_SEND).setType("image/png")
            .putExtra(Intent.EXTRA_STREAM,Uri.parse("content://untrusted.shared/other")).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newPlainText("not a grant for other", "fixture")
            }
        ActivityScenario.launch<ShareImportActivity>(intent).use {
            ui.onNodeWithText("공유한 파일과 읽기 권한이 일치하지 않아요. 원래 앱에서 다시 공유해 주세요.").assertExists()
        }
    }
}
