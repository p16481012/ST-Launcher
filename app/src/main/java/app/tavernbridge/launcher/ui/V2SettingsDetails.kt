package app.tavernbridge.launcher.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.BuildConfig
import app.tavernbridge.launcher.LauncherViewModel
import app.tavernbridge.launcher.R
import app.tavernbridge.launcher.data.TermuxInstallationInspector
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.theme.LocalV2ReducedMotion
import app.tavernbridge.launcher.ui.theme.LocalLauncherColors
import app.tavernbridge.launcher.ui.theme.V2Mono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun V2DisplaySettings(state: LauncherUiState, onTheme: (AppTheme) -> Unit, onDisplay: (DisplayPreferences) -> Unit, wide: Boolean) {
    val prefs = state.displayPreferences
    val colors = MaterialTheme.colorScheme
    val reduced = LocalV2ReducedMotion.current
    var info by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        SettingsHeading("글자 크기")
        IconButton(onClick = { info = true }) { ReviewGlyph(R.drawable.rv_info, 18.dp, description = "글자 크기 설명") }
    }
    val preview: @Composable () -> Unit = {
        AppearanceTextPreview()
    }
    val sizes: @Composable () -> Unit = { SettingsSegments(LauncherTextSize.entries, prefs.textSize, { it.label },
        { onDisplay(prefs.copy(textSize = it)) }) }
    if (wide) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.weight(1f)) { sizes() }
        Box(Modifier.weight(.85f)) { preview() }
    } else {
        sizes()
        preview()
    }
    val themes: @Composable () -> Unit = {
        SettingsHeading("테마")
        SettingsSegments(listOf(AppTheme.SYSTEM, AppTheme.LIGHT, AppTheme.DARK), state.theme, ::settingsThemeName, onTheme)
    }
    val accents: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("강조색", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            FlowRow(Modifier.weight(1f).selectableGroup(), horizontalArrangement = Arrangement.SpaceAround) {
                LauncherAccent.entries.forEach { accent ->
                    val chosen = prefs.accent == accent
                    val source = remember { MutableInteractionSource() }
                    val pressed by source.collectIsPressedAsState()
                    Box(Modifier.widthIn(min = 38.dp).height(48.dp)
                        .selectable(chosen, interactionSource = source, indication = null, role = Role.RadioButton) { onDisplay(prefs.copy(accent = accent)) }
                        .semantics { contentDescription = accent.label }, contentAlignment = Alignment.Center) {
                        if (pressed) Box(Modifier.size(44.dp).background(colors.onSurface.copy(alpha = .08f), CircleShape))
                        Box(Modifier.size(32.dp).then(if (chosen) Modifier.border(1.5.dp, colors.onSurface, CircleShape) else Modifier)
                            .padding(4.dp).background(Color(if (colors.surface.luminance() < .45f) accent.darkArgb else accent.lightArgb), CircleShape))
                    }
                }
            }
        }
    }
    if (wide) BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Use the actual detail pane, not the device width: six swatches must fit beside their label.
        if (maxWidth >= 660.dp) Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { themes() }
            Box(Modifier.weight(1f)) { accents() }
        } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { themes(); accents() }
    } else { themes(); accents() }
    SettingsHeading("읽기 편의")
    SettingsGroup {
        SettingsRow("고대비", checked = prefs.highContrast, caption = "글자와 선을 더 진하게 표시해요",
            onClick = { onDisplay(prefs.copy(highContrast = !prefs.highContrast)) })
        SettingsDivider()
        SettingsRow("동작 줄이기", checked = reduced, caption = "화면 전환 효과를 최소로 해요",
            onClick = { onDisplay(prefs.copy(reducedMotion = !reduced)) })
    }
    if (info) SettingsInfo("글자 크기", "아래 문장은 글자 크기 미리보기입니다. 시스템 글자 크기에 선택한 배율을 곱하며 최대 2배까지 표시합니다.") { info = false }
}

