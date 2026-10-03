package app.tavernbridge.launcher.data

import app.tavernbridge.launcher.model.TermuxSetupStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TermuxSetupProbeTest {
    @Test
    fun missingAppAndPermissionAreSeparatePrerequisites() {
        assertEquals(TermuxSetupStatus.TERMUX_MISSING, TermuxSetupProbe.prerequisites(false, false)?.status)
        assertEquals(TermuxSetupStatus.TERMUX_MISSING, TermuxSetupProbe.prerequisites(false, true)?.status)
        assertEquals(TermuxSetupStatus.PERMISSION_REQUIRED, TermuxSetupProbe.prerequisites(true, false)?.status)
        assertNull(TermuxSetupProbe.prerequisites(true, true))
    }

    @Test
    fun explicitExternalAppsRefusalIsRecognized() {
        for (detail in listOf(
            "RunCommandService requires the 'allow-external-apps' property to be set to 'true' in termux.properties.",
            "allow-external-apps=false",
            "allow-external-apps is not set to true",
        )) {
            assertEquals(TermuxSetupStatus.EXTERNAL_APPS_DISABLED, TermuxSetupProbe.failure(detail).status)
        }
    }

    @Test
    fun delayAndGenericFailuresMustNotBeReportedAsDisabled() {
        for (detail in listOf(
            null, "", "[TERMUX_TIMEOUT]", "Service unavailable", "Permission denied", "allow-external-apps=true",
            "[TERMUX_TIMEOUT] Check whether allow-external-apps is disabled",
            "[TERMUX_TIMEOUT] allow-external-apps=false",
        )) {
            assertEquals(TermuxSetupStatus.UNVERIFIED, TermuxSetupProbe.failure(detail).status)
        }
    }
}
