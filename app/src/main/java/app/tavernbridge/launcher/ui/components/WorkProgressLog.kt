package app.tavernbridge.launcher.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun WorkProgressLog(logText: String, actions: @Composable RowScope.() -> Unit = {}) {
    val clipboard = LocalClipboardManager.current
    val scrollState = rememberScrollState()
    var expanded by remember { mutableStateOf(true) }
    FollowLogTail(logText, scrollState, enabled = expanded)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                    contentDescription = if (expanded) "상세 로그 접기" else "상세 로그 보기"
                    stateDescription = if (expanded) "펼쳐짐" else "접힘"
                },
                contentPadding = PaddingValues(horizontal = 6.dp),
            ) {
                Icon(
                    if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("로그", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            actions()
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(logText)) },
                enabled = logText.isNotBlank(),
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "전체 로그 복사")
            }
        }
        if (expanded) {
            Surface(
                modifier = Modifier.fillMaxWidth().height(210.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp),
            ) {
                SelectionContainer {
                    Text(
                        logText.ifBlank { "아직 출력된 상세 로그가 없습니다." },
                        modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(12.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}