@Composable
internal fun V2BackupPreferences(state: LauncherUiState, vm: LauncherViewModel) {
    val reminder = state.backupReminder
    val prefs = state.displayPreferences
    val cleanupTitle = stringResource(R.string.backup_cleanup_rule)
    val keepCount = prefs.keepBackups.count
    var retention by rememberSaveable { mutableStateOf(false) }
    var secretsConfirm by rememberSaveable { mutableStateOf(false) }
    SettingsGroup { SettingsRow("백업 알림 받기", caption = "백업할 때가 되면 백업 탭에 점이 뜨고 홈의 마지막 백업 날짜가 강조돼요. 휴대폰 알림은 보내지 않아요.",
        checked = reminder.enabled, onClick = { vm.setBackupReminderEnabled(!reminder.enabled) }) }
    if (reminder.enabled) {
        SettingsHeading("알림 주기")
        SettingsSegments(BackupInterval.entries, reminder.interval, { it.label }, vm::setBackupReminderInterval)
    } else SettingsCaption("백업 알림을 켜면 알림 주기를 고를 수 있어요.")
    SettingsHeading("자동 백업과 보관")
    SettingsGroup {
        SettingsRow("업데이트 전에 자동 백업", checked = state.autoBackupBeforeUpdate,
            onClick = { vm.setAutoBackupBeforeUpdate(!state.autoBackupBeforeUpdate) })
        SettingsDivider()
        SettingsRow(cleanupTitle, value = prefs.keepBackups.label,
            caption = if (keepCount == null) stringResource(R.string.backup_cleanup_unlimited)
                      else stringResource(R.string.backup_cleanup_recommendation, keepCount),
            onClick = { retention = true })
        SettingsDivider()
        SettingsRow("API 키 포함", caption = "직접 만드는 백업의 기본 선택이에요", checked = prefs.includeSecretsByDefault,
            onClick = { if (prefs.includeSecretsByDefault) vm.setDisplayPreferences(prefs.copy(includeSecretsByDefault = false)) else secretsConfirm = true })
    }
    if (prefs.includeSecretsByDefault) SettingsCaption("API 키를 포함한 백업은 다른 사람에게 보내지 마세요.")
    if (retention) SettingsChoice(cleanupTitle, BackupKeep.entries, prefs.keepBackups, { it.label },
        { vm.setDisplayPreferences(prefs.copy(keepBackups = it)); retention = false },
        hint = stringResource(R.string.backup_cleanup_exempt)) { retention = false }
    if (secretsConfirm) V2AlertDialog(onDismissRequest = { secretsConfirm = false }, title = { Text("수동 백업에 API 키를 포함할까요?") },
        text = { Text("사용자 데이터 또는 전체 설치를 직접 백업하면 비밀 설정이 암호화되지 않은 ZIP에 포함될 수 있어요. 백업할 때마다 변경할 수 있어요.") },
        confirmButton = { V2DialogAction(onClick = { secretsConfirm = false; vm.setDisplayPreferences(prefs.copy(includeSecretsByDefault = true)) }) { Text("포함") } },
        dismissButton = { V2DialogAction(tone = V2DialogTone.Quiet, onClick = { secretsConfirm = false }) { Text("취소") } })
}

