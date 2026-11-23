package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.model.BackupReminderNotice
import app.tavernbridge.launcher.model.UpdatePreflight

@Composable
internal fun BackupReminderCard(notice: BackupReminderNotice, onOpen: () -> Unit, onSnooze: () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("백업을 확인해 주세요", style = MaterialTheme.typography.titleMedium)
            Text(if (notice.ageDays == null) "최근 사용자 데이터 백업 기록을 확인하지 못했습니다."
                else "마지막 사용자 데이터 백업 기록은 ${notice.ageDays}일 전입니다.")
            Text("생성 기록 기준이며, 백업 파일이 지금도 남아 있는지는 백업 목록에서 확인해 주세요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpen) { Text("백업 확인") }
                TextButton(onClick = onSnooze) { Text("7일 뒤에 알림") }
            }
        }
    }
}

@Composable
internal fun BackupReminderSetting(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("백업 알림", fontWeight = FontWeight.SemiBold)
            Text("사용자 데이터 백업 기록이 없거나 7일이 지나면 홈에서 안내합니다. 자동 백업은 실행하지 않습니다.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = enabled, onCheckedChange = onChange)
    }
}

@Composable
internal fun UpdateChangesPreview(report: UpdatePreflight) {
    var expanded by rememberSaveable(report.currentCommit, report.targetCommit) { mutableStateOf(false) }
    if (!report.updateAvailable) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { expanded = !expanded }) {
            Text("변경 내역 미리보기")
            Icon(if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (expanded) "변경 내역 접기" else "변경 내역 펼치기")
        }
        if (expanded) {
            if (!report.changesAvailable) {
                Text("변경 내역을 불러오지 못했습니다. 안전 검사를 다시 실행하면 재조회합니다.",
                    style = MaterialTheme.typography.bodySmall)
            } else {
                Text("SillyTavern 커밋 제목입니다. 기능별 릴리스 설명과는 다를 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer {
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        report.changes.forEach { change ->
                            Column {
                                Text(change.subject, style = MaterialTheme.typography.bodyMedium)
                                Text(change.commit.take(7), fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (report.changesTruncated) Text("최근 ${report.changes.size}개만 표시합니다.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
