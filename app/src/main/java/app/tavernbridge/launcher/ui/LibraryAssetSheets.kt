package app.tavernbridge.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.data.*
import kotlinx.coroutines.CancellationException

internal val LibraryKind.managedAsset: Boolean get() = when(this) {
    LibraryKind.BACKGROUNDS, LibraryKind.PRESETS, LibraryKind.WORLDS, LibraryKind.THEMES, LibraryKind.QUICK_REPLIES -> true
    else -> false
}

@Composable internal fun AssetOverflow(row: LibraryItem, action: (AssetChange) -> Unit) {
    var open by remember(row.path, row.revision) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.MoreVert, "${row.label} 더보기")
        }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text("이름 변경") }, onClick = { open = false; action(AssetChange.RENAME) })
            DropdownMenuItem(text = { Text("휴지통으로 옮기기") }, onClick = { open = false; action(AssetChange.TRASH) })
        }
    }
}

/** Read one asset only while its detail sheet is visible. QR/CSS never enter an execution surface. */
@Composable internal fun LibraryPreviewSection(library: DataLibraryAccess, user: String, kind: LibraryKind, row: LibraryItem) {
    var preview by remember(user,row.path,row.revision) { mutableStateOf<LibraryAssetPreview?>(null) }
    var error by remember(user,row.path,row.revision) { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    var expanded by remember(user,row.path,row.revision) { mutableStateOf<Int?>(null) }
    LaunchedEffect(library,user,row.path,row.revision,attempt) {
        preview = null; error = ""; expanded = null
        try { preview = library.preview(user,row) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "자료 내용을 읽을 수 없어요." }
    }
    SettingsHeading("미리보기")
    val p = preview
    when {
        error.isNotEmpty() -> {
            SettingsCaption(error)
            V2OutlineAction("미리보기 다시 읽기", { attempt++ })
        }
        p == null -> if (rememberProgressVisible(true)) SettingsCaption("미리보기를 읽고 있어요.")
        else -> {
            if (kind == LibraryKind.THEMES && p.entries.any { it.colorArgb != null }) {
                val c = MaterialTheme.colorScheme
                val palette = p.entries.mapNotNull { e -> e.colorArgb?.let { e.title to Color(it.toInt()) } }
                val bg = palette.firstOrNull { "배경" in it.first }?.second ?: c.surfaceVariant
                val ink = palette.firstOrNull { "글자" in it.first }?.second ?: c.onSurfaceVariant
                val accent = palette.firstOrNull { "강조" in it.first }?.second ?: ink
                Surface(Modifier.fillMaxWidth(),shape = RoundedCornerShape(12.dp),color = bg) {
                    Column(Modifier.padding(14.dp),verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("캐릭터",color = accent,style = MaterialTheme.typography.labelLarge)
                        Text("이 테마의 대화 예시예요.",color = ink,style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            if (p.entries.isEmpty() && p.notice.isBlank()) SettingsCaption("표시할 미리보기 항목이 없어요.")
            if (p.entries.isNotEmpty()) SettingsGroup {
                p.entries.forEachIndexed { i, entry ->
                    if (i > 0) SettingsDivider()
                    if (entry.colorArgb != null) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp,vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(Color(entry.colorArgb.toInt())))
                            Column { Text(entry.title); if (entry.summary.isNotBlank()) SettingsCaption(entry.summary) }
                        }
                    } else if (entry.excerpt.isNotBlank()) {
                        SettingsRow(entry.title, value = if (expanded == i) "접기" else "내용 보기", caption = entry.summary,
                            onClick = { expanded = if (expanded == i) null else i })
                        if (expanded == i) Text(entry.excerpt, Modifier.padding(start=16.dp,end=16.dp,bottom=10.dp),
                            style = MaterialTheme.typography.bodyMedium)
                    } else SettingsRow(entry.title, caption = entry.summary)
                }
            }
            if (p.omitted > 0) SettingsCaption("미리보기에서 ${p.omitted}개 항목은 생략했어요.")
            if (p.notice.isNotBlank()) SettingsCaption(p.notice)
        }
    }
}

@Composable internal fun AssetChangeSheet(library: DataLibraryAccess, user: String, row: LibraryItem, requested: AssetChange,
    canWrite: Boolean, serverRunning: Boolean, close: () -> Unit, perform: (LibraryWrite) -> Unit) {
    val rename = requested == AssetChange.RENAME
    val card = row.isCardAsset()
    val file = row.path.substringAfterLast('/')
    var stem by rememberSaveable { mutableStateOf(file.substringBeforeLast('.')) }
    var edited by rememberSaveable { mutableStateOf(false) }
    var usage by remember { mutableStateOf<AssetUsage?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var readError by remember { mutableStateOf("") }
    var submitError by remember { mutableStateOf("") }
    var forceRename by remember { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(false) }
    LaunchedEffect(library,user,row.path,row.revision,attempt) {
        usage = null; readError = ""; forceRename = false; accepted = false
        try { usage = library.assetUsage(user,row) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { readError = e.message ?: "사용처 검사를 마치지 못했어요." }
    }
    val status = usage?.state
    val copy = rename && (status == AssetUse.USED || status == AssetUse.UNKNOWN && (card || !forceRename))
    val destructive = !copy && status != null
    val needsConsent = destructive && status == AssetUse.UNKNOWN
    val usedBlocked = !rename && status == AssetUse.USED
    val nameError = if (rename) assetNameError(row,stem) else null
    val canSubmit = canWrite && status != null && !usedBlocked && nameError == null && (!needsConsent || accepted)
    val label = when { !rename -> "휴지통으로 옮기기"; copy -> "새 이름으로 복사"; else -> "이름 변경" }
    val confirmLabel = if (serverRunning) "서버 끄고 $label" else label
    val apply: () -> Unit = {
        if (canSubmit) {
            try { perform(library.changeAsset(user,row,if(copy) AssetChange.COPY else requested,
                if(rename) stem else "",needsConsent && accepted)) }
            catch (e: IllegalArgumentException) { submitError = e.message ?: "입력한 이름을 확인해 주세요." }
        }
    }
    V2BottomSheet(close,footer = {
        Column(Modifier.fillMaxWidth()) {
            if (!canWrite || usedBlocked) V2SheetPrimaryButton("닫기", onClick = close)
            else if (readError.isNotEmpty()) {
                V2SheetPrimaryButton("사용처 다시 확인",onClick = { attempt++ })
                V2QuietAction("취소",close,Modifier.fillMaxWidth())
            } else if (status == null) V2OutlineAction("취소",close,Modifier.fillMaxWidth())
            else {
                if (!rename && canSubmit) V2DangerAction(confirmLabel,apply,Modifier.fillMaxWidth())
                else V2SheetPrimaryButton(confirmLabel,enabled = canSubmit,onClick = apply)
                V2QuietAction("취소",close,Modifier.fillMaxWidth())
            }
        }
    }) {
        Text(if (card && rename) "캐릭터 카드 이름 변경" else if (rename) "파일 이름 변경" else "휴지통으로 옮길까요?",style = MaterialTheme.typography.titleLarge)
        SettingsCaption(if (card) "${row.label} · $file" else file)
        if (serverRunning && !usedBlocked && canWrite) SettingsCaption("확정하면 서버를 잠시 끄고 작업을 마친 뒤 다시 켜요.")
        val result = usage
        if (!canWrite) SettingsCaption("다른 작업이나 복구가 끝난 뒤 변경할 수 있어요.")
        if (readError.isNotEmpty()) Text(readError,color = MaterialTheme.colorScheme.error)
        if (result == null && readError.isEmpty()) {
            if (rememberProgressVisible(true)) SettingsCaption("이 자료가 어디서 쓰이는지 확인하고 있어요.")
        } else if (result != null) {
            SettingsHeading(result.label)
            if (result.detail.isNotBlank()) SettingsCaption(result.detail)
            if (card && copy) SettingsCaption("원래 카드와 채팅·그룹 연결을 유지하고 새 카드만 만들어요. 채팅은 복사하지 않아요.")
            else if (status == AssetUse.USED) SettingsCaption(if(rename)
                "연결을 유지하려면 원래 파일은 그대로 두고 새 이름으로 복사해요."
                else "이 자료를 사용하는 곳에서 다른 자료로 바꾸거나 연결을 해제해 주세요.")
            if (rename) {
                OutlinedTextField(value = stem, onValueChange = { stem = it; edited = true; submitError = "" },
                    label = { Text(if (card) "새 카드 이름" else "새 파일 이름") }, suffix = { Text("." + file.substringAfterLast('.')) }, singleLine = true,
                    isError = edited && nameError != null, modifier = Modifier.fillMaxWidth())
                if (edited && nameError != null) Text(nameError,color = MaterialTheme.colorScheme.error)
                SettingsCaption("확장자는 그대로 두고, 같은 이름이 있으면 덮어쓰지 않아요.")
                if (card || row.path.startsWith("themes/") || row.path.startsWith("QuickReplies/"))
                    SettingsCaption("실리태번 목록에 표시되는 이름도 새 이름에 맞춰요.")
                if (!card && status == AssetUse.UNKNOWN) {
                    SettingsGroup {
                        SettingsRow("새 이름으로 복사",selected = !forceRename,caption = "원래 파일과 연결을 유지해요.",
                            onClick = { forceRename = false; accepted = false })
                        SettingsDivider()
                        SettingsRow("원본 이름 변경",selected = forceRename,caption = "예전 이름을 쓰는 연결이 끊길 수 있어요.",
                            onClick = { forceRename = true; accepted = false })
                    }
                }
            } else if (!usedBlocked) SettingsCaption("원본은 휴지통에 보관해요. 같은 이름의 파일이 새로 생기면 되돌릴 때 덮어쓰지 않아요.")
            if (needsConsent) SettingsRow("연결이 끊길 수 있음을 확인했어요", checked = accepted, onClick = { accepted = !accepted })
            if (submitError.isNotBlank()) Text(submitError,color = MaterialTheme.colorScheme.error)
        }
    }
}
