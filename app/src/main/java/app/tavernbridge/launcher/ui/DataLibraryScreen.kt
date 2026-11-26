package app.tavernbridge.launcher.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.data.*
import app.tavernbridge.launcher.R
import app.tavernbridge.launcher.model.LauncherUiState
import java.text.DateFormat
import java.text.Collator
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** Only the profile list is read on hub entry. Each deeper scan has its own deliberate entry point. */
@Composable
internal fun DataLibraryScreen(state: LauncherUiState, library: DataLibraryAccess,
    submit: (LibraryWrite) -> Unit, onBack: () -> Unit) {
    // Save a wire value instead of an enum so retired destinations safely return to the hub.
    var pageId by rememberSaveable { mutableStateOf<String?>(null) }
    var cardOrder by rememberSaveable { mutableStateOf(CardOrder.TOTAL) }
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
            user = receipt.first; pageId = null; restoreQuick = receipt.second
        }
        library.pendingUndo.compareAndSet(receipt,null)
    }
    val destination = LibraryKind.entries.find { it.wire == pageId }
    V2BackHandler(destination == null && !showUsers && restoreQuick == null, onBack)
    if (destination == null) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LibraryTop("자료 관리", onBack)
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Lead("실리태번 자료", "사용자별로 자료를 살펴보고 새 파일을 가져와요.")
                if (userError.isNotEmpty()) {
                    Text(userError, color = MaterialTheme.colorScheme.error)
                    V2OutlineAction("사용자 다시 확인", { usersRequest++ })
                } else if (userLoading) {
                    if (rememberProgressVisible(true)) SettingsCaption("실리태번 사용자를 확인하고 있어요.")
                } else if (user.isEmpty()) SettingsCaption("자료 폴더가 있는 사용자가 없어요. 실리태번을 한 번 열어 주세요.")
                else {
                    if (users.size > 1) SettingsGroup { SettingsRow("사용자", user, onClick = { showUsers = true }) }
                    else SettingsCaption("사용자 · $user")
                    ToolGroup("내 자료") {
                        listOf(LibraryKind.CARDS, LibraryKind.BACKGROUNDS, LibraryKind.WORLDS,
                            LibraryKind.PRESETS, LibraryKind.THEMES, LibraryKind.QUICK_REPLIES).forEachIndexed { i, k ->
                            if (i != 0) ToolDivider()
                            ToolRow(k.label, if (k == LibraryKind.PRESETS) "Chat Completion API에서 쓰는 설정이에요" else "",
                                totals[k].orEmpty(), icon = iconFor(k), onClick = { pageId = k.wire })
                        }
                    }
                    ToolGroup("정리 도구") {
                        ToolRow("카드 없이 남은 채팅", "카드가 없어도 소중한 대화일 수 있어요", icon = iconFor(LibraryKind.ORPHANS)) { pageId = LibraryKind.ORPHANS.wire }
                        ToolDivider()
                        ToolRow("휴지통에서 되돌리기", "자료 관리에서 옮긴 기록", icon = iconFor(LibraryKind.HISTORY)) { pageId = LibraryKind.HISTORY.wire }
                    }
                    recent?.takeIf { it.first == user }?.let { (_, group) ->
                        if (!state.isWorking) V2OutlineAction("방금 옮긴 항목 되돌리기", { restoreQuick = group })
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }
        }
    } else if (user.isNotEmpty()) key(user, destination) {
        LibraryDetail(state,library,user,destination,cardOrder,{ cardOrder = it },submit,
            { label -> totals[destination] = label }, { pageId = null })
    } else {
        Column(Modifier.padding(18.dp)) {
            LibraryTop("자료 관리", { pageId = null })
            SettingsCaption(userError.ifBlank { "사용자를 확인하고 있어요." })
        }
    }
    if (showUsers) V2BottomSheet({ showUsers = false }) {
        Text("사용자 선택", style = MaterialTheme.typography.titleLarge)
        users.forEach { handle -> SettingsRow(handle, selected = handle == user, onClick = {
            user = handle; pageId = null; showUsers = false
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
    LibraryKind.THEMES -> Icons.Outlined.Palette
    LibraryKind.QUICK_REPLIES -> Icons.Outlined.ChatBubbleOutline
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
private val LibraryKind.trashable get() = this == LibraryKind.CARDS || this == LibraryKind.ORPHANS

private enum class CardOrder(val label: String) {
    TOTAL("총용량 큰 순"), NAME("이름순"), CHATS("채팅 많은 순");

    fun comparator(): Comparator<LibraryItem> {
        val collator = Collator.getInstance(Locale.KOREAN)
        val byName = Comparator<LibraryItem> { a, b -> collator.compare(a.label, b.label) }.thenBy { it.path }
        return when (this) {
            TOTAL -> compareByDescending<LibraryItem> { it.bytes }.then(byName)
            CHATS -> compareByDescending<LibraryItem> { it.chats }.then(byName)
            NAME -> byName
        }
    }
}

@Composable private fun LibraryDetail(state: LauncherUiState, library: DataLibraryAccess, user: String,
    kind: LibraryKind, cardOrder: CardOrder, setCardOrder: (CardOrder) -> Unit,
    submit: (LibraryWrite) -> Unit, summary: (String) -> Unit, back: () -> Unit) {
    var generation by remember { mutableIntStateOf(0) }
    var raw by remember { mutableStateOf(emptyList<LibraryItem>()) }
    var showSort by remember { mutableStateOf(false) }
    var groups by remember { mutableStateOf(emptyList<LibraryGroup>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var detail by remember { mutableStateOf<LibraryItem?>(null) }
    var assetChange by remember { mutableStateOf<Pair<LibraryItem,AssetChange>?>(null) }
    var trashConfirm by remember { mutableStateOf<List<LibraryItem>?>(null) }
    var restore by remember { mutableStateOf<LibraryGroup?>(null) }
    var picked by remember { mutableStateOf(emptyList<Uri>()) }
    val last by library.lastMoved.collectAsState()
    val results = state.lastOperationResult
    var observed by remember { mutableStateOf(results?.completedAtMillis) }
    LaunchedEffect(results?.completedAtMillis) {
        if (results != null && results.completedAtMillis != observed && results.operation in setOf("library-import","library-trash","library-undo","library-asset-change","library-card-change")) {
            observed = results.completedAtMillis
            selected = emptySet(); selecting = false; detail = null; assetChange = null; generation++
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.distinct().size > 20) error = "한 번에 최대 20개까지 가져올 수 있어요."
        else if (uris.isNotEmpty()) picked = uris.distinct()
    }
    LaunchedEffect(kind,user,generation) {
        raw = emptyList(); groups = emptyList(); error = ""; loading = true
        try {
            if (kind == LibraryKind.HISTORY) groups = library.groups(user)
            else {
                val all = linkedMapOf<String,LibraryItem>(); val seen = mutableSetOf<String>()
                var cursor = ""
                do {
                    check(seen.add(cursor)) { "같은 목록이 반복되어 검사를 멈췄어요." }
                    val chunk = library.page(user,kind,cursor)
                    chunk.items.forEach { all[it.path] = it }
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
    val order = remember(kind,cardOrder) { (if (kind == LibraryKind.CARDS) cardOrder else CardOrder.TOTAL).comparator() }
    val rows = remember(raw,order) { raw.sortedWith(order) }
    val filtered = remember(rows,query) { rows.filter { it.label.contains(query,true) || it.path.contains(query,true) } }
    val selectedRows = remember(rows,selected) { rows.filter { it.path in selected } }
    val blocked = state.isWorking || state.environment.operationActive || state.environment.recoveryPending
    val leave = { if (selecting) { selecting = false; selected = emptySet() } else back() }
    val canChangeAsset = !blocked && !loading && error.isEmpty()
    V2BackHandler(detail == null && assetChange == null && trashConfirm == null && restore == null && picked.isEmpty() && !showSort,leave)
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
                    else -> "${kind.label} ${rows.size}개"
                }
                Lead(title, when(kind) {
                    LibraryKind.CARDS -> "$user · 총용량은 카드 파일과 연결된 채팅을 합친 값이에요."
                    LibraryKind.ORPHANS -> "$user · 카드를 지울 때 남긴 채팅이에요. 옮길 항목만 직접 골라 주세요."
                    LibraryKind.PRESETS -> "$user · Chat Completion API용 프리셋만 보여줘요."
                    LibraryKind.HISTORY -> "$user · 같은 이름이 생긴 파일은 덮어쓰지 않아요."
                    else -> "$user · 항목을 눌러 미리 보거나 더보기에서 이름을 바꾸고 휴지통으로 옮길 수 있어요."
                })
            }
            if (error.isNotBlank()) item {
                Column { Text(error,color = MaterialTheme.colorScheme.error); V2OutlineAction("다시 확인", { generation++ }) }
            }
            if (kind != LibraryKind.HISTORY && raw.isNotEmpty()) item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(query,{ query = it },Modifier.weight(1f),singleLine = true, label = { Text("이름으로 찾기") })
                    if (kind == LibraryKind.CARDS) V2LinkAction(cardOrder.label, { showSort = true }) {
                        Icon(Icons.Outlined.Sort,contentDescription = "정렬",modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (loading) item { if (rememberProgressVisible(true)) SettingsCaption("불러온 항목 ${raw.size}개 · 확인하고 있어요.") }
            if (!loading && error.isEmpty() && groups.isEmpty() && filtered.isEmpty()) item {
                SettingsCaption(when {
                    query.isNotEmpty() -> "검색 결과가 없어요."
                    kind == LibraryKind.HISTORY -> "이 사용자에서 자료 관리로 옮긴 기록이 없어요."
                    else -> "표시할 항목이 없어요."
                })
            }
            items(groups,key = { it.id }) { group ->
                V2NavigationRow(friendlyDate(group.date), { restore = group },
                    description = "되돌릴 항목 ${group.count}개")
            }
            if (kind == LibraryKind.BACKGROUNDS) {
                // Keep only viewport tiles composed, including the last unpaired tile.
                items(filtered.size / 2 + filtered.size % 2, key = { "pair:" + filtered[it * 2].path }) { pair ->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(2) { offset ->
                            val bg = filtered.getOrNull(pair * 2 + offset)
                            if (bg == null) Spacer(Modifier.weight(1f))
                            else key(bg.path) { BackgroundTile(library,user,bg,Modifier.weight(1f), canChangeAsset,
                                { action -> assetChange = bg to action }, { detail = bg }) }
                        }
                    }
                }
            } else items(filtered,key = { it.path }) { row ->
                val isProtected = row.protected
                val toggle: () -> Unit = {
                    if (!isProtected && !blocked) {
                        if (row.path in selected) selected = selected - row.path
                        else if (selected.size < 500) selected = selected + row.path
                        else error = "한 번에 최대 500개까지 선택할 수 있어요."
                    }
                }
                LibraryItemRow(library,user,kind,row,selecting,row.path in selected,canChangeAsset,
                    { action ->
                        if (kind == LibraryKind.CARDS && action == AssetChange.TRASH) trashConfirm = listOf(row)
                        else assetChange = row to action
                    },
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
            V2DangerAction("${selectedRows.size}개 휴지통으로", { trashConfirm = selectedRows },Modifier.fillMaxWidth())
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
    if (showSort) V2BottomSheet({ showSort = false }) {
        Text("카드 정렬", style = MaterialTheme.typography.titleLarge)
        SettingsGroup {
            CardOrder.entries.forEach { choice ->
                SettingsRow(choice.label, selected = choice == cardOrder, onClick = { setCardOrder(choice); showSort = false })
            }
        }
    }
    detail?.let { row ->
        ItemSheet(library,user,kind,row,!blocked && !loading && error.isEmpty(),{ detail = null }) {
            detail = null; trashConfirm = listOf(row)
        }
    }
    assetChange?.let { (row,action) ->
        key(user,row.path,row.revision,action) {
            AssetChangeSheet(library,user,row,action,canChangeAsset,state.environment.processRunning,
                close = { assetChange = null }, perform = { task ->
                    if (canChangeAsset) { assetChange = null; submit(task) }
                })
        }
    }
    trashConfirm?.let { records ->
        var withChats by remember(records) { mutableStateOf(false) }
        val chatCount = records.sumOf { it.chats }
        if (records.any { it.protected }) V2BottomSheet({ trashConfirm = null },footer = {
            V2SheetPrimaryButton("닫기",onClick = { trashConfirm = null })
        }) {
            Text("보호된 카드는 옮기지 않아요",style = MaterialTheme.typography.titleLarge)
            records.filter { it.protected }.map { it.protectionReason ?: LibraryProtection.UNKNOWN }.distinct().forEach {
                SettingsCaption(it.detail)
            }
            SettingsCaption("원본과 채팅은 그대로 두었어요.")
        } else V2BottomSheet({ trashConfirm = null }) {
            Text("${records.size}개를 휴지통으로 옮길까요?",style = MaterialTheme.typography.titleLarge)
            SettingsCaption(when {
                kind == LibraryKind.ORPHANS -> "선택한 채팅 폴더와 대화 파일이 함께 옮겨져요."
                chatCount > 0 && !withChats -> "채팅 ${chatCount}개는 그대로 남아요. 자료 관리의 ‘카드 없이 남은 채팅’에서 볼 수 있어요."
                withChats -> "카드와 선택한 채팅을 함께 휴지통으로 옮겨요."
                else -> "카드만 옮겨요."
            })
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

@Composable private fun BackgroundTile(library: DataLibraryAccess, user: String, row: LibraryItem, modifier: Modifier,
    canChange: Boolean, onAction: (AssetChange) -> Unit, click: () -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(16.dp),color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = click)) {
            LibraryArtwork(library,user,row,Modifier.fillMaxWidth().aspectRatio(16f/9f))
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 10.dp),verticalAlignment = Alignment.CenterVertically) {
                Text(row.label,Modifier.weight(1f),style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,overflow = TextOverflow.Ellipsis)
                if (canChange) AssetOverflow(row,onAction) else Icon(Icons.Outlined.ChevronRight,null,Modifier.size(18.dp))
            }
        }
    }
}

@Composable private fun LibraryItemRow(library: DataLibraryAccess, user: String, kind: LibraryKind, row: LibraryItem, selecting: Boolean,
    selected: Boolean, canChange: Boolean, onAction: (AssetChange) -> Unit, click: () -> Unit) {
    val color = MaterialTheme.colorScheme
    val interactive = if (selecting) Modifier.toggleable(selected, enabled = !row.protected, role = Role.Checkbox) { click() }
        else Modifier.clickable(role = Role.Button, onClick = click)
    Surface(Modifier.fillMaxWidth(),shape = RoundedCornerShape(16.dp),color = if(selected) color.secondaryContainer else color.surface) {
        Row(Modifier.fillMaxWidth().then(interactive).padding(horizontal = 14.dp,vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if(selecting) SettingsChoiceMark(selected,selection = true,enabled = !row.protected)
            if (kind == LibraryKind.CARDS) {
                LibraryArtwork(library,user,row,Modifier.size(width = 48.dp,height = 64.dp).clip(RoundedCornerShape(8.dp)))
            } else Icon(iconFor(kind),null,Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(row.label,
                    maxLines = 2,overflow = TextOverflow.Ellipsis,style = MaterialTheme.typography.bodyLarge)
                SettingsCaption(when(kind) {
                    LibraryKind.CARDS -> "카드 ${formatBackupBytes(row.fileBytes)} · 채팅 ${row.chats}개" +
                        (if(row.protected) " · 보호됨" else "")
                    LibraryKind.ORPHANS -> "채팅 ${row.chats}개" + if (row.modified > 0) " · ${friendlyDate(row.modified)}" else ""
                    else -> if (row.modified > 0) friendlyDate(row.modified) else row.path
                })
            }
            Text((if (kind == LibraryKind.CARDS) "총 " else "") + formatBackupBytes(row.bytes),
                style = MaterialTheme.typography.bodySmall)
            if (!selecting && (kind.managedAsset || kind == LibraryKind.CARDS) && canChange) AssetOverflow(row,onAction)
            else if(!selecting) Icon(Icons.Outlined.ChevronRight,null,Modifier.size(18.dp))
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
        if (kind == LibraryKind.BACKGROUNDS || kind == LibraryKind.CARDS) {
            LibraryArtwork(library,user,row,Modifier.fillMaxWidth().height(216.dp).clip(RoundedCornerShape(12.dp)),detail = true)
        } else if (kind.managedAsset) LibraryPreviewSection(library,user,kind,row)
        if (kind != LibraryKind.CARDS) SettingsCaption("${row.path} · ${formatBackupBytes(row.bytes)}")
        if (kind == LibraryKind.CARDS) {
            SettingsGroup {
                SettingsRow("파일 이름",row.path.substringAfterLast('/'))
                SettingsDivider()
                SettingsRow("카드 크기",formatBackupBytes(row.fileBytes))
                SettingsDivider()
                SettingsRow("연결된 채팅","${row.chats}개")
            }
            if (row.protected) SettingsCaption((row.protectionReason ?: LibraryProtection.UNKNOWN).detail)
        } else if (kind.trashable) {
            SettingsRow("채팅", "${row.chats}개")
            if (row.protected) SettingsCaption((row.protectionReason ?: LibraryProtection.UNKNOWN).detail)
            else if (canWrite) V2DangerAction("휴지통으로 옮기기",trash,Modifier.fillMaxWidth())
        } else {
            SettingsHeading("어디서 쓰나요?")
            Text(usage ?: if(checking) "사용처를 확인하고 있어요." else "아직 확인하지 않았어요.")
            if(!checking) V2OutlineAction(if(usage == null) "사용처 확인" else "다시 확인", {
                checking = true
                scope.launch {
                    try {
                        val result = library.assetUsage(user,row)
                        usage = listOf(result.label,result.detail).filter(String::isNotBlank).joinToString(" · ")
                    }
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
        SettingsCaption(if (server) "서버를 잠깐 끄고 되돌린 뒤 다시 켜요."
            else "되돌리기 전에 다시 확인해요. 그사이에 서버가 켜졌다면 잠깐 끄고 되돌린 뒤 다시 켜요.")
        if(busy) {
            SettingsCaption("다른 작업이나 복구가 끝난 뒤 되돌릴 수 있어요.")
            V2SheetPrimaryButton("닫기",onClick = close)
        } else {
            V2SheetPrimaryButton("원래 위치로 되돌리기",onClick = apply)
            V2QuietAction("취소",close,Modifier.fillMaxWidth())
        }
    }
}

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
    V2BottomSheet(close, footer = {
        Column(Modifier.fillMaxWidth()) {
            if(!reading && valid.isEmpty()) V2SheetPrimaryButton("확인",onClick = close)
            else {
                V2SheetPrimaryButton(if(reading) "파일 확인 중" else "${valid.size}개 가져오기",
                    enabled = !reading && valid.isNotEmpty()) { apply(valid) }
                V2QuietAction("취소",close,Modifier.fillMaxWidth())
            }
        }
    }) {
        Text("${kind.label} 가져오기",style = MaterialTheme.typography.titleLarge)
        SettingsCaption("넣을 사용자 · $user\n같은 이름이면 원래 파일을 바꾸지 않고 새 이름으로 넣어요.")
        if(reading) SettingsCaption("파일 확인 중 · $fileIndex / ${picked.size}개")
        plans.forEach { p ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp),verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(p.name,style = MaterialTheme.typography.titleMedium)
                if(p.rejection.isNotBlank()) Text("못 넣음 · ${p.rejection}",color = MaterialTheme.colorScheme.error)
                else SettingsCaption(p.info)
            }
        }
        if(!reading && valid.isNotEmpty()) SettingsCaption("서버가 켜져 있다면 가져온 뒤 실리태번 목록을 새로고침해 주세요.")
    }
}
