package app.tavernbridge.launcher.model

import org.junit.Assert.*
import org.junit.Test

class HomeServerActionsTest {
    @Test fun startImmediatelyShowsCancelableInlineUntilTermuxPublishesProcess() {
        val pending = homeServerActions(ServerReadiness.OFFLINE, false, true, "start", false)
        assertEquals(HomePrimary.STARTING, pending.primary)
        assertEquals(HomeStop.CANCEL_START, pending.stop)
        assertFalse(pending.canPrimary)
        assertEquals(HomeStop.STOPPING, homeServerActions(ServerReadiness.STARTING, true, true, "start", true).stop)
        assertEquals(HomePrimary.CHAT, homeServerActions(ServerReadiness.READY, true, false, null, false).primary)
    }

    @Test fun stopBlocksChatThenRemovesStop() {
        val stopping = homeServerActions(ServerReadiness.READY, true, true, "stop", false)
        assertFalse(stopping.canPrimary)
        assertEquals(HomeStop.STOPPING, stopping.stop)
        val off = homeServerActions(ServerReadiness.OFFLINE, false, false, null, false)
        assertEquals(HomeStop.HIDDEN, off.stop)
        assertTrue(off.canPrimary)
    }

    @Test fun portConflictDoesNotRestartTheOtherProcessAndUnresponsiveOpensLogs() {
        val conflict = homeServerActions(ServerReadiness.PORT_CONFLICT, false, false, null, false)
        assertEquals(HomePrimary.PORT, conflict.primary)
        assertEquals(HomeStop.HIDDEN, conflict.stop)
        assertEquals(HomePrimary.LOGS, homeServerActions(ServerReadiness.UNRESPONSIVE, true, false, null, false).primary)
    }

    @Test fun stopSubStepOfBackupIsNeverAUserStopAction() {
        val ui = LauncherUiState(isWorking = true, workingStartedAtMillis = 12, dispatchedAtMillis = 12,
            dispatchedOperation = "backup", workProgress = WorkProgress(0, "서버 종료", "", operation = "stop"))
        val controls = homeServerActions(ServerReadiness.READY, true, true, ui.visibleOperation, false)
        assertEquals(HomeStop.BLOCKED, controls.stop)
        assertTrue(controls.otherWork)
        assertEquals("stop", ui.copy(dispatchedAtMillis = -1).visibleOperation)
        assertNull(ui.copy(isWorking = false).visibleOperation)
    }
}
