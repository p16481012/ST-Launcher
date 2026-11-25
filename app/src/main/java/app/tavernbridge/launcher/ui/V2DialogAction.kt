package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.ui.theme.LocalLauncherColors

internal enum class V2DialogTone { Primary, Quiet, Danger }

/** All modal actions keep a touch target but expand for wrapping or larger text. */
@Composable
internal fun V2DialogAction(
    onClick: () -> Unit,
    enabled: Boolean = true,
    tone: V2DialogTone = V2DialogTone.Primary,
    content: @Composable RowScope.() -> Unit,
) {
    val palette = LocalLauncherColors.current
    val background = when (tone) {
        V2DialogTone.Primary -> palette.ink
        V2DialogTone.Quiet -> androidx.compose.ui.graphics.Color.Transparent
        V2DialogTone.Danger -> palette.danger
    }
    val foreground = when (tone) {
        V2DialogTone.Quiet -> palette.ink2
        V2DialogTone.Danger -> if (palette.dark) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White
        V2DialogTone.Primary -> palette.onInk
    }
    Button(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp).alpha(if (enabled) 1f else if (palette.highContrast) .60f else .38f),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = background, contentColor = foreground,
            disabledContainerColor = background, disabledContentColor = foreground),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
    ) {
        ProvideTextStyle(MaterialTheme.typography.titleMedium) { content() }
    }
}
