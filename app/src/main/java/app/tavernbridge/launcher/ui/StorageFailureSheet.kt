package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.tavernbridge.launcher.model.OperationResultSummary
import app.tavernbridge.launcher.model.SpaceLocation
import app.tavernbridge.launcher.model.StorageFailureAdvice

@Composable
internal fun StorageFailureSheet(summary: OperationResultSummary, advice: StorageFailureAdvice,
    recoveryPending: Boolean, onClose: () -> Unit, onDownloads: () -> Unit, onInventory: () -> Unit,
    onDeviceStorage: () -> Unit, onRetry: () -> Unit, onHistory: () -> Unit) {
    val (actionLabel, action) = when {
        advice.location == SpaceLocation.DOWNLOAD -> "다운로드 폴더 보기" to onDownloads
        advice.location == SpaceLocation.PRIVATE_SAFETY && !recoveryPending -> "안전 사본 목록 보기" to onInventory
        else -> "휴대폰 저장 공간 열기" to onDeviceStorage
    }
    V2BottomSheet(onClose = onClose, footer = if (summary.canRetry) ({
        V2OutlineAction("다시 시도", onRetry, Modifier.fillMaxWidth())
    }) else null) {
        Text("저장 공간을 확인해 주세요", style = MaterialTheme.typography.titleLarge)
        Text("${summary.title} 작업을 마치지 못했어요. 확인할 위치: ${advice.location.description}.")
        if (advice.needed != null && advice.free != null) {
            Text("검사 당시 필요 ${formatBackupBytes(advice.needed)} · 여유 ${formatBackupBytes(advice.free)}")
        } else Text("실패 시점의 필요 용량과 남은 용량은 확인되지 않았어요.")
        Text(if (recoveryPending) "아직 복구 중인 자료가 있어요. 그 자료는 지우지 말고 다른 파일부터 확인해 주세요."
            else "다른 곳에 사본이 있는 파일부터 확인해 주세요. Termux의 앱 데이터를 지우면 설치와 채팅을 잃을 수 있어요.")
        SettingsGroup {
            V2NavigationRow(actionLabel, action, description = when {
                advice.location == SpaceLocation.DOWNLOAD -> "파일 앱이 열려요."
                advice.location == SpaceLocation.PRIVATE_SAFETY && !recoveryPending -> ""
                else -> "Android 설정 앱이 열려요."
            })
            SettingsDivider()
            V2NavigationRow("작업 기록 보기", onHistory)
        }
        FailureTechnicalDetails(summary.completedAtMillis, summary.detail, summary.errorCode)
    }
}
