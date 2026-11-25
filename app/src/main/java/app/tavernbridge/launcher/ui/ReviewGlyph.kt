package app.tavernbridge.launcher.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Local raster exports of the revision board's glyphs, at their native 4x density. */
@Composable internal fun ReviewGlyph(@DrawableRes res: Int, size: Dp = 20.dp, tint: Color = LocalContentColor.current,
    description: String? = null) {
    Icon(painterResource(res), description, Modifier.size(size), tint = tint)
}
