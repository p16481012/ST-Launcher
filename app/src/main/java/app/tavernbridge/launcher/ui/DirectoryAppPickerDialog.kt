package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.model.DirectoryAppOption

@Composable
internal fun DirectoryAppPickerDialog(
    options: List<DirectoryAppOption>,
    error: String,
    onSelect: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var pending by remember(options) { mutableStateOf<DirectoryAppOption?>(null) }
    val unverified = pending
    if (unverified != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("${unverified.label}(으)로 열까요?") },
            text = {
                Text("이 앱의 문서 접근 권한을 확인할 수 없습니다. 앱 자체에서 Termux 폴더 연결을 지원해야 하며, 권한 오류가 나거나 다른 위치가 열릴 수 있습니다. 런처가 추가 접근 권한을 부여하지는 않습니다.")
            },
            confirmButton = {
                Button(onClick = { pending = null; onSelect(unverified.id, true) }) { Text("그래도 열기") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("앱 다시 선택") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("폴더를 열 앱 선택") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (options.any { it.isRecommended })
                    "기존에 자동으로 열리던 앱을 권장합니다. 다른 앱도 선택할 수 있습니다. Termux 위치가 열리면 SillyTavern 폴더를 선택하세요."
                    else "문서 접근 권한을 확인한 앱이 없습니다. 다른 앱은 지원 여부에 따라 열리지 않을 수 있습니다.")
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                if (options.isEmpty()) Text("이 폴더 열기 요청을 처리할 앱을 찾지 못했습니다. 런처 안의 폴더 관리는 계속 사용할 수 있습니다.")
                options.forEach { option ->
                    OutlinedCard(
                        onClick = {
                            if (option.hasDocumentAccess) onSelect(option.id, false) else pending = option
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (option.isRecommended) Text("권장", color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelMedium)
                            Text(option.label, style = MaterialTheme.typography.titleSmall)
                            Text(if (option.hasDocumentAccess) "문서 접근 권한 확인됨" else "지원 확인 필요 · 선택 후 안내",
                                style = MaterialTheme.typography.bodySmall)
                            Text(option.packageName, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text("이 폴더 열기 방식을 처리한다고 등록한 앱만 표시됩니다. 다른 파일 앱이 목록에 없을 수 있으며, 앱 실행 후 실제 폴더가 열렸는지는 런처에서 확인할 수 없습니다.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}
