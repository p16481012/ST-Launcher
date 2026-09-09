package app.tavernbridge.launcher.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.model.OperationResultSummary

@Composable
fun OperationResultCard(
    summary: OperationResultSummary,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = if (summary.succeeded) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val content = if (summary.succeeded) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (summary.succeeded) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = content,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (summary.succeeded) "${summary.title} 완료" else "${summary.title} 실패",
                    color = content,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(summary.detail, color = content.copy(alpha = .82f), style = MaterialTheme.typography.bodyMedium)
            if (summary.errorCode.isNotBlank()) {
                Text(
                    "오류 코드 ${summary.errorCode}",
                    color = content,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                "${summary.completedAt} · ${summary.durationSeconds}초",
                color = content.copy(alpha = .68f),
                style = MaterialTheme.typography.bodySmall,
            )
            if (summary.canRetry) {
                FilledTonalButton(
                    onClick = onRetry,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("다시 시도")
                }
            }
        }
    }
}
