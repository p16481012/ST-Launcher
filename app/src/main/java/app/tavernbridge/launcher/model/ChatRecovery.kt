package app.tavernbridge.launcher.model

import java.util.Base64

data class ChatDamage(val path: String, val kind: String, val messages: Int, val broken: Int,
    val modified: Long, val revision: String, val backup: String, val backupMessages: Int,
    val backupModified: Long, val backupRevision: String, val match: String) {
    val label get() = path.substringAfter("chats/").substringBeforeLast('/').substringAfterLast('/').let {
        (if (path.contains("/group chats/")) "그룹" else it) + " · " + path.substringAfterLast('/').removeSuffix(".jsonl")
    }
    val user get() = path.split('/').getOrNull(1).orEmpty()
    val restorable get() = match == "verified"
    val problem get() = when(kind) {
        "bad_tail" -> "마지막 줄 잘림"; "bad_middle" -> "읽을 수 없는 줄 ${broken}개"; "bad_header" -> "채팅 정보 줄 손상"
        "too_large" -> "검사 한도 초과"; else -> "확인할 수 없는 형식"
    }
}
data class ChatScanPage(val scanned: Int, val ok: Int, val unread: Int, val next: String, val rows: List<ChatDamage>,
    val receipts: List<ChatUndo> = emptyList()) {
    val issues get() = rows.filter { it.kind.startsWith("bad_") }
    val skipped get() = unread + rows.size - issues.size
    fun item() = DiagnosticItem("chat_integrity",
        if (issues.isNotEmpty()) "읽을 수 없는 채팅 ${issues.size}개" else "채팅 파일",
        when { issues.isNotEmpty() -> "마지막 메시지가 사라졌을 수 있어요"
            next.isNotEmpty() || skipped > 0 -> "일부만 검사했어요 · ${scanned}개 확인"
            else -> "${ok}개" }, status = when { issues.isNotEmpty() -> DiagnosticStatus.WARNING
            next.isNotEmpty() || skipped > 0 -> DiagnosticStatus.INFO; else -> DiagnosticStatus.OK })
    fun append(p: ChatScanPage) = ChatScanPage(scanned+p.scanned,ok+p.ok,unread+p.unread,p.next,(rows+p.rows).distinctBy { it.path },receipts)
    companion object {
        fun parse(text: String): ChatScanPage {
            fun decode(s:String) = String(Base64.getDecoder().decode(s), Charsets.UTF_8)
            fun header(k:String) = text.lineSequence().single { it.startsWith("$k=") }.substringAfter('=')
            val rev=Regex("[a-f0-9]{64}")
            val rows=text.lineSequence().filter { it.startsWith("chat\t") }.map {
                val c=it.split('\t'); require(c.size==12 && rev.matches(c[6]) && c[2] in setOf("bad_tail","bad_middle","bad_header","unknown","too_large"))
                require(c[11] in setOf("none","verified","candidate") && (if(c[11]=="none") c[7].isEmpty() && c[10].isEmpty() else c[7].isNotEmpty() && rev.matches(c[10])))
                ChatDamage(decode(c[1]),c[2],c[3].toInt(),c[4].toInt(),c[5].toLong(),c[6],decode(c[7]),c[8].toInt(),c[9].toLong(),c[10],c[11])
            }.toList()
            val receipts = text.lineSequence().filter { it.startsWith("chat_undo\t") }.mapNotNull {
                val c = it.split('\t')
                if (c.size != 3 || !ChatUndo.validId(c[1])) null else ChatUndo(c[1],decode(c[2]).substringAfterLast('/').removeSuffix(".jsonl"))
            }.toList()
            return ChatScanPage(header("scanned").toInt(),header("ok_count").toInt(),header("unread").toInt(),decode(header("next_cursor")),rows,receipts)
        }
    }
}

data class ChatUndo(val id: String, val label: String) {
    companion object { fun validId(s: String) = s.matches(Regex("[0-9]{1,16}-[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")) }
}

