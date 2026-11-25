package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.model.ChatDamage
import app.tavernbridge.launcher.model.ChatUndo
import app.tavernbridge.launcher.model.LauncherUiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun chatTime(ms: Long) = DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))

private data class ChatChoice(val rows: List<ChatDamage>, val mode: String, val label: String)
private fun ChatDamage.choice() = when {
    restorable -> ChatChoice(listOf(this),"restore-backup","사본으로 되찾기")
    match == "candidate" -> ChatChoice(listOf(this),"candidate-copy","사본을 새 파일로 저장")
    kind == "bad_tail" -> ChatChoice(listOf(this),"trim-tail","잘린 마지막 줄만 정리")
    messages > 0 -> ChatChoice(listOf(this),"rescue-copy","읽을 수 있는 메시지를 새 파일로 저장")
    else -> null
}

@Composable internal fun ChatRecoveryDetail(state: LauncherUiState, scan: (Boolean) -> Unit,
    repair: (List<ChatDamage>,String) -> Unit, undo: (ChatUndo) -> Unit, modifier: Modifier) {
    val report = state.diagnosticReport
    val page = report?.chatScan
    var hidden by remember(report?.checkedAt,page?.scanned) { mutableStateOf(emptySet<String>()) }
    var selected by remember { mutableStateOf<ChatDamage?>(null) }
    var batch by remember { mutableStateOf(false) }
    var undoItem by remember { mutableStateOf<ChatUndo?>(null) }
    val stop = rememberStopToContinue(state, state.navigation.entryId)
    val ready = !state.isWorking && !state.environment.operationActive && !state.environment.recoveryPending
    val remaining = page?.issues.orEmpty().filterNot { it.path in hidden }
    val verified = remaining.filter { it.restorable }
    val others = remaining.filterNot { it.restorable }
    val running = state.environment.processRunning
    val busyNote = if (!ready) "진행 중인 작업을 마친 뒤 복구할 수 있어요." else if (running) "서버를 끈 뒤에만 채팅을 바꿀 수 있어요." else ""
    Column(modifier) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(vertical = 12.dp)) {
            item { Lead(when {
                page == null -> "채팅 파일을 확인해 주세요"
                remaining.isNotEmpty() -> "채팅 ${remaining.size}개에 문제가 있어요"
                page.next.isNotEmpty() || page.skipped > 0 -> "확인한 범위에는 문제가 없어요"
                hidden.isNotEmpty() -> "이번 결과에 남은 채팅이 없어요"
                else -> "문제 있는 채팅이 없어요"
            }, listOfNotNull(report?.checkedAt?.takeIf { it.isNotBlank() }?.let { "$it 확인" },
                "고치기 전에 원본은 휴지통에 보관해요.").joinToString(" · ")) }
            if (state.chatCompletion.isNotBlank()) item {
                Column {
                    Text(state.chatCompletion,style=MaterialTheme.typography.bodyMedium)
                    SettingsCaption("실리태번 탭이 열려 있다면 새로고침해 주세요. 예전 내용으로 다시 저장될 수 있어요.")
                }
            }
            listOf("사본으로 되찾을 수 있어요" to verified,"따로 확인해 주세요" to others).forEach { (title,entries) ->
                if (entries.isNotEmpty()) item { Text(title,style=MaterialTheme.typography.titleSmall) }
                items(entries.size, key={ entries[it].path }) { i ->
                    val r = entries[i]
                    ToolGroup { ToolRow(r.label, "${r.user} · ${r.problem} · " + when {
                        r.restorable -> "사본에 메시지 ${r.backupMessages-r.messages}개 더"
                        r.match == "candidate" -> "비슷한 사본이 있지만 같은 대화인지 확실하지 않아요"
                        else -> "메시지 ${r.messages}개 읽을 수 있음"
                    }) { selected = r } }
                }
            }
            if (page != null && (page.next.isNotEmpty() || page.skipped > 0)) item {
                SettingsCaption("채팅 ${page.scanned}개 확인 · 검사 한도 또는 읽기 제한으로 전체를 확인하지 못한 항목 ${page.skipped}개")
            }
            if (ready) item {
                ToolGroup { if (page?.next?.isNotEmpty()==true) {
                    ToolRow("이어서 검사", "아직 확인하지 않은 채팅을 검사해요.") { scan(true) }; ToolDivider()
                }
                ToolRow(if(page==null) "채팅 검사" else "처음부터 다시 검사", "서버가 켜져 있어도 검사할 수 있어요.") { scan(false) } }
            }
            if (state.chatUndo.isNotEmpty()) item { ToolGroup("복구 실행 취소") {
                state.chatUndo.forEachIndexed { i,u -> if(i>0) ToolDivider()
                    ToolRow(u.label,"복구 뒤에 바뀐 파일은 덮어쓰지 않아요.",onClick=if(ready) ({undoItem=u}) else null) }
            } }
            if(busyNote.isNotEmpty()) item { SettingsCaption(busyNote) }
        }
        if (verified.isNotEmpty() && ready && (!running || stop != null)) V2BottomActions {
            V2SheetPrimaryButton("사본으로 모두 되찾기 (${verified.size})") { if(running) stop?.invoke("채팅 복구"){batch=true} else batch=true }
        }
    }
    selected?.let { row ->
        val choice=row.choice()
        V2BottomSheet(onClose={selected=null}, footer={
            Column(Modifier.fillMaxWidth(), horizontalAlignment=androidx.compose.ui.Alignment.CenterHorizontally) {
            if(choice!=null && ready && (!running||stop!=null)) V2SheetPrimaryButton(choice.label) {
                selected=null
                if(running) stop?.invoke("채팅 복구"){selected=row} else repair(choice.rows,choice.mode)
            }
            V2DialogAction(tone=if(choice!=null && ready && (!running||stop!=null)) V2DialogTone.Quiet else V2DialogTone.Primary,
                onClick={selected=null;hidden=hidden+row.path}) { Text("그대로 두기") }
            }
        }) {
            Lead(row.label, when(row.kind){
                "bad_tail" -> "마지막 줄이 잘려 있어요. 실리태번은 이 줄을 건너뛰어서 마지막 메시지가 보이지 않아요."
                "bad_middle" -> "읽을 수 없는 줄이 ${row.broken}개 있어요. 읽을 수 있는 메시지는 별도 파일에 보관할 수 있어요."
                else -> "채팅 정보 줄이 깨져 있어요. 실리태번이 저장할 때 경고를 띄울 수 있어요."
            })
            ToolGroup { ToolRow("지금 파일","메시지 ${row.messages}개 · ${chatTime(row.modified)}")
                if(row.backup.isNotEmpty()){ToolDivider();ToolRow(if(row.restorable) "확인된 자동 사본" else "비슷한 자동 사본",
                    "메시지 ${row.backupMessages}개 · ${chatTime(row.backupModified)}")} }
            if(!row.restorable) SettingsCaption(when {
                row.match=="candidate" -> "같은 대화라고 확인되지 않았어요. 원본을 바꾸지 않고 별도 파일로 저장해요."
                row.kind=="bad_tail" -> "사라진 메시지는 되찾을 수 없어요."
                row.messages==0 -> "읽을 수 있는 메시지가 없어 변경하지 않아요."
                else -> "원본은 그대로 두고, 읽을 수 있는 메시지만 같은 폴더의 새 파일에 저장해요."
            })
            if(busyNote.isNotEmpty()) SettingsCaption(busyNote)
        }
    }
    if(batch) V2BottomSheet(onClose={batch=false},footer={
        Column(Modifier.fillMaxWidth(),horizontalAlignment=androidx.compose.ui.Alignment.CenterHorizontally) {
        V2SheetPrimaryButton("확인된 ${verified.size}개 되찾기",enabled=verified.isNotEmpty()&&ready&&!running){
            batch=false;repair(verified,"restore-backup")
        }
        V2DialogAction(tone=V2DialogTone.Quiet,onClick={batch=false}){Text("취소")}
        }
    }) { Lead("사본으로 모두 되찾을까요?","같은 대화이고 현재 읽을 수 있는 메시지가 전부 남아 있는 사본만 사용해요. 중간에 파일이 바뀌면 멈춰요.") }
    undoItem?.let { u -> V2BottomSheet(onClose={undoItem=null},footer={
        if(ready && (!running||stop!=null)) V2SheetPrimaryButton("실행 취소"){
            undoItem=null;if(running)stop?.invoke("복구 실행 취소"){undoItem=u} else undo(u)
        }
    }) { Lead(u.label,"복구 전 상태로 되돌려요. 이후 내용이 바뀐 파일은 건드리지 않아요.") } }
}
