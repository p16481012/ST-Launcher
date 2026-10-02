package app.tavernbridge.launcher.data

import app.tavernbridge.launcher.model.TermuxSetupStatus
import app.tavernbridge.launcher.termux.TermuxCommandResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxSetupProbeTest {
    private val callbackId = "new-callback"
    private val marker = "st-launcher-setup:unique-nonce"
    private fun success() = TermuxCommandResult(callbackId, marker, "", 0, -1, "")
    private fun classify(result: TermuxCommandResult) = TermuxSetupProbe.classify(callbackId, marker, result)

    @Test
    fun missingAppAndPermissionAreSeparatePrerequisites() {
        assertEquals(TermuxSetupStatus.TERMUX_MISSING, TermuxSetupProbe.prerequisites(false, false)?.status)
        assertEquals(TermuxSetupStatus.TERMUX_MISSING, TermuxSetupProbe.prerequisites(false, true)?.status)
        assertEquals(TermuxSetupStatus.PERMISSION_REQUIRED, TermuxSetupProbe.prerequisites(true, false)?.status)
        assertNull(TermuxSetupProbe.prerequisites(true, true))
    }

    @Test
    fun matchingSuccessfulCallbackAndExactMarkerProveReadiness() {
        assertTrue(classify(success()).verified)
        assertEquals(TermuxSetupStatus.READY, classify(success()).status)
    }

    @Test
    fun staleCallbackOrNonceDoesNotProveReadiness() {
        assertFalse(classify(success().copy(callbackId = "previous-callback")).verified)
        assertFalse(classify(success().copy(stdout = "st-launcher-setup:previous-nonce")).verified)
        assertEquals(TermuxSetupStatus.UNVERIFIED, classify(success().copy(
            callbackId = "unrelated", errorCode = 1, errorMessage = "allow-external-apps=false",
        )).status)
    }

    @Test
    fun markerRequiresSuccessfulExitAndSuccessfulTermuxResult() {
        assertFalse(classify(success().copy(exitCode = 1)).verified)
        assertFalse(classify(success().copy(errorCode = 0)).verified)
        assertFalse(classify(success().copy(errorCode = 1)).verified)
        assertFalse(classify(success().copy(exitCode = -1)).verified)
    }

    @Test
    fun emptyExtraOrTruncatedOutputIsNotAccepted() {
        for (output in listOf("", "ok", marker + "\n", marker + marker, "welcome\n" + marker)) {
            assertFalse(classify(success().copy(stdout = output)).verified)
        }
        assertFalse(classify(success().copy(stdoutOriginalLength = marker.length + 10)).verified)
    }

    @Test
    fun explicitExternalAppsRefusalIsRecognizedInEitherErrorChannel() {
        val refusal = "RunCommandService requires the 'allow-external-apps' property to be set to 'true' in termux.properties."
        assertEquals(TermuxSetupStatus.EXTERNAL_APPS_DISABLED, classify(success().copy(
            errorCode = 1, errorMessage = refusal,
        )).status)
        assertEquals(TermuxSetupStatus.EXTERNAL_APPS_DISABLED, classify(success().copy(
            exitCode = 1, stderr = "allow-external-apps=false",
        )).status)
        assertEquals(TermuxSetupStatus.EXTERNAL_APPS_DISABLED, TermuxSetupProbe.failure(
            "allow-external-apps is not set to true",
        ).status)
    }

    @Test
    fun delayAndGenericFailuresMustNotBeReportedAsDisabled() {
        for (detail in listOf(null, "", "[TERMUX_TIMEOUT]", "Service unavailable", "Permission denied", "allow-external-apps=true")) {
            assertEquals(TermuxSetupStatus.UNVERIFIED, TermuxSetupProbe.failure(detail).status)
        }
        assertEquals(TermuxSetupStatus.UNVERIFIED, TermuxSetupProbe.failure(
            "[TERMUX_TIMEOUT] Check whether allow-external-apps is disabled",
        ).status)
        assertEquals(TermuxSetupStatus.UNVERIFIED, classify(success().copy(
            stdout = "", exitCode = 1, stderr = "[TERMUX_TIMEOUT] allow-external-apps=false",
        )).status)
    }

    @Test
    fun probeCommandOnlyPrintsItsLiteralNonce() {
        assertEquals("printf '%s' '$marker'", TermuxSetupProbe.command(marker))
    }

    @Test(expected = IllegalArgumentException::class)
    fun probeCommandRejectsShellSyntax() {
        TermuxSetupProbe.command("'; touch unexpected; '")
    }
}
