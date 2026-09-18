package app.tavernbridge.launcher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.components.WorkProgressLog
import java.util.Locale

@Composable
internal fun ExistingInstallationImportCard(
    enabled: Boolean,
    onPickFolder: () -> Unit,
    onInspectPath: (String) -> Unit,
) {
    var pathPrompt by remember { mutableStateOf(false) }
    var path by remember { mutableStateOf("~/SillyTavern") }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("기존 설치 가져오기", style = MaterialTheme.typography.titleLarge)
            Text("내부 저장소 또는 Termux에 있는 SillyTavern 설치 폴더를 가져옵니다. 프로그램을 다시 내려받지 않고, 실행 패키지만 이 환경에 맞게 구성합니다.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onPickFolder, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.FolderOpen, null)
                Spacer(Modifier.width(8.dp))
                Text("내부 저장소 폴더 선택")
            }
            OutlinedButton(onClick = { pathPrompt = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Terminal, null)
                Spacer(Modifier.width(8.dp))
                Text("Termux 폴더 경로 입력")
            }
            Text("서버를 종료한 뒤 진행하세요. 검사 후 이동할 내용을 확인합니다.", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (pathPrompt) AlertDialog(
        onDismissRequest = { pathPrompt = false },
        title = { Text("기존 설치 폴더 경로") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("package.json과 server.js가 들어 있는 SillyTavern 폴더를 입력하세요. 이 단계에서는 원본을 변경하지 않습니다.")
                OutlinedTextField(value = path, onValueChange = { path = it }, singleLine = true,
                    label = { Text("Termux에서 접근 가능한 경로") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { Button(enabled = path.isNotBlank(), onClick = {
            pathPrompt = false
            onInspectPath(path)
        }) { Text("검사") } },
        dismissButton = { TextButton(onClick = { pathPrompt = false }) { Text("취소") } },
    )
}

@Composable
internal fun InstallationImportConfirmation(
    installation: ExistingInstallation,
    destinationExists: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (installation.sameInstallation) "현재 설치를 사용할까요?" else "이 설치를 이동할까요?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("원본: ${installation.sourcePath}", fontFamily = FontFamily.Monospace)
                Text("버전: ${installation.version.ifBlank { "알 수 없음" }}")
                Text("파일 용량: ${fileBytes(installation.sizeBytes)}")
                Text("사용할 위치: ${installation.destinationPath}", fontFamily = FontFamily.Monospace)
                Text(if (installation.sameInstallation) "이미 런처가 사용하는 설치 폴더입니다. 이 폴더를 복사하거나 삭제하지 않습니다."
                    else "다른 런처에서 실행한 서버도 먼저 종료해 주세요. 원본을 임시 복사하고 구조·용량·실행 패키지를 검사합니다. 이동이 성공한 뒤에만 선택한 원본 폴더를 삭제합니다. 실패하면 원본을 유지합니다.")
                if (!installation.sameInstallation) Text(
                    if (destinationExists) "현재 ~/SillyTavern의 데이터와 설정은 가져온 설치로 교체됩니다. 교체 전 현재 설치를 안전 보관합니다."
                    else "대상 위치에 기존 폴더가 남아 있다면 먼저 안전 보관하고 가져온 설치로 교체합니다.",
                    color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text(if (installation.sameInstallation) "현재 설치 사용" else "검증 후 이동") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
internal fun TavernFileManagerDialog(
    state: LauncherUiState,
    onNavigate: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenDirectory: () -> Unit,
    onEdit: (String) -> Unit,
    onSave: (String) -> Unit,
    onCloseEditor: () -> Unit,
    onCreateFolder: (String) -> Unit,
    onCreateFile: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onTrash: (String) -> Unit,
    onUndoTrash: () -> Unit,
    onImport: () -> Unit,
    onClose: () -> Unit,
) {
    var selected by remember { mutableStateOf<TavernFileEntry?>(null) }
    var renameTarget by remember { mutableStateOf<TavernFileEntry?>(null) }
    var trashTarget by remember { mutableStateOf<TavernFileEntry?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var newFile by remember { mutableStateOf(false) }
    var externalFile by remember { mutableStateOf<TavernFileEntry?>(null) }
    val canModify = state.canModifyTavernFiles()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(10.dp),
            shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onNavigate(state.fileBrowserPath.substringBeforeLast('/', "")) },
                        enabled = state.fileBrowserPath.isNotEmpty() && !state.fileBrowserLoading && !state.fileBrowserMutating) {
                        Icon(Icons.Outlined.ArrowBack, "상위 폴더")
                    }
                    Text("SillyTavern 폴더", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = { onNavigate(state.fileBrowserPath) }, enabled = !state.fileBrowserLoading && !state.fileBrowserMutating) {
                        Icon(Icons.Outlined.Refresh, "폴더 새로고침")
                    }
                    IconButton(onClick = onClose, enabled = !state.fileBrowserMutating) { Icon(Icons.Outlined.Close, "닫기") }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onNavigate("") }, enabled = !state.fileBrowserMutating) { Text("SillyTavern") }
                    var prefix = ""
                    state.fileBrowserPath.split('/').filter(String::isNotBlank).forEach { segment ->
                        prefix = listOf(prefix, segment).filter(String::isNotBlank).joinToString("/")
                        val target = prefix
                        Text("/")
                        TextButton(onClick = { onNavigate(target) }, enabled = !state.fileBrowserMutating) { Text(segment) }
                    }
                }
                FilledTonalButton(onClick = onOpenDirectory, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    enabled = !state.fileBrowserMutating) {
                    Icon(Icons.Outlined.FolderOpen, null)
                    Spacer(Modifier.width(8.dp))
                    Text("외부에서 폴더 열기")
                }
                Text("권장 파일 앱의 Termux 위치를 엽니다.\nSillyTavern 폴더를 선택하세요.",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { newFolder = true }, enabled = canModify, modifier = Modifier.weight(1f)) { Text("새 폴더") }
                    OutlinedButton(onClick = { newFile = true }, enabled = canModify, modifier = Modifier.weight(1f)) { Text("새 파일") }
                }
                OutlinedButton(onClick = onImport, enabled = canModify,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Text("파일 가져오기")
                }
                if (state.environment.processRunning || state.environment.operationActive) Text(
                    "서버 또는 작업 실행 중에는 탐색만 가능합니다. 변경하려면 먼저 종료하세요.",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                if (state.fileBrowserError.isNotBlank()) Text(state.fileBrowserError,
                    Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (state.fileBrowserNotice.isNotBlank()) Text(state.fileBrowserNotice,
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                if (state.lastTrashedEntryId.isNotBlank()) TextButton(onClick = onUndoTrash, enabled = canModify,
                    modifier = Modifier.padding(horizontal = 8.dp)) { Text("방금 휴지통으로 옮긴 항목 복구") }
                if (state.fileBrowserMutating) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        FileOperationProgress(state.workProgress, state.workingLabel)
                    }
                } else {
                    if (state.fileBrowserLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.fileBrowserEntries.isEmpty() && !state.fileBrowserLoading) item { Text("표시할 항목이 없습니다.") }
                        items(state.fileBrowserEntries, key = { it.relativePath }) { entry ->
                            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                                Row(Modifier.fillMaxWidth().clickable(enabled = !state.fileBrowserLoading && !state.fileBrowserMutating) {
                                    if (entry.isDirectory) onNavigate(entry.relativePath) else selected = entry
                                }.padding(start = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (entry.isDirectory) Icons.Outlined.FolderOpen else Icons.Outlined.InsertDriveFile, null)
                                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                        Text(entry.name, fontWeight = FontWeight.SemiBold)
                                        Text(if (entry.isDirectory) "폴더" else fileBytes(entry.sizeBytes), style = MaterialTheme.typography.bodySmall)
                                        if (entry.sensitive) Text("민감한 정보 포함 가능", color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.labelSmall)
                                    }
                                    IconButton(onClick = { selected = entry }, enabled = !state.fileBrowserMutating) {
                                        Icon(Icons.Outlined.MoreVert, "${entry.name} 작업")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        selected?.let { entry ->
            AlertDialog(onDismissRequest = { selected = null }, title = { Text(entry.name) }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!entry.isDirectory) {
                        Button(onClick = { selected = null; onEdit(entry.relativePath) }, modifier = Modifier.fillMaxWidth(),
                            enabled = !state.fileBrowserLoading) { Text("텍스트 확인·수정") }
                        OutlinedButton(onClick = { selected = null; externalFile = entry }, modifier = Modifier.fillMaxWidth()) { Text("다른 앱으로 파일 열기") }
                        Text("텍스트 편집은 64 KiB 이하의 UTF-8 파일을 지원합니다.", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { selected = null; renameTarget = entry }, enabled = canModify,
                        modifier = Modifier.fillMaxWidth()) { Text("이름 변경") }
                    OutlinedButton(onClick = { selected = null; trashTarget = entry }, enabled = canModify,
                        modifier = Modifier.fillMaxWidth()) { Text("휴지통으로 이동") }
                }
            }, confirmButton = { TextButton(onClick = { selected = null }) { Text("닫기") } })
        }
        externalFile?.let { entry ->
            AlertDialog(onDismissRequest = { externalFile = null }, title = { Text("다른 앱으로 열까요?") },
                text = { Text("${entry.name} 파일에 읽기 권한을 부여합니다. API 키나 인증 정보가 포함될 수 있으므로 신뢰하는 앱을 선택하세요.") },
                confirmButton = { Button(onClick = { externalFile = null; onOpenFile(entry.relativePath) }) { Text("앱 선택") } },
                dismissButton = { TextButton(onClick = { externalFile = null }) { Text("취소") } })
        }
        if (newFolder) EntryNameDialog("새 폴더", "", onDismiss = { newFolder = false }) {
            newFolder = false; onCreateFolder(it)
        }
        if (newFile) EntryNameDialog("새 파일", "", onDismiss = { newFile = false }, nameHint = "예: memo.txt") {
            newFile = false; onCreateFile(it)
        }
        renameTarget?.let { entry -> EntryNameDialog("이름 변경", entry.name, onDismiss = { renameTarget = null }) {
            renameTarget = null; onRename(entry.relativePath, it)
        } }
        trashTarget?.let { entry ->
            AlertDialog(onDismissRequest = { trashTarget = null }, title = { Text("휴지통으로 옮길까요?") },
                text = { Text("${entry.name}${if (entry.isDirectory) " 폴더와 내부의 모든 항목을" else " 파일을"} 설치 폴더에서 분리합니다. 영구 삭제하지 않고 Termux의 런처 휴지통에 보관하며 복구할 수 있습니다.") },
                confirmButton = { Button(enabled = canModify, onClick = { trashTarget = null; onTrash(entry.relativePath) }) { Text("휴지통으로 이동") } },
                dismissButton = { TextButton(onClick = { trashTarget = null }) { Text("취소") } })
        }
        state.editingFile?.let { file -> TavernTextEditor(file, state, onSave, onCloseEditor) }
    }
}

@Composable
private fun EntryNameDialog(title: String, initial: String, onDismiss: () -> Unit, nameHint: String? = null,
    onConfirm: (String) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("이름") },
            placeholder = { nameHint?.let { Text(it) } })
    }, confirmButton = { Button(onClick = { onConfirm(name) }, enabled = validTavernEntryName(name)) { Text("확인") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } })
}

