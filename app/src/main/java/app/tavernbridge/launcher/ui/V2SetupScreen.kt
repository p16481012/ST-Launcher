package app.tavernbridge.launcher.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalDensity
import app.tavernbridge.launcher.ui.theme.LocalV2ReducedMotion
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.model.LauncherUiState
import app.tavernbridge.launcher.model.SillyBranch
import app.tavernbridge.launcher.model.TermuxSetupStatus
import app.tavernbridge.launcher.model.setupEligibleStep
import app.tavernbridge.launcher.ui.theme.V2Mono

private val setupLabels = listOf("Termux 설치", "실행 권한 허용", "외부 명령 허용", "연결 확인", "SillyTavern 설치")

/** Presentation only: skipping or revisiting a page never grants a permission or starts a probe. */
@Composable
internal fun SetupScreen(
    state: LauncherUiState,
    setupCommand: String,
    onDownloadTermux: () -> Unit,
    onOpenTermux: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenPermissionSettings: () -> Unit,
    onVerifyTermux: () -> Unit,
    onConfirmFirstConnection: () -> Unit,
    onConnect: () -> Unit,
    onSelectBranch: (SillyBranch) -> Unit,
    onInstall: () -> Unit,
    onSwitchBranch: (SillyBranch) -> Unit,
    onBackup: () -> Unit,
    onCopied: () -> Unit,
    onPickInstallation: () -> Unit,
    onInspectInstallation: (String) -> Unit,
    onImportBackup: () -> Unit,
    onLeave: () -> Unit,
    onOpenSetupTermux: () -> Unit = onOpenTermux,
) {
    val env = state.environment
    val eligible = setupEligibleStep(env, state.termuxSetup)
    var requested by rememberSaveable { mutableIntStateOf(eligible) }
    LaunchedEffect(eligible) {
        if (requested == 3 && eligible >= 4) kotlinx.coroutines.delay(1_000)
        requested = eligible
    }
    val step = requested.coerceIn(1, eligible)
    val done = step == 5 && eligible == 5 && env.sillyTavernInstalled
    val ready = !state.isWorking && state.termuxSetup.status != TermuxSetupStatus.CHECKING
    var showFullCommand by rememberSaveable { mutableStateOf(false) }
    var copied by remember(setupCommand) { mutableStateOf(false) }
    var copySequence by remember { mutableIntStateOf(0) }
    LaunchedEffect(copySequence) { if (copySequence > 0) { kotlinx.coroutines.delay(3_000); copied = false } }
    var showImport by rememberSaveable { mutableStateOf(false) }
    var existingActions by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val copy = { clipboard.setText(AnnotatedString(setupCommand)); copied = true; copySequence++; onCopied() }
    val canInstall = eligible == 5 && !env.processRunning && ready
    val importPage = showImport && step == 5 && !done
    V2BackHandler(enabled = importPage || ready && step > 1) {
        if (importPage) showImport = false else requested = step - 1
    }
    if (importPage) {
        SetupImportPage(canInstall, { showImport = false }, onPickInstallation, onInspectInstallation, onImportBackup)
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val minimumHeight = maxHeight
        val reduced = LocalV2ReducedMotion.current
        val distance = with(LocalDensity.current) { 24.dp.roundToPx() }
        AnimatedContent(step, transitionSpec = {
            val forward = targetState > initialState
            val ms = if (reduced) 0 else if (forward) 220 else 180
            (fadeIn(tween(ms)) + slideInHorizontally(tween(ms)) { if (!reduced && forward) distance else 0 }) togetherWith
                (fadeOut(tween(ms)) + slideOutHorizontally(tween(ms)) { if (!reduced && !forward) distance else 0 })
        }, label = "설정 단계") { step ->
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .heightIn(min = minimumHeight).padding(horizontal = 24.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (step > 1 && ready) IconButton(onClick = { requested = step - 1 }) {
                        Icon(Icons.Outlined.ArrowBack, "이전으로")
                    }
                    Row(Modifier.weight(1f).semantics { contentDescription = "처음 설정, 5단계 중 ${step}단계" },
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(5) { index ->
                            Surface(Modifier.weight(1f).height(3.dp), shape = CircleShape,
                                color = if (index < (if (step == 3 && state.termuxSetup.verified) 4 else step))
                                    MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant) {}
                        }
                    }
                    if (step < 5 && ready) V2TopBarAction("건너뛰기", onLeave)
                    if (done && ready) Box {
                        IconButton(onClick = { existingActions = true }) {
                            Icon(Icons.Outlined.MoreHoriz, "기존 설치 작업")
                        }
                        DropdownMenu(expanded = existingActions, onDismissRequest = { existingActions = false }) {
                            if (!env.processRunning) SillyBranch.entries.filter { it != env.branch }.forEach { branch ->
                                DropdownMenuItem(text = { Text("${branch.label} 브랜치로 전환") },
                                    trailingIcon = { Text("›") }, onClick = { existingActions = false; onSwitchBranch(branch) })
                            }
                            DropdownMenuItem(text = { Text("전체 설치 백업") }, trailingIcon = { Text("›") },
                                onClick = { existingActions = false; onBackup() })
                        }
                    }
                }
                Spacer(Modifier.height(44.dp))
                if (step != 3) Text("$step / 5", fontFamily = V2Mono, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(when (step) {
                    1 -> "Termux부터\n설치해요"
                    2 -> "실행 권한을\n허용해요"
                    3 -> "명령 한 줄을\n붙여 넣어요"
                    4 -> "런처와 Termux를\n연결해요"
                    else -> if (done) "준비됐어요" else "SillyTavern을\n설치해요"
                }, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(12.dp))
                Text(when (step) {
                    1 -> "SillyTavern은 Termux 안에서 돌아가요.\nF-Droid에서 Termux를 설치하고 한 번 열어 주세요."
                    2 -> "Android에서 런처가 Termux에 명령을 보낼 수 있게 ‘Termux 환경에서 명령 실행’을 허용해 주세요."
                    3 -> "Termux 화면을 길게 눌러 붙여 넣고\nEnter를 누르세요."
                    4 -> "관리 스크립트를 연결하고 기존 설치를 확인해요. 지금 있는 파일을 지우거나 덮어쓰지 않아요."
                    else -> if (done) "처음 한 번만 필요한 설정을 모두 마쳤어요." else "처음이라면 정식 버전인 Release를 선택하세요. 이미 쓰던 폴더나 설치 ZIP도 가져올 수 있어요."
                }, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(30.dp))
                when {
                    step == 3 -> {
                        Surface(color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().combinedClickable(enabled = ready,
                                onClickLabel = "명령 복사", onLongClickLabel = "전체 명령 보기", onLongClick = { showFullCommand = true }, onClick = copy)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Termux", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                                    Text(if (copied) "복사됨" else "복사", color = if (copied) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.inverseOnSurface,
                                        style = MaterialTheme.typography.labelMedium)
                                }
                                Text("~ $ " + setupCommand, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    fontFamily = V2Mono, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (showFullCommand) V2BottomSheet({ showFullCommand = false }) {
                            Text("복사되는 전체 명령", style = MaterialTheme.typography.titleLarge)
                            SelectionContainer { Text(setupCommand, fontFamily = V2Mono, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    step == 5 && !done -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SillyBranch.entries.forEach { branch ->
                                FilterChip(selected = state.selectedInstallBranch == branch, enabled = canInstall,
                                    onClick = { onSelectBranch(branch) }, label = { Text(branch.label) })
                            }
                        }
                        Text(state.selectedInstallBranch.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> {
                        setupLabels.forEachIndexed { index, label ->
                            val completed = index + 1 < eligible || done
                            val current = index + 1 == step && !completed
                            Row(Modifier.heightIn(min = 42.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                if (completed) Icon(Icons.Outlined.Check, null, Modifier.size(20.dp), tint = app.tavernbridge.launcher.ui.theme.LocalLauncherColors.current.live)
                                else Surface(Modifier.size(24.dp), shape = CircleShape, border = if (current) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    color = if (current) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.background,
                                    contentColor = if (current) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurfaceVariant) {
                                    Box(contentAlignment = Alignment.Center) { Text("${index + 1}", style = MaterialTheme.typography.labelMedium) }
                                }
                                Text(label, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (completed || current) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.height(24.dp))
                V2BottomActions {
                Column {
                if (!ready && step != 3) SettingsCaption("진행 중인 확인을 마친 뒤 계속할 수 있어요.")
                when (step) {
                    1 -> {
                        if (env.termuxInstalled) {
                            SetupPrimary("다음", ready) { requested = 2 }
                            if (ready) V2NavigationRow("Termux 열기", onOpenTermux, description = "Termux 앱이 열려요.")
                        } else if (ready) {
                            SetupPrimary("F-Droid에서 받기", true, onDownloadTermux)
                            V2QuietAction("이미 설치했어요", onVerifyTermux, Modifier.fillMaxWidth())
                        }
                    }
                    2 -> {
                        SetupPrimary(if (env.commandPermissionGranted) "다음" else "권한 허용", ready) {
                            if (env.commandPermissionGranted) requested = 3 else onRequestPermission()
                        }
                        if (ready) V2NavigationRow("앱 권한 설정 열기", onOpenPermissionSettings, description = "Android 설정 앱이 열려요.")
                    }
                    3 -> {
                        val setup = state.termuxSetup
                        val scheme = MaterialTheme.colorScheme
                        val checking = setup.status == TermuxSetupStatus.CHECKING
                        val bad = setup.status != TermuxSetupStatus.UNKNOWN && !checking && !setup.verified
                        val ink = when { bad -> scheme.error; setup.verified -> app.tavernbridge.launcher.ui.theme.LocalLauncherColors.current.live; else -> scheme.onSurfaceVariant }
                        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).semantics {
                            liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
                            if (bad) customActions = listOf(androidx.compose.ui.semantics.CustomAccessibilityAction("연결 다시 확인") {
                                onConfirmFirstConnection(); true
                            })
                        }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                            if (checking) VisibleBusyIndicator(Modifier.size(14.dp), false, 1.5.dp)
                            else Spacer(Modifier.size(6.dp).background(if (bad || setup.verified) ink else scheme.secondary, CircleShape))
                            Text(when {
                                setup.verified -> "허용됐어요"
                                checking -> "확인하고 있어요"
                                bad -> setup.detail.ifBlank { "아직 허용되지 않았어요. 붙여 넣고 Enter를 눌렀는지 확인해 주세요." }
                                else -> "Termux에서 돌아오면 자동으로 확인해요"
                            }, style = MaterialTheme.typography.labelMedium, color = ink)
                        }
                        if (ready && !setup.verified) {
                            SetupPrimary("Termux 열기", true) { copy(); onOpenSetupTermux() }
                            V2QuietAction("명령 다시 복사", copy, Modifier.fillMaxWidth())
                        }
                    }
                    4 -> SetupPrimary(if (env.managerConnected) "다음" else "연결 확인", ready && state.termuxSetup.verified) {
                        if (env.managerConnected) requested = 5 else onConnect()
                    }
                    else -> if (done) SetupPrimary("시작하기", ready, onLeave)
                        else if (canInstall) {
                            SetupPrimary("설치 시작", true, onInstall)
                            V2QuietAction("다른 폴더의 설치 가져오기", { showImport = true }, Modifier.fillMaxWidth())
                        }
                        else if (ready && env.processRunning) SettingsCaption("서버를 종료한 뒤 설치할 수 있어요.")
                }
                }
                }
            }
        }
    }
}

@Composable
private fun SetupImportPage(enabled: Boolean, back: () -> Unit, pick: () -> Unit,
    inspect: (String) -> Unit, fromZip: () -> Unit) {
    var pathPrompt by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { Icon(Icons.Outlined.ArrowBack, "이전으로") }
                Text("기존 설치 가져오기", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            }
            SettingsCaption("사용하던 폴더를 고르면 가져오기 전에 내용을 확인해요. 원본을 유지할지 이동할지는 검사 후에 선택해요.")
            if (enabled) V2NavigationGroup {
                V2NavigationRow("내부 저장소 폴더 선택", pick, description = "Android 파일 앱이 열려요.",
                    leadingIcon = { Icon(Icons.Outlined.FolderOpen, null) })
                V2NavigationRow("Termux 폴더 경로 입력", { pathPrompt = true }, leadingIcon = { Icon(Icons.Outlined.Terminal, null) })
                V2NavigationRow("전체 설치 ZIP에서 복원", fromZip, description = "ZIP을 선택할 파일 앱이 열려요.")
            } else SettingsCaption("서버와 진행 중인 작업을 종료한 뒤 가져올 수 있어요.")
        }
    }
    if (pathPrompt) ExistingInstallationPathDialog(enabled, onDismiss = { pathPrompt = false }, onInspect = inspect)
}

@Composable
private fun SetupPrimary(label: String, enabled: Boolean, action: () -> Unit) {
    if (!enabled) return
    Button(onClick = action, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(label) }
}
