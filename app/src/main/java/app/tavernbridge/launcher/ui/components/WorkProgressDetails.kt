package app.tavernbridge.launcher.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.model.WorkProgress
import app.tavernbridge.launcher.ui.ProgressActivityState
import app.tavernbridge.launcher.ui.activityState
import app.tavernbridge.launcher.ui.formatProgressBytes
import app.tavernbridge.launcher.ui.formatProgressDuration
import app.tavernbridge.launcher.ui.formatProgressItem
import app.tavernbridge.launcher.ui.formatRecentProgressLog
import java.util.Locale

@Composable
internal fun WorkProgressDetails(progress: WorkProgress, nowMillis: Long) {
    val complete = progress.status == "success"
    val currentItem = formatProgressItem(progress.currentItem)
    val recentHistory = formatRecentProgressLog(progress.logText)
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (progress.progressMode == "bytes" || progress.totalBytes > 0L || progress.completedBytes > 0L) {
            Text(
                if (progress.totalBytes > 0L) {
                    "처리 용량  ${formatProgressBytes(progress.completedBytes)} / ${formatProgressBytes(progress.totalBytes)}"
                } else {
                    "처리 용량  ${formatProgressBytes(progress.completedBytes)} · 전체 용량 확인 중"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (progress.progressMode == "files" || progress.totalFiles > 0L || progress.completedFiles > 0L) {
            val completed = String.format(Locale.KOREA, "%,d", progress.completedFiles)
            val total = String.format(Locale.KOREA, "%,d", progress.totalFiles)
            Text(
                if (progress.totalFiles > 0L) "처리 항목  $completed / ${total}개" else "처리 항목  ${completed}개",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (currentItem.isNotBlank()) {
            Text("처리 대상", style = MaterialTheme.typography.labelSmall)
            Text(
                currentItem,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!complete) {
            Text(
                if (progress.measuredPercent == null) {
                    "전체 처리량을 알 수 없는 단계는 % 없이 표시합니다."
                } else {
                    "현재 단계의 처리량입니다. 다음 단계에서는 0%부터 다시 표시될 수 있습니다."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            if (progress.phaseStartedAtMillis > 0L) {
                Text(
                    "현재 단계 ${formatProgressDuration((nowMillis - progress.phaseStartedAtMillis) / 1_000L)} 경과",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            val activityState = progress.activityState(nowMillis)
            val activityAge = formatProgressDuration((nowMillis - progress.activityAtMillis) / 1_000L)
            val responseAge = formatProgressDuration((nowMillis - progress.heartbeatAtMillis) / 1_000L)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = when (activityState) {
                    ProgressActivityState.RESPONSE_STALE -> MaterialTheme.colorScheme.errorContainer
                    ProgressActivityState.NO_RECENT_ACTIVITY -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        when (activityState) {
                            ProgressActivityState.WAITING_FOR_RESPONSE -> "상태 확인 응답 대기 중"
                            ProgressActivityState.RESPONSE_STALE -> "상태 확인 응답이 ${responseAge} 동안 없습니다."
                            ProgressActivityState.NO_RECENT_ACTIVITY -> "상태 확인 응답 중 · 최근 처리량 변화 없음"
                            ProgressActivityState.ACTIVE -> "처리·출력 변화 ${activityAge} 전"
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (activityState != ProgressActivityState.ACTIVE && progress.activityAtMillis > 0L) {
                        Text("마지막 처리·출력 변화 ${activityAge} 전", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        when (activityState) {
                            ProgressActivityState.RESPONSE_STALE ->
                                "파일 제공 앱·Termux의 응답 지연이나 종료 여부를 확인하세요. 응답 지연만으로 작업 중단을 확정할 수는 없습니다."
                            ProgressActivityState.NO_RECENT_ACTIVITY ->
                                "감시 응답은 오지만 처리량·로그 변화는 감지되지 않았습니다. 대기 중일 수도 있으므로 중단으로 단정하지 않습니다."
                            ProgressActivityState.WAITING_FOR_RESPONSE ->
                                "새 처리 상태를 기다리고 있습니다. 회전 표시만으로 실제 작업 진행을 판단하지 않습니다."
                            ProgressActivityState.ACTIVE -> "상태 확인 응답 ${responseAge} 전"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("최근 처리 내역", style = MaterialTheme.typography.labelMedium)
                Text(
                    recentHistory.ifBlank { "아직 기록된 세부 처리 내역이 없습니다." },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 10,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
