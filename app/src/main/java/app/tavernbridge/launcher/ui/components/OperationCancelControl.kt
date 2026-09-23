package app.tavernbridge.launcher.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.model.WorkProgress

/** Shared by the main operation panel, folder imports and the text editor. */
@Composable
internal fun OperationCancelControl(
    progress: WorkProgress?,
    requested: Boolean,
    startedAtMillis: Long,
    onCancel: () -> Unit,
) {
    var confirming by remember(startedAtMillis) { mutableStateOf(false) }
    val pending = requested || progress?.cancellationRequested == true
    if (!pending && progress?.status in setOf("success", "error", "cancelled")) return

    OutlinedButton(
        onClick = { confirming = true },
        enabled = !pending,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Outlined.Stop, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(if (pending) {
            if (progress?.cancellationMode == "deferred") "중단 대기 중" else "중단 요청 중"
        } else "작업 중단")
    }
    if (confirming && !pending) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("진행 중인 작업을 중단할까요?") },
            text = {
                Text("파일 적용·패키지 설치 중에는 안전한 단계까지 처리한 뒤 중단합니다. 이미 완료된 작업은 되돌리지 않습니다.")
            },
            confirmButton = {
                Button(onClick = { confirming = false; onCancel() }) { Text("중단 요청") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("계속 진행") }
            },
        )
    }
}
