package app.tavernbridge.launcher.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
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

@Composable
internal fun V2DisplaySettings(state: LauncherUiState, onTheme: (AppTheme) -> Unit, onDisplay: (DisplayPreferences) -> Unit, wide: Boolean) {
    val prefs = state.displayPreferences
    val colors = MaterialTheme.colorScheme
    val reduced = LocalV2ReducedMotion.current
    var info by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        SettingsHeading("글자 크기")
        IconButton(onClick = { info = true }) { Icon(Icons.Outlined.Info, "글자 크기 설명", Modifier.size(18.dp)) }
    }
    val preview: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().border(1.dp, colors.outlineVariant, RoundedCornerShape(14.dp)).padding(12.dp)
            .clearAndSetSemantics { contentDescription = "글자 크기 미리보기: 대화 열기 · 마지막 백업 3일 전" },
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("미리보기", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            // Deliberately not clickable: this is a sample of a primary action, not a second server control.
            Surface(color = colors.primary, contentColor = colors.onPrimary, shape = RoundedCornerShape(14.dp)) {
                Text("대화 열기", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
            }
            Text("마지막 백업 3일 전", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
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
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsHeading("강조색")
            Row(Modifier.selectableGroup()) {
                LauncherAccent.entries.forEach { accent ->
                    val chosen = prefs.accent == accent
                    Box(Modifier.size(48.dp)
                        .selectable(chosen, role = Role.RadioButton) { onDisplay(prefs.copy(accent = accent)) }
                        .semantics { contentDescription = accent.label }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(32.dp).then(if (chosen) Modifier.border(2.dp, colors.secondary, CircleShape) else Modifier)
                            .padding(4.dp).background(Color(if (colors.surface.luminance() < .45f) accent.darkArgb else accent.lightArgb), CircleShape))
                    }
                }
            }
        }
    }
    if (wide) Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { themes() }
        Box(Modifier.weight(1f)) { accents() }
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
    SettingsGroup { SettingsRow("백업 알림 받기", caption = "마지막 백업 후 시간이 지나면 앱에서 알려줘요",
        checked = reminder.enabled, onClick = { vm.setBackupReminderEnabled(!reminder.enabled) }) }
    SettingsHeading("알림 주기")
    SettingsSegments(BackupInterval.entries, reminder.interval, { it.label }, vm::setBackupReminderInterval, enabled = reminder.enabled)
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
    if (secretsConfirm) AlertDialog(onDismissRequest = { secretsConfirm = false }, title = { Text("수동 백업에 API 키를 포함할까요?") },
        text = { Text("사용자 데이터 또는 전체 설치를 직접 백업하면 비밀 설정이 암호화되지 않은 ZIP에 포함될 수 있어요. 백업할 때마다 변경할 수 있어요.") },
        confirmButton = { TextButton(onClick = { secretsConfirm = false; vm.setDisplayPreferences(prefs.copy(includeSecretsByDefault = true)) }) { Text("포함") } },
        dismissButton = { TextButton(onClick = { secretsConfirm = false }) { Text("취소") } })
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
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "외부 접속 설명") {
        explain(blocked ?: "같은 Wi-Fi의 다른 기기에서 접속하려면 허용을 켜고 기기 주소를 등록해 주세요. 실행 중인 서버는 설정을 저장할 때 잠시 멈췄다가 다시 시작해요.")
    }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("외부 접속", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Icon(Icons.Outlined.Info, "외부 접속 설명")
    }
    SettingsGroup {
        SettingsRow("같은 Wi-Fi에서 접속 허용", caption = blocked ?: "허용한 기기만 들어올 수 있어요", checked = env.externalAccessEnabled,
            onClick = { edit("toggle-listen") })
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
    }
    SettingsCaption(if (env.processRunning) "서버를 미리 끌 필요는 없어요. 저장하면 잠시 멈췄다가 다시 시작해요."
        else "바꾼 내용은 다음 서버 시작부터 적용돼요.")
}

