package app.tavernbridge.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalDensity
import app.tavernbridge.launcher.ui.theme.LocalV2ReducedMotion
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.heading
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
    V2BackHandler(enabled = ready && step > 1) { requested = step - 1 }

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
                .heightIn(min = minimumHeight).padding(horizontal = 20.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (step > 1) TextButton(onClick = { requested = step - 1 }, enabled = ready,
                        contentPadding = PaddingValues(horizontal = 2.dp)) { Text("이전으로") }
                    Row(Modifier.weight(1f).semantics { contentDescription = "처음 설정, 5단계 중 ${step}단계" },
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(5) { index ->
                            Surface(Modifier.weight(1f).height(3.dp), shape = CircleShape,
                                color = if (index < step) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant) {}
                        }
                    }
                    if (step < 5) TextButton(onClick = onLeave, enabled = ready, contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Text("건너뛰기")
                    }
                    if (done) Box {
                        IconButton(onClick = { existingActions = true }, enabled = ready) {
                            Icon(Icons.Outlined.MoreHoriz, "기존 설치 작업")
                        }
                        DropdownMenu(expanded = existingActions, onDismissRequest = { existingActions = false }) {
                            SillyBranch.entries.filter { it != env.branch }.forEach { branch ->
                                DropdownMenuItem(text = { Text("${branch.label} 브랜치로 전환") }, enabled = !env.processRunning,
                                    onClick = { existingActions = false; onSwitchBranch(branch) })
                            }
                            DropdownMenuItem(text = { Text("전체 설치 백업") }, onClick = { existingActions = false; onBackup() })
                        }
                    }
                }
                Spacer(Modifier.height(44.dp))
                Text("$step / 5", fontFamily = V2Mono, style = MaterialTheme.typography.labelMedium,
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
                    3 -> "아래 버튼으로 Termux를 열면 명령이 복사돼요. 빈 곳을 길게 눌러 붙여 넣고 Enter를 누르세요. 런처로 돌아오면 연결을 확인해요."
                    4 -> "관리 스크립트를 연결하고 기존 설치를 확인해요. 지금 있는 파일을 지우거나 덮어쓰지 않아요."
                    else -> if (done) "처음 한 번만 필요한 설정을 모두 마쳤어요." else "처음이라면 정식 버전인 Release를 선택하세요. 이미 쓰던 폴더나 설치 ZIP도 가져올 수 있어요."
                }, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(30.dp))
                when {
                    step == 3 -> {
                        Surface(color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "명령 복사", onClick = copy)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Termux", Modifier.weight(1f), fontFamily = V2Mono, style = MaterialTheme.typography.labelMedium)
                                    TextButton(onClick = copy, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inverseOnSurface)) {
                                        Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (copied) "복사됨" else "명령 복사")
                                    }
                                }
                                if (showFullCommand) SelectionContainer { Text(setupCommand, fontFamily = V2Mono,
                                    style = MaterialTheme.typography.bodyMedium) }
                                else Text(setupCommand.take(82) + if (setupCommand.length > 82) "…" else "",
                                    fontFamily = V2Mono, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        TextButton(onClick = { showFullCommand = !showFullCommand }) { Text(if (showFullCommand) "전체 명령 접기" else "복사될 전체 명령 보기") }
                        when (state.termuxSetup.status) {
                            TermuxSetupStatus.READY -> Text("허용됐어요", color = app.tavernbridge.launcher.ui.theme.LocalLauncherColors.current.live)
                            TermuxSetupStatus.CHECKING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Text("확인하고 있어요")
                            }
                            TermuxSetupStatus.UNKNOWN -> Unit
                            else -> Text(state.termuxSetup.detail.ifBlank {
                                if (state.termuxSetup.status == TermuxSetupStatus.EXTERNAL_APPS_DISABLED)
                                    "외부 명령 허용이 꺼져 있어요. Termux에 명령을 붙여 넣고 Enter를 눌러 주세요."
                                else "연결을 확인하지 못했어요. Termux에서 Enter를 누른 뒤 다시 확인해 주세요."
                            }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
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
                        TextButton(onClick = { showImport = !showImport }) { Text(if (showImport) "기존 설치 가져오기 접기" else "기존 설치 가져오기") }
                        if (showImport) {
                            ExistingInstallationImportCard(enabled = canInstall, onPickFolder = onPickInstallation, onInspectPath = onInspectInstallation)
                            TextButton(onClick = onImportBackup, enabled = canInstall) { Text("전체 설치 ZIP에서 복원") }
                        }
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
                when (step) {
                    1 -> {
                        SetupPrimary(if (env.termuxInstalled) "다음" else "F-Droid에서 받기", ready) {
                            if (env.termuxInstalled) requested = 2 else onDownloadTermux()
                        }
                        SetupQuiet(if (env.termuxInstalled) "Termux 열기" else "이미 설치했어요", ready,
                            if (env.termuxInstalled) onOpenTermux else onVerifyTermux)
                    }
                    2 -> {
                        SetupPrimary(if (env.commandPermissionGranted) "다음" else "권한 허용", ready) {
                            if (env.commandPermissionGranted) requested = 3 else onRequestPermission()
                        }
                        SetupQuiet("앱 권한 설정 열기", ready, onOpenPermissionSettings)
                    }
                    3 -> {
                        SetupPrimary("Termux 열기", ready && !state.termuxSetup.verified) { copy(); onOpenSetupTermux() }
                        if (state.termuxSetup.status in setOf(TermuxSetupStatus.UNVERIFIED, TermuxSetupStatus.EXTERNAL_APPS_DISABLED))
                            SetupQuiet("연결 다시 확인", ready, onConfirmFirstConnection)
                    }
                    4 -> SetupPrimary(if (env.managerConnected) "다음" else "연결 확인", ready && state.termuxSetup.verified) {
                        if (env.managerConnected) requested = 5 else onConnect()
                    }
                    else -> if (done) SetupPrimary("시작하기", ready, onLeave)
                        else SetupPrimary("${state.selectedInstallBranch.label} 설치", canInstall, onInstall)
                }
                }
                }
            }
        }
    }
}

@Composable
private fun SetupPrimary(label: String, enabled: Boolean, action: () -> Unit) {
    Button(onClick = action, enabled = enabled, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(label) }
}

@Composable
private fun SetupQuiet(label: String, enabled: Boolean, action: () -> Unit) {
    TextButton(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) }
}
