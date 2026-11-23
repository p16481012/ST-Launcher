package app.tavernbridge.launcher.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagerDeploymentStateTest {
    @Test fun untouchedOrCollidingDirectoryCannotBeCleaned() {
        val state = ManagerDeploymentState()
        assertFalse(state.canCleanStaging)
        state.dispatching()
        state.acknowledged(createsStaging = true, succeeded = false)
        assertFalse(state.canCleanStaging)
    }

    @Test fun lostPrepareCallbackDoesNotProveOwnershipOrIdleness() {
        val state = ManagerDeploymentState()
        state.dispatching()
        assertFalse(state.canCleanStaging)
    }

    @Test fun cancellationBetweenAcknowledgedChunksCanCleanOwnedStaging() {
        val state = ManagerDeploymentState()
        state.dispatching()
        state.acknowledged(createsStaging = true, succeeded = true)
        assertTrue(state.canCleanStaging)
        state.dispatching()
        assertFalse(state.canCleanStaging)
        state.acknowledged(createsStaging = false, succeeded = true)
        assertTrue(state.canCleanStaging)
    }

    @Test fun failedChunkWithTerminalAcknowledgementCanBeCleaned() {
        val state = ManagerDeploymentState()
        state.dispatching()
        state.acknowledged(createsStaging = true, succeeded = true)
        state.dispatching()
        state.acknowledged(createsStaging = false, succeeded = false)
        assertTrue(state.canCleanStaging)
    }

    @Test fun timedOutOrCancelledInstallerCannotRaceCleanup() {
        val state = ManagerDeploymentState()
        state.dispatching()
        state.acknowledged(createsStaging = true, succeeded = true)
        state.dispatching()
        // There is deliberately no terminal acknowledgement. The shell may still be running.
        assertFalse(state.canCleanStaging)
    }

    @Test fun successfulInstallerAlreadyRemovedStagingAndNeedsNoExtraDispatch() {
        val state = ManagerDeploymentState()
        state.dispatching()
        state.acknowledged(createsStaging = true, succeeded = true)
        state.dispatching()
        state.acknowledged(createsStaging = false, succeeded = true, removesStaging = true)
        assertFalse(state.canCleanStaging)
    }

    @Test fun failedInstallerCannotProveThatItsChildrenHaveStopped() {
        val state = ManagerDeploymentState()
        state.dispatching()
        state.acknowledged(createsStaging = true, succeeded = true)
        state.dispatching()
        state.acknowledged(createsStaging = false, succeeded = false, removesStaging = true)
        assertFalse(state.canCleanStaging)
    }
}
