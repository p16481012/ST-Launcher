package app.tavernbridge.launcher.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.components.OperationResultCard
import app.tavernbridge.launcher.ui.theme.V2Mono
import app.tavernbridge.launcher.ui.theme.serverStatusIndicatorColor
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
internal fun V2HomeDashboard(state: LauncherUiState, onOpen: () -> Unit, onStart: () -> Unit, onStop: () -> Unit,
    onRestart: () -> Unit, onBackups: () -> Unit, onUpdate: () -> Unit, onLogs: () -> Unit, onHistory: () -> Unit,
    onReadHistory: () -> Unit, onRefresh: () -> Unit, onRetry: () -> Unit, onSnooze: () -> Unit) {
    val env = state.environment
    var info by rememberSaveable { mutableStateOf<String?>(null) }
    val hero: @Composable () -> Unit = {
        HomeServer(state, onOpen, onStart, onStop, onRestart, onBackups, onLogs, { info = "서버 정보" })
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
        if (maxWidth >= 720.dp && LocalDensity.current.fontScale < 1.6f) {
            LaunchedEffect(state.isWorking, env.managerConnected) {
                if (!state.isWorking && env.managerConnected) onReadHistory()
            }
            val heroWidth = (maxWidth * .34f).coerceIn(280.dp, 380.dp)
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(heroWidth).fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp)) { hero() }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.surface)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 36.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    status()
                    Spacer(Modifier.height(10.dp))
                    BoxWithConstraints {
                        val recentBackups: @Composable () -> Unit = {
                            Text("최근 백업", style = MaterialTheme.typography.titleMedium)
                            val list = state.backups.sortedByDescending { it.createdAt }.take(2)
                            if (list.isEmpty()) HomeRow(Icons.Outlined.FileDownload, if (state.backupsLoaded) "백업 없음" else "백업 목록 보기", "", onBackups)
                            else list.forEach { HomeRow(Icons.Outlined.FileDownload, it.createdAt.ifBlank { it.fileName }, formatBackupBytes(it.sizeBytes), onBackups) }
                        }
                        val activities: @Composable () -> Unit = {
                            Text("최근 활동", style = MaterialTheme.typography.titleMedium)
                            val recent = remember(state.operationHistory) { v2RecentActivity(state.operationHistory) }
                            if (recent.isEmpty()) HomeRow(Icons.Outlined.History, state.lastOperationResult?.title ?: "작업 기록 보기", "", onHistory)
                            else recent.forEach { (time, label) -> HomeRow(null, label, time, onHistory) }
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
        V2Explanation(title, if (title == "외부 접속")
            "다른 기기가 이 서버에 연결할 수 있는지는 네트워크와 허용 목록 설정에 따라 달라요. 설정 › 서버 연결에서 접속 범위를 확인할 수 있어요."
        else listOfNotNull(env.branch?.label, env.sillyTavernVersion.takeIf(String::isNotBlank), env.commit.take(8).takeIf(String::isNotBlank)).joinToString(" · ") +
            "\n이 주소로 이 기기의 SillyTavern을 열어요. 서버 상태는 다시 확인할 수 있어요.",
            onDismiss = { info = null }, secondary = if (title == "서버 정보") onRefresh else null)
    }
}

@Composable
private fun HomeServer(state: LauncherUiState, onOpen: () -> Unit, onStart: () -> Unit, onStop: () -> Unit,
    onRestart: () -> Unit, onBackups: () -> Unit, onLogs: () -> Unit, onInfo: () -> Unit) {
    val env = state.environment
    val cs = MaterialTheme.colorScheme
    val ready = env.serverReadiness
    val locked = state.isWorking || env.operationActive
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(listOfNotNull(env.branch?.value, env.sillyTavernVersion.takeIf(String::isNotBlank)).joinToString(" · ").ifBlank { "설치됨" },
            Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, fontFamily = V2Mono, color = cs.onSurfaceVariant)
        IconButton(onClick = onInfo) { Icon(Icons.Outlined.Info, "서버 정보 설명", Modifier.size(20.dp)) }
    }
    Spacer(Modifier.height(20.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val color = serverStatusIndicatorColor(ready, cs.background)
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(when (ready) {
            ServerReadiness.READY -> "실행 중"; ServerReadiness.STARTING -> "시작 중"; ServerReadiness.OFFLINE -> "서버 꺼짐"
            ServerReadiness.UNRESPONSIVE -> "응답 없음"; ServerReadiness.PORT_CONFLICT -> "포트 충돌"
        }, style = MaterialTheme.typography.labelMedium, color = cs.onSurface)
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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val problem = ready == ServerReadiness.UNRESPONSIVE || ready == ServerReadiness.PORT_CONFLICT
        Button(onClick = if (ready == ServerReadiness.READY) onOpen else if (problem) onLogs else onStart,
            modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp),
            enabled = ready == ServerReadiness.READY || problem || (!locked && ready == ServerReadiness.OFFLINE)) {
            Text(when { ready == ServerReadiness.READY -> "대화 열기"; problem -> "로그 보기"; ready == ServerReadiness.STARTING -> "시작 중"; else -> "서버 시작" })
        }
        if (env.processRunning) Surface(onClick = onStop, enabled = !locked,
            modifier = Modifier.size(52.dp), shape = RoundedCornerShape(14.dp), color = cs.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, cs.outlineVariant)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Stop, "서버 종료", tint = cs.error) }
        }
    }
    Spacer(Modifier.height(28.dp))
    Surface(shape = RoundedCornerShape(20.dp), color = cs.surface) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HomeAction(Modifier.weight(1f), Icons.Outlined.RestartAlt, "재시작", !locked && env.processRunning, onRestart)
            VerticalDivider(Modifier.height(28.dp), color = cs.outlineVariant)
            HomeAction(Modifier.weight(1f), Icons.Outlined.FileDownload, "백업", true, onBackups)
            VerticalDivider(Modifier.height(28.dp), color = cs.outlineVariant)
            HomeAction(Modifier.weight(1f), Icons.Outlined.Subject, "로그", true, onLogs)
        }
    }
}

@Composable
private fun HomeAction(modifier: Modifier, icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick).heightIn(min = 72.dp).padding(4.dp),
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
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 56.dp)
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.bodyLarge)
            if (LocalDensity.current.fontScale >= 1.3f && value.isNotBlank()) Text(value, style = MaterialTheme.typography.bodyMedium) }
        if (value.isNotBlank() && LocalDensity.current.fontScale < 1.3f)
            Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun V2Explanation(title: String, detail: String, onDismiss: () -> Unit, secondary: (() -> Unit)? = null) {
    V2BottomSheet(onClose = onDismiss) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(detail, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (secondary != null) TextButton(onClick = { onDismiss(); secondary() }) { Text("서버 상태 새로고침") }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) { Text("확인") }
    }
}