@Composable
internal fun ServerLanRows(state: LauncherUiState, openEditor: (String) -> Unit, openAddress: () -> Unit, explain: (String) -> Unit) {
    val env = state.environment
    val blocked = when {
        state.isWorking || env.operationActive -> "진행 중인 작업이 끝나면 외부 접속 설정을 변경할 수 있어요."
        !state.environmentChecked || !state.termuxSetup.verified || !env.managerConnected -> "홈에서 Termux 연결을 확인한 뒤 외부 접속을 설정해 주세요."
        !env.sillyTavernInstalled -> "먼저 SillyTavern을 설치해 주세요."
        else -> null
    }
    fun edit(page: String) { if (blocked == null) openEditor(page) else explain(blocked) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("외부 접속", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = {
            explain(blocked ?: "같은 Wi-Fi의 다른 기기에서 접속하려면 허용을 켜고 기기 주소를 등록해 주세요. 실행 중인 서버는 설정을 저장할 때 잠시 멈췄다가 다시 시작해요.")
        }) { Icon(Icons.Outlined.Info, "외부 접속 설명") }
    }
    SettingsGroup {
        SettingsRow("같은 Wi-Fi에서 접속 허용", caption = blocked ?: "허용한 기기만 들어올 수 있어요", checked = env.externalAccessEnabled,
            onClick = { edit(if (env.externalAccessEnabled) "disable-listen" else "toggle-listen") })
        val duration = if (LocalV2ReducedMotion.current) 0 else 200
        AnimatedVisibility(env.externalAccessEnabled, enter = expandVertically(tween(duration)), exit = shrinkVertically(tween(duration))) {
        Column {
        SettingsDivider()
        SettingsRow("접속 주소", if (!env.externalAccessEnabled) "외부 접속 꺼짐" else state.lanEndpoints.firstOrNull()?.url?.removePrefix("http://")?.removeSuffix("/") ?: "주소 확인",
            mono = true, onClick = {
                if (!env.externalAccessEnabled) explain("먼저 ‘같은 Wi-Fi에서 접속 허용’을 켜고 저장해 주세요. 그 뒤 다른 기기에서 쓸 주소를 볼 수 있어요.")
                else if (blocked != null) explain(blocked) else openAddress()
            })
        SettingsDivider()
        SettingsRow("허용한 기기", env.whitelist.filterNot { it in LanWhitelist.loopbacks }.joinToString(", ").ifBlank { "이 기기만" },
            mono = true, onClick = { edit("lan") })
        SettingsDivider()
        SettingsRow("로그인 요구", caption = if (env.perUserBasicAuth) "SillyTavern의 사용자별 인증을 사용하고 있어요" else "접속할 때 사용자 이름과 암호를 물어요", checked = env.basicAuthEnabled,
            onClick = { if (env.perUserBasicAuth) explain("사용자별 인증은 SillyTavern에서 관리해요. 런처에서는 변경하지 않아요.") else edit("toggle-auth") })
        } }
    }
    SettingsCaption(if (env.processRunning) "서버를 미리 끌 필요는 없어요. 저장하면 잠시 멈췄다가 다시 시작해요."
        else "바꾼 내용은 다음 서버 시작부터 적용돼요.")
}

