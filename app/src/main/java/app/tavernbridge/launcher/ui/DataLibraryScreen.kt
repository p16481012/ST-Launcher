package app.tavernbridge.launcher.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.data.*
import app.tavernbridge.launcher.R
import app.tavernbridge.launcher.model.LauncherUiState
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** Only the profile list is read on hub entry. Each deeper scan has its own deliberate entry point. */
@Composable
internal fun DataLibraryScreen(state: LauncherUiState, library: DataLibraryAccess,
    submit: (LibraryWrite) -> Unit, onBack: () -> Unit) {
    var page by rememberSaveable { mutableStateOf<LibraryKind?>(null) }
    var user by rememberSaveable { mutableStateOf("") }
    var users by remember { mutableStateOf(emptyList<String>()) }
    var showUsers by remember { mutableStateOf(false) }
    var userError by remember { mutableStateOf("") }
    var usersRequest by remember { mutableIntStateOf(0) }
    var userLoading by remember { mutableStateOf(true) }
    val totals = remember(user) { mutableStateMapOf<LibraryKind,String>() }
    val recent by library.lastMoved.collectAsState()
    val requested by library.pendingUndo.collectAsState()
    var restoreQuick by remember(user) { mutableStateOf<LibraryGroup?>(null) }
    LaunchedEffect(library,usersRequest) {
        userError = ""; userLoading = true
        try {
            users = library.users()
            if (user !in users) user = users.firstOrNull { it == "default-user" } ?: users.firstOrNull().orEmpty()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { userError = e.message ?: "사용자를 확인하지 못했어요." }
        finally { userLoading = false }
    }
    LaunchedEffect(requested, users, userLoading) {
        val receipt = requested ?: return@LaunchedEffect
        if (userLoading) return@LaunchedEffect
        if (receipt.first in users && receipt == library.lastMoved.value) {
            user = receipt.first; page = null; restoreQuick = receipt.second
        }
        library.pendingUndo.compareAndSet(receipt,null)
    }
    V2BackHandler(page == null && !showUsers && restoreQuick == null, onBack)
    val destination = page
    if (destination == null) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LibraryTop("데이터 정리", onBack)
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Lead("실리태번 자료", "캐릭터·배경·로어북·프리셋을 가져오거나 정리해요.")
                if (userError.isNotEmpty()) {
                    Text(userError, color = MaterialTheme.colorScheme.error)
                    V2OutlineAction("사용자 다시 확인", { usersRequest++ })
                } else if (userLoading) {
                    if (rememberProgressVisible(true)) SettingsCaption("실리태번 사용자를 확인하고 있어요.")
                } else if (user.isEmpty()) SettingsCaption("자료 폴더가 있는 사용자가 없어요. 실리태번을 한 번 열어 주세요.")
                else {
                    if (users.size > 1) SettingsGroup { SettingsRow("사용자", user, onClick = { showUsers = true }) }
                    else SettingsCaption("사용자 · $user")
                    ToolGroup("자료") {
                        listOf(LibraryKind.CARDS,LibraryKind.BACKGROUNDS,LibraryKind.WORLDS,LibraryKind.PRESETS).forEachIndexed { i, k ->
                            if (i != 0) ToolDivider()
                            ToolRow(k.label, if (k == LibraryKind.CARDS) "카드와 채팅을 합쳐 큰 순서로 봐요" else "",
                                totals[k].orEmpty(), icon = iconFor(k), onClick = { page = k })
                        }
                    }
                    ToolGroup("정리 도구") {
                        ToolRow("카드 없이 남은 채팅", "카드가 없어도 소중한 대화일 수 있어요", icon = iconFor(LibraryKind.ORPHANS)) { page = LibraryKind.ORPHANS }
                        ToolDivider()
                        ToolRow("중복 카드 찾기", "요청할 때만 그림까지 비교해요", icon = iconFor(LibraryKind.DUPLICATES)) { page = LibraryKind.DUPLICATES }
                        ToolDivider()
                        ToolRow("휴지통에서 되돌리기", "데이터 정리에서 옮긴 기록", icon = iconFor(LibraryKind.HISTORY)) { page = LibraryKind.HISTORY }
                    }
                    recent?.takeIf { it.first == user }?.let { (_, group) ->
                        if (!state.isWorking) V2OutlineAction("방금 옮긴 항목 되돌리기", { restoreQuick = group })
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }
        }
    } else if (user.isNotEmpty()) key(user, destination) {
        LibraryDetail(state,library,user,destination,submit,
            { label -> totals[destination] = label }, { page = null })
    } else {
        Column(Modifier.padding(18.dp)) {
            LibraryTop("데이터 정리", { page = null })
            SettingsCaption(userError.ifBlank { "사용자를 확인하고 있어요." })
        }
    }
    if (showUsers) V2BottomSheet({ showUsers = false }) {
        Text("사용자 선택", style = MaterialTheme.typography.titleLarge)
        users.forEach { handle -> SettingsRow(handle, selected = handle == user, onClick = {
            user = handle; page = null; showUsers = false
        }) }
    }
    restoreQuick?.let { receipt -> UndoGroupSheet(receipt,state.environment.processRunning,
        state.isWorking || state.environment.operationActive || state.environment.recoveryPending,{ restoreQuick = null }) {
        restoreQuick = null; submit(library.undo(user,receipt))
    } }
}

