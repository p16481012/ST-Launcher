package app.tavernbridge.launcher.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tavernbridge.launcher.model.searchLog
import app.tavernbridge.launcher.security.redactSensitiveText
import app.tavernbridge.launcher.ui.components.FollowLogTail

@Composable
internal fun LogViewer(log: String, panelKey: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var query by rememberSaveable(panelKey) { mutableStateOf("") }
    var searchOpen by rememberSaveable(panelKey) { mutableStateOf(false) }
    var follow by rememberSaveable(panelKey) { mutableStateOf(true) }
    var shareError by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val matches = remember(log, query) { searchLog(log, query) }
    val highlight = MaterialTheme.colorScheme.tertiaryContainer
    val highlightText = MaterialTheme.colorScheme.onTertiaryContainer
    val annotated = remember(log, matches, highlight, highlightText) {
        AnnotatedString.Builder(log).apply {
            matches.ranges.forEach { addStyle(SpanStyle(background = highlight, color = highlightText), it.first, it.last + 1) }
        }.toAnnotatedString()
    }
    FollowLogTail(log, scroll, enabled = follow && query.isEmpty(), resetKey = panelKey to follow)
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" }) {
                Icon(Icons.Outlined.Search, "로그 검색")
            }
            IconToggleButton(checked = follow, onCheckedChange = { follow = it }) {
                Icon(Icons.Outlined.VerticalAlignBottom, if (follow) "로그 자동 따라가기 끄기" else "로그 자동 따라가기 켜기")
            }
            IconButton(onClick = {
                shareError = runCatching {
                    val text = redactSensitiveText(log, maxLength = 60_000)
                    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(intent, "로그 공유"))
                }.isFailure
            }) { Icon(Icons.Outlined.Share, "로그 공유 앱 선택") }
        }
        if (searchOpen) {
            OutlinedTextField(value = query, onValueChange = { if (it.length <= 200) query = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("찾을 내용") },
                trailingIcon = { IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "검색어 지우기") } },
                supportingText = { if (query.isNotEmpty()) Text("${matches.ranges.size}${if (matches.capped) "개 이상" else "개"} 일치 · 검색 중 자동 스크롤 일시 정지") })
        }
        if (shareError) Text("로그를 공유할 앱을 열지 못했습니다.", color = MaterialTheme.colorScheme.error)
        Surface(Modifier.fillMaxWidth().weight(1f), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(20.dp)) {
            SelectionContainer {
                Text(annotated, Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }
}
