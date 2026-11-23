package app.tavernbridge.launcher.entry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LauncherEntryPolicyTest {
    private fun gate(
        action: LauncherEntryAction = LauncherEntryAction.START,
        foreground: Boolean = true,
        setup: Boolean = true,
        working: Boolean = false,
        recovery: Boolean = false,
        running: Boolean = false,
    ) = LauncherEntryPolicy.evaluate(action, foreground, setup, working, recovery, running)

    @Test fun onlyKnownActionsCanBeParsed() {
        LauncherEntryAction.entries.forEach { assertEquals(it, LauncherEntryAction.parse(it.name)) }
        for (input in listOf(null, "start", "START; rm -rf", "START\n", "RESTART", "https://example.org")) {
            assertNull(LauncherEntryAction.parse(input))
        }
    }

    @Test fun allExternalRequestsRequireForegroundAndCurrentReadiness() {
        LauncherEntryAction.entries.forEach { action ->
            assertEquals(LauncherEntryGate.FOREGROUND_REQUIRED, gate(action, foreground = false))
        }
        listOf(LauncherEntryAction.START, LauncherEntryAction.STOP).forEach { action ->
            assertEquals(LauncherEntryGate.SETUP_REQUIRED, gate(action, setup = false))
            assertEquals(LauncherEntryGate.BUSY, gate(action, working = true))
        }
        assertEquals(LauncherEntryGate.RECOVERY_REQUIRED, gate(recovery = true))
    }

    @Test fun readyActionsStillRequireExplicitConfirmation() {
        assertEquals(LauncherEntryGate.CONFIRM_START, gate())
        assertEquals(LauncherEntryGate.CONFIRM_STOP, gate(LauncherEntryAction.STOP, running = true))
        assertEquals(LauncherEntryGate.CONFIRM_OPEN, gate(LauncherEntryAction.OPEN))
    }

    @Test fun repeatedStartStopDoNotExecuteAgain() {
        assertEquals(LauncherEntryGate.ALREADY_RUNNING, gate(running = true))
        assertEquals(LauncherEntryGate.ALREADY_STOPPED, gate(LauncherEntryAction.STOP))
        assertEquals(LauncherEntryGate.BUSY, gate(running = true, working = true))
    }

    @Test fun openingLauncherNeverNeedsSetupAndStoppingRemainsAvailableForRecovery() {
        assertEquals(LauncherEntryGate.CONFIRM_OPEN, gate(LauncherEntryAction.OPEN, setup = false, working = true, recovery = true))
        assertEquals(LauncherEntryGate.CONFIRM_STOP, gate(LauncherEntryAction.STOP, running = true, recovery = true))
        assertEquals(LauncherEntryGate.BUSY, gate(LauncherEntryAction.STOP, running = true, recovery = true, working = true))
    }
}
