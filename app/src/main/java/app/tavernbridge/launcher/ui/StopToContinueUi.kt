package app.tavernbridge.launcher.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import app.tavernbridge.launcher.model.LauncherUiState
import app.tavernbridge.launcher.model.readyForStopContinuation
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal typealias OpenAfterStop = (String, () -> Unit) -> Unit
internal val LocalVerifiedStop = staticCompositionLocalOf<(suspend (Long) -> Boolean)?> { null }

private class StopPrompt(val label: String, val open: () -> Unit)

/** Nothing is saved across process death/activity recreation. Switching destinations, changing
 * a selected target or leaving the foreground invalidates the UI intent, never the server stop. */
@Composable
internal fun rememberStopToContinue(state: LauncherUiState, origin: Any? = null): OpenAfterStop? {
    val verify = LocalVerifiedStop.current ?: return null
    val entry = state.navigation.entryId
    return key(entry, state.section, state.toolPage, origin) {
        val scope = rememberCoroutineScope()
        val owner = LocalView.current.findViewTreeLifecycleOwner()
        var prompt by remember { mutableStateOf<StopPrompt?>(null) }
        var waiter by remember { mutableStateOf<Job?>(null) }
        val canOffer = state.readyForStopContinuation(true)
        DisposableEffect(owner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) { prompt = null; waiter?.cancel(); waiter = null }
            }
            owner?.lifecycle?.addObserver(observer)
            onDispose { owner?.lifecycle?.removeObserver(observer); waiter?.cancel() }
        }
        if (!canOffer) LaunchedEffect(Unit) { prompt = null }
        prompt?.takeIf { canOffer }?.let { request ->
            V2AlertDialog(onDismissRequest = { prompt = null },
                title = { Text("서버를 끄고 이어갈까요?") },
                text = { Text("실행 중인 대화가 끊길 수 있어요. 종료를 확인하면 ‘${request.label}’ 화면으로 이어가요. 파일은 자동으로 변경하지 않아요.") },
                dismissButton = { V2DialogAction(tone = V2DialogTone.Quiet, onClick = { prompt = null }) { Text("취소") } },
                confirmButton = { V2DialogAction(onClick = {
                    if (prompt !== request || waiter?.isActive == true) return@V2DialogAction
                    prompt = null
                    waiter = scope.launch {
                        if (verify(entry) && owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true) {
                            request.open()
                        }
                    }
                }) { Text("서버 끄고 이어가기") } })
        }
        val open: OpenAfterStop = { label, next ->
            if (waiter?.isActive != true && prompt == null) prompt = StopPrompt(label, next)
        }
        if (canOffer) open else null
    }
}
