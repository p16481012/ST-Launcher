package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.model.*

@Composable
internal fun SelectiveRestoreDialog(
    state: SelectiveRestoreUiState,
    canRestore: Boolean,
    onMore: () -> Unit,
    onRefresh: () -> Unit,
    onRestore: (List<SelectiveRestoreChoice>, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val page = state.page
    var search by rememberSaveable(state.fileName) { mutableStateOf("") }
    var selected by rememberSaveable(state.fileName, page?.archiveRevision, page?.installationRevision) { mutableStateOf(emptyList<String>()) }
    var replacements by rememberSaveable(state.fileName, page?.archiveRevision, page?.installationRevision) { mutableStateOf(emptyList<String>()) }
    var confirming by rememberSaveable(state.fileName, page?.archiveRevision, page?.installationRevision) { mutableStateOf(false) }
    var backupBefore by rememberSaveable(state.fileName) { mutableStateOf(true) }
    val choices = page?.items.orEmpty().filter { it.relativePath in selected && it.profileExists }.map {
        SelectiveRestoreChoice(it, if (it.relativePath in replacements) SelectiveRestoreConflict.REPLACE else SelectiveRestoreConflict.SKIP)
    }
    val ready = canRestore && !state.loading && state.error.isBlank() && choices.isNotEmpty()
    val list = rememberLazyListState()
    LoadBackupPageAtEnd(list, page?.nextCursor, !confirming && !state.loading && state.error.isBlank() && (page?.items?.size ?: 0) < 1000, onMore)
    V2AlertDialog(
        onDismissRequest = onClose,
        scrollBody = false,
        title = { Text(if (confirming) "선택한 항목을 복원할까요?" else "캐릭터·채팅 선택 복원") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().testTag("restore-candidate-list"), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text("선택하지 않은 파일과 API 키는 변경하지 않습니다. 현재 설치에 존재하는 사용자 폴더의 항목만 복원합니다.", style = MaterialTheme.typography.bodySmall)
                }
                if (confirming) {
                    item { Text("${choices.size}개 선택 · 같은 이름 덮어쓰기 ${choices.count { it.item.targetExists && it.conflict == SelectiveRestoreConflict.REPLACE }}개") }
                    item { Text("채팅에 연결된 캐릭터가 현재 설치에 없다면 그 캐릭터 PNG도 함께 선택해야 합니다. 조건이 맞지 않으면 변경 전에 중단합니다.", style = MaterialTheme.typography.bodySmall) }
                    item { Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(backupBefore, { backupBefore = it })
                        Text("복원 전에 현재 설치 전체 백업", Modifier.weight(1f))
                    } }
                    item { Text(if (backupBefore) "보호 ZIP에는 비밀 설정이 포함될 수 있으며 암호화되지 않습니다. 전체 백업만큼 시간·공간이 더 필요합니다." else "보호 백업을 만들지 않습니다. 덮어쓰기 선택 항목의 현재 내용은 사라집니다.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                } else {
                    item { OutlinedTextField(search, { if (it.length <= 200) search = it }, label = { Text("불러온 목록에서 검색") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                    item { Text("${choices.size}/100개 선택 · ${page?.items?.size ?: 0}/${page?.candidateCount ?: 0}개 불러옴", style = MaterialTheme.typography.bodySmall) }
                    items(page?.items.orEmpty().filter { it.displayName.contains(search, true) || it.profile.contains(search, true) }, key = { it.relativePath }) { item ->
                        val checked = item.relativePath in selected
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked, { chosen ->
                                        selected = if (chosen) (selected + item.relativePath).distinct().take(100) else selected - item.relativePath
                                        if (!chosen) replacements = replacements - item.relativePath
                                    }, enabled = item.profileExists && (checked || selected.size < 100))
                                    Column(Modifier.weight(1f)) {
                                        Text(item.displayName)
                                        Text("${item.kind.label} · ${item.profile} · ${item.sizeBytes / 1024} KiB", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                if (!item.profileExists) Text("현재 설치에 이 사용자 폴더가 없어 선택할 수 없습니다.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                if (item.missingCharacter) Text("연결된 캐릭터 PNG도 함께 선택해야 합니다.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                if (item.targetExists && checked) Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(item.relativePath in replacements, { replace ->
                                        replacements = if (replace) (replacements + item.relativePath).distinct() else replacements - item.relativePath
                                    })
                                    Text("같은 이름의 현재 파일 덮어쓰기\n선택하지 않으면 건너뜁니다.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    if (page?.nextCursor != null && page.items.size < 1000)
                        item(key = BackupPageEnd) { Spacer(Modifier.height(1.dp)) }
                    if (page?.items?.size == 1000 && page.nextCursor != null) item { Text("한 번에 목록 1,000개까지 표시합니다.", style = MaterialTheme.typography.bodySmall) }
                }
                if (state.loading) item { V2BusyIndicator(Modifier.fillMaxWidth()) }
                if (state.error.isNotBlank()) item {
                    Text(state.error, color = MaterialTheme.colorScheme.error)
                    if (!state.loading) V2OutlineAction("목록 다시 확인", onRefresh)
                }
                if (!canRestore) item { Text("서버와 다른 작업을 종료한 뒤 복원할 수 있습니다.", color = MaterialTheme.colorScheme.error) }
                else if (confirming && !ready && !state.loading && state.error.isBlank())
                    item { SettingsCaption("현재 복원할 수 있는 선택 항목이 없어요. 이전 단계에서 다시 선택해 주세요.") }
            }
        },
        confirmButton = {
            if (ready) V2DialogAction(tone = if (confirming) V2DialogTone.Danger else V2DialogTone.Primary, onClick = { if (confirming) onRestore(choices, backupBefore) else confirming = true }) { Text(if (confirming) "검사 후 복원" else "선택 내용 확인") }
            else if (!confirming) V2DialogAction(onClick = onClose) { Text("확인") }
        },
        dismissButton = {
            val label = if (confirming) "이전" else "닫기"
            val leave: () -> Unit = { if (confirming) confirming = false else onClose() }
            if (ready || confirming) V2DialogAction(tone = V2DialogTone.Quiet, onClick = leave) { Text(label) }
        },
    )
}
