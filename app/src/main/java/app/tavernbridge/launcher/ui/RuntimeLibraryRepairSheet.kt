package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
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
import app.tavernbridge.launcher.model.LauncherUiState
import app.tavernbridge.launcher.model.canOfferRuntimeSync

@Composable
internal fun RuntimeLibraryRepairSheet(state: LauncherUiState, failureAt: Long, onDismiss: () -> Unit,
    onRetry: () -> Unit, onHistory: () -> Unit, onSync: (Long) -> Unit) {
    if (!state.canOfferRuntimeSync(failureAt)) return
    val failure = state.lastOperationResult ?: return
    // Leaving this sheet or recreating the Activity always discards the confirmation.
    var confirming by remember(failureAt) { mutableStateOf(false) }
    var submitted by remember(failureAt) { mutableStateOf(false) }
    V2BottomSheet(onClose = onDismiss, footer = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (confirming) {
                V2SheetPrimaryButton("동의하고 갱신·재검사", enabled = !submitted) {
                    if (!submitted) { submitted = true; onSync(failureAt) }
                }
                TextButton(onClick = { confirming = false }, modifier = Modifier.fillMaxWidth()) { Text("취소") }
            } else {
                V2SheetPrimaryButton("Termux 패키지 갱신 옵션") { confirming = true }
                OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("전체 갱신 없이 다시 시도") }
                TextButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) { Text("기록 보기") }
            }
        }
    }) {
        if (confirming) {
            Text("Termux 패키지 전체를 갱신할까요?", style = MaterialTheme.typography.titleLarge)
            Text("Node.js를 다시 검사해 같은 라이브러리 오류가 남아 있을 때만 갱신해요. SillyTavern용 도구뿐 아니라 이 Termux에서 쓰는 다른 패키지도 바뀔 수 있어요.")
            Text("인터넷과 여유 공간이 필요해요. Node.js와 npm이 실행되는지 확인한 뒤에만 실행 모듈을 다시 구성해요. 캐릭터·채팅·설정은 지우지 않아요.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("Node.js 실행 라이브러리를 확인해 주세요", style = MaterialTheme.typography.titleLarge)
            Text("Node.js가 필요한 라이브러리나 심볼을 찾지 못했어요. 아직 Termux 패키지 전체를 갱신하지 않았어요.")
            Text(failure.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(failure.errorCode, style = MaterialTheme.typography.labelMedium)
        }
    }
}
