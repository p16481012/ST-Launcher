package app.tavernbridge.launcher.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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

private class PreviousTail(var lines: List<String>? = null)

@Composable
internal fun LogViewer(log: String, panelKey: String, modifier: Modifier = Modifier) = key(panelKey) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var problemsOnly by rememberSaveable { mutableStateOf(false) }
    var shareError by rememberSaveable { mutableStateOf(false) }
    val applied = debouncedListQuery(query)
    val lines = remember(log, applied, problemsOnly) { visibleLogLines(log.trimEnd('\n', '\r'), applied, problemsOnly) }
    val visible = remember(lines) { lines.joinToString("\n") }
    val scroll = rememberScrollState()
    var following by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val reduced = LocalV2ReducedMotion.current
    val clipboard = LocalClipboardManager.current
    val cs = MaterialTheme.colorScheme
    val annotated = remember(visible, lines, applied, cs) {
        AnnotatedString.Builder(visible).apply {
            var pos = 0
            for (line in lines) {
                if (isProblemLog(line) && line.isNotEmpty()) addStyle(SpanStyle(color = cs.error, background = cs.errorContainer), pos, pos + line.length)
                else logTimestampLength(line).takeIf { it > 0 }?.let { addStyle(SpanStyle(color = cs.outline), pos, pos + it) }
                pos += line.length + 1
            }
            searchLog(visible, applied).ranges.forEach { addStyle(SpanStyle(background = cs.secondaryContainer, color = cs.onSecondaryContainer), it.first, it.last + 1) }
        }.toAnnotatedString()
    }
    val previous = remember(applied, problemsOnly) { PreviousTail() }
    val firstNew = remember(lines, previous) {
        // A tab switch, a filter change and initial content must never animate as incoming messages.
        previous.lines?.let { retainedLogPrefix(it, lines) } ?: lines.size
    }
    SideEffect { previous.lines = lines }
    val suffixOffset = remember(lines, firstNew) { lines.take(firstNew).sumOf { it.length + 1 }.coerceAtMost(visible.length) }
    val newAlpha = remember(lines, previous) { Animatable(if (!reduced && firstNew < lines.size) 0f else 1f) }
    LaunchedEffect(newAlpha, reduced) {
        if (reduced) newAlpha.snapTo(1f) else if (newAlpha.value < 1f) newAlpha.animateTo(1f, tween(120))
    }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.isScrollInProgress to scroll.canScrollForward }.collect { (moving, canScrollForward) ->
            // A taller log alone does not represent a user scrolling away from the end.
            if (moving) following = !canScrollForward
        }
    }
    LaunchedEffect(scroll.maxValue, following, applied) {
        if (following && applied.isEmpty() && !scroll.isScrollInProgress && scroll.maxValue != Int.MAX_VALUE)
            scroll.scrollTo(scroll.maxValue)
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
                    Column(Modifier.fillMaxSize().testTag("live-log-viewport").verticalScroll(scroll).padding(16.dp)) {
                        if (visible.isBlank()) Text(if (log.isBlank()) "표시할 로그가 없습니다." else "조건에 맞는 로그가 없습니다.", fontFamily = V2Mono, fontSize = 12.sp)
                        else {
                            if (suffixOffset > 0) {
                                val end = if (firstNew < lines.size) suffixOffset - 1 else annotated.length
                                Text(annotated.subSequence(0, end), fontFamily = V2Mono, fontSize = 12.sp, lineHeight = 18.sp)
                            }
                            if (firstNew < lines.size) Text(annotated.subSequence(suffixOffset, annotated.length), Modifier.graphicsLayer { alpha = newAlpha.value },
                                fontFamily = V2Mono, fontSize = 12.sp, lineHeight = 18.sp)
                        }
                    }
                }
                AnimatedVisibility(showJump, Modifier.align(Alignment.BottomEnd).padding(10.dp),
                    enter = fadeIn(tween(if (reduced) 0 else 150)), exit = fadeOut(tween(if (reduced) 0 else 150))) {
                    IconButton({ scope.launch {
                        if (reduced) scroll.scrollTo(scroll.maxValue) else scroll.animateScrollTo(scroll.maxValue, tween(300))
                        following = true
                    } }, Modifier.size(48.dp).semantics { contentDescription = "맨 아래로" }) {
                        Surface(Modifier.size(40.dp), shape = CircleShape, color = cs.onSurface, contentColor = cs.surface) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.ArrowDownward, null) }
                        }
                    }
                }
            }
        }
        V2BottomActions { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ clipboard.setText(AnnotatedString(redactSensitiveText(visible, maxLength = 60_000))) },
                Modifier.weight(1f).heightIn(min = 48.dp), enabled = visible.isNotBlank(), shape = RoundedCornerShape(14.dp)) { Text("복사") }
            OutlinedButton({
                shareError = runCatching {
                    val text = redactSensitiveText(visible, maxLength = 60_000)
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "로그 공유"))
                }.isFailure
            }, Modifier.weight(1f).heightIn(min = 48.dp), enabled = visible.isNotBlank(), shape = RoundedCornerShape(14.dp)) { Text("공유") }
        } }
    }
}
