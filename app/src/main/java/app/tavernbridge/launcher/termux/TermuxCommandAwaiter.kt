package app.tavernbridge.launcher.termux

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun awaitTermuxCommandResult(
    callbackId: String,
    timeoutMillis: Long,
    results: Flow<TermuxCommandResult>,
    execute: () -> Unit,
): TermuxCommandResult = coroutineScope {
    // Subscribe before dispatch so even an immediate callback cannot be lost.
    val pending = async(start = CoroutineStart.UNDISPATCHED) {
        withTimeoutOrNull(timeoutMillis) { results.first { it.callbackId == callbackId } }
    }
    try {
        execute()
        pending.await() ?: throw IllegalStateException(
            "[TERMUX_TIMEOUT]\nTermux 응답 대기 시간이 지났습니다. 작업이 계속 실행 중일 수 있으므로 상태를 확인해 주세요.",
        )
    } finally {
        pending.cancel()
    }
}
