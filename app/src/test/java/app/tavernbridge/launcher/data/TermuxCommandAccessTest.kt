package app.tavernbridge.launcher.data

import app.tavernbridge.launcher.model.TermuxSetupState
import app.tavernbridge.launcher.model.TermuxSetupStatus
import app.tavernbridge.launcher.model.UserOperationCancelled
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TermuxCommandAccessTest {
    @Test fun cancellationAfterVerifiedDoctorResponseKeepsAccessToCancelQueuedOperations() {
        val ready = TermuxSetupState(TermuxSetupStatus.READY)
        assertTrue(TermuxCommandAccess.keepsVerifiedResponse(ready, UserOperationCancelled()))
        assertNull(TermuxCommandAccess.blockedState("cancel", ready, true, true))
    }

    @Test fun cancellationBeforeDoctorResponseAndOtherFailuresNeverKeepUnprovenAccess() {
        for (status in TermuxSetupStatus.entries.filter { it != TermuxSetupStatus.READY }) {
            assertFalse(TermuxCommandAccess.keepsVerifiedResponse(TermuxSetupState(status), UserOperationCancelled()))
        }
        val ready = TermuxSetupState(TermuxSetupStatus.READY)
        assertFalse(TermuxCommandAccess.keepsVerifiedResponse(ready, IllegalStateException("timeout")))
        assertFalse(TermuxCommandAccess.keepsVerifiedResponse(ready, CancellationException("scope cancelled")))
    }

    @Test fun exactDoctorCanEstablishAccessWithoutAnyAuxiliaryProbeResult() {
        TermuxSetupStatus.entries.forEach { cached ->
            assertNull(TermuxCommandAccess.blockedState("doctor", TermuxSetupState(cached), true, true))
        }
    }

    @Test fun cachedExternalDenialDoesNotBlockRetryAfterUserCorrectsTheSetting() {
        val denied = TermuxSetupState(TermuxSetupStatus.EXTERNAL_APPS_DISABLED)
        assertNull(TermuxCommandAccess.blockedState("doctor", denied, true, true))
        assertEquals(denied, TermuxCommandAccess.blockedState("install release", denied, true, true))
    }

    @Test fun everyCommandIncludingDoctorRequiresFreshAndroidPrerequisites() {
        val ready = TermuxSetupState(TermuxSetupStatus.READY)
        for (arguments in listOf("doctor", "install release", "start", "reset-installation", "restore backup.zip")) {
            assertEquals(TermuxSetupStatus.TERMUX_MISSING,
                TermuxCommandAccess.blockedState(arguments, ready, false, true)?.status)
            assertEquals(TermuxSetupStatus.PERMISSION_REQUIRED,
                TermuxCommandAccess.blockedState(arguments, ready, true, false)?.status)
        }
    }

    @Test fun onlyExactDoctorGetsTheReadinessExemption() {
        val unverified = TermuxSetupState(TermuxSetupStatus.UNVERIFIED)
        for (arguments in listOf("doctor ", " doctor", "doctor install", "doctor; install release",
            "doctor\nreset-installation", "diagnose", "progress", "cancel", "install release", "reset-installation")) {
            assertNotNull(TermuxCommandAccess.blockedState(arguments, unverified, true, true))
        }
    }

    @Test fun verifiedAccessAllowsNormalCommandsWithoutAnotherProbe() {
        val ready = TermuxSetupState(TermuxSetupStatus.READY)
        for (arguments in listOf("start", "stop", "progress", "backup", "cancel", "install release")) {
            assertNull(TermuxCommandAccess.blockedState(arguments, ready, true, true))
        }
    }
}
