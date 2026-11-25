package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.tavernbridge.launcher.model.*

@Composable
fun ExtensionManagerDialog(
    inventory: ExtensionInventory?, loading: Boolean, busy: Boolean, canModify: Boolean, error: String,
    onRefresh: () -> Unit, onMore: () -> Unit, onRecover: () -> Unit,
    onAction: (ManagedExtension, ExtensionAction) -> Unit, onClose: () -> Unit,
    embedded: Boolean = false,
    onCheckUpdates: (() -> Unit)? = null,
    onUpdateAll: ((List<ManagedExtension>) -> Unit)? = null,
    stopState: LauncherUiState? = null,
) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedRevision by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAction by rememberSaveable { mutableStateOf<String?>(null) }
    var recoverConfirmation by rememberSaveable { mutableStateOf(false) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var bulkRevisions by remember { mutableStateOf<Map<String,String>?>(null) }
    var info by rememberSaveable { mutableStateOf(false) }
    val entries = remember(inventory) { extensionDisplayOrder(inventory?.items.orEmpty()) }
    val outdated = entries.filter { it.canUpdate && (it.remoteAhead ?: 0) > 0 }
    val canChange = canModify && !busy && !loading && inventory?.recoveryStatus == "none"
    val stop = if (stopState != null) rememberStopToContinue(stopState, openId) else null
    val canEnter = canChange || (stop != null && !busy && !loading && inventory?.recoveryStatus == "none")
    fun confirm(label: String, show: () -> Unit) {
        if (canModify) show() else stop?.invoke(label, show)
    }
    val select: (ManagedExtension, ExtensionAction) -> Unit = { item, action ->
        confirm("${item.name} · ${action.label} 확인") {
            selectedId = item.id; selectedRevision = item.revision; selectedAction = action.name
        }
    }
    val clearSelection = { selectedId = null; selectedRevision = null; selectedAction = null }
    ToolPaneHost(embedded, { if (!busy) onClose() }) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (inventory == null) "설치된 확장 확인" else listOf(
                        "${entries.size}개${if (inventory.nextCursor != null) "+" else ""} 설치됨", extensionCheckSummary(entries),
                        "꺼짐 ${entries.count { it.disabled }}개").joinToString(" · "),
                        Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = onRefresh, enabled = !loading && !busy) { Icon(Icons.Outlined.Refresh, "확장 목록 새로고침") }
                }
                if (loading || busy) V2BusyIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("설치된 확장", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            IconButton(onClick = { info = true }) { Icon(Icons.Outlined.Info, "확장 프로그램 설명") }
                        }
                        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                        if (inventory?.recoveryStatus != null && inventory.recoveryStatus != "none") Text(inventory.recoveryMessage, color = MaterialTheme.colorScheme.error)
                        if (inventory?.recoveryStatus == "pending") OutlinedButton(
                            onClick = { confirm("확장 교체 복구 확인") { recoverConfirmation = true } }, enabled = (canModify || stop != null) && !busy && !loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("중단된 확장 교체 점검·복구") }
                        if (inventory?.recoveryStatus == "blocked") Text("파일과 복구 기록을 그대로 보존했습니다. 기록을 임의로 삭제하지 말고 진단 정보를 전달해 주세요.", style = MaterialTheme.typography.bodySmall)
                        if ((inventory?.skipped ?: 0) > 0) Text("지원 구조나 접근 상태를 확인하지 못한 ${inventory?.skipped}개 항목은 변경하지 않았습니다.")
                    }
                    items(entries, key = { it.id }) { item ->
                        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
                            Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) {
                                    ToolRow(item.name, listOf(extensionVersionLabel(item),
                                        item.checkLabel().takeUnless { (item.remoteAhead ?: 0) > 0 }.orEmpty())
                                        .filter { it.isNotBlank() }.joinToString(" · "), if (item.disabled) "꺼짐" else "",
                                        icon = Icons.Outlined.Extension, singleLine = true,
                                        onClick = { openId = if (openId == item.id) null else item.id })
                                }
                                if (item.canUpdate && (item.remoteAhead ?: 0) > 0)
                                    TextButton({ select(item, ExtensionAction.UPDATE) }, enabled = canEnter) { Text("업데이트", color = MaterialTheme.colorScheme.secondary) }
                            }
                            ToolDivider()
                            if (openId == item.id)
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${item.scope} · ${if (item.disabled) "런처에서 비활성" else "사용 가능"}")
                                Text("버전 ${item.version.ifBlank { "알 수 없음" }} · ${item.commit.ifBlank { "Git 정보 없음" }}", style = MaterialTheme.typography.bodySmall)
                                if (item.gitBranch.isNotBlank()) Text("${item.gitBranch} · ${item.checkLabel()}", style = MaterialTheme.typography.bodySmall)
                                if (item.updateRestriction.isNotBlank()) Text(item.updateRestriction, style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = { select(item, ExtensionAction.UPDATE) },
                                    enabled = canEnter && item.canUpdate && item.remoteAhead != 0 && item.checkIssue != ExtensionCheckIssue.DIVERGED,
                                    modifier = Modifier.fillMaxWidth()) { Text(when {
                                        item.remoteAhead == 0 -> "변경할 내용 없음"
                                        item.remoteAhead == null -> "Git에서 직접 확인 후 업데이트"
                                        else -> "업데이트"
                                    }) }
                                val toggle = if (item.disabled) ExtensionAction.ENABLE else ExtensionAction.DISABLE
                                OutlinedButton(onClick = { select(item, toggle) }, enabled = canEnter,
                                    modifier = Modifier.fillMaxWidth()) { Text(toggle.label) }
                                TextButton(onClick = { select(item, ExtensionAction.TRASH) }, enabled = canEnter,
                                    modifier = Modifier.fillMaxWidth()) { Text("휴지통으로 이동", color = MaterialTheme.colorScheme.error) }
                            }
                            }
                        }
                    }
                    if (inventory != null && inventory.items.isEmpty() && !loading) item { Text("지원되는 확장 프로그램이 없습니다.") }
                    if (inventory?.nextCursor != null) item {
                        OutlinedButton(onClick = onMore, enabled = !busy && !loading, modifier = Modifier.fillMaxWidth()) { Text("확장 목록 더 보기") }
                    }
                }
                V2BottomActions {
                if (onCheckUpdates != null) OutlinedButton(onClick = onCheckUpdates, enabled = !loading && !busy && entries.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if ((inventory?.nextCheckOffset ?: 0) != 0) "새 버전 확인 · 다음 최대 40개" else "새 버전 확인")
                }
                if (onUpdateAll != null && outdated.isNotEmpty()) Button(onClick = {
                    val expected = outdated.associate { it.id to it.revision }
                    confirm("선택한 확장 업데이트 확인") { bulkRevisions = expected }
                }, enabled = canEnter,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("확인된 ${outdated.size}개 모두 업데이트") }
                if (!embedded) TextButton(onClick = onClose, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("닫기") }
                }
            }
        }
        if (info) V2Explanation("확장 프로그램", "공개 GitHub 원본에서 버전이나 새 커밋 수만 확인해요. 확장을 변경하려면 서버를 먼저 종료해 주세요.") { info = false }
        bulkRevisions?.let { expected ->
            val targets = outdated.filter { expected[it.id] == it.revision }
            V2AlertDialog(onDismissRequest = { bulkRevisions = null }, title = { Text("확장 ${expected.size}개를 업데이트할까요?") },
                text = { Column {
                    Text("각 확장을 순서대로 검사하고 교체해요. 오류나 중단이 발생하면 이미 완료한 확장은 유지하고 나머지는 진행하지 않아요.")
                    targets.forEach { Text("· ${it.name}") }
                } }, confirmButton = { V2DialogAction(onClick = { bulkRevisions = null; onUpdateAll?.invoke(targets) },
                    enabled = canChange && targets.size == expected.size) { Text("업데이트") } },
                dismissButton = { V2DialogAction(tone = V2DialogTone.Quiet, onClick = { bulkRevisions = null }) { Text("취소") } })
        }
        if (selectedId != null) {
            val item = inventory?.items?.singleOrNull { it.id == selectedId && it.revision == selectedRevision }
            val action = ExtensionAction.entries.firstOrNull { it.name == selectedAction }
            V2AlertDialog(onDismissRequest = clearSelection, title = { Text("${action?.label ?: "확장 변경"}할까요?") },
                text = { Text(if (item != null && action != null) extensionActionExplanation(item, action) else "선택한 항목이 변경되었거나 목록을 다시 확인해야 합니다. 닫은 뒤 새로고침하세요.") },
                confirmButton = { V2DialogAction(tone = if (action == ExtensionAction.TRASH) V2DialogTone.Danger else V2DialogTone.Primary, enabled = canChange && item != null && action != null && (action != ExtensionAction.UPDATE || item.canUpdate), onClick = {
                    if (item != null && action != null) { clearSelection(); onAction(item, action) }
                }) { Text(action?.label ?: "확인") } },
                dismissButton = { V2DialogAction(tone = V2DialogTone.Quiet, onClick = clearSelection) { Text("취소") } })
        }
        if (recoverConfirmation) {
            V2AlertDialog(onDismissRequest = { recoverConfirmation = false }, title = { Text("중단된 확장 교체를 복구할까요?") },
                text = { Text("기록된 폴더와 현재 파일이 일치할 때만 원래 확장을 되돌리거나 완료된 교체를 확정합니다. 일치하지 않으면 원본을 보존하고 중단합니다.") },
                confirmButton = { V2DialogAction(enabled = canModify && !busy && !loading && inventory?.recoveryStatus == "pending", onClick = {
                    recoverConfirmation = false; onRecover()
                }) { Text("점검·복구") } },
                dismissButton = { V2DialogAction(tone = V2DialogTone.Quiet, onClick = { recoverConfirmation = false }) { Text("취소") } })
        }
    }
}