@Composable
internal fun V2ServerSettings(state: LauncherUiState, vm: LauncherViewModel) {
    val env = state.environment
    var dialog by rememberSaveable { mutableStateOf("") }
    var browserRevision by remember { mutableIntStateOf(0) }
    val preferred = remember(vm, env.port, browserRevision) { vm.preferredBrowser() }
    var port by rememberSaveable(state.configuredPort) { mutableStateOf(state.configuredPort.toString()) }
    val clipboard = LocalClipboardManager.current
    val enabled = state.environmentChecked && env.sillyTavernInstalled && !state.isWorking
    SettingsHeading("이 기기")
    SettingsGroup {
        SettingsRow("주소", "127.0.0.1:${state.configuredPort}", mono = true)
        SettingsDivider()
        SettingsRow("포트", state.configuredPort.toString(), mono = true, enabled = enabled, onClick = { dialog = "port" })
    }
    var lanReason by rememberSaveable { mutableStateOf<String?>(null) }
    ServerLanRows(state, { dialog = it }, { vm.refreshLanAddresses(); dialog = "address" }, { lanReason = it })
    lanReason?.let { reason -> V2Explanation("외부 접속", reason) { lanReason = null } }
    SettingsGroup {
        SettingsRow("열 앱 선택", preferred?.label ?: "선택 안 함", onClick = { browserRevision++; dialog = "browser" })
        SettingsDivider()
        SettingsRow("시작 후 브라우저 자동 열기", checked = state.autoOpenBrowser, onClick = { vm.setAutoOpenBrowser(!state.autoOpenBrowser) })
    }
    if (dialog in listOf("lan", "toggle-listen", "toggle-auth")) LANSecuritySettingsDialog(
        LanSecuritySnapshot(env.externalAccessEnabled, env.whitelist, env.basicAuthEnabled, env.perUserBasicAuth),
        state.isWorking || env.operationActive, { dialog = "" }, { change -> vm.saveLanSecurity(change).also { if (it) dialog = "" } },
        requestedListen = if (dialog == "toggle-listen") !env.externalAccessEnabled else env.externalAccessEnabled,
        requestedBasicAuth = if (dialog == "toggle-auth") !env.basicAuthEnabled else env.basicAuthEnabled)
    if (dialog == "address") AlertDialog(onDismissRequest = { dialog = "" }, title = { Text("같은 Wi-Fi 접속 주소") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { LanConnectionCard(env.externalAccessEnabled, state.lanEndpoints,
            vm::refreshLanAddresses, { dialog = "lan" }, { clipboard.setText(AnnotatedString(it)); vm.showMessage("주소를 복사했어요.") }) } },
        confirmButton = { TextButton(onClick = { dialog = "" }) { Text("닫기") } })
    if (dialog == "browser") {
        val choices by produceState<List<BrowserOption>>(emptyList(), vm, env.port) {
            value = withContext(Dispatchers.IO) { vm.browserOptions() }
        }
        val selected = preferred?.packageName
        V2BottomSheet(onClose = { dialog = "" }) {
            Text("열 앱 선택", style = MaterialTheme.typography.titleLarge)
            SettingsRow("열 때마다 선택", if (selected == null) "선택됨" else "", onClick = { vm.clearPreferredBrowser(); browserRevision++; dialog = "" })
            choices.forEach { option ->
                SettingsDivider()
                SettingsRow(option.label, if (selected == option.packageName) "선택됨" else "", onClick = {
                    vm.savePreferredBrowser(option.packageName); browserRevision++; dialog = ""
                })
            }
        }
    }
    if (dialog == "port") {
        val validPort = port.toIntOrNull()?.takeIf { it in 1024..65535 && it != state.configuredPort }
        AlertDialog(onDismissRequest = { dialog = "" }, title = { Text("포트 번호") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(port, { if (it.length <= 5 && it.all(Char::isDigit)) port = it }, singleLine = true,
                    label = { Text("1024–65535") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text(if (env.processRunning) "저장하면 실행 중인 서버를 잠시 멈추고 다시 시작해요." else "다음 서버 시작부터 적용해요.")
            }
        }, confirmButton = { TextButton(enabled = enabled && validPort != null, onClick = {
            validPort?.let { vm.saveServerConnection(it, env.externalAccessEnabled, env.whitelist.joinToString("\n")) }; dialog = ""
        }) { Text(if (env.processRunning) "저장 후 재시작" else "저장") } },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text("취소") } })
    }
}

