package app.tavernbridge.launcher.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

private const val DEFAULT_SAMPLE = "비 오는 오후, 창가에 앉아 어제 나눈 대화를 읽었어요."

@Composable
internal fun AppearanceTextPreview(accentName: String) {
    val colors = MaterialTheme.colorScheme
    // A private scratchpad, not a preference or a value to include in a diagnostic report.
    var customText by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    Surface(Modifier.fillMaxWidth(), color = colors.surface, shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, colors.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("글자 미리보기", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Surface(color = colors.secondaryContainer, contentColor = colors.onSecondaryContainer, shape = RoundedCornerShape(8.dp)) {
                    Text(accentName, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(customText.ifBlank { DEFAULT_SAMPLE }, style = MaterialTheme.typography.bodyLarge)
            Text("안내 문구도 이 크기로 보여요.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (editing) OutlinedTextField(customText, { customText = it.take(200) }, Modifier.fillMaxWidth(),
                singleLine = true, label = { Text("미리 볼 문장") }, placeholder = { Text("비워 두면 예시 문장을 보여줘요.") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); editing = false }))
            TextButton(onClick = { if (editing) focus.clearFocus(); editing = !editing }) {
                Text(if (editing) "입력 마치기" else "내 문장으로 미리보기")
            }
        }
    }
}
