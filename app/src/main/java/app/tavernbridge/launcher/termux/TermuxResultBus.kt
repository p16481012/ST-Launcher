package app.tavernbridge.launcher.termux

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object TermuxResultBus {
    private val mutableResults = MutableSharedFlow<TermuxCommandResult>(extraBufferCapacity = 16)
    val results = mutableResults.asSharedFlow()

    fun publish(result: TermuxCommandResult) {
        mutableResults.tryEmit(result)
    }
}
