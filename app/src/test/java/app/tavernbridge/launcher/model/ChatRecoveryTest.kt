package app.tavernbridge.launcher.model

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class ChatRecoveryTest {
    private val path = "data/나/chats/A/비공개 대화.jsonl"
    private val rev = "ab".repeat(32)
    private fun b(s:String) = Base64.getEncoder().encodeToString(s.toByteArray())
    private fun page(match:String="verified", extra:String="") = ChatScanPage.parse(
        listOf("chat",b(path),"bad_tail","4","1","100",rev,b("data/나/backups/chat_x.jsonl"),"6","99",rev,match).joinToString("\t")+
            "\nscanned=4\nok_count=3\nunread=0\nnext_cursor=\n"+extra)
    @Test fun diagnosticReportsCountsWithoutLeakingPrivateNames(){
        val p=page();val report=DiagnosticReport("now",emptyList()).withChat(p)
        assertEquals("A · 비공개 대화",p.rows.single().label);assertTrue(p.rows.single().restorable)
        assertEquals(1,report.warningCount);assertFalse(report.copyText().contains("비공개 대화"))
        assertFalse(page("candidate").rows.single().restorable)
    }
    @Test fun aPartialScanCannotReportAllClear(){
        val p=ChatScanPage(12,12,0,"data/path",emptyList())
        assertEquals(DiagnosticStatus.INFO,p.item().status);assertEquals(DiagnosticStatus.OK,p.copy(next="").item().status)
    }
    @Test fun reloadingCarriesOnlyValidUndoIds(){
        val id="100-00000000-0000-4000-8000-000000000000"
        val p=page(extra="chat_undo\t$id\t"+b("비공개 대화.jsonl")+"\nchat_undo\t../other\t"+b("wrong"))
        assertEquals(listOf(ChatUndo(id,"비공개 대화")),p.receipts)
        assertEquals(p.receipts,p.append(ChatScanPage(0,0,0,"",emptyList())).receipts)
    }
    @Test(expected=IllegalArgumentException::class) fun unknownMatchIsNotADestructiveChoice(){ page("guessed") }
}