@Composable
internal fun V2ServerSettings(state: LauncherUiState, vm: LauncherViewModel, openEditor: (String) -> Unit) {
    val env = state.environment
    var dialog by rememberSaveable { mutableStateOf("") }
    var browserRevision by remember { mutableIntStateOf(0) }
    val preferred = remember(vm, env.port, browserRevision) { vm.preferredBrowser() }
    LaunchedEffect(state.portEditorRequested) { if (state.portEditorRequested) dialog = "port" }
    LaunchedEffect(state.lanAddressRequested) {
        if (state.lanAddressRequested) {
            if (env.externalAccessEnabled) { vm.refreshLanAddresses(); dialog = "address" }
            vm.consumeLanAddressRequest()
        }
    }
    val clipboard = LocalClipboardManager.current
    val enabled = state.environmentChecked && env.sillyTavernInstalled && !state.isWorking
    SettingsHeading("이 기기")
    SettingsGroup {
        SettingsRow("주소", "127.0.0.1:${state.configuredPort}", mono = true)
        SettingsDivider()
        SettingsRow("포트", state.configuredPort.toString(), mono = true,
            caption = if (enabled) "" else if (state.isWorking) "진행 중인 작업이 끝나면 바꿀 수 있어요." else "Termux 연결과 설치를 확인한 뒤 바꿀 수 있어요.",
            onClick = if (enabled) ({ dialog = "port" }) else null)
    }
    var lanReason by rememberSaveable { mutableStateOf<String?>(null) }
    ServerLanRows(state, { action ->
        if (action == "disable-listen") {
            vm.saveLanSecurity(LanSecurityChange.disableExposure())
        } else openEditor(action)
    }, { vm.refreshLanAddresses(); dialog = "address" }, { lanReason = it })
    lanReason?.let { reason -> V2Explanation("외부 접속", reason) { lanReason = null } }
    SettingsGroup {
        SettingsRow("열 앱 선택", preferred?.label ?: "선택 안 함", onClick = { browserRevision++; dialog = "browser" })
        SettingsDivider()
        SettingsRow("시작 후 브라우저 자동 열기", checked = state.autoOpenBrowser, onClick = { vm.setAutoOpenBrowser(!state.autoOpenBrowser) })
    }
    if (dialog == "address") V2BottomSheet(onClose = { dialog = "" }, footer = if (env.externalAccessEnabled) ({
        V2OutlineAction("주소 새로고침", vm::refreshLanAddresses, Modifier.fillMaxWidth(), footer = true)
    }) else null) {
        Text("같은 Wi-Fi 접속 주소", style = MaterialTheme.typography.titleLarge)
        if (!env.externalAccessEnabled) Text("외부 접속이 꺼져 있어요.")
        else {
            val addresses = state.lanEndpoints.take(16)
            var chosen by rememberSaveable { mutableStateOf("") }
            val endpoint = addresses.firstOrNull { it.url == chosen } ?: addresses.firstOrNull()
            if (endpoint == null) Text(stringResource(R.string.lan_no_address))
            else {
                if (addresses.size > 1) Column(Modifier.selectableGroup()) {
                    addresses.forEach { SettingsRadioRow(it.url, it == endpoint) { chosen = it.url } }
                }
                LanAddressCopyRow(endpoint.url) { clipboard.setText(AnnotatedString(endpoint.url)); vm.showMessage("주소를 복사했어요.") }
                LanQrImage(endpoint)
            }
        }
    }
    if (dialog == "browser") {
        val choices by produceState<List<BrowserOption>>(emptyList(), vm, env.port) {
            value = withContext(Dispatchers.IO) { vm.browserOptions() }
        }
        val selected = preferred?.packageName
        V2BottomSheet(onClose = { dialog = "" }) {
            Text("열 앱 선택", style = MaterialTheme.typography.titleLarge)
            Column(Modifier.selectableGroup()) {
                SettingsRadioRow("열 때마다 선택", selected == null) { vm.clearPreferredBrowser(); browserRevision++; dialog = "" }
                choices.forEach { option ->
                    SettingsDivider()
                    SettingsRadioRow(option.label, selected == option.packageName) {
                        vm.savePreferredBrowser(option.packageName); browserRevision++; dialog = ""
                    }
                }
            }
        }
    }
    if (dialog == "port") {
        LaunchedEffect(state.portEditorRequested) { if (state.portEditorRequested) vm.consumePortEditorRequest() }
        ServerPortDialog(state.configuredPort, env.configPort, env.processRunning, enabled, { dialog = "" }) {
            vm.saveServerPort(it); dialog = ""
        }
    }
}