private fun iconFor(kind: LibraryKind): ImageVector = when(kind) {
    LibraryKind.CARDS -> Icons.Outlined.Person
    LibraryKind.ORPHANS -> Icons.Outlined.Folder
    LibraryKind.BACKGROUNDS -> Icons.Outlined.Image
    LibraryKind.WORLDS -> Icons.Outlined.MenuBook
    LibraryKind.PRESETS -> Icons.Outlined.Tune
    LibraryKind.DUPLICATES -> Icons.Outlined.ContentCopy
    LibraryKind.HISTORY -> Icons.Outlined.Restore
}
@Composable private fun LibraryTop(title: String, back: () -> Unit, action: String? = null, click: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = back) { ReviewGlyph(R.drawable.rv_arrow_left,24.dp,description = "뒤로") }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        if (action != null) V2TopBarAction(action,click)
    }
}
private fun friendlyDate(ms: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(ms))
private val LibraryKind.trashable get() = this in setOf(LibraryKind.CARDS,LibraryKind.ORPHANS,LibraryKind.DUPLICATES)

@Composable private fun LibraryDetail(state: LauncherUiState, library: DataLibraryAccess, user: String,
    kind: LibraryKind, submit: (LibraryWrite) -> Unit, summary: (String) -> Unit, back: () -> Unit) {
    var generation by remember { mutableIntStateOf(0) }
    var raw by remember { mutableStateOf(emptyList<LibraryItem>()) }
    var missed by remember { mutableIntStateOf(0) }
    var groups by remember { mutableStateOf(emptyList<LibraryGroup>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var detail by remember { mutableStateOf<LibraryItem?>(null) }
    var trashConfirm by remember { mutableStateOf<List<LibraryItem>?>(null) }
    var restore by remember { mutableStateOf<LibraryGroup?>(null) }
    var picked by remember { mutableStateOf(emptyList<Uri>()) }
    val last by library.lastMoved.collectAsState()
    val results = state.lastOperationResult
    var observed by remember { mutableStateOf(results?.completedAtMillis) }
    LaunchedEffect(results?.completedAtMillis) {
        if (results != null && results.completedAtMillis != observed && results.operation in setOf("library-import","library-trash","library-undo")) {
            observed = results.completedAtMillis
            selected = emptySet(); selecting = false; detail = null; generation++
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.distinct().size > 20) error = "한 번에 최대 20개까지 가져올 수 있어요."
        else if (uris.isNotEmpty()) picked = uris.distinct()
    }
    LaunchedEffect(kind,user,generation) {
        raw = emptyList(); groups = emptyList(); error = ""; loading = true; missed = 0
        try {
            if (kind == LibraryKind.HISTORY) groups = library.groups(user)
            else {
                val all = linkedMapOf<String,LibraryItem>(); val seen = mutableSetOf<String>()
                var cursor = ""
                do {
                    check(seen.add(cursor)) { "같은 목록이 반복되어 검사를 멈췄어요." }
                    val chunk = library.page(user,kind,cursor)
                    chunk.items.forEach { all[it.path] = it }
                    missed += chunk.unexamined
                    raw = all.values.toList()
                    cursor = chunk.next
                    yield()
                } while (cursor.isNotEmpty())
                summary("${all.size}개 · ${formatBackupBytes(all.values.sumOf { it.bytes })}")
            }
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) { error = e.message ?: "자료 목록을 읽지 못했어요." }
        finally { loading = false }
    }
    val rows = remember(raw,kind) {
        when(kind) {
            LibraryKind.DUPLICATES -> raw.filter { it.duplicate.isNotEmpty() }.groupBy { it.duplicate }.values
                .filter { it.size > 1 }.sortedBy { it.first().label }.flatMap { it.sortedBy(LibraryItem::path) }
            else -> raw.sortedWith(compareByDescending<LibraryItem> { it.bytes }.thenBy { it.label })
        }
    }
    val filtered = remember(rows,query) { rows.filter { it.label.contains(query,true) || it.path.contains(query,true) } }
    val groupNumbers = remember(rows,kind) { if (kind == LibraryKind.DUPLICATES)
        rows.map { it.duplicate }.distinct().withIndex().associate { it.value to it.index + 1 } else emptyMap() }
    val selectedRows = remember(rows,selected) { rows.filter { it.path in selected } }
    val blocked = state.isWorking || state.environment.operationActive || state.environment.recoveryPending
    val unsafeDuplicates = kind == LibraryKind.DUPLICATES && selectedRows.any { chosen ->
        rows.none { it.duplicate == chosen.duplicate && it.path !in selected }
    }
    val leave = { if (selecting) { selecting = false; selected = emptySet() } else back() }
    V2BackHandler(detail == null && trashConfirm == null && restore == null && picked.isEmpty(),leave)
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LibraryTop(if(selecting) "${selected.size}개 선택" else kind.label, leave,
            when {
                selecting -> "선택 해제"
                kind.trashable && !loading && error.isEmpty() && rows.isNotEmpty() && !blocked -> "선택"
                else -> null
            }) { if (selecting) { selected = emptySet(); selecting = false } else selecting = true }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding = PaddingValues(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                val title = when {
                    kind == LibraryKind.HISTORY -> "옮긴 작업 기록"
                    loading -> "자료 확인 중"
                    error.isNotEmpty() -> "자료 확인"
                    kind == LibraryKind.DUPLICATES -> "같은 카드 ${rows.map { it.duplicate }.distinct().size}묶음"
                    else -> "${kind.label} ${rows.size}개"
                }
                Lead(title, when(kind) {
                    LibraryKind.CARDS -> "$user · 카드+채팅 용량이 큰 순서예요."
                    LibraryKind.ORPHANS -> "$user · 카드를 지울 때 남긴 채팅이에요. 옮길 항목만 직접 골라 주세요."
                    LibraryKind.DUPLICATES -> "$user · PNG 전체가 같은 카드만 보여요. 묶음마다 하나는 남겨 주세요."
                    LibraryKind.HISTORY -> "$user · 같은 이름이 생긴 파일은 덮어쓰지 않아요."
                    else -> "$user · 사용처는 항목을 열고 직접 확인할 때만 검사해요."
                })
            }
            if (error.isNotBlank()) item {
                Column { Text(error,color = MaterialTheme.colorScheme.error); V2OutlineAction("다시 확인", { generation++ }) }
            }
            if (kind != LibraryKind.HISTORY && raw.isNotEmpty()) item {
                OutlinedTextField(query,{ query = it },Modifier.fillMaxWidth(),singleLine = true, label = { Text("이름으로 찾기") })
            }
            if (loading) item { if (rememberProgressVisible(true)) SettingsCaption("불러온 항목 ${raw.size}개 · 확인하고 있어요.") }
            if (missed > 0) item { SettingsCaption("확인 못 한 카드 ${missed}개는 정리 후보에 넣지 않았어요.") }
            if (!loading && error.isEmpty() && groups.isEmpty() && filtered.isEmpty()) item {
                SettingsCaption(when {
                    query.isNotEmpty() -> "검색 결과가 없어요."
                    kind == LibraryKind.HISTORY -> "이 사용자에서 데이터 정리로 옮긴 기록이 없어요."
                    kind == LibraryKind.DUPLICATES -> "확인한 카드 중 그림까지 같은 카드가 없어요."
                    else -> "표시할 항목이 없어요."
                })
            }
            items(groups,key = { it.id }) { group ->
                V2NavigationRow(friendlyDate(group.date), { restore = group },
                    description = "되돌릴 항목 ${group.count}개")
            }
            items(filtered,key = { it.path }) { row ->
                val isProtected = row.protected
                val toggle: () -> Unit = {
                    if (!isProtected && !blocked) {
                        if (row.path in selected) selected = selected - row.path
                        else if (selected.size < 500) selected = selected + row.path
                        else error = "한 번에 최대 500개까지 선택할 수 있어요."
                    }
                }
                LibraryItemRow(kind,row,groupNumbers[row.duplicate],selecting,row.path in selected,
                    { if(selecting) toggle() else detail = row })
            }
            if (!loading && !blocked && kind != LibraryKind.HISTORY && !selecting) {
                last?.takeIf { it.first == user }?.let { (_,g) -> item {
                    V2OutlineAction("방금 옮긴 항목 되돌리기", { restore = g })
                } }
            }
        }
        if (blocked) SettingsCaption("다른 작업이나 복구가 끝난 뒤 변경할 수 있어요.")
        if (selecting && !blocked && selectedRows.isNotEmpty()) {
            if (unsafeDuplicates) SettingsCaption("같은 카드 묶음마다 하나는 남겨 주세요.")
            else V2DangerAction("${selectedRows.size}개 휴지통으로", { trashConfirm = selectedRows },Modifier.fillMaxWidth())
        } else if (!selecting && kind.maxMiB > 0 && !blocked) {
            V2SheetPrimaryButton("가져오기") {
                filePicker.launch(when(kind) {
                    LibraryKind.CARDS -> arrayOf("image/png","application/octet-stream")
                    LibraryKind.BACKGROUNDS -> arrayOf("image/*","application/octet-stream")
                    else -> arrayOf("application/json","text/*","application/octet-stream")
                })
            }
        }
        Spacer(Modifier.height(2.dp))
    }
    detail?.let { row ->
        ItemSheet(library,user,kind,row,!blocked && !loading && error.isEmpty(),{ detail = null }) {
            detail = null; trashConfirm = listOf(row)
        }
    }
    trashConfirm?.let { records ->
        var withChats by remember(records) { mutableStateOf(false) }
        val chatCount = records.sumOf { it.chats }
        V2BottomSheet({ trashConfirm = null }) {
            Text("${records.size}개를 휴지통으로 옮길까요?",style = MaterialTheme.typography.titleLarge)
            SettingsCaption(if (kind == LibraryKind.ORPHANS) "선택한 채팅 폴더와 대화 파일이 함께 옮겨져요." else "카드만 옮기면 채팅은 그대로 남아요.")
            if (kind != LibraryKind.ORPHANS && chatCount > 0) SettingsRow("채팅 ${chatCount}개도 같이 옮기기",checked = withChats,
                onClick = { withChats = !withChats })
            SettingsCaption("직접 휴지통을 비우기 전까지 되돌릴 수 있어요. 지금은 저장 공간이 늘지 않아요.")
            if (state.environment.processRunning) SettingsCaption("실행 중인 서버를 끄고 옮긴 뒤 다시 켜요.")
            if (!blocked) V2DangerAction("휴지통으로 옮기기", {
                try { val task = library.trash(user,records,withChats); trashConfirm = null; submit(task) }
                catch (e: IllegalArgumentException) { trashConfirm = null; error = "선택한 항목의 경로가 너무 길어요. 나눠서 옮겨 주세요." }
            },Modifier.fillMaxWidth())
            V2QuietAction("취소", { trashConfirm = null },Modifier.fillMaxWidth())
        }
    }
    restore?.let { group -> UndoGroupSheet(group,state.environment.processRunning,blocked,{ restore = null }) {
        restore = null; if (!blocked) submit(library.undo(user,group))
    } }
    if (picked.isNotEmpty()) LibraryImportSheet(library,kind,user,picked,{ picked = emptyList() }) { plans ->
        if (!blocked) { picked = emptyList(); submit(library.import(user,kind,plans)) }
    }
}

