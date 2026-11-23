package app.tavernbridge.launcher.model

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ServerMaintenanceTest {
    @Test fun runningServerIsStoppedBeforeWorkAndThenResumed() = runBlocking {
        val calls = mutableListOf<String>()
        val result = withPausedServer(true, { calls += "stop" }, { calls += "inspect"; true }, { calls += "start" }, { false }) { calls += "work"; 42 }
        assertEquals(listOf("stop", "work", "inspect", "start"), calls)
        assertEquals(ServerMaintenanceResult(42, ServerResumeStatus.STARTED), result)
    }
    @Test fun stoppedServerIsNeverStarted() = runBlocking {
        val result = withPausedServer(false, { fail() }, { fail(); false }, { fail() }, { false }) { 42 }
        assertEquals(ServerResumeStatus.NOT_NEEDED, result.resumeStatus)
    }
    @Test fun failedStartCannotRelabelCommittedWorkAsFailed() = runBlocking {
        val result = withPausedServer(true, {}, { true }, { error("start failed") }, { false }) { 42 }
        assertEquals(42, result.value)
        assertEquals(ServerResumeStatus.FAILED, result.resumeStatus)
    }
    @Test fun cancellationOrActiveWriterPreventsRestart() = runBlocking {
        val cancelled = withPausedServer(true, {}, { fail(); false }, { fail() }, { true }) { 42 }
        assertEquals(ServerResumeStatus.LEFT_STOPPED, cancelled.resumeStatus)
        val active = withPausedServer(true, {}, { false }, { fail() }, { false }) { 42 }
        assertEquals(ServerResumeStatus.LEFT_STOPPED, active.resumeStatus)
    }
    @Test fun failedWorkIsStillTheFailureEvenIfRecoveryStartAlsoFails() = runBlocking {
        val original = IllegalStateException("backup failed")
        try {
            withPausedServer(true, {}, { true }, { error("start failed") }, { false }) { throw original }
            fail("must fail")
        } catch (actual: IllegalStateException) {
            assertSame(original, actual)
            assertEquals(1, actual.suppressed.size)
        }
    }
    @Test fun cancellationDuringWorkNeverResumes() = runBlocking {
        try {
            withPausedServer(true, {}, { fail(); true }, { fail() }, { false }) { throw UserOperationCancelled() }
            fail("must cancel")
        } catch (_: UserOperationCancelled) { }
    }
    @Test fun failedStopNeverStartsWork() = runBlocking {
        try {
            withPausedServer(true, { error("stop failed") }, { fail(); true }, { fail() }, { false }) { fail() }
            fail("must fail")
        } catch (error: IllegalStateException) { assertEquals("stop failed", error.message) }
    }
    @Test fun cancelledFollowUpStartPreservesSuccessfulWork() = runBlocking {
        val result = withPausedServer(true, {}, { true }, { throw UserOperationCancelled() }, { false }) { 42 }
        assertEquals(42, result.value)
        assertEquals(ServerResumeStatus.LEFT_STOPPED, result.resumeStatus)
    }
}