@Composable
internal fun ServerPortDialog(port: Int, configPort: Int?, running: Boolean, enabled: Boolean, close: () -> Unit, save: (Int) -> Unit) {
    val focus = remember { FocusRequester() }
    val haptic = LocalHapticFeedback.current
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(port.toString(), TextRange(0, port.toString().length)))
    }
    val value = field.text.toIntOrNull()?.takeIf { it in 1024..65535 }
    var focused by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(false) }
    fun validate() {
        if (value == null && !showError) { showError = true; haptic.performHapticFeedback(HapticFeedbackType.Reject) }
    }
    fun submit() {
        if (value == null) validate() else if (enabled && value != port) save(value)
    }
    V2AlertDialog(onDismissRequest = close, title = { Text("포트 번호") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val keyboard = LocalSoftwareKeyboardController.current
            OutlinedTextField(field, {
                if (it.text.length <= 8 && it.text.all { digit -> digit in '0'..'9' }) { field = it; showError = false }
            }, Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged {
                if (focused && !it.isFocused) validate()
                focused = it.isFocused
            }, singleLine = true, label = { Text("포트") }, isError = showError,
                shape = RoundedCornerShape(12.dp), colors = settingsInputColors(),
                supportingText = if (showError) { { Text("1024–65535 사이로 입력해 주세요.") } } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }))
            if (value != null && configPort != null && configPort != value)
                Text("config.yaml은 ${configPort}이에요. 런처로 시작하면 이 포트를 써요.", style = MaterialTheme.typography.bodySmall)
            Text(if (running) "저장하면 실행 중인 서버를 잠시 멈추고 다시 시작해요." else "다음 서버 시작부터 적용해요.")
            LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
        } },
        dismissButton = { V2DialogAction(tone = V2DialogTone.Quiet, onClick = close) { Text("취소") } },
        confirmButton = { V2DialogAction(enabled = enabled && value != null && value != port, onClick = ::submit) {
            Text(if (running) "저장하고 재시작" else "저장")
        } },
    )
}

@Composable
internal fun settingsInputColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.onSurface, unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    cursorColor = MaterialTheme.colorScheme.secondary, focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface)

@Composable
internal fun V2BackgroundSettings(state: LauncherUiState, vm: LauncherViewModel) {
    val bg = state.backgroundStatus
    var help by rememberSaveable { mutableStateOf(false) }
    val tokens = LocalLauncherColors.current
    val installed = state.environment.termuxInstalled
    val battery = bg.termuxBatteryUnrestricted.takeIf { installed }
    val storage = state.backupStorageReady.takeIf { installed }
    val pending = if (!installed && state.environmentChecked) "Termux 미설치" else "확인 전"
    SettingsHeading("실행 설정")
    SettingsGroup {
        SettingsRow("배터리 사용 제한 해제", when (battery) { true -> "정상"; false -> "최적화 대상"; null -> pending },
            caption = if (installed) "Android 설정 앱이 열려요" else "Termux를 설치하면 설정할 수 있어요.",
            valueColor = when (battery) { true -> tokens.live; false -> tokens.accent; null -> tokens.ink2 },
            onClick = if (installed) vm::openDeviceBatterySettings else null)
        SettingsDivider()
        SettingsRow("화면이 꺼져도 계속 실행", if (bg.wakeLockEnabled) "켜짐" else "꺼짐", checked = bg.wakeLockEnabled.takeIf { installed },
            caption = if (installed) "필요할 때 켜세요. 서버 실행 중 절전을 막아요." else "Termux를 설치하면 설정할 수 있어요.",
            onClick = if (installed) ({ vm.setWakeLockEnabled(!bg.wakeLockEnabled) }) else null)
        SettingsDivider()
        SettingsRow("저장소 접근", when (storage) { true -> "정상"; false -> "연결 필요"; null -> pending },
            caption = "백업을 Download 폴더에 저장해요",
            valueColor = when (storage) { true -> tokens.live; false -> tokens.accent; null -> tokens.ink2 },
            onClick = vm::openBackups)
    }
    if (TermuxProcessHelp.includesAndroid12ProcessLimit(Build.VERSION.SDK_INT)) {
        SettingsHeading("도움말")
        SettingsGroup {
            SettingsRow("하위 프로세스 제한", "안내", caption = "작업이 반복해서 강제 종료될 때 참고하세요.",
                onClick = { help = true })
        }
    }
    if (help) {
        val uri = LocalUriHandler.current
        V2BottomSheet(onClose = { help = false }) {
                Text("백그라운드 실행 도움말", style = MaterialTheme.typography.titleLarge)
                Text("Android 12 이상에 표시되는 안내예요. 런처가 이 기기의 실제 하위 프로세스 제한 설정을 검사한 결과는 아니에요.")
                Text(TermuxProcessHelp.explanation(Build.VERSION.SDK_INT))
                Text(if (bg.isSamsung) "배터리 설정 → 백그라운드 사용 제한 → 절전·초절전 앱에서 Termux를 제거하세요. 절전 예외에 추가하고, Termux 앱 정보 → 배터리에서 제한 없음을 선택하세요."
                    else "Termux 앱 정보에서 알림을 허용하고 배터리 제한을 확인하세요. Wake lock은 기기의 강제 종료까지 막지는 못해요.")
                V2NavigationGroup {
                    V2NavigationRow("배터리 설정", vm::openDeviceBatterySettings, description = "Android 설정 앱이 열려요")
                    V2NavigationRow("Termux 앱 정보", vm::openTermuxAppSettings, description = "Android 설정 앱이 열려요")
                    V2NavigationRow("Termux 공식 안내", { runCatching { uri.openUri(TermuxProcessHelp.OFFICIAL_HELP_URL) }.onFailure { vm.showMessage("링크를 열 수 없어요.") } },
                        description = "브라우저 앱이 열려요")
                }
                Button(onClick = { help = false }) { Text("확인") }
        }
    }
}

