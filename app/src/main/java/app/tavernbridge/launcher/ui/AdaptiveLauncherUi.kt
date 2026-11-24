package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.model.MainSection
import app.tavernbridge.launcher.model.launcherTabs

@Composable
internal fun AdaptiveInfoRow(
    label: String,
    value: String,
    monospace: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val fontScale = LocalDensity.current.fontScale
    val valueFont = if (monospace) app.tavernbridge.launcher.ui.theme.V2Mono else MaterialTheme.typography.bodyMedium.fontFamily
    BoxWithConstraints(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        if (shouldStackInfoRow(maxWidth.value, fontScale)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Text(value, fontWeight = FontWeight.SemiBold, fontFamily = valueFont,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(.38f))
                Text(value, fontWeight = FontWeight.SemiBold, fontFamily = valueFont,
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End,
                    modifier = Modifier.weight(.62f))
            }
        }
    }
}

/** Scrollable at large font sizes and in short landscape windows; never clips later destinations. */
@Composable
internal fun LauncherNavigationRail(
    selected: MainSection,
    onSelect: (MainSection) -> Unit,
    icon: (MainSection) -> ImageVector,
    modifier: Modifier = Modifier,
) {
    NavigationRail(modifier = modifier.width(96.dp).fillMaxHeight(), containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            launcherTabs.forEach { section ->
                NavigationRailItem(
                    selected = selected == section,
                    onClick = { onSelect(section) },
                    icon = { V2TabGlyph(section, icon(section), androidx.compose.material3.LocalContentColor.current, Modifier.size(24.dp)) },
                    label = { Text(section.label) },
                    modifier = Modifier.heightIn(min = 64.dp).v2SelectionEdge(selected == section, MaterialTheme.colorScheme.secondary),
                    alwaysShowLabel = true,
                    colors = androidx.compose.material3.NavigationRailItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.outline,
                        unselectedTextColor = MaterialTheme.colorScheme.outline,
                    ),
                )
            }
        }
    }
}
