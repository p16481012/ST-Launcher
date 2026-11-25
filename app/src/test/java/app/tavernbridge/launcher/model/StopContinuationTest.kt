package app.tavernbridge.launcher.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopContinuationTest {
    @Test fun abandonedContinuationIsAnnouncedExactlyOnceOnlyAfterVerifiedStop() {
        val before = AbandonedStopNotice()
        assertFalse(before.invalidate()); assertTrue(before.verified()); assertFalse(before.invalidate())
        val after = AbandonedStopNotice()
        assertFalse(after.verified()); assertTrue(after.invalidate()); assertFalse(after.verified())
        val failed = AbandonedStopNotice()
        assertFalse(failed.invalidate()); assertFalse(failed.invalidate())
    }

    private val env = EnvironmentStatus(termuxInstalled = true, commandPermissionGranted = true,
        managerConnected = true, sillyTavernInstalled = true, processRunning = true, serverReachable = true)
    private val live = LauncherUiState(isWorking = false, environment = env,
        termuxSetup = TermuxSetupState(TermuxSetupStatus.READY))

    @Test fun offerAndVerifiedStoppedAreDifferentGates() {
        assertTrue(live.readyForStopContinuation(true))
        assertFalse(live.readyForStopContinuation(false))
        val stopped = live.copy(environment = env.copy(processRunning = false, serverReachable = false))
        assertFalse(stopped.readyForStopContinuation(true))
        assertTrue(stopped.readyForStopContinuation(false))
        assertFalse(stopped.copy(environment = stopped.environment.copy(serverReachable = true)).readyForStopContinuation(false))
    }

    @Test fun aRunningServerMustBeTheOnlyReasonTheOperationIsUnavailable() {
        for (state in listOf(live.copy(isWorking = true), live.copy(cancellationRequested = true),
            live.copy(fileBrowserMutating = true), live.copy(termuxSetup = TermuxSetupState()),
            live.copy(environment = env.copy(operationActive = true)),
            live.copy(environment = env.copy(recoveryPending = true)), live.copy(environment = env.copy(managerConnected = false)),
            live.copy(environment = env.copy(commandPermissionGranted = false)), live.copy(environment = env.copy(sillyTavernInstalled = false)))) {
            assertFalse(state.readyForStopContinuation(true))
        }
    }
}
