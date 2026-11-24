package app.tavernbridge.launcher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import app.tavernbridge.launcher.ui.theme.LocalV2ReducedMotion
import kotlinx.coroutines.delay
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.theme.V2Mono
import java.util.Locale
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
            Text("서버를 종료한 뒤 진행하세요. 검사 후 원본 유지 또는 이동을 선택합니다.", style = MaterialTheme.typography.bodySmall)
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
    onConfirm: (InstallationImportMode, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var moveSource by rememberSaveable(installation.sourcePath) { mutableStateOf(false) }
    var backupExisting by rememberSaveable(installation.sourcePath) { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (installation.sameInstallation) "현재 설치를 사용할까요?" else "이 설치를 가져올까요?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("원본: ${installation.sourcePath}", fontFamily = V2Mono)
                Text("버전: ${installation.version.ifBlank { "알 수 없음" }}")
                Text("파일 용량: ${fileBytes(installation.sizeBytes)}")
                Text("사용할 위치: ${installation.destinationPath}", fontFamily = V2Mono)
                if (installation.sameInstallation) {
                    Text("이미 런처가 사용하는 설치 폴더입니다. 이 폴더를 복사하거나 삭제하지 않습니다.")
                } else {
                    Text("다른 런처에서 실행한 서버도 먼저 종료해 주세요. 가져오기가 실패하거나 중단되면 원본을 삭제하지 않습니다.")
                    Row(Modifier.fillMaxWidth().clickable { moveSource = false }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !moveSource, onClick = { moveSource = false })
                        Text("복사해서 가져오기 · 원본 유지")
                    }
                    Row(Modifier.fillMaxWidth().clickable { moveSource = true }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = moveSource, onClick = { moveSource = true })
                        Text("옮겨오기 · 검증 성공 후 원본 삭제")
                    }
                    Text(if (moveSource) "완료 후 원본이 변경되지 않았는지 다시 확인하고 삭제합니다."
                        else "이후 런처는 새 위치의 설치를 사용합니다. 원본과 자동 동기화되지 않으며 두 폴더가 남습니다.",
                        style = MaterialTheme.typography.bodySmall)
                    if (destinationExists) {
                        Text("현재 ${installation.destinationPath}의 데이터와 설정은 가져온 설치로 교체됩니다.", color = MaterialTheme.colorScheme.error)
                        ReplacementBackupChoice(backupExisting) { backupExisting = it }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = {
            onConfirm(if (moveSource) InstallationImportMode.MOVE else InstallationImportMode.COPY,
                destinationExists && !installation.sameInstallation && backupExisting)
        }) { Text(when {
            installation.sameInstallation -> "현재 설치 사용"
            destinationExists && !backupExisting -> "백업 없이 교체"
            moveSource -> "검증 후 이동"
            else -> "검증 후 가져오기"
        }) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
internal fun ReplacementBackupChoice(checked: Boolean, label: String = "현재 설치를 먼저 ZIP으로 백업", onChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = onChange)
            Text(label, modifier = Modifier.weight(1f))
        }
        Text(if (checked) "Download에 전체 백업을 만듭니다. 데이터 크기만큼 시간·공간이 추가되며, 백업 실패 시 교체하지 않습니다. API 키 등 비밀 설정도 포함되며 암호화되지 않습니다."
            else "교체가 완료되면 기존 데이터의 임시 보호 사본은 삭제됩니다. 별도 백업이 없으면 이전 상태로 되돌릴 수 없습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = if (checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    onImport: () -> Unit,
    onImportFolder: () -> Unit,
    onClose: () -> Unit,
    onTrashMany: (List<TavernFileEntry>) -> Unit,
    onPreview: (String) -> Unit,
    preview: TavernFilePreview?,
    onClosePreview: () -> Unit,
    embedded: Boolean = false,
    sideBySide: Boolean = false,
) {
    var selected by remember(state.fileBrowserPath) { mutableStateOf<TavernFileEntry?>(null) }
    var renameTarget by remember { mutableStateOf<TavernFileEntry?>(null) }
    var trashTarget by remember { mutableStateOf<TavernFileEntry?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var newFile by remember { mutableStateOf(false) }
    var actionMenu by remember { mutableStateOf<FileActionMenu?>(null) }
    var externalFile by remember { mutableStateOf<TavernFileEntry?>(null) }
    var query by rememberSaveable(state.fileBrowserPath) { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(TavernFileSort.MODIFIED) }
    var sortSheet by remember { mutableStateOf(false) }
    var selectionMode by remember(state.fileBrowserPath) { mutableStateOf(false) }
    var selectedPaths by remember(state.fileBrowserPath) { mutableStateOf(emptySet<String>()) }
    var batchConfirmation by remember { mutableStateOf<List<TavernFileEntry>?>(null) }
    var moreMenu by remember { mutableStateOf(false) }
    BackHandler(embedded && !state.fileBrowserMutating && (selectionMode || state.fileBrowserPath.isNotEmpty())) {
        if (selectionMode) { selectionMode = false; selectedPaths = emptySet() }
        else onNavigate(state.fileBrowserPath.substringBeforeLast('/', ""))
    }
    val appliedQuery = debouncedListQuery(query)
    val visibleEntries = remember(state.fileBrowserEntries, appliedQuery, sort) { visibleTavernFiles(state.fileBrowserEntries, appliedQuery, sort) }
    val canModify = state.canModifyTavernFiles()
    val reduced = LocalV2ReducedMotion.current
    val haptics = LocalHapticFeedback.current
    val systemTouch = LocalViewConfiguration.current
    val rowTouch = remember(systemTouch) { object : ViewConfiguration by systemTouch {
        // Retain an explicitly extended Android accessibility long-press delay.
        override val longPressTimeoutMillis = if (systemTouch.longPressTimeoutMillis > 500L) systemTouch.longPressTimeoutMillis else 300L
    } }
    var highlighted by remember { mutableStateOf(emptySet<String>()) }
    val restoredResult = state.lastOperationResult?.takeIf { it.operation == "restore-st-trash" && it.succeeded }
    var seenRestore by rememberSaveable { mutableStateOf(if (state.fileBrowserMutating) null else restoredResult?.completedAtMillis) }
    LaunchedEffect(restoredResult?.completedAtMillis, state.fileBrowserMutating) {
        highlighted = emptySet()
        if (restoredResult != null && !state.fileBrowserMutating && restoredResult.completedAtMillis != seenRestore) {
            seenRestore = restoredResult.completedAtMillis
            highlighted = state.restoredFilePaths; delay(1000); highlighted = emptySet()
        }
    }
    fun selectEntry(entry: TavernFileEntry) {
        selected = entry
        onClosePreview()
        // Never display possible API keys just because a file is selected.
        val knownPreview = isTavernPng(entry) || entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in
            setOf("txt", "md", "json", "yaml", "yml", "css", "js", "ts", "ini", "toml", "csv", "log")
        if (knownPreview && !entry.isDirectory && !entry.sensitive && !state.fileBrowserLoading && !state.isWorking) onPreview(entry.relativePath)
    }
    LaunchedEffect(state.fileBrowserEntries, state.fileBrowserPath) {
        selectedPaths = selectedPaths.intersect(state.fileBrowserEntries.map { it.relativePath }.toSet())
    }
    fun toggleSelection(entry: TavernFileEntry) {
        if (!canModify) return
        if (entry.relativePath in selectedPaths) {
            selectedPaths = selectedPaths - entry.relativePath
            if (selectedPaths.isEmpty()) selectionMode = false
        }
        else if (selectedPaths.size < MAX_FILE_SELECTION && canSelectTavernFileForTrash(entry)) selectedPaths = selectedPaths + entry.relativePath
    }
    LaunchedEffect(canModify, state.fileBrowserPath) {
        actionMenu = null
        if (!canModify) { selectionMode = false; selectedPaths = emptySet() }
    }
    ToolPaneHost(embedded, onClose) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        when { selectionMode -> { selectionMode = false; selectedPaths = emptySet() }
                            state.fileBrowserPath.isEmpty() -> onClose()
                            else -> onNavigate(state.fileBrowserPath.substringBeforeLast('/', "")) }
                    }, enabled = !state.fileBrowserLoading && !state.fileBrowserMutating) {
                        Icon(if (selectionMode) Icons.Outlined.Close else Icons.Outlined.ArrowBack, "뒤로")
                    }
                    Text(if (selectionMode) "${selectedPaths.size}개 선택" else "파일", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (selectionMode) {
                        val visiblePaths = visibleEntries.filter(::canSelectTavernFileForTrash).map { it.relativePath }.take(MAX_FILE_SELECTION).toSet()
                        val allSelected = visiblePaths.isNotEmpty() && selectedPaths.containsAll(visiblePaths)
                        TextButton(onClick = {
                            selectedPaths = if (allSelected) selectedPaths - visiblePaths else (selectedPaths + visiblePaths).take(MAX_FILE_SELECTION).toSet()
                            if (selectedPaths.isEmpty()) selectionMode = false
                        },
                            enabled = canModify && visiblePaths.isNotEmpty()) { Text(if (allSelected) "선택 해제" else "모두 선택") }
                    } else {
                        Box {
                            IconButton(onClick = { moreMenu = true }) { Icon(Icons.Outlined.MoreVert, "파일 더보기") }
                            DropdownMenu(moreMenu, { moreMenu = false }) {
                                DropdownMenuItem(text = { Text("Android 파일 앱에서 열기") }, enabled = !state.fileBrowserMutating,
                                    onClick = { moreMenu = false; onOpenDirectory() })
                                DropdownMenuItem(text = { Text("폴더 새로고침") }, enabled = !state.fileBrowserLoading && !state.fileBrowserMutating,
                                    onClick = { moreMenu = false; onNavigate(state.fileBrowserPath) })
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val trail = rememberScrollState()
                    LaunchedEffect(state.fileBrowserPath, trail.maxValue) { trail.scrollTo(trail.maxValue) }
                    Row(Modifier.weight(1f).horizontalScroll(trail),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (state.fileBrowserPath.isEmpty()) Text("SillyTavern", Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) else
                        TextButton(onClick = { onNavigate("") }, enabled = !state.fileBrowserMutating,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary)) { Text("SillyTavern") }
                        var prefix = ""
                        state.fileBrowserPath.split('/').filter(String::isNotBlank).forEach { segment ->
                            prefix = listOf(prefix, segment).filter(String::isNotBlank).joinToString("/")
                            val target = prefix
                            Text("/")
                            if (target == state.fileBrowserPath) Text(segment, Modifier.padding(horizontal = 12.dp)) else
                            TextButton(onClick = { onNavigate(target) }, enabled = !state.fileBrowserMutating,
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary)) { Text(segment) }
                        }
                    }
                }
                if (!selectionMode) FileLogSearchField(query, { query = it }, "이 폴더에서 찾기",
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    TextButton({ sortSheet = true }) { Text(sort.label) }
                }
                val toolbar: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface)) {
                    Box(Modifier.weight(1f)) {
                        TextButton(onClick = { actionMenu = FileActionMenu.CREATE }, enabled = canModify, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp), shape = RoundedCornerShape(0.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Outlined.Add, null); Text("새로 만들기", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        DropdownMenu(expanded = actionMenu == FileActionMenu.CREATE && canModify,
                            onDismissRequest = { actionMenu = null }) {
                            DropdownMenuItem(text = { Text("새 파일") },
                                leadingIcon = { Icon(Icons.Outlined.InsertDriveFile, null) }, enabled = canModify,
                                onClick = { actionMenu = null; newFile = true })
                            DropdownMenuItem(text = { Text("새 폴더") },
                                leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) }, enabled = canModify,
                                onClick = { actionMenu = null; newFolder = true })
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        TextButton(onClick = { actionMenu = FileActionMenu.IMPORT }, enabled = canModify, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp), shape = RoundedCornerShape(0.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Outlined.FileDownload, null); Text("가져오기", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        DropdownMenu(expanded = actionMenu == FileActionMenu.IMPORT && canModify,
                            onDismissRequest = { actionMenu = null }) {
                            DropdownMenuItem(text = { Text("파일 가져오기") },
                                leadingIcon = { Icon(Icons.Outlined.InsertDriveFile, null) }, enabled = canModify,
                                onClick = { actionMenu = null; onImport() })
                            DropdownMenuItem(text = { Text("폴더 가져오기") },
                                leadingIcon = { Icon(Icons.Outlined.FolderOpen, null) }, enabled = canModify,
                                onClick = { actionMenu = null; onImportFolder() })
                        }
                    }
                    TextButton(onClick = { selectionMode = true; selected = null; onClosePreview() }, enabled = canModify && visibleEntries.any(::canSelectTavernFileForTrash),
                        modifier = Modifier.weight(1f).heightIn(min = 72.dp), shape = RoundedCornerShape(0.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Outlined.Checklist, null); Text("선택", style = MaterialTheme.typography.labelSmall) }
                    }
                }
                }
                if (state.environment.processRunning || state.environment.operationActive) {
                    Text("서버·작업 실행 중에는 탐색만 가능합니다.",
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
                if (state.fileBrowserError.isNotBlank() && !state.fileListReadFailed) {
                    Text(state.fileBrowserError, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                if (state.fileBrowserNotice.isNotBlank()) {
                    Text(state.fileBrowserNotice, Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall)
                }
                if (state.fileBrowserLoading) {
                    V2BusyIndicator(Modifier.fillMaxWidth())
                }
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(Modifier.weight(if (sideBySide && !selectionMode) 1.3f else 1f).fillMaxHeight(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        if (state.fileListReadFailed) item {
                            Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("폴더를 불러오지 못했어요", style = MaterialTheme.typography.titleMedium)
                                Text(state.fileBrowserError, style = MaterialTheme.typography.bodySmall)
                                OutlinedButton({ onNavigate(state.fileBrowserPath) }) { Text("다시 시도") }
                            }
                        } else if (visibleEntries.isEmpty() && !state.fileBrowserLoading) item {
                            Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(if (appliedQuery.isBlank()) Icons.Outlined.FolderOpen else Icons.Outlined.Search, null)
                                Text(if (appliedQuery.isBlank()) "빈 폴더예요" else "검색 결과가 없어요", style = MaterialTheme.typography.titleMedium)
                                Text(if (appliedQuery.isBlank()) "새로 만들거나 가져와서 채울 수 있어요." else "이 폴더 안에서만 찾아요. 상위 폴더로 이동해 다시 찾아보세요.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        items(visibleEntries, key = { it.relativePath }) { entry ->
                            Surface(Modifier.fillMaxWidth(), color =
                                if (entry.relativePath in highlighted || entry.relativePath in selectedPaths) MaterialTheme.colorScheme.secondaryContainer
                                else if (sideBySide && selected?.relativePath == entry.relativePath) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface) {
                                CompositionLocalProvider(LocalViewConfiguration provides rowTouch) {
                                val selectable = canModify && canSelectTavernFileForTrash(entry)
                                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).semantics { if (selectionMode) this.selected = entry.relativePath in selectedPaths }
                                    .combinedClickable(enabled = !state.fileBrowserLoading && !state.fileBrowserMutating && (!selectionMode || selectable),
                                    onLongClickLabel = "선택", hapticFeedbackEnabled = false, onLongClick = if (!selectable) null else ({
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        if (selectionMode) toggleSelection(entry) else {
                                            selected = null; onClosePreview(); selectedPaths = setOf(entry.relativePath); selectionMode = true
                                        }
                                    }), onClick = {
                                    if (selectionMode) toggleSelection(entry)
                                    else if (entry.isDirectory) { selected = null; onClosePreview(); onNavigate(entry.relativePath) } else selectEntry(entry)
                                }).padding(start = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.animation.AnimatedVisibility(selectionMode,
                                        enter = expandHorizontally(tween(if (reduced) 0 else 120)) + fadeIn(tween(if (reduced) 0 else 120)),
                                        exit = shrinkHorizontally(tween(if (reduced) 0 else 120)) + fadeOut(tween(if (reduced) 0 else 120))) {
                                        Checkbox(checked = entry.relativePath in selectedPaths, onCheckedChange = null, enabled = selectable)
                                    }
                                    if (!selectionMode) Icon(if (entry.isDirectory) Icons.Outlined.FolderOpen else Icons.Outlined.InsertDriveFile, null)
                                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                        Text(entry.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(listOf(if (entry.isDirectory) "폴더" else fileBytes(entry.sizeBytes), fileListDate(entry.modifiedAt)).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, fontFamily = V2Mono)
                                        if (entry.sensitive) Text("민감", color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.labelSmall)
                                    }
                                    if (!selectionMode) IconButton(onClick = { selectEntry(entry) }, enabled = !state.fileBrowserLoading && !state.fileBrowserMutating) {
                                        Icon(Icons.Outlined.MoreVert, "${entry.name} 작업")
                                    }
                                }
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                    if (sideBySide && !selectionMode) {
                        val active = selected?.let { candidate -> state.fileBrowserEntries.firstOrNull { it.relativePath == candidate.relativePath && it.revision == candidate.revision } }
                        Surface(Modifier.weight(1f).padding(start = 0.dp, top = 16.dp, end = 16.dp, bottom = 16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
                            Crossfade(active, animationSpec = tween(if (reduced) 0 else 120), label = "파일 상세 전환") { shown ->
                            val active = shown
                            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (active == null) Text("목록에서 파일을 선택하면 이곳에서 미리보기와 파일 작업을 확인할 수 있어요.",
                                    style = MaterialTheme.typography.bodyMedium)
                                else {
                                    Text(active.name, style = MaterialTheme.typography.titleMedium)
                                    Text(listOf(if (active.isDirectory) "폴더" else fileBytes(active.sizeBytes), fileListDate(active.modifiedAt)).filter { it.isNotBlank() }.joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall, fontFamily = V2Mono)
                                    if (active.sensitive) Text("민감한 정보가 포함될 수 있어요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                    if (!active.isDirectory) {
                                        val current = preview?.takeIf { it.relativePath == active.relativePath }
                                        if (current != null) InlineFilePreview(current)
                                        else if (state.pendingFilePreviewPath == active.relativePath) V2BusyIndicator(Modifier.fillMaxWidth()) else
                                            TextButton(onClick = { onPreview(active.relativePath) }, enabled = !state.fileBrowserLoading) { Text("내용 미리보기") }
                                        TextButton(onClick = { onEdit(active.relativePath) }, enabled = !state.fileBrowserLoading && active.sizeBytes <= 65536 && !isTavernPng(active)) { Text("텍스트 수정") }
                                    }
                                    TextButton(onClick = { renameTarget = active; selected = null; onClosePreview() }, enabled = canModify) { Text("이름 변경") }
                                    if (!active.isDirectory) TextButton(onClick = { externalFile = active }) { Text("다른 앱으로 열기") }
                                    TextButton(onClick = { trashTarget = active; selected = null; onClosePreview() }, enabled = canModify) { Text("휴지통으로 이동", color = MaterialTheme.colorScheme.error) }
                                }
                            }
                            }
                        }
                    }
                    }
                if (!state.fileBrowserMutating && !state.fileListReadFailed && WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) == 0) V2BottomActions {
                    if (!selectionMode) toolbar() else Button(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), onClick = {
                        val entries = selectedTavernFiles(state.fileBrowserEntries, selectedPaths)
                        if (entries.size == 1) { selectionMode = false; selectedPaths = emptySet(); onTrashMany(entries) }
                        else if (entries.isNotEmpty()) batchConfirmation = entries
                    },
                        enabled = canModify && selectedPaths.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Text("${selectedPaths.size}개 휴지통으로 이동")
                    }
                }
            }
        }
        selected?.takeUnless { sideBySide }?.let { entry ->
            val closeActions = { selected = null; onClosePreview() }
            V2BottomSheet(onClose = closeActions) {
                    Text(entry.name, style = MaterialTheme.typography.titleLarge)
                    Text(listOf(if (entry.isDirectory) "폴더" else fileBytes(entry.sizeBytes), fileListDate(entry.modifiedAt)).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, fontFamily = V2Mono)
                    if (entry.sensitive) Text("API 키 등 민감한 정보가 들어 있을 수 있어요.", color = MaterialTheme.colorScheme.error)
                    if (!entry.isDirectory) {
                        val current = preview?.takeIf { it.relativePath == entry.relativePath }
                        when { current != null -> InlineFilePreview(current)
                            state.pendingFilePreviewPath == entry.relativePath -> V2BusyIndicator(Modifier.fillMaxWidth())
                            else -> TextButton(onClick = { onPreview(entry.relativePath) }) { Text("내용 미리보기") }
                        }
                        if (state.fileBrowserError.isNotBlank()) Text(state.fileBrowserError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Button(onClick = { closeActions(); onEdit(entry.relativePath) }, modifier = Modifier.fillMaxWidth(),
                            enabled = !state.fileBrowserLoading && entry.sizeBytes <= 65_536 && !isTavernPng(entry)) { Text("텍스트 수정") }
                        OutlinedButton(onClick = { closeActions(); externalFile = entry }, modifier = Modifier.fillMaxWidth()) { Text("다른 앱으로 파일 열기") }
                        Text("텍스트 편집은 64 KiB 이하의 UTF-8 파일을 지원합니다.", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { closeActions(); renameTarget = entry }, enabled = canModify,
                        modifier = Modifier.fillMaxWidth()) { Text("이름 변경") }
                    OutlinedButton(onClick = { closeActions(); trashTarget = entry }, enabled = canModify,
                        modifier = Modifier.fillMaxWidth()) { Text("휴지통으로 이동", color = MaterialTheme.colorScheme.error) }
            }
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
        batchConfirmation?.let { entries ->
            AlertDialog(onDismissRequest = { batchConfirmation = null }, title = { Text("${entries.size}개 항목을 휴지통으로 옮길까요?") },
                text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("아래 항목만 순서대로 옮깁니다. 폴더 안의 내용도 포함됩니다. 실패하면 이미 옮긴 항목은 휴지통에 남으며 성공·실패 내역을 표시합니다.")
                    entries.forEach { Text("• ${it.name}${if (it.isDirectory) " (폴더)" else ""}") }
                } },
                confirmButton = { Button(enabled = canModify, onClick = {
                    batchConfirmation = null; selectionMode = false; selectedPaths = emptySet(); onTrashMany(entries)
                }) { Text("선택 항목 이동") } },
                dismissButton = { TextButton(onClick = { batchConfirmation = null }) { Text("취소") } })
        }
        if (sortSheet) FileSortSheet(sort, onSelect = { sort = it; sortSheet = false }, onClose = { sortSheet = false })
        state.editingFile?.let { file -> TavernTextEditor(file, state, onSave, onCloseEditor) }
    }
}

private enum class FileActionMenu { CREATE, IMPORT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileSortSheet(current: TavernFileSort, onSelect: (TavernFileSort) -> Unit, onClose: () -> Unit) {
    V2BottomSheet(onClose = onClose) {
            Text("현재 폴더 정렬", style = MaterialTheme.typography.titleLarge)
            Text("폴더를 먼저 표시합니다. 하위 폴더를 추가로 검사하지 않습니다.", style = MaterialTheme.typography.bodyMedium)
            TavernFileSort.entries.forEach { sort ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onSelect(sort) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = current == sort, onClick = { onSelect(sort) })
                    Text(sort.label)
                }
            }
            TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("닫기") }
    }
}

