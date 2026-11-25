package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.model.LauncherUiState
import app.tavernbridge.launcher.model.ServerSetting
import app.tavernbridge.launcher.ui.theme.V2Mono

private fun copiesLabel(value: String) = if (value == "-1") "무제한" else "${value}개"

@Composable
internal fun V2ServerConfigSettings(state: LauncherUiState, draft: String, onDraft: (String) -> Unit,
    onRead: () -> Unit, onSnapshots: () -> Unit, onSave: (String, Map<ServerSetting,String>) -> Unit,
    getUsage: suspend () -> app.tavernbridge.launcher.model.ChatCopyUsage?) {
    val config = state.serverConfig
    if (config == null) {
        val connected = state.environment.managerConnected && state.environment.sillyTavernInstalled
        Text(if (state.serverConfigLoading) "서버 설정을 확인하고 있어요."
            else if (!connected) "먼저 실리태번 설치와 Termux 연결을 확인해 주세요."
            else "config.yaml을 읽을 수 없어요.", style = MaterialTheme.typography.titleMedium)
        if (connected && !state.serverConfigLoading) SettingsGroup {
            SettingsRow("설정 사본에서 되돌리기", caption = "안전 사본에서 보관된 설정을 골라요.", onClick = onSnapshots)
        }
        if (connected && !state.serverConfigLoading) V2OutlineAction("다시 불러오기", onRead)
        return
    }
    val changes = remember(config.revision, draft) {
        draft.split(',').mapNotNull { pair ->
            val key = ServerSetting.entries.find { it.key == pair.substringBefore('=') }
            val value = pair.substringAfter('=', "")
            if (key != null && key in config.values && key.accepts(value) && value != config.values[key]) key to value else null
        }.toMap()
    }
    fun change(key: ServerSetting, value: String) {
        val next = if (config.values[key] == value) changes - key else changes + (key to value)
        onDraft(next.entries.joinToString(",") { it.key.key + "=" + it.value })
    }
    var explain by rememberSaveable { mutableStateOf<ServerSetting?>(null) }
    var confirm by rememberSaveable { mutableStateOf<ServerSetting?>(null) }
    var quota by rememberSaveable { mutableStateOf(false) }
    val busy = state.isWorking || state.environment.operationActive || state.serverConfigLoading
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("실리태번이 켜질 때 읽는 설정이에요", style = MaterialTheme.typography.titleLarge)
            Text("SillyTavern " + config.version.ifBlank { "—" } + " · config.yaml",
                fontFamily = V2Mono, style = MaterialTheme.typography.bodySmall)
            ServerSetting.entries.filter { it in config.values }.groupBy { it.group }.forEach { (group, keys) ->
                SettingsHeading(group)
                SettingsGroup {
                    keys.forEachIndexed { index, key ->
                        val value = changes[key] ?: config.values.getValue(key)
                        SettingsRow(key.title, caption = key.hint, enabled = !busy, changed = key in changes,
                            checked = if (key == ServerSetting.COPIES) null else value == "true",
                            value = if (key == ServerSetting.COPIES) copiesLabel(value) else "",
                            details = { explain = key }) {
                            when {
                                key == ServerSetting.COPIES -> quota = true
                                value != "true" && (key == ServerSetting.PLUGINS || key == ServerSetting.CORS && state.environment.externalAccessEnabled) -> confirm = key
                                else -> change(key,(value != "true").toString())
                            }
                        }
                        if (index < keys.lastIndex) SettingsDivider()
                    }
                }
            }
            SettingsCaption("바꾸기 전 설정은 자동으로 보관해요. 서버를 다시 시작하면 적용돼요.")
            val hidden = ServerSetting.entries.size - config.values.size
            if (hidden > 0) SettingsCaption("이 버전에서 지원하지 않는 항목 ${hidden}개는 숨겼어요.")
        }
        V2SheetPrimaryButton(if (state.environment.processRunning) "저장하고 재시작" else "저장", changes.isNotEmpty() && !busy) {
            onSave(config.revision,changes)
        }
    }
    explain?.let { key ->
        V2BottomSheet(onClose = { explain = null }, footer = { V2SheetPrimaryButton("확인") { explain = null } }) {
            Text(key.title, style = MaterialTheme.typography.titleLarge); Text(key.help)
        }
    }
    confirm?.let { key ->
        V2AlertDialog(onDismissRequest = { confirm = null }, title = { Text(if (key == ServerSetting.PLUGINS) "서버 플러그인을 켤까요?" else "CORS 프록시를 켤까요?") },
            text = { Text(key.help) }, dismissButton = { V2DialogAction(onClick = { confirm = null }, tone = V2DialogTone.Quiet) { Text("취소") } },
            confirmButton = { V2DialogAction(onClick = { change(key,"true"); confirm = null }) { Text("켜기") } })
    }
    if (quota) {
        var usage by remember { mutableStateOf("지금 사본 수와 용량을 확인하고 있어요.") }
        LaunchedEffect(Unit) { usage = getUsage()?.let { (count, bytes, users) -> buildString {
            append("지금 사본 ${count}개 · ${formatBackupBytes(bytes)}")
            if (users.size > 1) users.forEach { (user, n, size) -> append("\n$user · ${n}개 · ${formatBackupBytes(size)}") }
        } }
            ?: "지금 사본 수와 용량을 확인할 수 없어요." }
        V2BottomSheet(onClose = { quota = false }) {
            Text("채팅 자동 사본", style = MaterialTheme.typography.titleLarge)
            listOf("50","100","200","-1").forEach { n -> SettingsRadioRow(copiesLabel(n),
                (changes[ServerSetting.COPIES] ?: config.values[ServerSetting.COPIES]) == n) { change(ServerSetting.COPIES,n); quota = false } }
            SettingsCaption(usage)
        }
    }
}
