package app.tavernbridge.launcher.ui

import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.LauncherViewModel
import app.tavernbridge.launcher.R
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.theme.LocalLauncherColors
import app.tavernbridge.launcher.ui.theme.V2Mono
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val checkAreas = listOf(R.drawable.rv_folder to "저장 공간", R.drawable.rv_terminal to "Termux 연결과 권한",
    R.drawable.rv_pulse to "Node.js·SillyTavern 실행 환경", R.drawable.rv_shield to "배터리·백그라운드 제한")

private fun diagnosticTime(raw: String): String {
    val stamp = runCatching { LocalDateTime.parse(raw, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) }.getOrNull()
        ?: return if (raw.isBlank()) "진단 시각 미확인" else "$raw 진단"
    return (if (stamp.toLocalDate() == LocalDate.now()) "오늘" else stamp.toLocalDate().toString()) +
        " " + stamp.format(DateTimeFormatter.ofPattern("HH:mm")) + " 진단"
}

@Composable internal fun DiagnosticDetail(state: LauncherUiState, vm: LauncherViewModel, mod: Modifier, onRun: () -> Unit = vm::runDiagnostics) {
    val report = state.diagnosticReport
    val clipboard = LocalClipboardManager.current
    val colors = LocalLauncherColors.current
    var selected by rememberSaveable(report?.checkedAt) { mutableStateOf<String?>(null) }
    var showAll by rememberSaveable(report?.checkedAt) { mutableStateOf(false) }
    val open: (DiagnosticItem) -> Unit = { selected = it.id }
    DetailLayout(mod, footer = {
        if (report != null) OutlinedButton({
            clipboard.setText(AnnotatedString(report.copyText()))
            vm.showMessage("진단 보고서를 복사했어요")
        }, Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = colors.surface)) { Text("보고서 복사") }
        Button(onRun, Modifier.weight(1f).heightIn(min = 52.dp), enabled = !state.isWorking, shape = RoundedCornerShape(14.dp)) {
            Text(if (report == null) "진단 시작" else "다시 진단")
        }
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (report == null) "아직 진단하지 않았어요" else if (report.checkedCount == 0) "판정한 검사 없음"
                else "${report.checkedCount}개 중 ${report.okCount}개 정상", Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(if (report == null) "저장 공간·Termux·서버 실행 환경을 확인해요. 약 30초 걸려요." else diagnosticTime(report.checkedAt),
                color = colors.ink2, style = if (report == null) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodySmall,
                fontFamily = if (report == null) null else V2Mono)
        }
        if (report == null) DiagnosticGroup("확인하는 것") {
            checkAreas.forEachIndexed { i, (icon, label) ->
                if (i > 0) ToolDivider(52)
                DiagnosticActionRow(icon, label)
            }
        } else {
            val issues = report.rows(DiagnosticStatus.ERROR) + report.rows(DiagnosticStatus.WARNING)
            if (issues.isNotEmpty()) DiagnosticGroup("확인 필요") {
                issues.forEachIndexed { i, item ->
                    if (i > 0) ToolDivider(40)
                    DiagnosticResultRow(item, open)
                }
            }
            val normal = report.rows(DiagnosticStatus.OK).sortedBy {
                when (it.id) { "termux_storage" -> 0; "termux" -> 1; "node" -> 2; else -> 3 }
            }
            if (normal.isNotEmpty()) DiagnosticGroup("정상", if (normal.size <= 3) null else if (showAll) "접기" else "${normal.size}개 모두",
                { showAll = !showAll }) {
                (if (showAll) normal else normal.take(3)).forEachIndexed { i, item ->
                    if (i > 0) ToolDivider(40)
                    DiagnosticResultRow(item, null)
                }
            }
            val info = report.rows(DiagnosticStatus.INFO)
            if (info.isNotEmpty()) DiagnosticGroup("참고 ${info.size}개 · 검사 수에서 제외") {
                info.forEachIndexed { i, item ->
                    if (i > 0) ToolDivider(40)
                    DiagnosticResultRow(item, if (item.detail.isNotBlank() || item.recommendation.isNotBlank()) open else null)
                }
            }
        }
    }
    report?.items?.find { it.id == selected }?.let { DiagnosticSolution(it, vm, state) { selected = null } }
}

@Composable private fun DiagnosticGroup(title: String, action: String? = null, onAction: () -> Unit = {}, body: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (action != null) Text(action, Modifier.v2RowTap("diag-group:$title", click = onAction).padding(vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium, color = LocalLauncherColors.current.accent)
        }
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = LocalLauncherColors.current.surface) { Column(content = body) }
    }
}

@Composable private fun DiagnosticActionRow(@DrawableRes icon: Int, label: String, note: String = "", action: (() -> Unit)? = null) {
    val c = LocalLauncherColors.current
    Row(Modifier.fillMaxWidth().then(if (action == null) Modifier else Modifier.v2RowTap("diagnostic:$label", click = action))
        .heightIn(min = if (note.isEmpty()) 56.dp else 70.dp).padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        ReviewGlyph(icon, tint = c.ink2)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (note.isNotBlank()) Text(note, color = c.ink2, style = MaterialTheme.typography.bodySmall)
        }
        if (action != null) ReviewGlyph(R.drawable.rv_chevron, size = 16.dp, tint = c.ink2)
    }
}