@Composable
private fun previewBitmap(preview: TavernFilePreview): ImageBitmap? {
    // The preview never enters saveable state and has no save path to the file editor.
    val bitmap by produceState<ImageBitmap?>(null, preview.pngBytes) {
        value = null
        value = withContext(Dispatchers.Default) {
            preview.pngBytes?.let { bytes -> runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                check(bounds.outWidth in 1..512 && bounds.outHeight in 1..512)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull() }
        }
    }
    return bitmap
}

@Composable
private fun InlineFilePreview(preview: TavernFilePreview) {
    val image = previewBitmap(preview)
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                preview.text != null -> SelectionContainer {
                    Text(preview.text, Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                        fontFamily = V2Mono, style = MaterialTheme.typography.bodySmall)
                }
                image != null -> Image(image, "${preview.relativePath} 미리보기", Modifier.fillMaxWidth().height(160.dp))
                else -> Text(preview.cardName ?: "이미지를 앱 안에서 표시할 수 없어요.", style = MaterialTheme.typography.bodySmall)
            }
            preview.cardDescription?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 5, overflow = TextOverflow.Ellipsis) }
            if (preview.truncated) Text("파일 앞부분만 표시했어요.", style = MaterialTheme.typography.labelSmall)
        }
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
    // Keep the draft in memory, but don't cover the global progress sheet while saving.
    if (state.fileBrowserMutating) return
    Dialog(onDismissRequest = { if (!state.fileBrowserMutating) close() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(8.dp),
            shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(file.relativePath, style = MaterialTheme.typography.titleMedium)
                Text("다른 곳에서 파일이 바뀌면 저장을 중단합니다. 수정 내용을 복사한 뒤 파일을 다시 열어 주세요.",
                    style = MaterialTheme.typography.bodySmall)
                if (state.fileBrowserError.isNotBlank()) {
                    Text(state.fileBrowserError, color = MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(value = content, onValueChange = { if (it.toByteArray(Charsets.UTF_8).size <= 65_536) content = it },
                    modifier = Modifier.fillMaxWidth().weight(1f), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = V2Mono),
                    readOnly = !state.canModifyTavernFiles())
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


private fun fileBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 * 1024 -> String.format(Locale.KOREA, "%.1f GiB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024 * 1024 -> String.format(Locale.KOREA, "%.1f MiB", bytes / (1024.0 * 1024))
    bytes >= 1024 -> String.format(Locale.KOREA, "%.1f KiB", bytes / 1024.0)
    else -> "$bytes B"
}