@Composable
private fun TavernTextEditor(file: TavernTextFile, state: LauncherUiState, onSave: (String) -> Unit, onClose: () -> Unit) {
    // Deliberately not saveable: file contents may include credentials and must not enter saved state.
    var content by remember(file.relativePath, file.revision) { mutableStateOf(file.content) }
    var discard by remember { mutableStateOf(false) }
    val close = { if (content != file.content) discard = true else onClose() }
    Dialog(onDismissRequest = { if (!state.fileBrowserMutating) close() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(8.dp),
            shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(file.relativePath, style = MaterialTheme.typography.titleMedium)
                Text("다른 곳에서 파일이 바뀌면 저장을 중단합니다. 수정 내용을 복사한 뒤 파일을 다시 열어 주세요.",
                    style = MaterialTheme.typography.bodySmall)
                if (state.fileBrowserError.isNotBlank()) Text(state.fileBrowserError, color = MaterialTheme.colorScheme.error)
                if (state.fileBrowserMutating) {
                    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                        FileOperationProgress(state.workProgress, state.workingLabel)
                    }
                } else {
                    OutlinedTextField(value = content, onValueChange = { if (it.toByteArray(Charsets.UTF_8).size <= 65_536) content = it },
                        modifier = Modifier.fillMaxWidth().weight(1f), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        readOnly = !state.canModifyTavernFiles())
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { close() }, enabled = !state.fileBrowserMutating) { Text("닫기") }
                    Button(onClick = { onSave(content) }, enabled = state.canModifyTavernFiles() && content != file.content) { Text("저장") }
                }
            }
        }
        if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("수정을 취소할까요?") },
            text = { Text("저장하지 않은 변경 내용은 사라집니다.") },
            confirmButton = { TextButton(onClick = { discard = false; onClose() }) { Text("변경 버리기") } },
            dismissButton = { TextButton(onClick = { discard = false }) { Text("계속 수정") } })
    }
}

@Composable
private fun FileOperationProgress(progress: WorkProgress?, label: String) {
    val measuredPercent = progress?.measuredPercent
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                progress?.phase?.ifBlank { label } ?: label.ifBlank { "파일 작업 중" },
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            if (measuredPercent != null) Text("${measuredPercent}%", fontWeight = FontWeight.Bold)
        }
        if (measuredPercent == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { measuredPercent / 100f }, modifier = Modifier.fillMaxWidth())
        }
        WorkProgressLog(progress?.logText.orEmpty())
    }
}

private fun fileBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 * 1024 -> String.format(Locale.KOREA, "%.1f GiB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024 * 1024 -> String.format(Locale.KOREA, "%.1f MiB", bytes / (1024.0 * 1024))
    bytes >= 1024 -> String.format(Locale.KOREA, "%.1f KiB", bytes / 1024.0)
    else -> "$bytes B"
}
