package app.tavernbridge.launcher.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.tavernbridge.launcher.data.DataLibraryAccess
import app.tavernbridge.launcher.data.LibraryWrite
import app.tavernbridge.launcher.data.LibraryChatMove
import app.tavernbridge.launcher.data.LibraryChatMoveNotices
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
import kotlinx.coroutines.runBlocking

class DataLibraryFlowTest {
    @get:Rule val ui = createComposeRule()
    private fun enc(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())
    private val revision = "1".repeat(64)
    private val writes = mutableListOf<LibraryWrite>()
    private val calls = CopyOnWriteArrayList<String>()
    private lateinit var library: DataLibraryAccess
    private fun notices() = LibraryChatMoveNotices(InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences("test_library_chat_advice", android.content.Context.MODE_PRIVATE))
    private val receiptId = "1730ca6e-f651-4862-9470-24cd33205135"
    private fun row(path: String, name: String, bytes: Int, chats: Int = 0, protected: Boolean = false, reason: String = "") =
        listOf("item", enc(path), enc(name), bytes.toString(), chats.toString(), "100", revision,
            (if (protected) "1" else "0"), bytes.toString(), if(path.startsWith("chats/")) "" else revision, reason).joinToString("\t")
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
    private val assetFolders = linkedMapOf("presets" to "OpenAI Settings","worlds" to "worlds",
        "quickreplies" to "QuickReplies","themes" to "themes")
    private fun previewOutput(args: String): String {
        fun entry(title: String, summary: String = "", excerpt: String = "", argb: String = "-") =
            listOf("peek",enc(title),enc(summary),enc(excerpt),argb).joinToString("\t")
        return when {
            "'${enc("OpenAI Settings/샘플.json")}'" in args -> entry("온도","0.8") + "\npreview\t0\t"
            "'${enc("worlds/샘플.json")}'" in args -> entry("지도","왕국","읽기 전용 본문") + "\npreview\t1\t"
            "'${enc("QuickReplies/샘플.json")}'" in args -> entry("인사",excerpt = "/echo 안녕") + "\npreview\t0\t"
            "'${enc("themes/샘플.json")}'" in args -> entry("본문 글자","#556677",argb = "FF556677") + "\npreview\t0\t"
            else -> error("예상하지 않은 미리보기 경로")
        }
    }
    private fun launch(assetUse: String? = null, serverRunning: Boolean = false, cardCases: Boolean = false, showChatAdvice: Boolean = false) {
        library = DataLibraryAccess(InstrumentationRegistry.getInstrumentation().targetContext, { listOf("default-user") }, notices()) { args,_ ->
            calls += args
            val output = when {
                args.startsWith("library-artwork ") -> artOutput(args)
                args.startsWith("library-card-usage ") -> {
                    val state = when {
                        "'${enc("characters/새.png")}'" in args -> "none"
                        "'${enc("characters/대화.png")}'" in args -> "none"
                        "'${enc("characters/미확인.png")}'" in args -> "unconfirmed"
                        else -> "used"
                    }
                    "state=$state\ndetail_b64=" + enc("채팅·그룹 검사 결과")
                }
                args.startsWith("library-card-change ") -> "action=" + (if (" 'copy' " in args) "copy" else "rename") +
                    "\nname_b64=" + enc("새 이름.png") + "\nchats_moved=" + (if ("'${enc("characters/대화.png")}'" in args && " 'rename' " in args) 3 else 0)
                args.startsWith("library-trash ") -> "group_id=$receiptId\nmoved=1\nskipped=0\nchats_moved=" + if(args.endsWith(" '1'")) 3 else 0
                args.startsWith("library-undo ") -> "restored=1\nskipped=0"
                args.startsWith("library-usage ") -> "state=" + assetUse + "\ndetail_b64=" + enc("검사 결과")
                args.startsWith("library-preview ") -> previewOutput(args)
                args.startsWith("library-list ") -> (when {
                    " 'orphans' " in args -> row("chats/남은 대화","남은 대화",1000,5)
                    cardCases && " 'cards' " in args -> listOf(
                        row("characters/새.png","다른 표시 이름",400),
                        row("characters/대화.png","대화 있음",300,3),
                        row("characters/그룹.png","그룹 멤버",250,2,true,"disabled_group"),
                        row("characters/미확인.png","연결 미확인",200,0,true,"unreadable")
                    ).joinToString("\n")
                    " 'cards' " in args -> listOf(row("characters/가.png","가나다",200,4),
                        row("characters/나.png","나라마",900,1)).joinToString("\n")
                    " 'backgrounds' " in args -> listOf("봄.jpg","여름.png","겨울.webp").mapIndexed { i, name ->
                        row("backgrounds/$name",name,300-i)
                    }.joinToString("\n")
                    assetUse != null -> assetFolders.entries.firstOrNull { " '${it.key}' " in args }?.let {
                        row("${it.value}/샘플.json","샘플.json",1024)
                    }.orEmpty()
                    else -> ""
                }) + "\nnext_cursor="
                else -> error("테스트 중 예상하지 않은 명령: $args")
            }
            TermuxCommandResult("",output,"",0,Activity.RESULT_OK,"")
        }
        library.chatMoveNotices.pending.value?.let(library.chatMoveNotices::acknowledge)
        ui.setContent {
            SillyTavernLauncherTheme(AppTheme.LIGHT,DisplayPreferences(reducedMotion=true)) {
                DataLibraryScreen(LauncherUiState(isWorking=false,environment = EnvironmentStatus(processRunning = serverRunning)),
                    library, { writes += it }, {})
                if (showChatAdvice) library.chatMoveNotices.pending.collectAsState().value?.let { kind ->
                    LibraryChatRefreshSheet(kind) { library.chatMoveNotices.acknowledge(kind) }
                }
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
    private fun footer(text: String) =
        ui.onNode(hasText(text) and hasAnyAncestor(hasTestTag("v2-sheet-footer")))
    private fun sheetInput() =
        ui.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("v2-sheet-body")))

    private fun openCardList() = ui.onNodeWithText("캐릭터 카드").performScrollTo().performClick()
    private fun openCardMenu(label: String) = ui.onNodeWithContentDescription("$label 더보기").performScrollTo().performClick()

    @Test fun cardTapShowsUncroppedArtAndMetadataWithoutOpeningActions() {
        launch(cardCases = true)
        openCardList()
        ui.runOnIdle { assertFalse(calls.any { " 'detail' " in it || it.startsWith("library-card-usage ") }) }
        ui.onNodeWithText("다른 표시 이름").performScrollTo().performClick()
        ui.onNodeWithText("새.png").assertExists()
        ui.onNodeWithText("연결된 채팅").assertExists()
        val art = hasTestTag("library-artwork-ready") and hasAnyAncestor(hasTestTag("v2-sheet-surface"))
        ui.waitUntil(10_000) { ui.onAllNodes(art,useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        assertEquals(setOf(Color.RED,Color.GREEN,Color.BLUE),sampledColors(ui.onNode(art,useUnmergedTree = true)))
        ui.onNodeWithText("휴지통으로 옮기기").assertDoesNotExist()
        ui.runOnIdle { assertTrue(writes.isEmpty()); assertFalse(calls.any { it.startsWith("library-card-usage ") }) }
    }

    @Test fun cardRenameIncludesSoloChatsAndOffersOnlyCopyForGroupOrUnknownLinks() {
        launch(cardCases = true)
        openCardList()
        for ((index,pair) in listOf("다른 표시 이름" to "rename","대화 있음" to "rename","그룹 멤버" to "copy","연결 미확인" to "copy").withIndex()) {
            openCardMenu(pair.first)
            ui.runOnIdle { assertEquals(index,calls.count { it.startsWith("library-card-usage ") }); assertEquals(index,writes.size) }
            ui.onNodeWithText("이름 변경").performClick()
            val label = if (pair.second == "copy") "새 이름으로 복사" else "이름 변경"
            if (pair.first == "대화 있음") {
                ui.onNodeWithText("1:1 채팅 3개가 새 이름의 카드에 그대로 연결돼요. 대화 내용은 바꾸지 않아요.").assertExists()
                footer("새 이름으로 복사").assertDoesNotExist()
            }
            if (pair.second == "copy") {
                ui.onNodeWithText("원래 카드와 채팅·그룹 연결을 유지하고 새 카드만 만들어요. 채팅은 복사하지 않아요.").assertExists()
                ui.onNodeWithText("원본 이름 변경").assertDoesNotExist()
                ui.onNodeWithText("연결이 끊길 수 있음을 확인했어요").assertDoesNotExist()
            }
            sheetInput().performTextReplacement("새 이름")
            footer(label).assertIsEnabled().performClick()
            val write = ui.runOnIdle { writes.last() }
            assertEquals("library-card-change",write.operation)
            val result = runBlocking { write.execute() }
            if (pair.first == "대화 있음") {
                assertTrue(write.message(result).contains("1:1 채팅 3개"))
                assertEquals(LibraryChatMove.RENAMED, library.chatMoveNotices.pending.value)
                library.chatMoveNotices.acknowledge(LibraryChatMove.RENAMED)
            } else assertNull(library.chatMoveNotices.pending.value)
            val dispatched = calls.filter { it.startsWith("library-card-change ") }
            assertEquals(index + 1, dispatched.size)
            assertTrue("잘못된 작업: ${dispatched.last()}", " '${pair.second}' " in dispatched.last())
            ui.runOnIdle { assertEquals(label,write.label) }
        }
    }

    @Test fun cardTrashDefaultsToCardOnlyAndProtectedCardExplainsWhy() {
        launch(cardCases = true)
        openCardList()
        fun trash(label: String) {
            openCardMenu(label)
            ui.onNodeWithText("휴지통으로 옮기기").performClick()
        }
        trash("대화 있음")
        val include = ui.onNodeWithText("채팅 3개도 같이 옮기기")
        ui.onNodeWithText("채팅 3개는 그대로 남아요. 자료 관리의 ‘카드 없이 남은 채팅’에서 볼 수 있어요.").assertExists()
        include.assertIsOff().performClick().assertIsOn()
        ui.onNodeWithText("취소").performClick()
        trash("대화 있음")
        include.assertIsOff()
        ui.onNodeWithText("휴지통으로 옮기기").performClick()
        val task = ui.runOnIdle { writes.single() }
        assertEquals("library-trash",task.operation)
        runBlocking { task.execute() }
        assertTrue(calls.single { it.startsWith("library-trash ") }.endsWith(" '0'"))
        assertNull(library.chatMoveNotices.pending.value)
        trash("연결 미확인")
        ui.onNodeWithText("보호된 카드는 옮기지 않아요").assertExists()
        ui.onNodeWithText("캐릭터 카드 정보를 읽을 수 없어 원본을 보호해요.").assertExists()
        ui.onNodeWithText("휴지통으로 옮기기").assertDoesNotExist()
        footer("닫기").performClick()
        ui.runOnIdle { assertEquals(1,writes.size) }
    }

    @Test fun movedChatsKeepRefreshAdviceUntilAcknowledgedAndUndoUsesOnlyItsReceipt() {
        launch(cardCases = true, showChatAdvice = true)
        openCardList(); openCardMenu("대화 있음")
        ui.onNodeWithText("휴지통으로 옮기기").performClick()
        ui.onNodeWithText("채팅 3개도 같이 옮기기").performClick()
        ui.onNodeWithText("휴지통으로 옮기기").performClick()
        val task = ui.runOnIdle { writes.single() }
        runBlocking { task.execute() }
        ui.onNodeWithText("열어 둔 실리태번 탭을 새로고침해 주세요").assertExists()
        assertEquals(LibraryChatMove.TRASHED, notices().pending.value)
        footer("확인").performClick()
        ui.onNodeWithText("열어 둔 실리태번 탭을 새로고침해 주세요").assertDoesNotExist()
        assertNull(notices().pending.value)
        val receipt = checkNotNull(library.lastMoved.value)
        assertNull(library.directUndo("other-user" to receipt.second))
        val undo = checkNotNull(library.directUndo(receipt))
        assertFalse(undo.pause)
        runBlocking { undo.execute() }
        assertTrue(calls.last().startsWith("library-undo ") && receiptId in calls.last())
        assertNull(library.directUndo(receipt)); assertTrue(runCatching { runBlocking { undo.execute() } }.isFailure)
    }

    @Test fun jsonAssetsPreviewOnlyWhenOpenedAndNeverExecuteTheirContents() {
        launch("none")
        for ((index,label) in listOf("채팅 완성 프리셋","로어북","빠른 답장(QR)","테마").withIndex()) {
            ui.onNodeWithText(label).performScrollTo().performClick()
            ui.onNodeWithText("샘플.json").assertExists()
            ui.onNodeWithContentDescription("샘플.json 더보기").assertExists()
            ui.runOnIdle { assertEquals(index,calls.count { it.startsWith("library-preview ") }) }
            ui.onNodeWithText("샘플.json").performClick()
            when(label) {
                "채팅 완성 프리셋" -> ui.onNodeWithText("0.8").assertExists()
                "로어북" -> {
                    ui.onNodeWithText("지도").performScrollTo().performClick()
                    ui.onNodeWithText("읽기 전용 본문").assertExists()
                    ui.onNodeWithText("미리보기에서 1개 항목은 생략했어요.").assertExists()
                }
                "빠른 답장(QR)" -> {
                    ui.onNodeWithText("인사").performScrollTo().performClick()
                    ui.onNodeWithText("/echo 안녕").assertHasNoClickAction()
                }
                "테마" -> ui.onNodeWithText("이 테마의 대화 예시예요.").assertExists()
            }
            ui.onNodeWithContentDescription("시트 닫기").performClick()
            ui.onNodeWithContentDescription("뒤로").performClick()
        }
        ui.runOnIdle {
            assertEquals(4,calls.count { it.startsWith("library-preview ") })
            assertFalse(calls.any { it.startsWith("library-usage ") }); assertTrue(writes.isEmpty())
        }
    }

    @Test fun usedBackgroundIsNotMovedAndOpeningItsMenuDoesNotStartAnOperation() {
        launch("used")
        ui.onNodeWithText("배경 그림").performScrollTo().performClick()
        ui.onNodeWithContentDescription("봄.jpg 더보기").performClick()
        ui.runOnIdle { assertTrue(writes.isEmpty()); assertFalse(calls.any { it.startsWith("library-usage ") }) }
        ui.onNodeWithText("휴지통으로 옮기기").performClick()
        ui.onNodeWithText("사용 중").assertExists()
        footer("휴지통으로 옮기기").assertDoesNotExist()
        footer("닫기").performClick()
        ui.runOnIdle { assertEquals(1,calls.count { it.startsWith("library-usage ") }); assertTrue(writes.isEmpty()) }
    }

    @Test fun unreferencedAssetRenameRequiresValidStemAndExplainsServerRestart() {
        launch("none",serverRunning = true)
        ui.onNodeWithText("빠른 답장(QR)").performScrollTo().performClick()
        ui.onNodeWithContentDescription("샘플.json 더보기").performClick()
        ui.onNodeWithText("이름 변경").performClick()
        ui.onNodeWithText("확인된 사용처 없음").assertExists()
        val confirm = footer("서버 끄고 이름 변경")
        confirm.assertIsNotEnabled()
        sheetInput().performTextReplacement("다른/이름")
        confirm.assertIsNotEnabled()
        sheetInput().performTextReplacement("새 답장")
        ui.onNodeWithText("확정하면 서버를 잠시 끄고 작업을 마친 뒤 다시 켜요.").assertExists()
        confirm.assertIsEnabled().performClick()
        ui.runOnIdle { assertEquals("이름 변경",writes.single().label); assertTrue(writes.single().pause) }
    }

    @Test fun unknownReferencesDefaultToCopyAndRequireConsentToTrashOrRenameOriginal() {
        launch("unconfirmed")
        ui.onNodeWithText("로어북").performScrollTo().performClick()
        fun open(action: String) {
            ui.onNodeWithContentDescription("샘플.json 더보기").performClick()
            ui.onNodeWithText(action).performClick()
            ui.onNodeWithText("확인 못 함").assertExists()
        }
        open("이름 변경")
        footer("새 이름으로 복사").assertIsNotEnabled()
        sheetInput().performTextReplacement("내 사본")
        footer("새 이름으로 복사").assertIsEnabled().performClick()
        ui.runOnIdle { assertEquals("새 이름으로 복사",writes.single().label) }
        open("이름 변경")
        sheetInput().performTextReplacement("새 이름")
        ui.onNodeWithText("원본 이름 변경").performScrollTo().performClick()
        footer("이름 변경").assertIsNotEnabled()
        ui.onNodeWithText("연결이 끊길 수 있음을 확인했어요").performScrollTo().performClick()
        footer("이름 변경").assertIsEnabled().performClick()
        open("휴지통으로 옮기기")
        footer("휴지통으로 옮기기").assertIsNotEnabled()
        ui.onNodeWithText("연결이 끊길 수 있음을 확인했어요").performScrollTo().performClick()
        footer("휴지통으로 옮기기").assertIsEnabled().performClick()
        ui.runOnIdle {
            assertEquals(listOf("새 이름으로 복사","이름 변경","휴지통으로 옮기기"),writes.map { it.label })
            assertTrue(writes.all { it.operation == "library-asset-change" && it.pause })
        }
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
