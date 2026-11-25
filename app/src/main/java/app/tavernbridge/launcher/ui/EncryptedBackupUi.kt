package app.tavernbridge.launcher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import app.tavernbridge.launcher.LauncherViewModel
import app.tavernbridge.launcher.model.*

@Composable
internal fun EncryptedBackupHost(state: LauncherUiState, viewModel: LauncherViewModel) {
    val transfer = state.encryptedBackup
    var sourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var destinationId by rememberSaveable { mutableStateOf<String?>(null) }
    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.selectEncryptedBackupSource(sourceId, uri); sourceId = null
    }
    val targetPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        viewModel.selectEncryptedBackupDestination(destinationId, uri); destinationId = null
    }
    LaunchedEffect(transfer?.id, transfer?.phase) {
        if (transfer != null) when (transfer.phase) {
            EncryptedBackupPhase.PICK_SOURCE -> if (viewModel.encryptedPickerOpened(transfer.id, true)) {
                sourceId = transfer.id
                runCatching { sourcePicker.launch(arrayOf("*/*")) }.onFailure { viewModel.cancelEncryptedBackup(); viewModel.showMessage("파일 선택 화면을 열지 못했습니다.") }
            }
            EncryptedBackupPhase.PICK_DESTINATION -> if (viewModel.encryptedPickerOpened(transfer.id, false)) {
                destinationId = transfer.id
                runCatching { targetPicker.launch("SillyTavern-${transfer.id.take(8)}.stbackup") }.onFailure { viewModel.cancelEncryptedBackup(); viewModel.showMessage("새 파일 저장 화면을 열지 못했습니다.") }
            }
            else -> Unit
        }
    }
    if (transfer == null || transfer.phase in setOf(EncryptedBackupPhase.PICK_SOURCE, EncryptedBackupPhase.SOURCE_OPEN,
            EncryptedBackupPhase.PICK_DESTINATION, EncryptedBackupPhase.DESTINATION_OPEN, EncryptedBackupPhase.RUNNING)) return
    // Deliberately NOT rememberSaveable: passwords never enter saved-instance state.
    var password by remember(transfer.id) { mutableStateOf("") }
    var again by remember(transfer.id) { mutableStateOf("") }
    var backupBefore by rememberSaveable(transfer.id) { mutableStateOf(true) }
    val export = transfer.mode == EncryptedBackupMode.EXPORT
    val entering = transfer.phase == EncryptedBackupPhase.PASSWORD
    val restoring = transfer.phase == EncryptedBackupPhase.RESTORE_CONFIRM
    val canSubmit = entering && password.isNotEmpty() && (!export || (password.length >= 12 && password == again && password.any { !it.isWhitespace() }))
    val canRestore = restoring && !state.environment.processRunning && !state.isWorking
    val close = { password = ""; again = ""; viewModel.cancelEncryptedBackup() }
    V2AlertDialog(onDismissRequest = close,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(when { entering -> "백업 암호"; restoring -> "인증 완료 · 복원 확인"; else -> "백업 처리 실패" }) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (entering) {
                Text(if(export) "12자 이상 암호를 설정하세요. 암호는 저장하지 않으며 분실하면 복구할 수 없습니다." else "백업을 만들 때 사용한 암호를 입력하세요. 전체 인증을 통과하기 전에는 복원하지 않습니다.")
                OutlinedTextField(password, { if(it.length <= 1024) password = it }, label={Text("암호")}, singleLine=true,
                    visualTransformation=PasswordVisualTransformation(), keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password), modifier=Modifier.fillMaxWidth())
                if(export) OutlinedTextField(again, { if(it.length<=1024) again=it }, label={Text("암호 다시 입력")}, singleLine=true,
                    visualTransformation=PasswordVisualTransformation(), keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password), modifier=Modifier.fillMaxWidth())
                if (export && again.isNotEmpty() && password != again) SettingsCaption("두 암호를 같게 입력해 주세요.")
                if(!export) Text("복호화 ZIP을 앱 전용 저장소에서 인증한 뒤 Termux에 전달합니다. 원본 외에 임시 ZIP 두 개와 해제 공간이 필요합니다.", style=MaterialTheme.typography.bodySmall)
            } else if(restoring) {
                Text("같은 항목의 현재 데이터는 백업 내용으로 교체됩니다. 백업 이후의 대화가 사라질 수 있습니다. 원본 암호화 파일은 유지합니다.")
                if(state.environment.sillyTavernInstalled) Row(verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(backupBefore,{backupBefore=it});Text("복원 전에 현재 설치 전체 백업")
                }
                Text("복원 전 ZIP에는 비밀 설정이 포함될 수 있으며 암호화되지 않습니다.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                if(state.environment.processRunning) Text("닫은 뒤 서버를 종료하고 다시 진행해 주세요.",color=MaterialTheme.colorScheme.error)
            } else Text(transfer.error)
        } },
        confirmButton = {
            when {
                canSubmit -> V2DialogAction(onClick = { val secret=password.toCharArray();password="";again="";viewModel.submitEncryptedBackupPassword(secret) }) { Text(if(export) "저장 위치 선택" else "복호화·인증") }
                canRestore -> V2DialogAction(tone = V2DialogTone.Danger, onClick={viewModel.restoreDecryptedBackup(state.environment.sillyTavernInstalled && backupBefore)}) {Text("검사 후 복원")}
            }
        },
        dismissButton = {
            if (canSubmit || canRestore) V2DialogAction(tone = V2DialogTone.Quiet, onClick = close) { Text("닫기") }
            else V2OutlineAction("닫기", close)
        })
}