internal fun needsBackgroundAttention(state: LauncherUiState, sdk: Int) =
    !state.backgroundStatus.termuxBatteryUnrestricted || !state.backgroundStatus.wakeLockEnabled ||
        state.backupStorageReady != true || TermuxProcessHelp.includesAndroid12ProcessLimit(sdk)

@Composable
internal fun V2BackgroundSettings(state: LauncherUiState, vm: LauncherViewModel) {
    val bg = state.backgroundStatus
    var help by rememberSaveable { mutableStateOf(false) }
    val tokens = LocalLauncherColors.current
    val checks = listOf(bg.termuxBatteryUnrestricted, bg.wakeLockEnabled, state.backupStorageReady == true)
    val processLimit = TermuxProcessHelp.includesAndroid12ProcessLimit(Build.VERSION.SDK_INT)
    listOf(false, true).forEach { normal ->
        val rows = checks.indices.filter { checks[it] == normal }
        if (rows.isNotEmpty() || (!normal && processLimit)) {
            SettingsHeading(if (normal) "정상" else "확인이 필요해요")
            SettingsGroup {
                rows.forEachIndexed { index, row ->
                    if (index != 0) SettingsDivider()
                    val tint = if (normal) tokens.live else tokens.accent
                    when (row) {
                        0 -> SettingsRow("배터리 사용 제한 해제", if (normal) "정상" else "확인 필요", caption = "Termux가 백그라운드에서 멈추지 않게 해요",
                            valueColor = tint, onClick = vm::openDeviceBatterySettings)
                        1 -> SettingsRow("화면이 꺼져도 계속 실행", if (normal) "정상" else "꺼짐", checked = bg.wakeLockEnabled,
                            caption = "서버 실행 중 CPU·Wi-Fi 절전을 막아요", valueColor = tint, onClick = { vm.setWakeLockEnabled(!bg.wakeLockEnabled) })
                        else -> SettingsRow("저장소 접근", when (state.backupStorageReady) { true -> "정상"; false -> "연결 필요"; null -> "확인 전" },
                            caption = "백업을 Download 폴더에 저장해요", valueColor = tint, onClick = vm::openBackups)
                    }
                }
                if (!normal && processLimit) {
                    if (rows.isNotEmpty()) SettingsDivider()
                    SettingsRow("하위 프로세스 제한", "확인 필요", caption = "Android 12 이상에서 긴 작업이 중간에 멈출 수 있어요",
                        valueColor = tokens.accent, onClick = { help = true })
                }
            }
        }
    }
    if (help) {
        val uri = LocalUriHandler.current
        V2BottomSheet(onClose = { help = false }) {
                Text("백그라운드 실행 도움말", style = MaterialTheme.typography.titleLarge)
                Text(TermuxProcessHelp.explanation(Build.VERSION.SDK_INT))
                Text(if (bg.isSamsung) "배터리 설정 → 백그라운드 사용 제한 → 절전·초절전 앱에서 Termux를 제거하세요. 절전 예외에 추가하고, Termux 앱 정보 → 배터리에서 제한 없음을 선택하세요."
                    else "Termux 앱 정보에서 알림을 허용하고 배터리 제한을 확인하세요. Wake lock은 기기의 강제 종료까지 막지는 못해요.")
                TextButton(onClick = vm::openDeviceBatterySettings) { Text("배터리 설정") }
                TextButton(onClick = vm::openTermuxAppSettings) { Text("Termux 앱 정보") }
                TextButton(onClick = { runCatching { uri.openUri(TermuxProcessHelp.OFFICIAL_HELP_URL) }.onFailure { vm.showMessage("링크를 열 수 없어요.") } }) { Text("Termux 공식 안내") }
                Button(onClick = { help = false }) { Text("확인") }
        }
    }
}