@Composable private fun DiagnosticResultRow(item: DiagnosticItem, onOpen: ((DiagnosticItem) -> Unit)?) {
    val c = LocalLauncherColors.current
    val issue = item.status == DiagnosticStatus.ERROR || item.status == DiagnosticStatus.WARNING
    val note = if (issue) when (item.id) {
        "battery" -> "화면이 꺼지면 서버가 멈출 수 있어요"
        "git_changes" -> "새 버전에 자동 적용되지 않아요"
        else -> item.value
    } else ""
    val actionWord = issue && item.id != "git_changes"
    Row(Modifier.fillMaxWidth().then(if (onOpen == null) Modifier else Modifier.v2RowTap("diag-item:${item.id}") { onOpen(item) })
        .heightIn(min = if (issue) 70.dp else 56.dp).padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(when (item.status) {
            DiagnosticStatus.OK -> c.live; DiagnosticStatus.WARNING -> c.accent
            DiagnosticStatus.ERROR -> c.danger; DiagnosticStatus.INFO -> c.ink3
        }, CircleShape).semantics { contentDescription = item.status.label })
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge)
            if (note.isNotBlank()) Text(note, color = c.ink2, style = MaterialTheme.typography.bodySmall)
        }
        // Reserve only the real value's width, but bound longer strings at large font scales.
        Box(Modifier.widthIn(max = 108.dp), contentAlignment = Alignment.CenterEnd) {
            Text(if (actionWord) "해결" else item.value, color = if (actionWord) c.accent else c.ink2,
                style = MaterialTheme.typography.bodyMedium, fontFamily = if (item.id in setOf("node", "git", "npm")) V2Mono else null)
        }
        if (onOpen != null) ReviewGlyph(R.drawable.rv_chevron, size = 16.dp, tint = c.ink2)
    }
}

private data class DiagnosticAction(@DrawableRes val icon: Int, val label: String, val note: String = "", val click: () -> Unit)

@Composable private fun DiagnosticSolution(item: DiagnosticItem, vm: LauncherViewModel, state: LauncherUiState, dismiss: () -> Unit) {
    val colors = LocalLauncherColors.current
    val browser = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val battery = item.id == "battery" && item.status == DiagnosticStatus.WARNING
    val cause = if (battery) "화면이 꺼지면 Android가 Termux를 멈춰 서버가 꺼질 수 있어요."
        else if (item.id == "git_changes") "설치 폴더에서 바뀐 파일이 ${item.value} 있어요." else item.detail.ifBlank { item.value }
    val next = if (battery) "배터리 사용을 ‘제한 없음’으로 바꿔 보세요. 배터리를 조금 더 써요."
        else if (item.id in setOf("git", "node", "npm", "dependencies", "restore_recovery", "node_requirement")) "도구의 점검·복구에서 실행 환경을 확인해 주세요."
        else item.recommendation
    val actions = buildList<DiagnosticAction> {
        when {
            battery -> {
                add(DiagnosticAction(R.drawable.rv_shield, "배터리 설정 열기", click = vm::openBatteryOptimizationSettings))
                add(DiagnosticAction(R.drawable.rv_open, "Termux 앱 정보 열기", click = vm::openTermuxAppSettings))
                if (TermuxProcessHelp.includesAndroid12ProcessLimit(Build.VERSION.SDK_INT)) add(
                    DiagnosticAction(R.drawable.rv_globe, "Android 12 이상 도움말", "외부 브라우저로 열려요") {
                        runCatching { browser.openUri(TermuxProcessHelp.OFFICIAL_HELP_URL) }.onFailure { vm.showMessage("도움말 링크를 열 수 없어요. 브라우저 앱을 확인해 주세요.") }
                    })
            }
            item.id == "download_storage" && item.status == DiagnosticStatus.INFO && item.value == "아직 사용하지 않음" ->
                add(DiagnosticAction(R.drawable.rv_terminal, "연결 명령 복사·Termux 열기") {
                    clipboard.setText(AnnotatedString(vm.storageSetupCommand)); vm.openTermux()
                })
            item.id in setOf("termux_storage", "download_storage") -> add(DiagnosticAction(R.drawable.rv_folder, "휴대폰 저장 공간 열기", click = vm::openDeviceStorageSettings))
            item.id == "command_permission" -> add(DiagnosticAction(R.drawable.rv_open, "런처 권한 설정 열기", click = vm::openPermissionSettings))
            item.id in setOf("termux", "termux_installation") && !state.environment.termuxInstalled -> add(DiagnosticAction(R.drawable.rv_globe, "Termux 다운로드", click = vm::openTermuxDownload))
            item.id == "git_changes" -> add(DiagnosticAction(R.drawable.rv_update, "업데이트 화면 열기") { dismiss(); vm.selectTool(ToolPage.UPDATE) })
            item.id in setOf("git", "node", "npm", "dependencies", "restore_recovery", "node_requirement") && state.environment.sillyTavernInstalled ->
                add(DiagnosticAction(R.drawable.rv_pulse, "점검·복구 열기") { dismiss(); vm.selectTool(ToolPage.REPAIR) })
            item.id == "termux_repo" -> add(DiagnosticAction(R.drawable.rv_terminal, "Termux 열기", click = vm::openTermux))
        }
    }
    V2BottomSheet(onClose = dismiss, footer = { V2SheetPrimaryButton("확인", onClick = LocalV2SheetDismiss.current ?: dismiss) }) {
        Text(item.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (cause.isNotBlank()) Text(cause, style = MaterialTheme.typography.bodyLarge, color = colors.ink2)
        if (next.isNotBlank()) Text(next, style = MaterialTheme.typography.bodyLarge, color = colors.ink2)
        if (item.id == "git_changes" && item.detail.isNotBlank()) Text(item.detail, style = MaterialTheme.typography.bodySmall, fontFamily = V2Mono)
        if (actions.isNotEmpty()) Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = colors.sunken) {
            Column {
                actions.forEachIndexed { i, a ->
                    if (i > 0) ToolDivider(52)
                    DiagnosticActionRow(a.icon, a.label, a.note, a.click)
                }
            }
        }
    }
}
