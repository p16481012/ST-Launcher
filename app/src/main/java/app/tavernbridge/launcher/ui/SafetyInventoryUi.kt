package app.tavernbridge.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tavernbridge.launcher.R
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.theme.LocalLauncherColors
import app.tavernbridge.launcher.ui.theme.V2Mono
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SafetyInventoryItem.title get() = when (kind) {
    SafetyItemKind.ARCHIVE -> "업데이트 전 보호 사본"
    SafetyItemKind.CONFIG -> "연결 설정 사본"
    SafetyItemKind.TRASH -> originalRelativePath.ifBlank { displayName }
}
private val SafetyInventoryItem.icon get() = when (kind) {
    SafetyItemKind.ARCHIVE -> R.drawable.rv_update
    SafetyItemKind.CONFIG -> R.drawable.rv_shield
    SafetyItemKind.TRASH -> if (isDirectory) R.drawable.rv_folder else R.drawable.rv_file
}
private val SafetyInventoryItem.unconfirmed get() = kind == SafetyItemKind.ARCHIVE && !completionRecorded
private val SafetyItemKind.heading get() = when (this) {
    SafetyItemKind.ARCHIVE -> "업데이트 보호 사본"
    SafetyItemKind.CONFIG -> "연결 설정 사본"
    SafetyItemKind.TRASH -> "휴지통"
}
private fun storageLabel(bytes: Long?): String = when {
    bytes == null -> "용량 미확인"
    bytes >= 1_000_000_000L -> String.format(Locale.KOREA, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000L -> String.format(Locale.KOREA, "%.1f MB", bytes / 1e6)
    bytes >= 1000L -> String.format(Locale.KOREA, "%.1f KB", bytes / 1e3)
    else -> "$bytes B"
}
private fun totalLabel(items: List<SafetyInventoryItem>): String {
    var total = 0L
    for (item in items) {
        val size = item.sizeBytes ?: return "전체 용량 미확인"
        if (size < 0 || size > Long.MAX_VALUE - total) return "전체 용량 미확인"
        total += size
    }
    return storageLabel(total)
}
private data class SafetyRequest(val action: String, val expected: Map<String,String>, val multiple: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SafetyInventoryScreen(
    state: SafetyInventoryUiState, serverRunning: Boolean, busy: Boolean,
    onClose: () -> Unit, onRefresh: () -> Unit, onMore: () -> Unit,
    onAction: (String, SafetyInventoryItem) -> Unit, onDeleteMany: (List<SafetyInventoryItem>) -> Unit,
    onRepair: () -> Unit, onNotice: (String) -> Unit,
) {
    val colors = LocalLauncherColors.current
    val indexed = remember(state.items) { state.items.associateBy { it.id } }
    val groups = remember(state.items) { state.items.groupBy { it.kind } }
    val stamp = remember { SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA) }
    fun meta(item: SafetyInventoryItem) = stamp.format(Date(item.modifiedAtMillis)) + " · " +
        if (item.isDirectory) "폴더" else storageLabel(item.sizeBytes)
    var selecting by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(emptyMap<String,String>()) }
    var sheet by remember { mutableStateOf<Pair<String,String>?>(null) }
    var pending by remember { mutableStateOf<SafetyRequest?>(null) }
    val locked = state.mutationBlocked
    val partial = state.nextCursor != null || state.truncated
    val blockedReason = "복구 중인 자료가 있어요. 점검·복구를 먼저 진행해 주세요."
    fun resolve(expected: Map<String,String>) = expected.mapNotNull { (id, rev) -> indexed[id]?.takeIf { it.revision == rev } }
    fun resetSelection() { selecting = false; selection = emptyMap() }
    val selectedItems = resolve(selection)
    val selectedTotal = totalLabel(selectedItems)
    val item = sheet?.let { (id, rev) -> indexed[id]?.takeIf { it.revision == rev } }
    val request = pending
    val changed = selectedItems.size != selection.size || (sheet != null && item == null) ||
        (request != null && resolve(request.expected).size != request.expected.size)
    LaunchedEffect(locked, changed) {
        if (locked || changed) {
            pending = null; sheet = null; resetSelection()
            if (changed) onNotice("목록이 바뀌었어요. 현재 항목을 다시 선택해 주세요.")
        }
    }
    val back = {
        when { pending != null -> pending = null; sheet != null -> sheet = null
            selecting -> resetSelection(); else -> onClose() }
    }
    V2BackHandler(true, back)
    fun choose(row: SafetyInventoryItem) {
        when {
            locked -> onNotice(blockedReason)
            busy || state.loading -> onNotice("진행 중인 확인이나 작업이 끝난 뒤 선택해 주세요.")
            selecting -> selection = if (selection.containsKey(row.id)) selection - row.id else selection + (row.id to row.revision)
            else -> sheet = row.id to row.revision
        }
    }
    fun ask(action: String, row: SafetyInventoryItem) {
        sheet = null
        pending = SafetyRequest(action, mapOf(row.id to row.revision), false)
    }
    Column(Modifier.fillMaxSize().background(colors.canvas)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) {
                ReviewGlyph(if (selecting) R.drawable.rv_close else R.drawable.rv_arrow_left, description = if (selecting) "선택 취소" else "뒤로")
            }
            Spacer(Modifier.width(12.dp))
            Text(if (selecting) "${selection.size}개 선택" else "안전 사본·휴지통", Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (!locked && state.items.isNotEmpty()) {
                if (selecting) TextButton(onClick = {
                    selection = if (selection.size == state.items.size) emptyMap() else indexed.mapValues { it.value.revision }
                }, enabled = !busy && !state.loading) {
                    Text(if (selection.size == state.items.size) "선택 해제" else "모두 선택", color = colors.ink)
                } else IconButton(onClick = { selecting = true }, enabled = !busy && !state.loading) {
                    ReviewGlyph(R.drawable.rv_select, description = "항목 선택")
                }
            }
            Spacer(Modifier.width(10.dp))
        }
        val pull = rememberPullToRefreshState()
        LazyColumn(Modifier.weight(1f).fillMaxWidth().semantics {
            if (!busy && !state.loading && !selecting) customActions = listOf(CustomAccessibilityAction("새로고침") { onRefresh(); true })
        }.pullToRefresh(isRefreshing = state.loading, state = pull,
            enabled = !busy && !state.loading && !selecting, threshold = 64.dp, onRefresh = onRefresh),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
            if (!selecting) item(key = "intro") {
                Spacer(Modifier.height(12.dp))
                if (locked) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.dangerWash).padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ReviewGlyph(R.drawable.rv_info, 17.dp, colors.danger)
                    Column {
                        Text("복구가 끝나지 않은 자료가 있어요", fontWeight = FontWeight.SemiBold)
                        Text("점검·복구를 먼저 하면 다시 관리할 수 있어요.", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = onRepair, enabled = !busy) { Text("점검·복구", color = colors.ink) }
                    }
                } else {
                    Text("업데이트·삭제 때 보관한 자료예요", style = MaterialTheme.typography.titleMedium,
                        fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("ZIP 백업 목록과는 따로 보관돼요. 복구에 필요한 자료는 정리 대상에서 빠져요.",
                        style = MaterialTheme.typography.bodyLarge, color = colors.ink2)
                }
            }
            if (state.loading) item { V2BusyIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp)) }
            if (state.error.isNotBlank()) item {
                Text(state.error, Modifier.padding(vertical = 8.dp), color = colors.danger)
                TextButton(onClick = onRefresh, enabled = !state.loading && !busy) { Text("다시 확인") }
            }
            for (kind in SafetyItemKind.entries) {
                val rows = groups[kind].orEmpty()
                val total = state.totalFor(kind)
                if (rows.isEmpty() && (total ?: 0) == 0) continue
                item(key = "group-${kind.key}") {
                    Row(Modifier.fillMaxWidth().padding(top = if (selecting && kind == SafetyItemKind.ARCHIVE) 12.dp else 24.dp,
                        bottom = 10.dp).alpha(if (locked) .38f else 1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(kind.heading, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(total?.let { "${it}개" } ?: "표시 ${rows.size}개", color = colors.ink3, fontSize = 13.sp)
                    }
                }
                if (rows.isEmpty()) item { Text("아래 ‘더 보기’로 항목을 불러오세요.", color = colors.ink2, style = MaterialTheme.typography.bodySmall) }
                itemsIndexed(rows, key = { _, it -> it.id }) { index, row ->
                    val first = if (index == 0) 20.dp else 0.dp
                    val last = if (index == rows.lastIndex) 20.dp else 0.dp
                    val selectedRow = selection[row.id] == row.revision
                    Column(Modifier.fillMaxWidth().alpha(if (locked) .38f else 1f)
                        .clip(RoundedCornerShape(first, first, last, last)).background(if (selectedRow) colors.sunken else colors.surface)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).semantics { if (selecting) selected = selectedRow }
                            .clickable(role = if (selecting) Role.Checkbox else Role.Button) { choose(row) }
                            .padding(start = 20.dp, end = if (selecting) 8.dp else 14.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (selecting) Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp))
                                .background(if (selectedRow) colors.accent else Color.Transparent)
                                .border(1.4.dp, if (selectedRow) colors.accent else colors.ink2, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                                if (selectedRow) ReviewGlyph(R.drawable.rv_check, 14.dp, colors.onInk)
                            }
                            ReviewGlyph(row.icon, 20.dp, colors.ink2)
                            Column(Modifier.weight(1f)) {
                                Text(row.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(meta(row), Modifier.weight(1f, fill = false), fontFamily = V2Mono, fontSize = 13.sp,
                                        lineHeight = 18.sp, color = colors.ink2)
                                    if (row.unconfirmed) Text("확인 필요", color = colors.onAccentWash, fontSize = 12.sp)
                                }
                            }
                            if (!selecting) ReviewGlyph(R.drawable.rv_more, 18.dp, colors.ink2)
                        }
                        if (index < rows.lastIndex) HorizontalDivider(Modifier.padding(start = if (selecting) 86.dp else 52.dp, end = 20.dp), color = colors.hairline)
                    }
                }
            }
            if (state.items.isEmpty() && !state.loading && state.error.isBlank()) item {
                Text("보관한 항목이 없어요.", Modifier.padding(vertical = 20.dp), color = colors.ink2)
            }
            if (state.protectedEntries > 0) item {
                Text("보호 중인 자료 ${state.protectedEntries}개는 표시하지 않아요.", Modifier.padding(top = 12.dp), color = colors.ink2, fontSize = 13.sp)
            }
            if (partial && selecting) item { Text("‘모두 선택’은 현재 불러온 항목에만 적용돼요.", color = colors.ink2, fontSize = 13.sp) }
            if (state.nextCursor != null) item { TextButton(onClick = onMore, enabled = !busy && !state.loading) { Text("더 보기") } }
            if (state.truncated) item { Text("조사 한도에 도달해 일부 항목만 표시해요.", style = MaterialTheme.typography.bodySmall) }
        }
        if (selecting && !locked) Button(onClick = { pending = SafetyRequest("delete", selection.toMap(), true) },
            enabled = selection.isNotEmpty() && !changed && !state.loading && !busy,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).heightIn(min = 52.dp),
            shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = colors.danger, contentColor = Color.White)) {
            Text("영구 삭제 (${selectedItems.size}) · $selectedTotal")
        }
    }
    if (item != null && !locked) V2BottomSheet(onClose = { sheet = null }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ReviewGlyph(item.icon, 22.dp, colors.ink2)
            Column { Text(item.title, style = MaterialTheme.typography.titleMedium); Text(meta(item), fontFamily = V2Mono, fontSize = 13.sp, color = colors.ink2) }
        }
        Text(item.displayName, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.sunken).padding(14.dp),
            fontFamily = V2Mono, fontSize = 13.sp, color = colors.ink2)
        if (item.unconfirmed) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReviewGlyph(R.drawable.rv_info, 16.dp, colors.onAccentWash)
            Text("완료 기록이 없는 사본이에요. 지우기 전에 내보내서 내용을 확인해 주세요.", color = colors.ink2, style = MaterialTheme.typography.bodySmall)
        }
        if (item.restorable) SafetySheetAction("원래 위치로 복원", R.drawable.rv_restart) {
            if (serverRunning) onNotice("서버를 종료한 뒤 원래 위치로 복원할 수 있어요.") else ask("restore", item)
        } else Text(if (item.kind == SafetyItemKind.TRASH) "현재 설치와 원래 설치 위치가 달라 직접 복원할 수 없어요."
            else "이 사본은 여기서 설치에 바로 덮어쓰지 않아요. Download로 내보내 내용을 확인할 수 있어요.",
            style = MaterialTheme.typography.bodySmall, color = colors.ink2)
        if (item.canExport) SafetySheetAction("Download로 사본 내보내기", R.drawable.rv_backup) { ask("export", item) }
        SafetySheetAction("영구 삭제", R.drawable.rv_trash, danger = true) { ask("delete", item) }
    }
    if (request != null && !locked && !changed) {
        val rows = resolve(request.expected)
        val subject = if (rows.size == 1) rows.single().title else "선택한 항목 ${rows.size}개"
        val action = request.action
        val allowed = !busy && !state.loading && rows.isNotEmpty() && rows.size == request.expected.size &&
            when (action) { "restore" -> !serverRunning && rows.singleOrNull()?.restorable == true
                "export" -> rows.singleOrNull()?.canExport == true; else -> action == "delete" }
        V2AlertDialog(onDismissRequest = { pending = null }, title = { Text(when (action) {
            "delete" -> "영구 삭제할까요?"; "restore" -> "원래 위치로 복원할까요?"; else -> "사본을 내보낼까요?"
        }) }, text = { Text(when (action) {
            "delete" -> "$subject(${totalLabel(rows)})을 지워요. 되돌릴 수 없어요. 지금 설치는 지우지 않아요."
            "restore" -> "$subject 항목을 원래 위치로 되돌려요. 같은 이름이 있으면 덮어쓰지 않고 중단해요."
            else -> "$subject 항목을 Download에 암호화하지 않고 복사해요. 대화나 비밀정보가 포함될 수 있어요. 원본은 유지해요."
        }) }, dismissButton = { V2DialogAction(onClick = { pending = null }, tone = V2DialogTone.Quiet) { Text("취소") } },
            confirmButton = { V2DialogAction(enabled = allowed, tone = if (action == "delete") V2DialogTone.Danger else V2DialogTone.Primary,
                onClick = {
                    if (pending == request && allowed) {
                        pending = null; resetSelection()
                        if (request.multiple) onDeleteMany(rows) else onAction(action, rows.single())
                    }
                }) { Text(when (action) { "delete" -> "영구 삭제"; "restore" -> "복원"; else -> "내보내기" }) } })
    }
}

@Composable private fun SafetySheetAction(label: String, icon: Int, danger: Boolean = false, onClick: () -> Unit) {
    val colors = LocalLauncherColors.current
    val ink = if (danger) colors.danger else colors.ink
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        ReviewGlyph(icon, 18.dp, ink); Text(label, color = ink, style = MaterialTheme.typography.bodyLarge)
    }
}
