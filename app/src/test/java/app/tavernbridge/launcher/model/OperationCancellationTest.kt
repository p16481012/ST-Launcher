package app.tavernbridge.launcher.model

import app.tavernbridge.launcher.data.WorkProgressParser
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationCancellationTest {
    private val requestId = "b906fc5e-d14d-4a40-a7c2-016320392025"
    private val otherRequestId = "86cb55cb-158b-4d34-8fc9-948309c6cc30"
    private val operationId = "4123:98765"

    private fun running() = WorkProgress(
        percent = 0,
        phase = "파일 보호 중",
        detail = "",
        operation = "restore",
        operationId = operationId,
        operationRequestId = requestId,
        cancellationMode = "deferred",
    )

    @Test
    fun eachFlowGetsANewStableRequestIdentity() {
        val first = OperationCancellationControl()
        val second = OperationCancellationControl()
        assertEquals(first.requestId, UUID.fromString(first.requestId).toString())
        assertNotEquals(first.requestId, second.requestId)
        val before = first.requestId
        first.beginDispatch("stop")
        first.finishDispatch()
        first.beginDispatch("backup")
        assertEquals(before, first.requestId)
        assertFalse(first.cancellationRequested)
    }

    @Test
    fun cancellationBeforeDispatchNeverMarksACommandAsSent() {
        val control = OperationCancellationControl()
        assertNull(control.dispatchedOperation)
        assertFalse(control.hasDispatched)
        control.requestCancellation()
        assertThrows(UserOperationCancelled::class.java) { control.beginDispatch("import-backup") }
        assertTrue(control.cancellationRequested)
        assertFalse(control.hasDispatched)
        assertNull(control.dispatchedOperation)
    }

    @Test
    fun repeatedRequestsDoNotForgetAnAwaitingBackendCommand() {
        val control = OperationCancellationControl()
        control.beginDispatch("restore")
        repeat(3) { control.requestCancellation() }
        assertTrue(control.hasDispatched)
        assertTrue(control.cancellationRequested)
        assertEquals("restore", control.dispatchedOperation)
        assertThrows(UserOperationCancelled::class.java) { control.checkpoint() }
        // A checkpoint is not an acknowledgement from Termux.
        assertEquals("restore", control.dispatchedOperation)
    }

    @Test
    fun finishingASubcommandDoesNotClearCompoundCancellation() {
        val control = OperationCancellationControl()
        control.beginDispatch("backup")
        control.requestCancellation()
        control.finishDispatch()
        assertNull(control.dispatchedOperation)
        assertTrue(control.hasDispatched)
        assertTrue(control.cancellationRequested)
        assertThrows(UserOperationCancelled::class.java) { control.beginDispatch("update") }
        assertNull(control.dispatchedOperation)
    }

    @Test
    fun cancellationBetweenSuccessfulCompoundStepsPreventsTheNextStep() {
        val control = OperationCancellationControl()
        control.beginDispatch("stop")
        control.finishDispatch()
        control.requestCancellation()
        assertThrows(UserOperationCancelled::class.java) { control.beginDispatch("prepare-persistent-start") }
        assertTrue(control.hasDispatched)
        assertNull(control.dispatchedOperation)
    }

    @Test
    fun anUncancelledCompoundFlowCanDispatchEachStep() {
        val control = OperationCancellationControl()
        for (operation in listOf("stop", "backup", "update", "prepare-persistent-start", "finish-persistent-start")) {
            control.beginDispatch(operation)
            assertEquals(operation, control.dispatchedOperation)
            control.finishDispatch()
            assertNull(control.dispatchedOperation)
            control.checkpoint()
        }
        assertTrue(control.hasDispatched)
    }

    @Test
    fun deferredCancellationKeepsAwaitingTheBackendBeforeBlockingTheNextStep() = runBlocking {
        val control = OperationCancellationControl()
        val reply = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val worker = async(control, start = CoroutineStart.UNDISPATCHED) {
            control.beginDispatch("restore")
            events += "dispatched"
            reply.await()
            events += "backend-finished-rollback"
            control.finishDispatch()
            operationCheckpoint()
            events += "must-not-dispatch-next-step"
        }
        try {
            assertEquals(listOf("dispatched"), events)
            control.requestCancellation()
            assertTrue(worker.isActive)
            assertFalse(worker.isCompleted)
            assertEquals("restore", control.dispatchedOperation)
            reply.complete(Unit)
            var cancelledAtCheckpoint = false
            try {
                worker.await()
            } catch (_: UserOperationCancelled) {
                cancelledAtCheckpoint = true
            }
            assertTrue(cancelledAtCheckpoint)
            assertEquals(listOf("dispatched", "backend-finished-rollback"), events)
            assertNull(control.dispatchedOperation)
            assertTrue(currentCoroutineContext().isActive)
        } finally {
            reply.complete(Unit)
            worker.cancel()
        }
    }

    @Test
    fun localCheckpointUsesTheFlowContextWithoutCancellingItsParentJob() = runBlocking {
        val control = OperationCancellationControl()
        var caught = false
        try {
            withContext(control) {
                operationCheckpoint()
                control.requestCancellation()
                assertTrue(currentCoroutineContext().isActive)
                operationCheckpoint()
            }
        } catch (_: UserOperationCancelled) {
            caught = true
        }
        assertTrue(caught)
        assertTrue(currentCoroutineContext().isActive)
        // Read-only control/status work outside this flow is still allowed.
        operationCheckpoint()
    }

    @Test
    fun committedHousekeepingSuppressesParentControlAndRestoresItAfterwards() = runBlocking {
        val control = OperationCancellationControl()
        withContext(control) {
            control.beginDispatch("backup")
            control.finishDispatch()
            control.requestCancellation()
            val result = withoutOperationCancellation {
                // withContext merges rather than replaces. The raw parent
                // element remains present, so the effective lookup must mask it.
                assertSame(control, currentCoroutineContext()[OperationCancellationControl])
                assertNull(currentCoroutineContext().operationCancellationControl())
                operationCheckpoint()
                "refreshed backup list"
            }
            assertEquals("refreshed backup list", result)
            assertSame(control, currentCoroutineContext().operationCancellationControl())
            assertTrue(control.cancellationRequested)
            assertThrows(UserOperationCancelled::class.java) { control.checkpoint() }
        }
    }

    @Test
    fun nestedHousekeepingKeepsCancellationSuppressedAcrossContextMerges() = runBlocking {
        val control = OperationCancellationControl()
        control.requestCancellation()
        withContext(control) {
            withoutOperationCancellation {
                withoutOperationCancellation {
                    withContext(currentCoroutineContext()) {
                        assertNull(currentCoroutineContext().operationCancellationControl())
                        operationCheckpoint()
                    }
                }
                assertNull(currentCoroutineContext().operationCancellationControl())
                operationCheckpoint()
            }
            assertSame(control, currentCoroutineContext().operationCancellationControl())
        }
    }

    @Test
    fun suppressedHousekeepingStillPropagatesItsRealFailure() = runBlocking {
        val control = OperationCancellationControl()
        val failure = IllegalStateException("[RESTORE_ROLLBACK_REQUIRED] protected journal remains")
        control.requestCancellation()
        withContext(control) {
            var caught: Exception? = null
            try {
                withoutOperationCancellation {
                    operationCheckpoint()
                    throw failure
                }
            } catch (error: Exception) {
                caught = error
            }
            assertSame(failure, caught)
            assertSame(control, currentCoroutineContext().operationCancellationControl())
            assertTrue(currentCoroutineContext().isActive)
        }
    }

    @Test
    fun cancellationSignalWakesExistingAndLaterObserversWithoutCancellingTheFlow() = runBlocking {
        val control = OperationCancellationControl()
        val observer = async(start = CoroutineStart.UNDISPATCHED) {
            control.awaitCancellationRequested()
            "close blocked provider stream"
        }
        try {
            assertTrue(observer.isActive)
            assertFalse(observer.isCompleted)
            repeat(3) { control.requestCancellation() }
            assertEquals("close blocked provider stream", observer.await())
            // This returns immediately even though the observer starts after
            // the request. Repeated cancellation must not lose the signal.
            control.awaitCancellationRequested()
            assertTrue(control.cancellationRequested)
            assertTrue(currentCoroutineContext().isActive)
        } finally {
            observer.cancel()
        }
    }

    @Test
    fun activeRequestIdentityMatchesEvenWhenACompoundSubcommandChanges() {
        val progress = running()
        assertEquals(operationId, cancellationTarget(progress, requestId, ""))
        val nextSubcommand = progress.copy(operation = "start", operationId = "4124:98770")
        assertEquals("4124:98770", cancellationTarget(nextSubcommand, requestId, operationId))
    }

    @Test
    fun mismatchedRequestCannotFallBackToAMatchingPid() {
        assertNull(cancellationTarget(running(), otherRequestId, operationId))
        assertNull(cancellationTarget(running().copy(operationRequestId = ""), requestId, operationId))
    }

    @Test
    fun detachedRecoveryRequiresExactPidAndStartIdentity() {
        assertEquals(operationId, cancellationTarget(running(), "", operationId))
        assertNull(cancellationTarget(running().copy(operationId = "4123:98766"), "", operationId))
        assertNull(cancellationTarget(running().copy(operationId = "4124:98765"), "", operationId))
        assertNull(cancellationTarget(running(), "", ""))
    }

    @Test
    fun missingMalformedAndTerminalProgressCannotBeCancelled() {
        assertNull(cancellationTarget(null, requestId, operationId))
        for (id in listOf("", "4123", ":98765", "4123:", "-1:2", "1:-2", "1:2:3", "1:2\n", "pid:time", "1:$(id)")) {
            assertFalse("Accepted malformed identity: $id", validOperationId(id))
            assertNull(cancellationTarget(running().copy(operationId = id), requestId, operationId))
        }
        for (status in listOf("success", "error", "cancelled", "unknown", "")) {
            assertNull(cancellationTarget(running().copy(status = status), requestId, operationId))
        }
    }

    @Test
    fun parserRestoresDeferredRequestWithoutClaimingTheOperationHasStopped() {
        val progress = WorkProgressParser.parse(
            "operation=restore\nstatus=running\noperation_id=$operationId\noperation_request_id=$requestId\n" +
                "cancellation_requested=1\ncancellation_mode=deferred\noperation_started_at=100\nphase_started_at=110\n",
        )!!
        assertEquals(operationId, progress.operationId)
        assertEquals(requestId, progress.operationRequestId)
        assertTrue(progress.cancellationRequested)
        assertEquals("deferred", progress.cancellationMode)
        assertEquals("running", progress.status)
        assertEquals(100_000L, progress.operationStartedAtMillis)
        assertEquals(operationId, cancellationTarget(progress, "", operationId))
        assertNotEquals(RecoveredOperationStatus.SUCCESS, recoveredOperationStatus(progress.status))
    }

    @Test
    fun parserPreservesFinalCancellationSeparatelyFromRollbackFailure() {
        val cancelled = WorkProgressParser.parse(
            "operation=restore\nstatus=cancelled\nerror_code=OPERATION_CANCELLED\n" +
                "operation_id=$operationId\noperation_request_id=$requestId\ncancellation_requested=1\nfinished_at=200\n",
        )!!
        assertEquals("OPERATION_CANCELLED", cancelled.errorCode)
        assertEquals("cancelled", cancelled.status)
        assertEquals(200_000L, cancelled.finishedAtMillis)
        assertNull(cancellationTarget(cancelled, requestId, operationId))
        assertEquals(RecoveredOperationStatus.FAILURE, recoveredOperationStatus(cancelled.status))
        val rollbackFailed = WorkProgressParser.parse(
            "operation=restore\nstatus=error\nerror_code=RESTORE_ROLLBACK_REQUIRED\n" +
                "operation_id=$operationId\ncancellation_requested=1\n",
        )!!
        assertTrue(rollbackFailed.cancellationRequested)
        assertEquals("error", rollbackFailed.status)
        assertEquals("RESTORE_ROLLBACK_REQUIRED", rollbackFailed.errorCode)
        assertNull(cancellationTarget(rollbackFailed, "", operationId))
    }

    @Test
    fun parserDoesNotTurnOldOrUnknownMetadataIntoAnImmediateCancellation() {
        val old = WorkProgressParser.parse("operation=backup\nstatus=running\n")!!
        assertEquals("none", old.cancellationMode)
        assertFalse(old.cancellationRequested)
        assertNull(cancellationTarget(old, requestId, operationId))
        val malformed = WorkProgressParser.parse(
            "operation=backup\nstatus=running\ncancellation_mode=force-kill\ncancellation_requested=true\n",
        )!!
        assertEquals("none", malformed.cancellationMode)
        assertFalse(malformed.cancellationRequested)
    }
}