@Composable
internal fun V2AboutSettings(state: LauncherUiState, vm: LauncherViewModel) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    var dialog by rememberSaveable { mutableStateOf("") }
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
        SettingsRow(if (state.diagnosticReport == null) "진단 보고서 만들기" else "진단 보고서 복사", enabled = !state.isWorking, onClick = {
            val report = state.diagnosticReport
            if (report != null) { clipboard.setText(AnnotatedString(report.copyText())); vm.showMessage("진단 보고서를 복사했어요.") }
            else { vm.openDiagnostics(DiagnosticPanel.OVERVIEW); vm.runDiagnostics() }
        })
        SettingsDivider()
        SettingsRow("GitHub 저장소", onClick = { runCatching { uri.openUri("https://github.com/p16481012/ST-Launcher") }.onFailure { vm.showMessage("저장소 링크를 열 수 없어요.") } })
        SettingsDivider()
        SettingsRow("오픈소스 라이선스", onClick = { dialog = "licenses" })
    }
    SettingsGroup {
        SettingsRow("처음 설정 · 설치 확인", onClick = { vm.selectSection(MainSection.SETUP) })
        SettingsDivider()
        SettingsRow("업데이트 정보", onClick = { dialog = "update" })
        SettingsDivider()
        SettingsRow("바로가기 · 앱 관리", onClick = { dialog = "advanced" })
    }
    if (dialog == "update" || dialog == "advanced") AlertDialog(onDismissRequest = { dialog = "" },
        title = { Text(if (dialog == "update") "업데이트 정보" else "바로가기 · 앱 관리") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (dialog == "update") UpdateInformationCard(state, vm::checkUpdateInformation) else {
                LauncherEntrySettingsCard()
                SettingsRow("SillyTavern 폴더 관리", enabled = state.environment.sillyTavernInstalled, onClick = { dialog = ""; vm.openSillyTavernFolder() })
                SettingsRow("확장 프로그램 관리", enabled = state.environment.sillyTavernInstalled && !state.isWorking,
                    onClick = { dialog = ""; vm.selectTool(app.tavernbridge.launcher.model.ToolPage.EXTENSIONS) })
                SettingsRow("런처 앱 상세 설정 열기", onClick = vm::openLauncherAppSettings)
            }
        } }, confirmButton = { TextButton(onClick = { dialog = "" }) { Text("닫기") } })
    if (dialog == "licenses") LicenseDialog { dialog = "" }
    SettingsCaption("깡통 커뮤니티에서 만든 비공식 런처예요. SillyTavern·Termux 공식 프로젝트와는 관련이 없어요.")
}

@Composable
private fun LicenseDialog(close: () -> Unit) {
    val assets = LocalContext.current.assets
    val names = remember(assets) { assets.list("licenses").orEmpty().filter { it.endsWith(".txt") }.sorted() }
    fun label(file: String) = if (file == "Android-runtime-libraries.txt") "AndroidX · Kotlin · 런타임 라이브러리" else file.removeSuffix(".txt")
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val body by produceState("", selected) {
        val name = selected
        value = if (name == null || name !in names) "" else withContext(Dispatchers.IO) {
            runCatching { assets.open("licenses/$name").bufferedReader().use { it.readText() } }.getOrDefault("라이선스를 열 수 없어요.")
        }
    }
    V2BottomSheet(onClose = close) {
        Text(selected?.let(::label) ?: "오픈소스 라이선스", style = MaterialTheme.typography.titleLarge)
        if (selected == null) names.forEach { name -> SettingsRow(label(name), onClick = { selected = name }) }
        else {
            TextButton(onClick = { selected = null }) { Text("목록") }
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = close) { Text("닫기") }
    }
}

@Composable
private fun SettingsInfo(title: String, body: String, close: () -> Unit) {
    V2Explanation(title, body, close)
}

@Composable
private fun <T> SettingsChoice(title: String, choices: List<T>, selected: T, label: (T) -> String, select: (T) -> Unit,
    hint: String = "", close: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
            if (hint.isNotEmpty()) SettingsCaption(hint)
            choices.forEach { item -> Row(Modifier.fillMaxWidth().selectable(item == selected, role = Role.RadioButton) { select(item) }
                .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RadioButton(selected = item == selected, onClick = null); Text(label(item))
            } }
        } }, confirmButton = { TextButton(onClick = close) { Text("닫기") } })
}
