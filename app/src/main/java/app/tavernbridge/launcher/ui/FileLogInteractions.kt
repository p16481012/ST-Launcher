package app.tavernbridge.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
internal fun debouncedListQuery(query: String): String {
    var applied by remember { mutableStateOf(query) }
    LaunchedEffect(query) {
        if (query.isNotEmpty()) delay(300)
        applied = query
    }
    return if (query.isEmpty()) "" else applied
}

@Composable
internal fun FileLogSearchField(query: String, change: (String) -> Unit, hint: String,
    modifier: Modifier = Modifier, emptyTrailing: @Composable (() -> Unit)? = null) {
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    // Let the IME consume the first Back. The next Back leaves the query intact.
    BackHandler(focused && !imeVisible) { focus.clearFocus() }
    val cs = MaterialTheme.colorScheme
    OutlinedTextField(value = query, onValueChange = { change(it.take(200)) }, singleLine = true,
        modifier = modifier.onFocusChanged { focused = it.isFocused || it.hasFocus },
        placeholder = { Text(hint) }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = cs.onSurface, cursorColor = cs.secondary),
        trailingIcon = if (query.isEmpty()) emptyTrailing else ({
            IconButton(onClick = { change("") }) { Icon(Icons.Outlined.Close, "검색어 지우기") }
        }))
}
