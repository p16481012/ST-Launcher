package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.model.LauncherUiState
import app.tavernbridge.launcher.model.SillyBranch
import app.tavernbridge.launcher.ui.theme.V2Mono

/** Installed users get one native Tools detail; onboarding and data-changing confirmations stay separate. */
@Composable
internal fun V2InstallationDetail(
    state: LauncherUiState, modifier: Modifier,
    onPickFolder: () -> Unit, onInspectPath: (String) -> Unit, onSwitchBranch: (SillyBranch) -> Unit,
    onOpenRepair: () -> Unit, onOpenReset: () -> Unit,
) {
    val stopToContinue = rememberStopToContinue(state, state.environment.branch)
    val env = state.environment
    val blocked = when {
        env.recoveryPending -> "중단된 작업을 먼저 점검·복구해 주세요."
        state.isWorking || env.operationActive || state.fileBrowserMutating ||
            state.backupTransfer != null || state.encryptedBackup != null -> "현재 작업이 끝나면 폴더 가져오기와 브랜치 전환을 할 수 있어요."
        !state.termuxSetup.verified || !env.termuxInstalled || !env.commandPermissionGranted || !env.managerConnected ->
            "Termux 연결과 명령 실행 권한을 먼저 확인해 주세요."
        env.processRunning && stopToContinue == null -> "폴더를 가져오거나 브랜치를 바꾸려면 먼저 서버를 종료해 주세요."
        else -> null
    }
    val request: (String, () -> Unit) -> Unit = { label, next -> enterStoppedAction(env.processRunning, stopToContinue, label, next) }
    var enterPath by rememberSaveable { mutableStateOf(false) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)) {
        ToolGroup("설치 정보") {
            InstallationValue("버전", env.sillyTavernVersion.ifBlank { "알 수 없음" })
            ToolDivider(); InstallationValue("브랜치", env.branch?.label ?: "알 수 없음")
            ToolDivider(); InstallationValue("설치 위치", "~/SillyTavern")
            ToolDivider(); InstallationValue("Node.js", env.nodeVersion.ifBlank { "확인 필요" })
            ToolDivider(); InstallationValue("커밋", env.commit.ifBlank { "알 수 없음" })
        }
        ToolGroup("기존 설치 가져오기") {
            InstallationNote("SillyTavern 설치 폴더를 다시 내려받지 않고 실행 모듈만 맞춰요. 폴더를 검사한 뒤 원본을 유지할지 옮길지 선택해요.")
            if (blocked != null) InstallationNote(blocked) else {
                ToolDivider()
                ToolRow("내부 저장소 폴더 선택", "설치 폴더를 선택할 파일 앱이 열려요.", icon = Icons.Outlined.FolderOpen,
                    onClick = { request("내부 저장소 폴더 선택", onPickFolder) })
                ToolDivider(50)
                ToolRow("Termux 폴더 경로 입력", icon = Icons.Outlined.Terminal,
                    onClick = { request("Termux 폴더 경로 입력") { enterPath = true } })
                if (env.processRunning) InstallationNote("폴더를 선택하면 서버 종료를 확인한 뒤 가져오기를 이어가요.")
            }
        }
        ToolGroup("브랜치 변경") {
            SillyBranch.entries.filter { it == env.branch || (blocked == null && env.sillyTavernInstalled) }.forEachIndexed { index, target ->
                if (index != 0) ToolDivider()
                if (target == env.branch) {
                    ToolRow(target.label, target.description, trailing = "현재 브랜치")
                } else {
                    ToolRow("${target.label}로 변경", target.description,
                        onClick = { request("${target.label} 브랜치 변경 확인") { onSwitchBranch(target) } })
                }
            }
            if (blocked != null) InstallationNote(if (env.processRunning && stopToContinue == null) "서버 종료 후 전환할 수 있어요." else blocked)
            else if (env.processRunning) InstallationNote("서버를 끈 뒤 변경할 브랜치를 다시 확인해요.")
            else if (!env.sillyTavernInstalled) InstallationNote("설치 상태를 확인한 뒤 브랜치를 바꿀 수 있어요.")
        }
        ToolGroup("설치 유지관리") {
            ToolRow("점검·복구", "캐릭터·대화·설정을 유지하고 실행 패키지를 다시 구성해요.",
                icon = Icons.Outlined.RestartAlt, onClick = onOpenRepair)
            ToolDivider(50)
            ToolRow("초기화", "현재 데이터를 비우고 같은 브랜치로 다시 설치해요. 다운로드에 있는 백업 ZIP은 남아요.",
                icon = Icons.Outlined.DeleteOutline, danger = true, onClick = onOpenReset)
        }
    }
    if (enterPath) ExistingInstallationPathDialog(blocked == null && !env.processRunning, onDismiss = { enterPath = false }, onInspect = onInspectPath)
}

@Composable private fun InstallationValue(label: String, value: String) {
    val colors = MaterialTheme.colorScheme
    // A longer commit or a large accessibility font wraps instead of truncating the actual value.
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 11.dp)) {
        val stacked = maxWidth / LocalDensity.current.fontScale < 220.dp
        if (stacked) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = V2Mono) }
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                SelectionContainer {
                    Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = V2Mono, textAlign = TextAlign.End)
                }
            }
        }
    }
}

@Composable private fun InstallationNote(text: String) {
    Text(text, Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
