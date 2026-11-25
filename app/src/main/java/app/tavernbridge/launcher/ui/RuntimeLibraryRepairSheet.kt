package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
                if (submitted) SettingsCaption("Termux 패키지 갱신을 요청했어요.")
                else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    V2DialogAction(onClick = { confirming = false }, tone = V2DialogTone.Quiet) { Text("취소") }
                    Box(Modifier.weight(1f)) { V2SheetPrimaryButton("동의하고 갱신·재검사") {
                        if (!submitted) { submitted = true; onSync(failureAt) }
                    } }
                }
            } else {
                V2SheetPrimaryButton("Termux 실행 도구 갱신하기") { confirming = true }
                OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("전체 갱신 없이 다시 시도") }

            }
        }
    }) {
        if (confirming) {
            Text("Termux 패키지 전체를 갱신할까요?", style = MaterialTheme.typography.titleLarge)
            Text("Node.js를 다시 검사해 같은 라이브러리 오류가 남아 있을 때만 갱신해요. SillyTavern용 도구뿐 아니라 이 Termux에서 쓰는 다른 패키지도 바뀔 수 있어요.")
            Text("인터넷과 여유 공간이 필요해요. Node.js와 npm이 실행되는지 확인한 뒤에만 실행 모듈을 다시 구성해요. 캐릭터·채팅·설정은 지우지 않아요.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("Termux에서 Node.js를 시작할 수 없어요", style = MaterialTheme.typography.titleLarge)
            Text("Node.js에 필요한 파일(라이브러리)이 없거나 버전이 맞지 않아요. Termux 패키지를 함께 갱신하면 고칠 수 있어요. 동의하기 전에는 갱신하지 않아요.")
            FailureTechnicalDetails(failureAt, failure.detail, failure.errorCode)
            V2NavigationRow("기록 보기", onHistory)
        }
    }
}

@Composable
internal fun FailureTechnicalDetails(resultAt: Long, detail: String, errorCode: String) {
    var expanded by remember(resultAt) { mutableStateOf(false) }
    V2OutlineAction(if (expanded) "기술 정보 접기" else "기술 정보 보기", { expanded = !expanded })
    if (expanded) SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (detail.isNotBlank()) Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (errorCode.isNotBlank()) Text(errorCode, style = MaterialTheme.typography.labelMedium)
        }
    }
}
