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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun PreviousVersionDialog(
    state: PreviousVersionUiState,
    canChange: Boolean,
    onRefresh: () -> Unit,
    onChange: (PreviousVersion, VersionSnapshot?) -> Unit,
    onClose: () -> Unit,
) {
    val page = state.versions
    var selected by rememberSaveable(page?.currentCommit, page?.installationRevision) { mutableStateOf("") }
    var snapshotName by rememberSaveable(selected) { mutableStateOf("") }
    var restoreData by rememberSaveable(selected) { mutableStateOf(false) }
    var confirming by rememberSaveable(selected) { mutableStateOf(false) }
    var acknowledged by rememberSaveable(selected, restoreData, snapshotName) { mutableStateOf(false) }
    val target = page?.versions?.find { it.commit == selected }
    val snapshots = state.snapshots?.snapshots.orEmpty().filter { it.commit == selected }
    val snapshot = snapshots.find { it.filename == snapshotName }
    val ready = canChange && !state.loading && state.error.isBlank() && target != null && (!restoreData || snapshot != null)
    V2AlertDialog(onDismissRequest = onClose, scrollBody = false,
        title = { Text(if (confirming) "이전 버전으로 변경할까요?" else "이전 SillyTavern 버전") },
        text = { LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (confirming) {
                item { Text("${page?.currentCommit?.take(7)} → ${target?.commit?.take(7)}\n${target?.subject.orEmpty()}") }
                item { Text("현재 설치 전체를 비밀 설정·API 키가 포함된 ZIP으로 먼저 백업하고 Download에 보관합니다. 암호화되지 않은 백업이므로 외부 공유에 주의하세요.", color = MaterialTheme.colorScheme.error) }
                item { Text("전체 백업과 임시 설치 준비에 시간·저장 공간이 필요합니다. 보호 백업이나 검사에 실패하면 교체하지 않습니다. 프로그램의 직접 수정 내용은 선택한 버전으로 바뀔 수 있습니다.", style = MaterialTheme.typography.bodySmall) }
                item { Text("변경 중에는 다른 앱에서 설치 폴더를 수정하지 마세요.", style = MaterialTheme.typography.bodySmall) }
                item { Text(if (restoreData) "선택한 백업 시점의 데이터도 복원합니다. 이후 추가·변경한 채팅과 설정은 되돌아가며, 변경 직전 내용은 새 전체 ZIP에 남습니다.\n${snapshot?.filename.orEmpty()}"
                    else "현재 채팅·캐릭터·config.yaml을 유지합니다. 다만 최신 데이터 형식이 오래된 프로그램과 완전히 호환된다고 보장할 수 없습니다.", color = MaterialTheme.colorScheme.error) }
                item { Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(acknowledged, { acknowledged = it }); Text("백업·교체 범위와 주의사항을 확인했습니다.", Modifier.weight(1f))
                } }
            } else {
                item { Text("현재 브랜치의 기기에 남아 있는 이전 커밋 최대 30개입니다. 출시 버전 목록이나 원격 태그를 새로 내려받지는 않습니다.", style = MaterialTheme.typography.bodySmall) }
                if (page != null && page.versions.isEmpty()) item { Text("선택 가능한 이전 커밋이 없습니다.") }
                items(page?.versions.orEmpty(), key = { it.commit }) { version ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected == version.commit, onClick = { selected = version.commit })
                        Column(Modifier.weight(1f)) {
                            Text(version.subject)
                            Text("${version.commit.take(7)} · ${formatVersionTime(version.committedAtSeconds * 1000)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (target != null) {
                    item { HorizontalDivider() }
                    item { Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(restoreData, { restoreData = it }, enabled = snapshots.isNotEmpty())
                        Text("선택 버전의 백업 데이터까지 되돌리기", Modifier.weight(1f))
                    } }
                    if (snapshots.isEmpty()) item { Text("정확한 커밋을 확인할 수 있는 전체 백업이 없어 데이터 롤백은 선택할 수 없습니다. 이 기능 추가 전 백업은 기존 전체 복원에서 사용할 수 있습니다.", style = MaterialTheme.typography.bodySmall) }
                    if (restoreData) items(snapshots, key = { it.filename }) { saved ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(snapshotName == saved.filename, onClick = { snapshotName = saved.filename })
                            Column(Modifier.weight(1f)) {
                                Text(formatVersionTime(saved.createdAtMillis))
                                Text("${saved.filename}\n${saved.sizeBytes / 1048576} MiB", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (state.snapshots?.truncated == true) item { Text("검증 가능한 백업 중 최대 50개까지만 표시합니다.", style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (state.loading) item { V2BusyIndicator(Modifier.fillMaxWidth()) }
            if (state.error.isNotBlank()) item {
                Text(state.error, color = MaterialTheme.colorScheme.error)
                if (!state.loading) V2OutlineAction("다시 확인", onRefresh)
            }
            if (!canChange) item { Text("서버와 다른 작업을 종료한 뒤 변경할 수 있습니다.", color = MaterialTheme.colorScheme.error) }
            else if (!state.loading && state.error.isBlank() && page?.versions?.isNotEmpty() == true && !ready) item {
                SettingsCaption(if (target == null) "먼저 변경할 버전을 선택해 주세요." else "되돌릴 백업을 선택해 주세요.")
            }
        } },
        confirmButton = { if (ready && (!confirming || acknowledged)) V2DialogAction(tone = if (confirming) V2DialogTone.Danger else V2DialogTone.Primary, onClick = {
            if (confirming) target?.let { onChange(it, if (restoreData) snapshot else null) } else confirming = true
        }) { Text(if (confirming) "전체 백업 후 변경" else "변경 내용 확인") } },
        dismissButton = {
            val label = if (confirming) "이전" else "닫기"
            val leave: () -> Unit = { if (confirming) confirming = false else onClose() }
            if (ready && (!confirming || acknowledged)) V2DialogAction(tone = V2DialogTone.Quiet, onClick = leave) { Text(label) }
            else V2OutlineAction(label, leave)
        },
    )
}

private fun formatVersionTime(millis: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date(millis))
