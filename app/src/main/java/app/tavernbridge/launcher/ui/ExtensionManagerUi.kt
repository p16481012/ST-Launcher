package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
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
) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedRevision by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAction by rememberSaveable { mutableStateOf<String?>(null) }
    var recoverConfirmation by rememberSaveable { mutableStateOf(false) }
    val canChange = canModify && !busy && !loading && inventory?.recoveryStatus == "none"
    val select: (ManagedExtension, ExtensionAction) -> Unit = { item, action ->
        selectedId = item.id; selectedRevision = item.revision; selectedAction = action.name
    }
    val clearSelection = { selectedId = null; selectedRevision = null; selectedAction = null }
    Dialog(onDismissRequest = { if (!busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(8.dp), tonalElevation = 6.dp) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("확장 프로그램", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onRefresh, enabled = !loading && !busy) { Icon(Icons.Outlined.Refresh, "확장 목록 새로고침") }
                }
                if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text("공용·사용자별 클라이언트 확장만 관리합니다. 서버 plugins와 알 수 없는 구조는 변경하지 않습니다. 업데이트는 HTTPS GitHub 원본만 지원합니다. 변경하려면 서버를 종료하세요.")
                        Text("‘사용 가능’은 파일 상태이며, SillyTavern의 계정별 활성 설정·실제 동작 여부와는 다릅니다. 목록 조회는 네트워크에 접속하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                        if (inventory?.recoveryStatus != null && inventory.recoveryStatus != "none") Text(inventory.recoveryMessage, color = MaterialTheme.colorScheme.error)
                        if (inventory?.recoveryStatus == "pending") OutlinedButton(
                            onClick = { recoverConfirmation = true }, enabled = canModify && !busy && !loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("중단된 확장 교체 점검·복구") }
                        if (inventory?.recoveryStatus == "blocked") Text("파일과 복구 기록을 그대로 보존했습니다. 기록을 임의로 삭제하지 말고 진단 정보를 전달해 주세요.", style = MaterialTheme.typography.bodySmall)
                        if ((inventory?.skipped ?: 0) > 0) Text("지원 구조나 접근 상태를 확인하지 못한 ${inventory?.skipped}개 항목은 변경하지 않았습니다.")
                    }
                    items(inventory?.items.orEmpty(), key = { it.id }) { item ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(item.name, style = MaterialTheme.typography.titleMedium)
                                Text("${item.scope} · ${if (item.disabled) "런처에서 비활성" else "사용 가능"}")
                                Text("버전 ${item.version.ifBlank { "알 수 없음" }} · ${item.commit.ifBlank { "Git 정보 없음" }}", style = MaterialTheme.typography.bodySmall)
                                if (item.updateRestriction.isNotBlank()) Text(item.updateRestriction, style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = { select(item, ExtensionAction.UPDATE) },
                                    enabled = canChange && item.canUpdate, modifier = Modifier.fillMaxWidth()) { Text("업데이트") }
                                val toggle = if (item.disabled) ExtensionAction.ENABLE else ExtensionAction.DISABLE
                                OutlinedButton(onClick = { select(item, toggle) }, enabled = canChange,
                                    modifier = Modifier.fillMaxWidth()) { Text(toggle.label) }
                                TextButton(onClick = { select(item, ExtensionAction.TRASH) }, enabled = canChange,
                                    modifier = Modifier.fillMaxWidth()) { Text("휴지통으로 이동", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                    if (inventory != null && inventory.items.isEmpty() && !loading) item { Text("지원되는 확장 프로그램이 없습니다.") }
                    if (inventory?.nextCursor != null) item {
                        OutlinedButton(onClick = onMore, enabled = !busy && !loading, modifier = Modifier.fillMaxWidth()) { Text("확장 목록 더 보기") }
                    }
                }
                TextButton(onClick = onClose, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("닫기") }
            }
        }
        if (selectedId != null) {
            val item = inventory?.items?.singleOrNull { it.id == selectedId && it.revision == selectedRevision }
            val action = ExtensionAction.entries.firstOrNull { it.name == selectedAction }
            AlertDialog(onDismissRequest = clearSelection, title = { Text("${action?.label ?: "확장 변경"}할까요?") },
                text = { Text(if (item != null && action != null) extensionActionExplanation(item, action) else "선택한 항목이 변경되었거나 목록을 다시 확인해야 합니다. 닫은 뒤 새로고침하세요.", Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) },
                confirmButton = { Button(enabled = canChange && item != null && action != null && (action != ExtensionAction.UPDATE || item.canUpdate), onClick = {
                    if (item != null && action != null) { clearSelection(); onAction(item, action) }
                }) { Text(action?.label ?: "확인") } },
                dismissButton = { TextButton(onClick = clearSelection) { Text("취소") } })
        }
        if (recoverConfirmation) {
            AlertDialog(onDismissRequest = { recoverConfirmation = false }, title = { Text("중단된 확장 교체를 복구할까요?") },
                text = { Text("기록된 폴더와 현재 파일이 일치할 때만 원래 확장을 되돌리거나 완료된 교체를 확정합니다. 일치하지 않으면 원본을 보존하고 중단합니다.") },
                confirmButton = { Button(enabled = canModify && !busy && !loading && inventory?.recoveryStatus == "pending", onClick = {
                    recoverConfirmation = false; onRecover()
                }) { Text("점검·복구") } },
                dismissButton = { TextButton(onClick = { recoverConfirmation = false }) { Text("취소") } })
        }
    }
}
