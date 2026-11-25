package app.tavernbridge.launcher.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.components.OperationResultCard
import app.tavernbridge.launcher.ui.theme.V2Mono
import app.tavernbridge.launcher.ui.theme.LocalV2ReducedMotion
import app.tavernbridge.launcher.ui.theme.serverStatusIndicatorColor
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
internal fun V2HomeDashboard(state: LauncherUiState, onOpen: () -> Unit, onStart: () -> Unit, onStop: () -> Unit,
    onRestart: () -> Unit, onBackups: () -> Unit, onUpdate: () -> Unit, onLogs: () -> Unit, onHistory: () -> Unit,
    onReadHistory: () -> Unit, onRefresh: () -> Unit, onRetry: () -> Unit, onSnooze: () -> Unit,
    onOpenArchive: (String) -> Unit, onCancelStart: () -> Unit = {}, onChangePort: () -> Unit = {},
    onBlocked: () -> Unit = {}, onServerSettings: (() -> Unit)? = null) {
    val env = state.environment
    var info by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedResult by remember { mutableStateOf<OperationResultSummary?>(null) }
    var historicResult by rememberSaveable { mutableStateOf<String?>(null) }
    val hero: @Composable () -> Unit = {
        HomeServer(state, onOpen, onStart, onStop, onRestart, onBackups, onLogs, { info = "서버 정보" },
            onCancelStart, onChangePort, onBlocked)
    }
    val status: @Composable () -> Unit = {
        val days = v2DaysSince(state.backupReminder.lastBackupAtMillis, state.backupReminderNowMillis)
        Text("상태", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column {
                HomeRow(Icons.Outlined.FileDownload, "마지막 백업", days ?: "기록 없음", onBackups)
                HomeRule()
                HomeRow(Icons.Outlined.Upgrade, "버전", when (state.updatePreflight?.updateAvailable ?: state.updateAvailable) {
                    true -> "업데이트 있음"; false -> "최신"; null -> env.sillyTavernVersion.ifBlank { "미확인" }
                }, onUpdate)
                HomeRule()
                HomeRow(Icons.Outlined.Language, "외부 접속", if (env.externalAccessEnabled) "켜짐" else "꺼짐", { info = "외부 접속" })
            }
        }
    }
    val notices: @Composable () -> Unit = {
        state.lastOperationResult?.takeIf { !it.succeeded }?.let { OperationResultCard(it, onRetry) }
        if (!state.isWorking) state.backupReminder.notice(state.backupReminderNowMillis, true)?.let {
            BackupReminderCard(it, onBackups, onSnooze)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 840 && maxWidth >= 720.dp && LocalDensity.current.fontScale < 1.6f) {
            LaunchedEffect(state.isWorking, state.lastOperationResult, env.managerConnected) {
                if (!state.isWorking) onReadHistory()
            }
            val heroWidth = (maxWidth * .34f).coerceIn(280.dp, 380.dp)
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(heroWidth).fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp)) { hero() }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.surface)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 40.dp, vertical = 36.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    status()
                    Spacer(Modifier.height(10.dp))
                    BoxWithConstraints {
                        val recentBackups: @Composable () -> Unit = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("최근 백업", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                TextButton(onClick = onBackups) { Text(if (state.backupsLoaded) "전체 ${state.backups.size}개" else "전체",
                                    color = MaterialTheme.colorScheme.secondary) }
                            }
                            val list = state.backups.sortedByDescending { it.createdAt }.take(2)
                            if (list.isEmpty()) HomeRow(Icons.Outlined.FileDownload, if (state.backupsLoaded) "백업 없음" else "백업 목록 보기", "", onBackups)
                            else list.forEach { entry -> HomeRow(Icons.Outlined.FileDownload, entry.createdAt.ifBlank { entry.fileName }, formatBackupBytes(entry.sizeBytes)) { onOpenArchive(entry.fileName) } }
                        }
                        val activities: @Composable () -> Unit = {
                            Text("최근 활동", style = MaterialTheme.typography.titleMedium)
                            val recent = remember(state.operationHistory) { v2RecentActivity(state.operationHistory) }
                            if (state.recentResults.isNotEmpty()) state.recentResults.forEach { result ->
                                HomeRow(null, result.title + if (result.succeeded) " 완료" else " 실패",
                                    result.completedAt.substringAfter(' ').take(5), { selectedResult = result })
                            } else if (recent.isEmpty()) HomeRow(Icons.Outlined.History, "작업 기록 보기", "", onHistory)
                            else recent.forEach { (time, label) -> HomeRow(null, label, time, { historicResult = "$time · $label" }) }
                        }
                        if (maxWidth >= 560.dp) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            Column(Modifier.weight(1f)) { recentBackups() }
                            Column(Modifier.weight(1f)) { activities() }
                        } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { recentBackups(); activities() }
                    }
                    notices()
                }
            }
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
            hero()
            Spacer(Modifier.height(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { status(); notices() }
        }
    }
    info?.let { title ->
        V2Explanation(title, if (title == "외부 접속") {
            val status = if (env.externalAccessEnabled) "켜져" else "꺼져"
            val apply = if (env.processRunning) "변경을 저장하면 실행 중인 서버가 잠시 멈췄다가 다시 시작해요."
                else "변경한 내용은 다음 서버 시작부터 적용돼요."
            "현재 외부 접속은 ${status} 있어요. 설정 → 서버 연결에서 같은 Wi-Fi 접속과 허용할 기기를 변경할 수 있어요.\n\n$apply"
        }
        else listOfNotNull(env.branch?.label, env.sillyTavernVersion.takeIf(String::isNotBlank), env.commit.take(8).takeIf(String::isNotBlank)).joinToString(" · ") +
            "\n이 주소로 이 기기의 SillyTavern을 열어요.",
            action = if (title == "외부 접속" && onServerSettings != null) "서버 연결 설정 열기" to {
                info = null
                onServerSettings()
            } else null, onDismiss = { info = null })
    }
    selectedResult?.let { result -> V2BottomSheet({ selectedResult = null }, footer = {
        V2SheetPrimaryButton("확인", onClick = LocalV2SheetDismiss.current ?: { selectedResult = null })
    }) {
        Text(result.title, style = MaterialTheme.typography.titleLarge)
        Text(listOf(result.completedAt, "${result.durationSeconds}초", if (result.succeeded) "완료" else "실패").joinToString(" · "), fontFamily = V2Mono)
        if (result.errorCode.isNotBlank()) Text(result.errorCode, fontFamily = V2Mono, color = MaterialTheme.colorScheme.error)
        Text(result.detail)
    } }
    historicResult?.let { V2Explanation("작업 결과", it) { historicResult = null } }
}

