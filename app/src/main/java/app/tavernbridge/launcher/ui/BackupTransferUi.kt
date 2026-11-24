package app.tavernbridge.launcher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.LauncherViewModel
import app.tavernbridge.launcher.model.BackupTransferAction
import app.tavernbridge.launcher.model.BackupTransferPhase
import app.tavernbridge.launcher.model.BackupTransferState
import java.util.Locale

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
    if (transfer == null || transfer.phase in setOf(BackupTransferPhase.SOURCE_OPEN,
            BackupTransferPhase.DESTINATION_OPEN, BackupTransferPhase.SHARE_READY)) return
    val confirmation = transfer.phase == BackupTransferPhase.CONFIRM
    val copying = transfer.phase == BackupTransferPhase.COPYING
    AlertDialog(
        onDismissRequest = { if (confirmation) viewModel.cancelBackupTransfer() },
        title = { Text(if (confirmation) "백업 ${transfer.action.label}" else if (copying) "백업 저장 중" else "백업 확인 중") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(transfer.archive.fileName, style = MaterialTheme.typography.bodySmall)
                if (confirmation) {
                    Text(if (transfer.action == BackupTransferAction.SHARE)
                        "선택한 앱에 이 ZIP의 읽기 권한을 전달합니다. 대화와 개인정보가 포함될 수 있으니 신뢰하는 앱·상대에게만 공유하세요."
                        else "선택한 위치에 ZIP을 복사합니다. 원본은 그대로 남고, 복사할 만큼의 저장 공간이 필요합니다.")
                    if (transfer.archive.includesSecrets) Text("API 키 등 비밀 설정이 포함된 암호화되지 않은 백업입니다.",
                        color = MaterialTheme.colorScheme.error)
                    Text("접근 권한이 없으면 파일 선택창이 열립니다. 다운로드 폴더에서 위 이름의 ZIP을 선택해 주세요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (copying) {
                    val ratio = if (transfer.total > 0) (transfer.bytes.toDouble() / transfer.total).coerceIn(0.0, 1.0) else 0.0
                    LinearProgressIndicator(progress = { ratio.toFloat() }, modifier = Modifier.fillMaxWidth())
                    Text("${(ratio * 100).toInt()}% · ${transferBytes(transfer.bytes)} / ${transferBytes(transfer.total)}")
                    Text(if (transfer.cancellationRequested) "중단 요청을 전달했습니다. 파일 제공 앱의 응답과 미완성 파일 정리를 기다립니다."
                        else if (transfer.bytes >= transfer.total) "기록 완료와 저장된 파일 크기를 확인하고 있습니다." else "원본 ZIP을 새 저장 위치로 복사하고 있습니다.",
                        style = MaterialTheme.typography.bodySmall)
                } else V2BusyIndicator(linear = false)
            }
        },
        confirmButton = {
            if (confirmation) Button(onClick = viewModel::confirmBackupTransfer) { Text("계속") }
            else TextButton(onClick = viewModel::cancelBackupTransfer, enabled = !transfer.cancellationRequested) {
                Text(if (transfer.cancellationRequested) "중단 중" else "중단")
            }
        },
        dismissButton = { if (confirmation) TextButton(onClick = viewModel::cancelBackupTransfer) { Text("취소") } },
    )
}

private fun transferBytes(value: Long): String = when {
    value >= 1024L * 1024 * 1024 -> String.format(Locale.KOREA, "%.1f GiB", value / (1024.0 * 1024 * 1024))
    value >= 1024L * 1024 -> String.format(Locale.KOREA, "%.1f MiB", value / (1024.0 * 1024))
    value >= 1024L -> String.format(Locale.KOREA, "%.1f KiB", value / 1024.0)
    else -> "$value B"
}
