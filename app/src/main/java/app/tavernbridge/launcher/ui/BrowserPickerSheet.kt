package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import app.tavernbridge.launcher.model.BrowserOption

/** Choosing a row is the explicit open action; merely showing or restoring the sheet never opens an app. */
@Composable
internal fun BrowserPickerSheet(options: List<BrowserOption>?, onClose: () -> Unit, open: (String, Boolean) -> Unit) {
    var rememberChoice by rememberSaveable { mutableStateOf(false) }
    var dispatched by remember { mutableStateOf(false) }
    V2BottomSheet(onClose, footer = if (options.isNullOrEmpty()) null else ({
        SettingsGroup {
            SettingsRow("항상 이 앱으로 열기", checked = rememberChoice) { rememberChoice = !rememberChoice }
        }
    })) {
        Text("열 앱 선택", style = MaterialTheme.typography.titleLarge)
        when {
            options == null -> SettingsCaption("열 수 있는 앱을 확인하고 있어요.")
            options.isEmpty() -> SettingsCaption("이 주소를 열 수 있는 앱을 찾지 못했어요.")
            else -> {
                SettingsCaption("앱을 누르면 바로 열려요.")
                Column(Modifier.fillMaxWidth().selectableGroup()) {
                    options.forEachIndexed { index, option ->
                        if (index > 0) SettingsDivider()
                        SettingsRadioRow(option.label, chosen = false) {
                            if (!dispatched) { dispatched = true; open(option.packageName, rememberChoice) }
                        }
                    }
                }
            }
        }
    }
}