@Composable
internal fun V2AboutSettings(state: LauncherUiState, vm: LauncherViewModel, openDetail: (String) -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val termux by produceState<TermuxInstallationInfo?>(null, state.environment.termuxInstalled) {
        value = withContext(Dispatchers.IO) { TermuxInstallationInspector(context.applicationContext).inspect() }
    }
    val update = state.launcherUpdateInfo
    Text("ST-Launcher", style = MaterialTheme.typography.titleMedium)
    Text(listOfNotNull(BuildConfig.VERSION_NAME, when (update?.newerVersionAvailable) { true -> "새 버전 있음"; false -> "최신 버전"; null -> null }).joinToString(" · "),
        fontFamily = V2Mono, style = MaterialTheme.typography.bodyMedium)
    SettingsHeading("환경")
    SettingsGroup {
        val info = termux
        SettingsRow("Termux", if (info?.installed == true) {
            val source = when (info.installerSource) {
                TermuxInstallerSource.F_DROID -> "F-Droid"
                TermuxInstallerSource.GOOGLE_PLAY -> "Google Play"
                TermuxInstallerSource.UNKNOWN -> "설치 출처 미확인"
            }
            listOfNotNull(info.versionName, source).joinToString(" · ")
        } else if (info?.installed == false) "설치 안 됨" else "확인 전", mono = true)
        SettingsDivider()
        SettingsRow("Node.js", state.environment.nodeVersion.ifBlank { "확인 전" }, mono = true)
        SettingsDivider()
        SettingsRow("SillyTavern", listOfNotNull(state.environment.sillyTavernVersion.takeIf(String::isNotBlank), state.environment.branch?.value).joinToString(" · ").ifBlank { "확인 전" }, mono = true)
    }
    SettingsHeading("지원")
    SettingsGroup {
        val canOpenReport = !state.isWorking || state.runningSettingsDiagnostic || state.diagnosticReport != null
        SettingsRow(if (state.diagnosticReport == null && !state.runningSettingsDiagnostic) "진단 보고서 만들기" else "진단 보고서 보기",
            caption = if (canOpenReport) "" else "진행 중인 작업이 끝나면 보고서를 만들 수 있어요.",
            onClick = if (canOpenReport) ({
                openDetail(ABOUT_REPORT)
                if (state.diagnosticReport == null && !state.isWorking) vm.makeSettingsReport()
            }) else null)
        SettingsDivider()
        SettingsRow("GitHub 저장소", caption = "브라우저 앱이 열려요", onClick = { runCatching { uri.openUri("https://github.com/p16481012/ST-Launcher") }.onFailure { vm.showMessage("저장소 링크를 열 수 없어요.") } })
        SettingsDivider()
        SettingsRow("오픈소스 라이선스", onClick = { openDetail(ABOUT_LICENSES) })
    }
    SettingsGroup {
        val installed = state.environment.sillyTavernInstalled && state.termuxSetup.verified && state.environment.managerConnected
        SettingsRow(if (installed) "설치 관리 열기" else "처음 설정 · 설치 확인",
            caption = if (installed) "버전·설치 위치를 보거나 기존 폴더 가져오기·브랜치 변경·점검을 할 수 있어요." else "Termux 연결과 SillyTavern 설치 상태를 확인해요.",
            onClick = { vm.openInstallationFromAbout(installed) })
        SettingsDivider()
        SettingsRow("업데이트 정보", onClick = { openDetail(ABOUT_UPDATE) })
        SettingsDivider()
        SettingsRow("바로가기 · 앱 관리", onClick = { openDetail(ABOUT_MANAGEMENT) })
    }
    SettingsCaption("깡통 커뮤니티에서 만든 비공식 런처예요.\nSillyTavern·Termux 공식 프로젝트와는 관련이 없어요.")
}

