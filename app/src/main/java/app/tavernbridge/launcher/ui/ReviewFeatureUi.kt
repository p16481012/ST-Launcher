package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.model.UpdatePreflight

@Composable
internal fun UpdateChangesPreview(report: UpdatePreflight) {
    var expanded by rememberSaveable(report.currentCommit, report.targetCommit) { mutableStateOf(false) }
    if (!report.updateAvailable) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { expanded = !expanded }) {
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
                                Text(change.commit.take(7), fontFamily = app.tavernbridge.launcher.ui.theme.V2Mono,
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