@Composable private fun LibraryItemRow(kind: LibraryKind, row: LibraryItem, groupNumber: Int?, selecting: Boolean,
    selected: Boolean, click: () -> Unit) {
    val color = MaterialTheme.colorScheme
    val interactive = if (selecting) Modifier.toggleable(selected, enabled = !row.protected, role = Role.Checkbox) { click() }
        else Modifier.clickable(role = Role.Button, onClick = click)
    Surface(Modifier.fillMaxWidth(),shape = RoundedCornerShape(16.dp),color = if(selected) color.secondaryContainer else color.surface) {
        Row(Modifier.fillMaxWidth().then(interactive).padding(horizontal = 14.dp,vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if(selecting) SettingsChoiceMark(selected,selection = true,enabled = !row.protected)
            if (kind == LibraryKind.CARDS || kind == LibraryKind.DUPLICATES) {
                Box(Modifier.size(40.dp).background(color.surfaceVariant,RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center) { Text(row.label.take(1),style = MaterialTheme.typography.titleMedium) }
            } else Icon(iconFor(kind),null,Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(if (groupNumber == null) row.label else row.path.substringAfterLast('/'),
                    maxLines = 2,overflow = TextOverflow.Ellipsis,style = MaterialTheme.typography.bodyLarge)
                SettingsCaption(when(kind) {
                    LibraryKind.CARDS, LibraryKind.DUPLICATES -> (if(row.chats > 0) "채팅 ${row.chats}개" else "채팅 없음") +
                        (if(row.protected) " · 보호됨" else "") + if(groupNumber != null) " · 같은 카드 묶음 $groupNumber" else ""
                    LibraryKind.ORPHANS -> "채팅 ${row.chats}개" + if (row.modified > 0) " · ${friendlyDate(row.modified)}" else ""
                    LibraryKind.PRESETS -> row.path.substringBefore('/')
                    else -> if (row.modified > 0) friendlyDate(row.modified) else row.path
                })
            }
            Text(formatBackupBytes(row.bytes),style = MaterialTheme.typography.bodySmall)
            if(!selecting) Icon(Icons.Outlined.ChevronRight,null,Modifier.size(18.dp))
        }
    }
}

@Composable private fun ItemSheet(library: DataLibraryAccess,user: String,kind: LibraryKind,row: LibraryItem,
    canWrite: Boolean,close: () -> Unit,trash: () -> Unit) {
    var usage by remember(row.path) { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    V2BottomSheet(close) {
        Text(row.label,style = MaterialTheme.typography.titleLarge)
        SettingsCaption("${row.path} · ${formatBackupBytes(row.bytes)}")
        if (kind.trashable) {
            SettingsRow("채팅", "${row.chats}개")
            if (row.protected) SettingsCaption("즐겨찾기·그룹 연결 등으로 보호된 항목은 여기서 옮기지 않아요.")
            else if (canWrite && kind != LibraryKind.DUPLICATES)
                V2DangerAction("휴지통으로 옮기기",trash,Modifier.fillMaxWidth())
            else if(kind == LibraryKind.DUPLICATES) SettingsCaption("선택 모드에서 같은 묶음의 카드 하나를 남기고 정리해 주세요.")
        } else {
            SettingsHeading("어디서 쓰나요?")
            Text(usage ?: if(checking) "사용처를 확인하고 있어요." else "아직 확인하지 않았어요.")
            if(!checking) V2OutlineAction(if(usage == null) "사용처 확인" else "다시 확인", {
                checking = true
                scope.launch {
                    try { usage = library.usage(user,row.path) }
                    catch(cancel: CancellationException) { throw cancel }
                    catch(e: Exception) { usage = "확인 못 함 · " + (e.message ?: "사용처를 읽지 못했어요.") }
                    finally { checking = false }
                }
            }, Modifier.fillMaxWidth())
        }
    }
}

@Composable private fun UndoGroupSheet(group: LibraryGroup,server: Boolean,busy: Boolean,close: () -> Unit,apply: () -> Unit) {
    V2BottomSheet(close) {
        Text("옮긴 항목을 되돌릴까요?",style = MaterialTheme.typography.titleLarge)
        SettingsCaption("${friendlyDate(group.date)} · 같은 이름이 있으면 그대로 두고 가능한 항목만 복구해요.")
        if (server) SettingsCaption("서버를 잠깐 끄고 되돌린 뒤 다시 켜요.")
        if(busy) {
            SettingsCaption("다른 작업이나 복구가 끝난 뒤 되돌릴 수 있어요.")
            V2SheetPrimaryButton("닫기",onClick = close)
        } else {
            V2SheetPrimaryButton("원래 위치로 되돌리기",onClick = apply)
            V2QuietAction("취소",close,Modifier.fillMaxWidth())
        }
    }
}

private val samplerLabels = mapOf("textgen" to "텍스트 완성","kobold" to "KoboldAI","novel" to "NovelAI")

@Composable private fun LibraryImportSheet(library: DataLibraryAccess,kind: LibraryKind,user: String,
    picked: List<Uri>,close: () -> Unit,apply: (List<LibraryFilePlan>) -> Unit) {
    var plans by remember(picked) { mutableStateOf(emptyList<LibraryFilePlan>()) }
    var reading by remember(picked) { mutableStateOf(true) }
    var fileIndex by remember(picked) { mutableIntStateOf(0) }
    LaunchedEffect(picked) {
        for ((i,uri) in picked.withIndex()) {
            fileIndex = i + 1
            val plan = try { library.plan(uri,kind) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { LibraryFilePlan(uri,"선택한 파일 ${i+1}","",e.message ?: "내용을 확인하지 못했어요.") }
            plans = plans + plan
        }
        reading = false
    }
    val valid = plans.filter { it.rejection.isEmpty() }
    val ambiguous = valid.any { it.recommended != null && it.chosen == null }
    V2BottomSheet(close, footer = {
        Column(Modifier.fillMaxWidth()) {
            if(!reading && valid.isEmpty()) V2SheetPrimaryButton("확인",onClick = close)
            else {
                V2SheetPrimaryButton(if(reading) "파일 확인 중" else "${valid.size}개 가져오기",
                    enabled = !reading && !ambiguous && valid.isNotEmpty()) { apply(valid) }
                V2QuietAction("취소",close,Modifier.fillMaxWidth())
            }
        }
    }) {
        Text("${kind.label} 가져오기",style = MaterialTheme.typography.titleLarge)
        SettingsCaption("넣을 사용자 · $user\n같은 이름이면 원래 파일을 바꾸지 않고 새 이름으로 넣어요.")
        if(reading) SettingsCaption("파일 확인 중 · $fileIndex / ${picked.size}개")
        plans.forEachIndexed { index, p ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp),verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(p.name,style = MaterialTheme.typography.titleMedium)
                if(p.rejection.isNotBlank()) Text("못 넣음 · ${p.rejection}",color = MaterialTheme.colorScheme.error)
                else if (p.recommended == null) SettingsCaption(p.info)
                else {
                    SettingsCaption("어떤 프리셋인가요? · 추천: ${samplerLabels[p.recommended]}")
                    samplerLabels.entries.sortedByDescending { it.key == p.recommended }.forEach { (value,label) ->
                        Row(Modifier.fillMaxWidth().clickable(role = Role.RadioButton) {
                            plans = plans.mapIndexed { n, item -> if(n == index) item.copy(chosen = value) else item }
                        }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(p.chosen == value,onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(label + if(value == p.recommended) " · 추천" else "")
                        }
                    }
                }
            }
        }
        if(!reading && valid.isNotEmpty()) SettingsCaption("서버가 켜져 있다면 가져온 뒤 실리태번 목록을 새로고침해 주세요.")
    }
}
