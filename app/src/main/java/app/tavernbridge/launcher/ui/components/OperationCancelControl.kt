package app.tavernbridge.launcher.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Stop
import app.tavernbridge.launcher.ui.V2AlertDialog
import app.tavernbridge.launcher.ui.V2DialogAction
import app.tavernbridge.launcher.ui.V2DialogTone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.model.WorkProgress

/** Shared by the main operation panel, folder imports and the text editor. */
@Composable
internal fun OperationCancelControl(
    progress: WorkProgress?,
    requested: Boolean,
    startedAtMillis: Long,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirming by remember(startedAtMillis) { mutableStateOf(false) }
    val pending = requested || progress?.cancellationRequested == true
    if (!pending && progress?.status in setOf("success", "error", "cancelled")) return

    val pendingDescription = if (progress?.cancellationMode == "deferred") "중단 대기 중" else "중단 요청 중"
    if (pending) {
        Text(pendingDescription, modifier.semantics { stateDescription = pendingDescription })
        return
    }
    OutlinedButton(
        onClick = { confirming = true },
        modifier = modifier.heightIn(min = 48.dp).semantics {
            contentDescription = "작업 중단"
        },
        contentPadding = PaddingValues(horizontal = 6.dp),
    ) {
        Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text("중단", maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (confirming && !pending) {
        V2AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("진행 중인 작업을 중단할까요?") },
            text = {
                Text("파일 적용·패키지 설치 중에는 안전한 단계까지 처리한 뒤 중단합니다. 이미 완료된 작업은 되돌리지 않습니다.")
            },
            confirmButton = {
                V2DialogAction(tone = V2DialogTone.Danger, onClick = { confirming = false; onCancel() }) { Text("중단 요청") }
            },
            dismissButton = {
                V2DialogAction(tone = V2DialogTone.Quiet, onClick = { confirming = false }) { Text("계속 진행") }
            },
        )
    }
}
