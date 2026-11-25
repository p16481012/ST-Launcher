package app.tavernbridge.launcher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.LauncherViewModel
import app.tavernbridge.launcher.model.BackupTransferAction
import app.tavernbridge.launcher.model.BackupTransferPhase
import app.tavernbridge.launcher.model.BackupTransferState

/** Separate from restoration: selecting a ZIP here never imports or changes installation data. */
@Composable
internal fun BackupTransferHost(transfer: BackupTransferState?, viewModel: LauncherViewModel) {
    val context = LocalContext.current
    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        viewModel.selectBackupTransferSource(it)
    }
    val destinationPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {
        viewModel.exportBackupTo(it)
    }
    LaunchedEffect(transfer?.phase) {
        when (transfer?.phase) {
            BackupTransferPhase.PICK_SOURCE -> if (viewModel.backupTransferPickerOpened(source = true)) {
                runCatching { sourcePicker.launch(arrayOf("*/*")) }.onFailure { viewModel.failBackupTransferPicker() }
            }
            BackupTransferPhase.PICK_DESTINATION -> if (viewModel.backupTransferPickerOpened(source = false)) {
                runCatching { destinationPicker.launch(transfer.archive.fileName) }.onFailure { viewModel.failBackupTransferPicker() }
            }
            BackupTransferPhase.SHARE_READY -> viewModel.takeBackupShareIntent()?.let { intent ->
                runCatching { context.startActivity(intent) }.onFailure { viewModel.showMessage("백업을 받을 앱을 열 수 없습니다. 다른 위치에 저장을 사용해 주세요.") }
            }
            else -> Unit
        }
    }
    if (transfer?.phase != BackupTransferPhase.CONFIRM) return
    V2AlertDialog(
        onDismissRequest = viewModel::cancelBackupTransfer,
        title = { Text("백업 ${transfer.action.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(transfer.archive.fileName, style = MaterialTheme.typography.bodySmall)
                Text(if (transfer.action == BackupTransferAction.SHARE)
                    "선택한 앱에 이 ZIP의 읽기 권한을 전달합니다. 대화와 개인정보가 포함될 수 있으니 신뢰하는 앱·상대에게만 공유하세요."
                    else "선택한 위치에 ZIP을 복사합니다. 원본은 그대로 남고, 복사할 만큼의 저장 공간이 필요합니다.")
                if (transfer.archive.includesSecrets) Text("API 키 등 비밀 설정이 포함된 암호화되지 않은 백업입니다.",
                    color = MaterialTheme.colorScheme.error)
                else if (transfer.action == BackupTransferAction.SHARE) Text("이전 버전이나 다른 런처에서 만든 ZIP은 secrets.json이 없어도 외부 접속 로그인 정보를 담고 있을 수 있어요.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Text("접근 권한이 없으면 파일 선택창이 열립니다. 다운로드 폴더에서 위 이름의 ZIP을 선택해 주세요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { V2DialogAction(onClick = viewModel::confirmBackupTransfer) { Text("계속") } },
        dismissButton = { V2DialogAction(tone = V2DialogTone.Quiet, onClick = viewModel::cancelBackupTransfer) { Text("취소") } },
    )
}
