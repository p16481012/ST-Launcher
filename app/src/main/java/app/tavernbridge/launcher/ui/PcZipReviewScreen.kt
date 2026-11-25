package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.tavernbridge.launcher.R
import app.tavernbridge.launcher.data.PcZipPreview

private val pcKinds = mapOf("characters" to "캐릭터", "chats" to "채팅", "group_chats" to "그룹 채팅",
    "groups" to "그룹", "worlds" to "로어북", "avatars" to "페르소나 그림", "backgrounds" to "배경",
    "presets" to "프리셋", "user_files" to "사용자 파일")

@Composable internal fun PcZipReviewScreen(
    preview: PcZipPreview, running: Boolean, close: () -> Unit, map: (String, String) -> Unit,
    apply: (replace: Boolean, backupBefore: Boolean) -> Unit,
) {
    var source by remember { mutableStateOf<String?>(null) }
    var confirm by rememberSaveable(preview.archive) { mutableStateOf(false) }
    val supported = preview.layout !in setOf("legacy_public", "unknown")
    var replace by rememberSaveable(preview.archive) { mutableStateOf(!preview.mergeable) }
    var backup by rememberSaveable(preview.archive) { mutableStateOf(true) }
    val title = when (preview.layout) {
        "user_data" -> "실리태번 사용자 데이터예요"
        "full_candidate" -> "설치 폴더 구조가 들어 있어요"
        "launcher_backup" -> "런처 백업이에요"
        "config_extensions" -> "설정·확장 ZIP이에요"
        "legacy_public" -> "구형 public 폴더 형식은 지원하지 않아요"
        else -> "가져올 수 있는 ZIP 구조가 아니에요"
    }
    val back: () -> Unit = { if (source != null) source = null else if (confirm) confirm = false else close() }
    Dialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = back) { ReviewGlyph(R.drawable.rv_arrow_left, 24.dp, description = "뒤로") }
                    Text("ZIP 가져오기", style = MaterialTheme.typography.titleMedium)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Lead(title, listOf(preview.name, "아직 휴대폰 데이터는 바뀌지 않았어요.").filter(String::isNotBlank).joinToString(" · "))
                    if (preview.mergeable) {
                        ToolGroup("가져오는 방식") {
                            SettingsRadioRow("기존 데이터에 합치기", !replace) { replace = false }
                            ToolDivider()
                            SettingsRadioRow("기존 복원 방식으로 교체", replace) { replace = true }
                        }
                        if (!replace) {
                            ToolGroup("넣을 곳") {
                                preview.profiles.entries.forEachIndexed { i, (from, to) ->
                                    if (i > 0) ToolDivider()
                                    ToolRow(from.ifBlank { "ZIP 사용자" }, trailing = to.ifBlank { "건너뛰기" },
                                        onClick = { source = from })
                                }
                            }
                            ToolGroup("합치면 달라지는 것") {
                                ToolRow("새로 더함", trailing = "${preview.added}개 · ${formatBackupBytes(preview.bytes)}")
                                ToolDivider()
                                ToolRow("기존 파일 유지", trailing = "${preview.kept}개")
                                preview.categories.filter { it.added > 0 || it.kept > 0 }.forEach {
                                    ToolDivider()
                                    ToolRow(pcKinds[it.key] ?: "기타 자료", trailing = "추가 ${it.added} · 유지 ${it.kept}")
                                }
                                if (preview.excluded > 0) {
                                    ToolDivider()
                                    ToolRow("합치지 않는 항목", trailing = "${preview.excluded}개")
                                }
                            }
                            Text("새 파일만 더해요. 기존 사용자 설정·API 키·실행 파일은 가져오지 않아요.")
                            if (preview.relationshipsSkipped > 0) Text("어느 카드·그룹의 대화인지 확인할 수 없는 채팅 ${preview.relationshipsSkipped}개는 섞이지 않게 건너뛰어요.")
                            if (preview.added > 0) Text("합치기에 필요한 여유 공간: ${formatBackupBytes(preview.requiredBytes)}" +
                                (preview.availableBytes?.let { " · 남은 공간 ${formatBackupBytes(it)}" } ?: "") + " (먼저 만드는 전체 백업은 별도예요.)")
                            if (!preview.canAdd) Text(if (preview.profiles.values.none(String::isNotBlank))
                                "넣을 사용자를 선택해 주세요." else "이 사용자에게 새로 더할 파일이 없어요.")
                        }
                    }
                    if (replace && supported) Text(if (preview.layout == "config_extensions")
                        "ZIP에 포함된 설정·확장을 교체해요. 안전하게 합치기는 사용자 데이터 ZIP에서 사용할 수 있어요."
                        else "기존 복원 방식은 ZIP이 담은 범위를 교체해요. 사용자 데이터 복원은 폰에만 있는 다른 계정과 채팅까지 지울 수 있어요.",
                        color = MaterialTheme.colorScheme.error)
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        V2SheetPrimaryButton(if (!supported) "닫기" else "다음", !supported || replace || preview.canAdd) {
                            if (!supported) close() else confirm = true
                        }
                }
            }
        }
    }
    source?.let { selected ->
        V2BottomSheet(onClose = { source = null }) {
            Text("휴대폰 사용자 선택", style = MaterialTheme.typography.titleLarge)
            (listOf("") + preview.targets).forEach { target ->
                SettingsRadioRow(target.ifBlank { "이 사용자 건너뛰기" }, preview.profiles[selected] == target) {
                    source = null
                    if (preview.profiles[selected] != target) map(selected, target)
                }
            }
        }
    }
    if (confirm) V2BottomSheet(onClose = { confirm = false }) {
        Text(if (!replace) "새 파일 ${preview.added}개를 더할까요?" else if (preview.layout == "config_extensions")
            "ZIP에 들어 있는 설정·확장을 교체할까요?" else "ZIP 범위의 기존 데이터를 교체할까요?", style = MaterialTheme.typography.titleLarge)
        Text(if (!replace) "이름이 겹치면 휴대폰 파일을 유지해요." else "같은 경로의 내용이 바뀌며, 교체 범위에서 ZIP에 없는 파일은 사라질 수 있어요. 원본 ZIP은 그대로예요.")
        if (running) Text("계속하면 서버를 잠깐 끄고, 작업이 끝나면 다시 켜요.")
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(backup, role = Role.Checkbox, onValueChange = { backup = it }),
            verticalAlignment = Alignment.CenterVertically) {
            Checkbox(backup, onCheckedChange = null); Spacer(Modifier.width(8.dp)); Text("먼저 휴대폰 전체 백업")
        }
        if (backup) Text("Download에 암호화되지 않은 전체 ZIP을 만들어요. 현재 API 키도 들어가며 시간과 공간이 추가로 필요해요.")
        if (replace) V2DangerAction(if (backup) "백업 후 교체" else "백업 없이 교체", { confirm = false; apply(true, backup) }, Modifier.fillMaxWidth())
        else V2SheetPrimaryButton(if (backup) "백업 후 합치기" else "백업 없이 합치기") { confirm = false; apply(false, backup) }
        V2QuietAction("취소", { confirm = false }, Modifier.fillMaxWidth())
    }
}
