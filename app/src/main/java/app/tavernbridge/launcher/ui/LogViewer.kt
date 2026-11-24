package app.tavernbridge.launcher.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tavernbridge.launcher.model.searchLog
import app.tavernbridge.launcher.model.visibleLogLines
import app.tavernbridge.launcher.model.isProblemLog
import app.tavernbridge.launcher.security.redactSensitiveText
import app.tavernbridge.launcher.ui.components.FollowLogTail

@Composable
internal fun LogViewer(log: String, panelKey: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var query by rememberSaveable(panelKey) { mutableStateOf("") }
    var problemsOnly by rememberSaveable(panelKey) { mutableStateOf(false) }
    var follow by rememberSaveable(panelKey) { mutableStateOf(true) }
    var shareError by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val clipboard = LocalClipboardManager.current
    val lines = remember(log, query, problemsOnly) { visibleLogLines(log, query, problemsOnly) }
    val visible = remember(lines) { lines.joinToString("\n") }
    val matches = remember(visible, query) { searchLog(visible, query) }
    val highlight = MaterialTheme.colorScheme.tertiaryContainer
    val highlightText = MaterialTheme.colorScheme.onTertiaryContainer
    val error = MaterialTheme.colorScheme.error
    val errorBg = MaterialTheme.colorScheme.errorContainer
    val annotated = remember(visible, lines, matches, highlight, highlightText, error, errorBg) {
        AnnotatedString.Builder(visible).apply {
            var pos = 0
            lines.forEach { line ->
                if (isProblemLog(line) && line.isNotEmpty()) addStyle(SpanStyle(color = error, background = errorBg), pos, pos + line.length)
                pos += line.length + 1
            }
            matches.ranges.forEach { addStyle(SpanStyle(background = highlight, color = highlightText), it.first, it.last + 1) }
        }.toAnnotatedString()
    }
    FollowLogTail(visible, scroll, enabled = follow && query.isEmpty(), resetKey = panelKey to follow)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = query, onValueChange = { if (it.length <= 200) query = it },
            modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("로그에서 찾기") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = { FilterChip(problemsOnly, { problemsOnly = !problemsOnly }, { Text("오류만") }, modifier = Modifier.padding(end = 8.dp)) })
        if (shareError) Text("로그를 공유할 앱을 열지 못했습니다.", color = MaterialTheme.colorScheme.error)
        Surface(Modifier.fillMaxWidth().weight(1f), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp)) {
            SelectionContainer {
                Text(if (visible.isNotBlank()) annotated else AnnotatedString(if (log.isBlank()) "표시할 로그가 없습니다." else "조건에 맞는 로그가 없습니다."),
                    Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconToggleButton(checked = follow, onCheckedChange = { follow = it }) {
                Icon(Icons.Outlined.VerticalAlignBottom, if (follow) "로그 자동 따라가기 끄기" else "로그 자동 따라가기 켜기")
            }
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(redactSensitiveText(visible, maxLength = 60_000))) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp), enabled = visible.isNotBlank(), shape = RoundedCornerShape(14.dp)) { Text("복사") }
            OutlinedButton(onClick = {
                shareError = runCatching {
                    val text = redactSensitiveText(visible, maxLength = 60_000)
                    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(intent, "로그 공유"))
                }.isFailure
            }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), enabled = visible.isNotBlank(), shape = RoundedCornerShape(14.dp)) { Text("공유") }
        }
    }
}
