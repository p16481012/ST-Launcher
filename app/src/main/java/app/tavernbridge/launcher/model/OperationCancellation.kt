package app.tavernbridge.launcher.model

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred

internal class UserOperationCancelled : CancellationException("[OPERATION_CANCELLED]\n사용자가 작업 중단을 요청했습니다.")

/** Cooperative cancellation: never abandon a live Termux callback by cancelling its coroutine. */
internal class OperationCancellationControl : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<OperationCancellationControl>
    val requestId: String = UUID.randomUUID().toString()
    private val requested = AtomicBoolean(false)
    private val cancellationSignal = CompletableDeferred<Unit>()
    private val dispatch = AtomicReference<String?>(null)
    val cancellationRequested: Boolean get() = requested.get()
    val dispatchedOperation: String? get() = dispatch.get()
    @Volatile var hasDispatched: Boolean = false
        private set

    fun requestCancellation() { requested.set(true); cancellationSignal.complete(Unit) }
    suspend fun awaitCancellationRequested() { cancellationSignal.await() }
    fun checkpoint() { if (requested.get()) throw UserOperationCancelled() }
    fun beginDispatch(operation: String) {
        checkpoint()
        hasDispatched = true
        dispatch.set(operation)
    }
    fun finishDispatch() { dispatch.set(null) }
}

internal suspend fun operationCheckpoint() {
    currentCoroutineContext().operationCancellationControl()?.checkpoint()
}

private object CancellationSuppressed : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<CancellationSuppressed>
}

internal fun CoroutineContext.operationCancellationControl(): OperationCancellationControl? =
    if (this[CancellationSuppressed.Key] != null) null else this[OperationCancellationControl]

/** withContext merges contexts; minusKey alone would not remove the parent's control. */
internal suspend fun <T> withoutOperationCancellation(block: suspend () -> T): T =
    withContext(CancellationSuppressed) { block() }

internal fun validOperationId(value: String): Boolean = value.matches(Regex("[0-9]+:[0-9]+"))

internal fun cancellationTarget(progress: WorkProgress?, requestId: String, detachedOperationId: String): String? {
    if (progress == null || progress.status != "running" || !validOperationId(progress.operationId)) return null
    return progress.operationId.takeIf {
        if (requestId.isNotBlank()) progress.operationRequestId == requestId
        else detachedOperationId.isNotBlank() && progress.operationId == detachedOperationId
    }
}
