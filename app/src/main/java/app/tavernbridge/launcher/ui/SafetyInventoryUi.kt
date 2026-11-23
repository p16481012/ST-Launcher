package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.model.SafetyInventoryItem
import app.tavernbridge.launcher.model.SafetyInventoryUiState
import app.tavernbridge.launcher.model.SafetyItemKind
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun SafetyInventoryDialog(
    state: SafetyInventoryUiState,
    serverRunning: Boolean,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    onMore: () -> Unit,
    onAction: (String, SafetyInventoryItem) -> Unit,
) {
    var pendingId by rememberSaveable { mutableStateOf("") }
    var pendingRevision by rememberSaveable { mutableStateOf("") }
    var pendingAction by rememberSaveable { mutableStateOf("") }
    fun select(action: String, item: SafetyInventoryItem) {
        pendingId = item.id
        pendingRevision = item.revision
        pendingAction = action
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("안전 사본·휴지통") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("업데이트 보호 사본과 삭제한 항목입니다. ZIP 백업 목록과는 다르며, 복구에 필요한 자료는 정리 대상에서 제외합니다.")
                }
                if (state.mutationBlocked) item {
                    Text("미완료 복구 자료가 있어 변경을 잠갔습니다. 점검·복구를 먼저 진행해 주세요.", color = MaterialTheme.colorScheme.error)
                }
                if (state.protectedEntries > 0) item {
                    Text("보호 중인 자료 ${state.protectedEntries}개는 표시하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                }
                if (state.error.isNotBlank()) item { Text(state.error, color = MaterialTheme.colorScheme.error) }
                items(state.items, key = { it.id }) { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(item.displayName, style = MaterialTheme.typography.titleSmall)
                            Text("${item.kind.label} · ${safetySize(item.sizeBytes)}", style = MaterialTheme.typography.bodySmall)
                            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.modifiedAtMillis)),
                                style = MaterialTheme.typography.bodySmall)
                            if (item.kind == SafetyItemKind.ARCHIVE && !item.completionRecorded) {
                                Text("완료 기록이 없는 이전 사본 · 무결성 확인 전 삭제에 주의하세요.", style = MaterialTheme.typography.bodySmall)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (item.canExport) IconButton(onClick = { select("export", item) }, enabled = !state.loading && !state.mutationBlocked) {
                                    Icon(Icons.Outlined.Download, "Download로 사본 내보내기")
                                }
                                if (item.restorable) IconButton(onClick = { select("restore", item) }, enabled = !state.loading && !state.mutationBlocked && !serverRunning) {
                                    Icon(Icons.Outlined.Restore, "원래 위치로 복원")
                                }
                                IconButton(onClick = { select("delete", item) }, enabled = !state.loading && !state.mutationBlocked) {
                                    Icon(Icons.Outlined.DeleteOutline, "선택한 보관 항목 영구 삭제")
                                }
                            }
                            if (item.kind == SafetyItemKind.TRASH && !item.restorable) {
                                Text("현재 설치와 원래 위치가 달라 자동 복원할 수 없습니다.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (state.loading) item { CircularProgressIndicator() }
                if (!state.loading && state.items.isEmpty() && state.error.isEmpty()) item { Text("표시할 보관 항목이 없습니다.") }
                if (state.nextCursor != null) item { TextButton(onClick = onMore, enabled = !state.loading) { Text("더 보기") } }
                if (state.truncated) item { Text("목록 조사 한도에 도달했습니다. 모든 항목이 표시된 것은 아닙니다.", style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("닫기") } },
        dismissButton = { TextButton(onClick = onRefresh, enabled = !state.loading) { Text("새로고침") } },
    )
    state.items.firstOrNull { it.id == pendingId && it.revision == pendingRevision }?.let { selected ->
        AlertDialog(
            onDismissRequest = { pendingId = "" },
            title = { Text(when (pendingAction) { "delete" -> "영구 삭제할까요?"; "export" -> "사본을 내보낼까요?"; else -> "원래 위치로 복원할까요?" }) },
            text = { Text(selected.displayName + "\n\n" + when (pendingAction) {
                "delete" -> "선택한 보관 항목을 영구 삭제합니다. 이 삭제는 되돌릴 수 없습니다. 현재 SillyTavern 설치는 삭제하지 않습니다."
                "export" -> "Download에 암호화하지 않은 사본을 만듭니다. API 키·대화 등 개인정보가 포함될 수 있으므로 공유에 주의하세요. 원본은 유지합니다."
                else -> "휴지통 항목을 원래 위치로 되돌립니다. 같은 이름의 파일이나 폴더가 있으면 덮어쓰지 않고 중단합니다."
            }) },
            confirmButton = { TextButton(onClick = { val action = pendingAction; pendingId = ""; onAction(action, selected) },
                enabled = !state.loading && !state.mutationBlocked) { Text(when (pendingAction) { "delete" -> "영구 삭제"; "export" -> "내보내기"; else -> "복원" }) } },
            dismissButton = { TextButton(onClick = { pendingId = "" }) { Text("취소") } },
        )
    }
}

private fun safetySize(bytes: Long?): String = when {
    bytes == null -> "용량 미계산"
    bytes >= 1_073_741_824 -> String.format(Locale.KOREA, "%.1f GiB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> String.format(Locale.KOREA, "%.1f MiB", bytes / 1_048_576.0)
    bytes >= 1024 -> String.format(Locale.KOREA, "%.1f KiB", bytes / 1024.0)
    else -> "$bytes B"
}