@Composable
private fun HomeServer(state: LauncherUiState, onOpen: () -> Unit, onStart: () -> Unit, onStop: () -> Unit,
    onRestart: () -> Unit, onBackups: () -> Unit, onLogs: () -> Unit, onInfo: () -> Unit,
    onCancelStart: () -> Unit, onChangePort: () -> Unit, onBlocked: () -> Unit) {
    val env = state.environment
    val cs = MaterialTheme.colorScheme
    val locked = state.isWorking || env.operationActive
    val actions = homeServerActions(env.serverReadiness, env.processRunning, locked, state.visibleOperation, state.cancellationRequested)
    val ready = actions.status
    val reduced = LocalV2ReducedMotion.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(listOfNotNull(env.branch?.value, env.sillyTavernVersion.takeIf(String::isNotBlank)).joinToString(" · ").ifBlank { "설치됨" },
            Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, fontFamily = V2Mono, color = cs.onSurfaceVariant)
        IconButton(onClick = onInfo) { Icon(Icons.Outlined.Info, "서버 정보 설명", Modifier.size(20.dp)) }
    }
    Spacer(Modifier.height(20.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val stopping = actions.stop == HomeStop.STOPPING
        val color = if (stopping) cs.outline else serverStatusIndicatorColor(ready, cs.background)
        val dotAlpha = if (ready == ServerReadiness.STARTING && !stopping && !reduced) {
            rememberInfiniteTransition(label = "시작 상태").animateFloat(1f, .45f,
                infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "시작 상태 점").value
        } else 1f
        Box(Modifier.size(8.dp).alpha(dotAlpha).background(color, CircleShape))
        val statusText = if (stopping) "끄는 중" else when (ready) {
            ServerReadiness.READY -> "실행 중"; ServerReadiness.STARTING -> "시작 중"; ServerReadiness.OFFLINE -> "서버 꺼짐"
            ServerReadiness.UNRESPONSIVE -> "응답 없음"; ServerReadiness.PORT_CONFLICT -> "포트 충돌"
        }
        Crossfade(statusText, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            animationSpec = tween(if (reduced) 0 else 120), label = "서버 상태") { Text(it, style = MaterialTheme.typography.labelMedium, color = color) }
        if (env.processRunning && env.sessionStartedEpoch > 0) {
            var now by remember(env.sessionStartedEpoch) { mutableLongStateOf(System.currentTimeMillis() / 1000) }
            LaunchedEffect(env.sessionStartedEpoch) { while (true) { now = System.currentTimeMillis() / 1000; delay(1000) } }
            val t = (now - env.sessionStartedEpoch).coerceAtLeast(0)
            Text("%d:%02d:%02d".format(Locale.ROOT, t / 3600, t / 60 % 60, t % 60), style = MaterialTheme.typography.labelMedium, fontFamily = V2Mono, color = cs.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(6.dp))
    Text("SillyTavern", fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(6.dp))
    Text("127.0.0.1:${env.port}", fontFamily = V2Mono, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
    Spacer(Modifier.height(26.dp))
    Row {
        val primary = actions.primary
        val mainModifier = Modifier.weight(1f).heightIn(min = 52.dp)
        val shape = RoundedCornerShape(14.dp)
        if (primary == HomePrimary.LOGS || primary == HomePrimary.PORT) {
            OutlinedButton(onClick = if (primary == HomePrimary.LOGS) onLogs else onChangePort,
                modifier = mainModifier, enabled = actions.canPrimary, shape = shape) {
                Text(if (primary == HomePrimary.LOGS) "로그 보기" else "포트 바꾸기")
            }
        } else Button(onClick = if (primary == HomePrimary.CHAT) onOpen else onStart,
            modifier = mainModifier, shape = shape, enabled = actions.canPrimary) {
            Crossfade(primary, animationSpec = tween(if (reduced) 0 else 120), label = "서버 동작") {
                when (it) {
                    HomePrimary.STARTING -> VisibleBusyIndicator(Modifier.size(22.dp).semantics {
                        contentDescription = "서버 시작 중"
                    }, false, 2.dp)
                    HomePrimary.CHAT -> Text("대화 열기")
                    HomePrimary.START -> Text("서버 시작")
                    else -> Unit
                }
            }
        }
        AnimatedVisibility(visible = actions.stop != HomeStop.HIDDEN,
            enter = fadeIn(tween(if (reduced) 0 else 120)) + expandHorizontally(tween(if (reduced) 0 else 200)),
            exit = fadeOut(tween(if (reduced) 0 else 120)) + shrinkHorizontally(tween(if (reduced) 0 else 200))) {
            Row { Spacer(Modifier.width(8.dp))
            Surface(onClick = when (actions.stop) {
                HomeStop.CANCEL_START -> onCancelStart; HomeStop.BLOCKED -> onBlocked; else -> onStop
            }, enabled = actions.stop !in setOf(HomeStop.STOPPING, HomeStop.HIDDEN),
            modifier = Modifier.size(52.dp), shape = RoundedCornerShape(14.dp), color = cs.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, cs.outlineVariant)) {
                Box(contentAlignment = Alignment.Center) { if (actions.stop == HomeStop.STOPPING)
                    VisibleBusyIndicator(Modifier.size(20.dp).semantics { contentDescription = "서버 종료 중" }, false, 2.dp)
                else Icon(Icons.Outlined.Stop, if (actions.stop == HomeStop.CANCEL_START) "시작 취소" else "서버 종료",
                    tint = if (actions.stop == HomeStop.BLOCKED) cs.onSurfaceVariant else cs.error) }
            } }
        }
    }
    if (ready == ServerReadiness.UNRESPONSIVE || ready == ServerReadiness.PORT_CONFLICT)
        Text(if (ready == ServerReadiness.PORT_CONFLICT) "다른 앱이 포트 ${env.port}을 쓰고 있어요." else "서버가 응답하지 않아요. 재시작해 보세요.",
            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    Spacer(Modifier.height(28.dp))
    Surface(shape = RoundedCornerShape(20.dp), color = cs.surface) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HomeAction(Modifier.weight(1f), Icons.Outlined.RestartAlt, "재시작", !locked && env.processRunning, onRestart, onBlocked.takeIf { actions.otherWork })
            VerticalDivider(Modifier.height(28.dp), color = cs.outlineVariant)
            HomeAction(Modifier.weight(1f), Icons.Outlined.FileDownload, "백업", !locked, onBackups, onBlocked.takeIf { locked })
            VerticalDivider(Modifier.height(28.dp), color = cs.outlineVariant)
            HomeAction(Modifier.weight(1f), Icons.Outlined.Subject, "로그", true, onLogs)
        }
    }
}

@Composable
private fun HomeAction(modifier: Modifier, icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit, blocked: (() -> Unit)? = null) {
    Column(modifier.v2RowTap("home-action:$label", enabled = enabled || blocked != null, role = Role.Button, click = if (enabled) onClick else blocked ?: {}).heightIn(min = 72.dp).padding(4.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .54f)
        Icon(icon, null, Modifier.size(21.dp), tint = ink)
        Text(label, style = MaterialTheme.typography.labelMedium, color = ink)
    }
}

@Composable
private fun HomeRule() = HorizontalDivider(Modifier.padding(start = 52.dp, end = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun HomeRow(icon: ImageVector?, label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().v2RowTap("home-row:$label", role = Role.Button, click = onClick).heightIn(min = 56.dp)
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.bodyLarge)
            if (LocalDensity.current.fontScale >= 1.3f && value.isNotBlank()) Text(value, style = MaterialTheme.typography.bodyMedium) }
        if (value.isNotBlank() && LocalDensity.current.fontScale < 1.3f)
            Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Icon(if (label == "외부 접속") Icons.Outlined.Info else Icons.Outlined.ChevronRight,
            if (label == "외부 접속") "외부 접속 설명" else null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun V2Explanation(title: String, detail: String, action: Pair<String, () -> Unit>? = null, onDismiss: () -> Unit) {
    V2BottomSheet(onClose = onDismiss, footer = {
        val close = LocalV2SheetDismiss.current ?: onDismiss
        if (action == null) V2SheetPrimaryButton("확인", onClick = close)
        else Column(Modifier.fillMaxWidth()) {
            V2SheetPrimaryButton(action.first, onClick = action.second)
            TextButton(onClick = close, modifier = Modifier.fillMaxWidth()) { Text("닫기") }
        }
    }) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(detail, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
