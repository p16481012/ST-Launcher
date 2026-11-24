package app.tavernbridge.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.model.*
import kotlinx.coroutines.CancellationException
import java.text.DateFormat
import java.util.Date

/** Reads Termux's receipts on every visit; the last-undo preference is not the trash catalog. */
@Composable
internal fun TavernTrashScreen(state: LauncherUiState, read: suspend (String) -> TavernTrashPage,
    restore: (String) -> Unit, onBack: () -> Unit) {
    var generation by remember { mutableIntStateOf(0) }
    var cursor by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf(emptyList<TavernTrashEntry>()) }
    var skipped by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    val readLatest by rememberUpdatedState(read)
    val lastRestore = state.lastOperationResult?.takeIf { it.operation == "restore-st-trash" && it.succeeded }?.completedAtMillis
    var seenRestore by remember { mutableStateOf(lastRestore) }
    LaunchedEffect(lastRestore, state.fileBrowserMutating) {
        if (!state.fileBrowserMutating && lastRestore != null && seenRestore != lastRestore) {
            seenRestore = lastRestore; cursor = ""; generation++
        }
    }
    LaunchedEffect(cursor, generation) {
        loading = true; error = ""
        if (cursor.isEmpty()) { entries = emptyList(); next = ""; skipped = 0 }
        try {
            val page = readLatest(cursor)
            entries = (entries + page.entries).distinctBy { it.id }
            next = page.next
            skipped += page.unavailable
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "휴지통 목록을 불러오지 못했어요." }
        finally { loading = false }
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "파일 목록으로 돌아가기") }
            Text("휴지통", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { cursor = ""; generation++ }, enabled = !loading && !state.fileBrowserMutating) {
                Icon(Icons.Outlined.Refresh, "휴지통 새로고침")
            }
        }
        Text("이 설치에서 옮긴 파일·폴더예요. 복구하면 원래 위치로 돌아가며, 같은 이름이 있으면 덮어쓰지 않아요.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.environment.processRunning || state.environment.operationActive)
            Text("복구하려면 먼저 서버와 진행 중인 작업을 종료해 주세요.", style = MaterialTheme.typography.bodySmall)
        if (state.fileBrowserError.isNotBlank()) Text(state.fileBrowserError, color = MaterialTheme.colorScheme.error)
        if (skipped > 0) Text("복구 정보를 읽을 수 없는 항목 ${skipped}개는 그대로 보관돼 있어요.", style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (error.isNotBlank()) item { Column {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { generation++ }) { Text("다시 시도") }
            } }
            if (!loading && error.isBlank() && entries.isEmpty() && next.isEmpty() && skipped == 0)
                item { Text("휴지통이 비어 있어요.", Modifier.padding(16.dp)) }
            items(entries, key = { it.id }) { entry ->
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.relativePath.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium)
                            Text("SillyTavern/${entry.relativePath}", style = MaterialTheme.typography.bodySmall)
                            val time = remember(entry.id) {
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.id.substringBefore('-').toLong()))
                            }
                            Text("${if (entry.isDirectory) "폴더" else "파일"} · $time", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { restore(entry.id) }, enabled = !loading && state.canModifyTavernFiles()) { Text("복구") }
                    }
                }
            }
            if (loading) item { if (rememberProgressVisible(true)) Text("휴지통을 불러오는 중…") }
            else if (error.isEmpty() && next.isNotEmpty()) item {
                OutlinedButton(onClick = { cursor = next }) { Text("더 보기") }
            }
        }
    }
}
