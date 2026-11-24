package app.tavernbridge.launcher.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import app.tavernbridge.launcher.entry.*

@Composable
internal fun EntryRequestDialog(request: LauncherEntryRequest?, gate: LauncherEntryGate?, onConfirm: () -> Unit, onClose: () -> Unit) {
    if (request == null || gate == null) return
    val allowed = gate in setOf(LauncherEntryGate.CONFIRM_START, LauncherEntryGate.CONFIRM_STOP, LauncherEntryGate.CONFIRM_OPEN)
    V2AlertDialog(onDismissRequest = onClose,
        title = { Text(if (request.action == LauncherEntryAction.STOP) "서버를 종료할까요?" else "서버를 시작할까요?") },
        text = { Text(when(gate) {
            LauncherEntryGate.CONFIRM_START -> "바로가기에서 요청한 SillyTavern 시작을 실행합니다."
            LauncherEntryGate.CONFIRM_STOP -> "진행 중인 대화가 끊길 수 있습니다. 서버만 종료하며 데이터는 삭제하지 않습니다."
            LauncherEntryGate.BUSY -> "다른 작업이나 환경 확인이 진행 중입니다. 완료된 뒤 확인해 주세요."
            LauncherEntryGate.SETUP_REQUIRED -> "먼저 런처에서 Termux 연결과 설치 상태를 확인해 주세요."
            LauncherEntryGate.RECOVERY_REQUIRED -> "중단된 작업의 복구가 먼저 필요합니다."
            LauncherEntryGate.ALREADY_RUNNING -> "서버가 이미 실행 중입니다."
            LauncherEntryGate.ALREADY_STOPPED -> "서버가 이미 종료되어 있습니다."
            else -> "런처 화면에서 상태를 확인해 주세요."
        }) },
        confirmButton = { if (allowed) V2DialogAction(onClick = onConfirm) { Text("확인 후 실행") } },
        dismissButton = { V2DialogAction(tone = V2DialogTone.Quiet, onClick = onClose) { Text("닫기") } })
}
