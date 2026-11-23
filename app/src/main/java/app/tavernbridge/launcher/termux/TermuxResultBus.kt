package app.tavernbridge.launcher.termux

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object TermuxResultBus {
    private val mutableResults = MutableSharedFlow<TermuxCommandResult>(extraBufferCapacity = 16)
    val results = mutableResults.asSharedFlow()
    internal val callbacks = TermuxCallbackRegistry()

    fun publish(result: TermuxCommandResult) {
        callbacks.deliver(result.callbackId) { mutableResults.tryEmit(result) }
    }
}