@Composable
internal fun V2AppManagementSettings(state: LauncherUiState, vm: LauncherViewModel) {
    LauncherEntrySettings()
    SettingsHeading("앱 관리")
    SettingsGroup {
        if (state.environment.sillyTavernInstalled) {
            SettingsRow("SillyTavern 폴더 관리", onClick = vm::openSillyTavernFolder)
            SettingsDivider()
            if (!state.isWorking) {
                SettingsRow("확장 프로그램 관리", onClick = { vm.selectTool(app.tavernbridge.launcher.model.ToolPage.EXTENSIONS) })
                SettingsDivider()
            }
        }
        SettingsRow("런처 앱 상세 설정 열기", caption = "Android 설정 앱이 열려요", onClick = vm::openLauncherAppSettings)
    }
    if (!state.environment.sillyTavernInstalled) SettingsCaption("폴더와 확장 프로그램은 SillyTavern 설치 후에 열 수 있어요.")
    else if (state.isWorking) SettingsCaption("진행 중인 작업이 끝나면 확장 프로그램을 관리할 수 있어요.")
}

internal fun licenseLabel(file: String) = if (file == "Android-runtime-libraries.txt") "AndroidX · Kotlin · 런타임 라이브러리" else file.removeSuffix(".txt")

@Composable
internal fun V2LicenseContent(selected: String?, openFile: (String) -> Unit) {
    val assets = LocalContext.current.assets
    val names = remember(assets) { assets.list("licenses").orEmpty().filter { it.endsWith(".txt") }.sorted() }
    val body by produceState("", selected) {
        val name = selected
        value = if (name == null || name !in names) "" else withContext(Dispatchers.IO) {
            runCatching { assets.open("licenses/$name").bufferedReader().use { it.readText() } }.getOrDefault("라이선스를 열 수 없어요.")
        }
    }
    if (selected == null) {
        SettingsGroup {
            names.forEachIndexed { index, name ->
                if (index > 0) SettingsDivider()
                SettingsRow(licenseLabel(name), onClick = { openFile(name) })
            }
        }
    } else Text(body, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun SettingsInfo(title: String, body: String, close: () -> Unit) {
    V2Explanation(title, body, onDismiss = close)
}

@Composable
internal fun SettingsRadioRow(label: String, chosen: Boolean, select: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(chosen, role = Role.RadioButton, onClick = select).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RadioButton(selected = chosen, onClick = null)
        Text(label)
    }
}

@Composable
private fun <T> SettingsChoice(title: String, choices: List<T>, selected: T, label: (T) -> String, select: (T) -> Unit,
    hint: String = "", close: () -> Unit) {
    V2AlertDialog(onDismissRequest = close, title = { Text(title) }, text = {
        Column(Modifier.selectableGroup()) {
            if (hint.isNotEmpty()) SettingsCaption(hint)
            choices.forEach { item -> SettingsRadioRow(label(item), item == selected) { select(item) } }
        } }, confirmButton = { V2DialogAction(onClick = close) { Text("닫기") } })
}
