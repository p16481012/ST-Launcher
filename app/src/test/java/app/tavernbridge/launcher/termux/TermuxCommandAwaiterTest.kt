package app.tavernbridge.launcher.termux

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxCommandAwaiterTest {
    private fun result(id: String) = TermuxCommandResult(id, "ok", "", 0, -1, "")

    @Test
    fun immediateCallbackIsCollectedBeforeDispatchReturns() = runBlocking {
        val results = MutableSharedFlow<TermuxCommandResult>(extraBufferCapacity = 2)
        val expected = result("ours")
        val actual = awaitTermuxCommandResult("ours", 1_000, results) {
            assertEquals(1, results.subscriptionCount.value)
            assertTrue(results.tryEmit(result("unrelated")))
            assertTrue(results.tryEmit(expected))
        }
        assertSame(expected, actual)
        assertEquals(0, results.subscriptionCount.value)
    }

    @Test
    fun missingCallbackBecomesNormalFailureWithStableCode() = runBlocking {
        val results = MutableSharedFlow<TermuxCommandResult>()
        val error = runCatching { awaitTermuxCommandResult("ours", 10, results) {} }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertFalse(error is CancellationException)
        assertTrue(error?.message.orEmpty().startsWith("[TERMUX_TIMEOUT]"))
        assertEquals(0, results.subscriptionCount.value)
    }

    @Test
    fun outerCancellationRemainsCancellation() = runBlocking {
        val results = MutableSharedFlow<TermuxCommandResult>()
        val error = runCatching {
            withTimeout(10) { awaitTermuxCommandResult("ours", 10_000, results) {} }
        }.exceptionOrNull()
        assertTrue(error is TimeoutCancellationException)
        assertEquals(0, results.subscriptionCount.value)
    }

    @Test
    fun dispatchFailureCancelsPendingCollector() = runBlocking {
        val results = MutableSharedFlow<TermuxCommandResult>()
        val expected = SecurityException("permission revoked")
        val error = runCatching {
            awaitTermuxCommandResult("ours", 10_000, results) { throw expected }
        }.exceptionOrNull()
        // Coroutine stacktrace recovery may copy an exception across a suspension.
        assertEquals(expected.javaClass, error?.javaClass)
        assertEquals(expected.message, error?.message)
        assertEquals(0, results.subscriptionCount.value)
    }
}
