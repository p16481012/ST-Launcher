package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.model.*
import java.util.Locale

private fun storageSize(bytes: Long): String = when {
    bytes >= 1L.shl(30) -> String.format(Locale.ROOT, "%.2f GiB", bytes / 1073741824.0)
    bytes >= 1L.shl(20) -> String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0)
    bytes >= 1024 -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
internal fun StorageManagementCard(state: LauncherUiState, onScan: () -> Unit, onPreview: (Int, Int) -> Unit, initiallyExpanded: Boolean = false) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    var keep by rememberSaveable { mutableStateOf("3") }
    var days by rememberSaveable { mutableStateOf("30") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) { Text("저장 공간·백업 정리 ${if (expanded) "접기" else "보기"}") }
            if (expanded) {
                Text("직접 확인할 때만 검사합니다. 백업을 자동으로 삭제하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                state.storageUsage?.let { report ->
                    Text("확인한 파일 크기: ${storageSize(report.totalBytes)}")
                    Text("파일의 논리적 크기 합계이며 실제 디스크 점유량과 다를 수 있습니다.", style = MaterialTheme.typography.bodySmall)
                    report.entries.forEach { row ->
                        AdaptiveInfoRow(row.category.label, "${storageSize(row.sizeBytes)}${if (row.complete) "" else " 이상 · 일부 미확인"}")
                    }
                    if (!report.complete) Text("시간·항목 수·접근 권한 제한으로 일부 항목을 확인하지 못했습니다.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = onScan, enabled = !state.isWorking, modifier = Modifier.fillMaxWidth()) { Text("저장 공간 확인") }
                HorizontalDivider()
                Text("오래된 백업 정리", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(keep, { if (it.length <= 4 && it.all(Char::isDigit)) keep = it }, label = { Text("최근 보관 개수 · 고정 백업 제외") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(days, { if (it.length <= 5 && it.all(Char::isDigit)) days = it }, label = { Text("이 날짜 수보다 오래된 백업") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("고정한 백업·날짜가 불명확한 백업은 보호합니다. 다음 화면에서 직접 고른 항목만 삭제합니다.", style = MaterialTheme.typography.bodySmall)
                val keepValue = keep.toIntOrNull(); val daysValue = days.toIntOrNull()
                OutlinedButton(onClick = { onPreview(requireNotNull(keepValue), requireNotNull(daysValue)) },
                    enabled = !state.isWorking && !state.backupCleanupLoading && keepValue != null && keepValue in 1..1000 && daysValue != null && daysValue in 1..36500,
                    modifier = Modifier.fillMaxWidth()) { Text(if (state.backupCleanupLoading) "미리보기 준비 중" else "정리 후보 미리보기") }
            }
        }
    }
}

@Composable
internal fun BackupAnnotationControl(archive: BackupArchive, annotation: BackupAnnotation, onSave: (Boolean, String) -> Unit) {
    var open by rememberSaveable(archive.fileName) { mutableStateOf(false) }
    var pinned by rememberSaveable(archive.fileName, annotation.pinned) { mutableStateOf(annotation.pinned) }
    var memo by rememberSaveable(archive.fileName, annotation.memo) { mutableStateOf(annotation.memo) }
    TextButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text(if (annotation.pinned) "고정됨 · 메모 편집" else if (annotation.memo.isNotBlank()) "메모 편집" else "고정·메모")
    }
    if (annotation.memo.isNotBlank()) Text(annotation.memo, style = MaterialTheme.typography.bodySmall)
    if (open) V2AlertDialog(onDismissRequest = { open = false }, title = { Text("백업 고정·메모") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(archive.createdAt.ifBlank { archive.fileName })
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(pinned, { pinned = it }); Text("정리·선택 삭제에서 보호") }
            if (!annotation.trusted) Text("기존 보호 설정을 읽지 못해 고정 상태로 보호했습니다. 직접 저장하면 다시 설정됩니다.")
            OutlinedTextField(memo, { memo = it.take(500) }, label = { Text("메모 · 최대 500자") }, modifier = Modifier.fillMaxWidth())
            Text("메모는 이 런처 안에만 저장됩니다. 비밀번호·API 키는 적지 마세요.", style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { V2DialogAction(onClick = { onSave(pinned, memo); open = false }) { Text("저장") } },
        dismissButton = { V2DialogAction(tone = V2DialogTone.Quiet, onClick = { open = false }) { Text("취소") } })
}

@Composable
internal fun BackupCleanupDialog(preview: BackupRetentionPreview, onClose: () -> Unit, onDelete: (Set<String>) -> Unit) {
    var selected by rememberSaveable(preview) { mutableStateOf(emptyList<String>()) }
    var confirm by rememberSaveable(preview) { mutableStateOf(false) }
    V2AlertDialog(onDismissRequest = onClose, title = { Text("백업 정리 미리보기") }, scrollBody = false,
        text = { LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("후보 ${preview.candidates.size}개 · 최대 ${storageSize(preview.reclaimableBytes)}\n보호 ${preview.protectedCount}개 · 날짜 미확인 ${preview.unknownDateCount}개")
                Text("기본 선택은 없습니다. 최대 200개를 직접 선택하세요. 백업만 삭제하며 설치 데이터는 변경하지 않습니다.", style = MaterialTheme.typography.bodySmall)
            }
            items(preview.candidates, key = { it.fileName }) { backup ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(backup.fileName in selected, { checked -> selected = if (checked) (selected + backup.fileName).distinct() else selected - backup.fileName },
                        enabled = backup.fileName in selected || selected.size < 200)
                    Column(Modifier.weight(1f)) {
                        Text(backup.createdAt); Text("${storageSize(backup.sizeBytes)} · ${backup.categories.joinToString { it.label }}", style = MaterialTheme.typography.bodySmall)
                        Text(backup.fileName, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } }, confirmButton = { V2DialogAction(onClick = { confirm = true }, enabled = selected.isNotEmpty()) { Text("선택한 ${selected.size}개 삭제") } },
        dismissButton = { V2DialogAction(tone = V2DialogTone.Quiet, onClick = onClose) { Text("닫기") } })
    if (confirm) V2AlertDialog(onDismissRequest = { confirm = false }, title = { Text("선택한 백업을 영구 삭제할까요?") },
        text = { Text("${selected.size}개 ZIP은 복구할 수 없습니다. 삭제 직전에 고정 상태와 파일 변경 여부를 다시 확인합니다.") },
        confirmButton = { V2DialogAction(tone = V2DialogTone.Danger, onClick = { confirm = false; onDelete(selected.toSet()) }) { Text("영구 삭제") } },
        dismissButton = { V2DialogAction(tone = V2DialogTone.Quiet, onClick = { confirm = false }) { Text("취소") } })
}
