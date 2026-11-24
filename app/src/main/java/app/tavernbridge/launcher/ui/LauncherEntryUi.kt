package app.tavernbridge.launcher.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.tavernbridge.launcher.R
import app.tavernbridge.launcher.entry.LauncherEntryAction
import app.tavernbridge.launcher.entry.LauncherShortcutInstaller
import app.tavernbridge.launcher.entry.PinShortcutResult

/** These are settings destinations/actions, not an extra card inside a confirmation prompt. */
@Composable
internal fun LauncherEntrySettings() {
    val context = LocalContext.current
    val installer = remember(context) { LauncherShortcutInstaller(context) }
    var result by rememberSaveable { mutableStateOf<PinShortcutResult?>(null) }
    SettingsHeading("홈 화면 바로가기")
    SettingsGroup {
        LauncherEntryAction.entries.forEachIndexed { index, action ->
            if (index > 0) SettingsDivider()
            val label = stringResource(when (action) {
                LauncherEntryAction.START -> R.string.entry_start
                LauncherEntryAction.STOP -> R.string.entry_stop
                LauncherEntryAction.OPEN -> R.string.entry_open
            })
            SettingsRow(stringResource(R.string.entry_pin_action, label), onClick = { result = installer.requestPin(action) })
        }
    }
    SettingsCaption(result?.let {
        stringResource(when (it) {
            PinShortcutResult.REQUESTED -> R.string.entry_pin_requested
            PinShortcutResult.UNSUPPORTED -> R.string.entry_pin_unsupported
            PinShortcutResult.FAILED -> R.string.entry_pin_failed
        })
    } ?: "바로가기를 눌러도 서버는 바로 시작하거나 종료되지 않아요. 런처에서 먼저 확인해요.")
    var showHelp by rememberSaveable { mutableStateOf(false) }
    SettingsGroup {
        SettingsRow("위젯 · 빠른 설정 타일 사용법", value = if (showHelp) "접기" else "보기",
            onClick = { showHelp = !showHelp })
    }
    if (showHelp) SettingsCaption(stringResource(R.string.entry_tile_widget_help))
}
