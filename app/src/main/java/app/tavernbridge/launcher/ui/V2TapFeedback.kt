package app.tavernbridge.launcher.ui

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.theme.LocalV2ReducedMotion
import kotlinx.coroutines.delay

/** Associate an actual forward navigation with the exact row tapped, not just its destination. */
internal class ReturnAnchors {
    private var current: Long? = null
    private var pending: Pair<String, Long>? = null
    private val links = mutableMapOf<Long, String>()
    var returning by mutableStateOf<String?>(null)
        private set
    fun tap(key: String, now: Long = SystemClock.uptimeMillis()) { pending = key to now }
    fun route(nav: LauncherNavigation, now: Long = SystemClock.uptimeMillis()) {
        val old = current
        if (old == nav.entryId) return
        returning = null
        if (old != null && nav.entryId < old) returning = links.remove(nav.entryId)
        else if (old != null && nav.previous.lastOrNull()?.id == old) {
            pending?.takeIf { now - it.second in 0..500L }?.let { links[old] = it.first }
        }
        val live = nav.previous.mapTo(mutableSetOf()) { it.id }
        links.keys.retainAll(live)
        pending = null
        current = nav.entryId
    }
    fun clearHighlight(key: String) { if (returning == key) returning = null }
    fun cancelTap() { pending = null }
}

internal val LocalV2ReturnAnchors = staticCompositionLocalOf<ReturnAnchors?> { null }
internal val LocalV2TabAttention = staticCompositionLocalOf { emptySet<MainSection>() }

internal fun LauncherUiState.tabAttention(): Set<MainSection> = buildSet {
    if (environment.recoveryPending || (updatePreflight?.updateAvailable ?: updateAvailable) == true ||
        diagnosticReport?.let { it.warningCount + it.errorCount > 0 } == true) add(MainSection.LOGS)
    if (backupReminder.notice(backupReminderNowMillis, environment.sillyTavernInstalled) != null) add(MainSection.BACKUPS)
    if (environment.serverReadiness == ServerReadiness.PORT_CONFLICT || environment.serverReadiness == ServerReadiness.UNRESPONSIVE)
        add(MainSection.HOME)
}

@Composable
internal fun Modifier.v2RowTap(key: String, enabled: Boolean = true, role: Role? = null, click: () -> Unit): Modifier {
    val links = LocalV2ReturnAnchors.current
    val returning = links?.returning == key
    LaunchedEffect(returning) { if (returning) { delay(300); links?.clearHighlight(key) } }
    val interactions = remember { MutableInteractionSource() }
    val down by interactions.collectIsPressedAsState()
    val focused by interactions.collectIsFocusedAsState()
    val reduced = LocalV2ReducedMotion.current
    val ink = MaterialTheme.colorScheme.onSurface
    val alpha by animateFloatAsState(if (down) .08f else if (returning) .04f else 0f,
        animationSpec = tween(if (down || returning || reduced) 0 else 100), label = "행 누름")
    return this.drawWithContent {
        drawContent()
        if (alpha > 0f) drawRect(ink.copy(alpha = alpha))
        if (focused && !down) drawRect(ink, style = Stroke(width = 2.dp.toPx()))
    }
        .clickable(interactionSource = interactions, indication = null, enabled = enabled, role = role) { links?.tap(key); click() }
}

/** A persistent shape as well as a tint; does not move text or change a row's hit target. */
internal fun Modifier.v2SelectionEdge(active: Boolean, color: Color): Modifier = if (!active) this else drawWithContent {
    drawContent()
    val inset = 5.dp.toPx()
    val x = if (layoutDirection == LayoutDirection.Ltr) inset else size.width - inset
    val half = minOf(10.dp.toPx(), size.height / 4)
    drawLine(color, Offset(x, center.y - half), Offset(x, center.y + half), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
}

@Composable
internal fun V2TabGlyph(section: MainSection, vector: ImageVector, color: Color, modifier: Modifier = Modifier) {
    val alert = section in LocalV2TabAttention.current
    Box(modifier.then(if (alert) Modifier.semantics { stateDescription = "확인 필요" } else Modifier)) {
        Icon(vector, null, Modifier.fillMaxSize(), tint = color)
        if (alert) Spacer(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-1).dp).size(6.dp)
            .background(MaterialTheme.colorScheme.secondary, CircleShape))
    }
}
