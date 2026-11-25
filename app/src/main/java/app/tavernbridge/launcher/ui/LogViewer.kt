package app.tavernbridge.launcher.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.security.redactSensitiveText
import app.tavernbridge.launcher.ui.theme.LocalV2ReducedMotion
import app.tavernbridge.launcher.ui.theme.V2Mono
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Composable
internal fun LogViewer(log: String, panelKey: String, modifier: Modifier = Modifier) = key(panelKey) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var problemsOnly by rememberSaveable { mutableStateOf(false) }
    var shareError by rememberSaveable { mutableStateOf(false) }
    val applied = debouncedListQuery(query)
    val timeline = remember { LiveLogTimeline() }
    val frame = remember(log) { timeline.update(log, panelKey == DiagnosticPanel.SERVER_LOG.name || panelKey == "SERVER") }
    val lines = remember(frame, applied, problemsOnly) {
        frame.rows.filter { (!problemsOnly || isProblemLog(it.text)) && (applied.isBlank() || it.text.contains(applied, ignoreCase = true)) }
    }
    val visible = remember(lines) { lines.joinToString("\n") { if (it.source == LogSource.ACCESS) "[access] ${it.text}" else it.text } }
    val scroll = rememberLazyListState()
    var following by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val reduced = LocalV2ReducedMotion.current
    val clipboard = LocalClipboardManager.current
    val cs = MaterialTheme.colorScheme
    val newAlpha = remember(frame) { Animatable(if (!reduced && frame.firstNewId != Long.MAX_VALUE) 0f else 1f) }
    LaunchedEffect(newAlpha, reduced) {
        if (reduced) newAlpha.snapTo(1f) else if (newAlpha.value < 1f) newAlpha.animateTo(1f, tween(120))
    }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.isScrollInProgress to scroll.canScrollForward }.collect { (moving, canScrollForward) ->
            // A taller log alone does not represent a user scrolling away from the end.
            if (moving) following = !canScrollForward
        }
    }
    LaunchedEffect(lines.lastOrNull()?.id, following, applied, problemsOnly) {
        if (following && lines.isNotEmpty() && !scroll.isScrollInProgress) scroll.scrollToItem(lines.size)
    }
    val showJump = !following && scroll.canScrollForward
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FileLogSearchField(query, { query = it }, "로그에서 찾기", Modifier.weight(1f))
            FilterChip(selected = problemsOnly, onClick = { problemsOnly = !problemsOnly }, label = { Text("오류만") },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = cs.secondaryContainer, selectedLabelColor = cs.onSecondaryContainer))
        }
        if (shareError) Text("로그를 공유할 앱을 열지 못했습니다.", color = cs.error)
        Surface(Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(20.dp), color = cs.surface) {
            Box(Modifier.fillMaxSize()) {
                SelectionContainer {
                    LazyColumn(Modifier.fillMaxSize().testTag("live-log-viewport"), state = scroll, contentPadding = PaddingValues(16.dp)) {
                        if (lines.isEmpty()) item { Text(if (frame.rows.isEmpty()) "표시할 로그가 없습니다." else "조건에 맞는 로그가 없습니다.", fontFamily = V2Mono, fontSize = 12.sp) }
                        items(lines, key = { it.id }) { row ->
                            val annotated = remember(row, applied, cs) {
                                val tag = if (row.source == LogSource.ACCESS) "[access] " else ""
                                AnnotatedString.Builder(tag + row.text).apply {
                                    if (tag.isNotEmpty()) addStyle(SpanStyle(color = cs.outline), 0, tag.length)
                                    if (row.text.isNotEmpty() && isProblemLog(row.text)) addStyle(SpanStyle(color = cs.error, background = cs.errorContainer), tag.length, length)
                                    else logTimestampLength(row.text).takeIf { it > 0 }?.let { addStyle(SpanStyle(color = cs.outline), tag.length, tag.length + it) }
                                    searchLog(row.text, applied).ranges.forEach { addStyle(SpanStyle(background = cs.secondaryContainer, color = cs.onSecondaryContainer), tag.length + it.first, tag.length + it.last + 1) }
                                }.toAnnotatedString()
                            }
                            Text(annotated, Modifier.graphicsLayer { alpha = if (row.id >= frame.firstNewId) newAlpha.value else 1f },
                                fontFamily = V2Mono, fontSize = 12.sp, lineHeight = 18.sp)
                        }
                        item(key = "end") { Spacer(Modifier.height(1.dp)) }
                    }
                }
                androidx.compose.animation.AnimatedVisibility(showJump, Modifier.align(Alignment.BottomEnd).padding(10.dp),
                    enter = fadeIn(tween(if (reduced) 0 else 150)), exit = fadeOut(tween(if (reduced) 0 else 150))) {
                    Button({ scope.launch {
                        if (reduced) scroll.scrollToItem(lines.size) else scroll.scroll {
                            val layout = LazyLayoutScrollScope(scroll, this)
                            val target = lines.size.coerceAtMost((layout.itemCount - 1).coerceAtLeast(0))
                            var moved = 0f
                            androidx.compose.animation.core.animate(0f, layout.calculateDistanceTo(target).toFloat(), animationSpec = tween(300)) { position, _ ->
                                moved += scrollBy(position - moved)
                            }
                            layout.snapToItem(target, 0)
                        }
                        following = true
                    } }, Modifier.heightIn(min = 48.dp).semantics { contentDescription = "맨 아래로" }) {
                        Icon(Icons.Outlined.ArrowDownward, null, Modifier.size(16.dp)); Text("맨 아래로")
                    }
                }
            }
        }
        if (visible.isNotBlank()) V2BottomActions { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ clipboard.setText(AnnotatedString(redactSensitiveText(visible, maxLength = 60_000))) },
                Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) { Text("복사") }
            OutlinedButton({
                shareError = runCatching {
                    val text = redactSensitiveText(visible, maxLength = 60_000)
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "로그 공유"))
                }.isFailure
            }, Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) { Text("공유") }
        } }
    }
}
