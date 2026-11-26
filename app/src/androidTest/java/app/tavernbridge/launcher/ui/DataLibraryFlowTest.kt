package app.tavernbridge.launcher.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.tavernbridge.launcher.data.DataLibraryAccess
import app.tavernbridge.launcher.data.LibraryWrite
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.termux.TermuxCommandResult
import app.tavernbridge.launcher.ui.theme.SillyTavernLauncherTheme
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DataLibraryFlowTest {
    @get:Rule val ui = createComposeRule()
    private fun enc(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())
    private val revision = "1".repeat(64)
    private val writes = mutableListOf<LibraryWrite>()
    private val calls = CopyOnWriteArrayList<String>()
    private fun row(path: String, name: String, bytes: Int, chats: Int = 0) =
        listOf("item", enc(path), enc(name), bytes.toString(), chats.toString(), "100", revision,
            "0", bytes.toString(), if(path.startsWith("chats/")) "" else revision).joinToString("\t")
    // This 4:1 green image has red/blue edge strips. A cropped landscape tile hides the strips;
    // the fitted detail must show them. Generate valid PNG bytes with Android's codec.
    private val artPng by lazy {
        val image = Bitmap.createBitmap(80,20, Bitmap.Config.ARGB_8888)
        try {
            val colors = IntArray(80 * 20) { i ->
                when (i % 80) { in 0..7 -> Color.RED; in 72..79 -> Color.BLUE; else -> Color.GREEN }
            }
            image.setPixels(colors,0,80,0,0,80,20)
            ByteArrayOutputStream().use {
                check(image.compress(Bitmap.CompressFormat.PNG,100,it))
                Base64.getEncoder().encodeToString(it.toByteArray())
            }
        } finally { image.recycle() }
    }
    private fun artOutput(args: String): String {
        val fields = Regex("'([^']*)'").findAll(args).map { it.groupValues[1] }.toList()
        require(fields.size == 3 && fields[0] == enc("default-user") && fields[1] in setOf("list","detail"))
        val list = JSONArray(String(Base64.getDecoder().decode(fields[2]),Charsets.UTF_8))
        require(list.length() in 1..(if (fields[1] == "detail") 1 else 4))
        return (0 until list.length()).mapNotNull { i ->
            val request = list.getJSONArray(i)
            require(request.getString(1) == revision)
            // Exercise the no-art row without aborting the other rows in a batch.
            if (request.getString(0) == "backgrounds/여름.png") null else "art\t$i\t$artPng"
        }.joinToString("\n")
    }
    private fun launch() {
        val library = DataLibraryAccess(InstrumentationRegistry.getInstrumentation().targetContext,{ listOf("default-user") }) { args,_ ->
            calls += args
            val output = if (args.startsWith("library-artwork ")) artOutput(args) else when {
                " 'orphans' " in args -> row("chats/남은 대화","남은 대화",1000,5)
                " 'cards' " in args -> listOf(row("characters/가.png","가나다",200,4),
                    row("characters/나.png","나라마",900,1)).joinToString("\n")
                " 'backgrounds' " in args -> listOf("봄.jpg","여름.png","겨울.webp").mapIndexed { i, name ->
                    row("backgrounds/$name",name,300-i)
                }.joinToString("\n")
                else -> ""
            } + "\nnext_cursor="
            TermuxCommandResult("",output,"",0,Activity.RESULT_OK,"")
        }
        ui.setContent {
            SillyTavernLauncherTheme(AppTheme.LIGHT,DisplayPreferences(reducedMotion=true)) {
                DataLibraryScreen(LauncherUiState(isWorking=false), library, { writes += it }, {})
            }
        }
    }
    @Test fun hubNeverRunsDeepChecksAndOrphanTrashNeedsSelectionAndConfirmation() {
        launch()
        ui.onNodeWithText("카드 없이 남은 채팅").performScrollTo().performClick()
        ui.onNodeWithText("남은 대화").assertExists()
        ui.onNodeWithText("1개 휴지통으로").assertDoesNotExist()
        ui.onNodeWithText("선택").performClick()
        ui.onNodeWithText("남은 대화").performClick()
        ui.onNodeWithText("1개 휴지통으로").performClick()
        ui.onNodeWithText("1개를 휴지통으로 옮길까요?").assertExists()
        ui.runOnIdle { assertTrue(writes.isEmpty()); assertTrue(calls.all { it.startsWith("library-list") }) }
        ui.onNodeWithText("휴지통으로 옮기기").performClick()
        ui.runOnIdle { assertEquals("library-trash",writes.single().operation);assertTrue(writes.single().pause) }
    }
    @Test fun hubHasSeparateAssetKindsAndOnlyOpensTheChosenList() {
        launch()
        ui.onNodeWithText("내 자료").assertExists()
        ui.onNodeWithText("중복 카드 찾기").assertDoesNotExist()
        ui.runOnIdle { assertTrue(calls.isEmpty()) }
        for ((label, wire) in listOf("채팅 완성 프리셋" to "presets","테마" to "themes","빠른 답장(QR)" to "quickreplies")) {
            ui.onNodeWithText(label).performScrollTo().performClick()
            ui.onNodeWithText("표시할 항목이 없어요.").assertExists()
            ui.runOnIdle { assertTrue(" '$wire' " in calls.last()) }
            ui.onNodeWithContentDescription("뒤로").performClick()
        }
    }
    @Test fun cardSortAppliesWithoutReadingTheListAgain() {
        launch()
        ui.onNodeWithText("캐릭터 카드").performScrollTo().performClick()
        fun above(a: String,b: String) {
            assertTrue(ui.onNodeWithText(a).fetchSemanticsNode().boundsInRoot.top <
                ui.onNodeWithText(b).fetchSemanticsNode().boundsInRoot.top)
        }
        above("나라마","가나다")
        ui.onNodeWithText("총용량 큰 순").performClick()
        ui.onNodeWithText("이름순").performClick()
        above("가나다","나라마")
        ui.onNodeWithText("이름순").performClick()
        ui.onNodeWithText("채팅 많은 순").performClick()
        above("가나다","나라마")
        // Real hit targets in a narrow phone layout: typing into the neighboring search field
        // must filter without triggering sort, and must not request a new Termux list.
        val input = ui.onNode(hasSetTextAction())
        assertTrue(input.fetchSemanticsNode().boundsInRoot.right <=
            ui.onNodeWithText("채팅 많은 순").fetchSemanticsNode().boundsInRoot.left)
        input.performTextReplacement("가나다")
        ui.onNodeWithText("나라마").assertDoesNotExist()
        // Exact text also occurs in the input; look for the clickable card specifically.
        ui.onNode(hasText("가나다") and hasClickAction() and !hasSetTextAction()).assertIsDisplayed()
        input.performTextClearance()
        ui.onNodeWithText("나라마").assertExists()
        ui.runOnIdle { assertEquals(1, calls.count { it.startsWith("library-list") }) }
    }
    private fun sampledColors(node: SemanticsNodeInteraction): Set<Int> {
        val bitmap = node.captureToImage().asAndroidBitmap()
        try {
            val result = mutableSetOf<Int>()
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) {
                val pixel = pixels[y * bitmap.width + x]
                if (pixel == Color.GREEN || pixel == Color.RED || pixel == Color.BLUE) result += pixel
            }
            return result
        } finally { bitmap.recycle() }
    }
    @Test fun backgroundsShareARowAndOpenTheUncroppedDetail() {
        launch()
        ui.onNodeWithText("배경 그림").performScrollTo().performClick()
        val first = ui.onNodeWithText("봄.jpg").fetchSemanticsNode().boundsInRoot
        val second = ui.onNodeWithText("여름.png").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, second.top, 1f)
        assertTrue(first.right <= second.left)
        val ready = hasTestTag("library-artwork-ready")
        val tileArt = ready and hasAnyAncestor(hasClickAction() and hasAnyDescendant(hasText("봄.jpg")))
        ui.waitUntil(10_000) { ui.onAllNodes(tileArt,useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        assertEquals(setOf(Color.GREEN), sampledColors(ui.onNode(tileArt,useUnmergedTree = true)))
        // A missing response for 여름 must not leave a spinner, and must not blank out 봄.
        ui.onNodeWithText("여름.png").assertIsDisplayed()
        val missing = hasContentDescription("미리보기 없음") or hasText("미리보기 없음")
        ui.waitUntil(10_000) {
            ui.onAllNodes(missing,useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithText("봄.jpg").performClick()
        ui.onNodeWithText("어디서 쓰나요?").assertExists()
        val detailArt = ready and hasAnyAncestor(hasTestTag("v2-sheet-surface"))
        ui.waitUntil(10_000) { ui.onAllNodes(detailArt,useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        assertEquals(setOf(Color.RED,Color.GREEN,Color.BLUE),sampledColors(ui.onNode(detailArt,useUnmergedTree = true)))
        assertTrue(calls.any { it.startsWith("library-artwork ") && " 'detail' " in it })
    }
}
