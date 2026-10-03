package app.tavernbridge.launcher.data

import app.tavernbridge.launcher.model.TermuxSetupStatus
import app.tavernbridge.launcher.termux.TermuxCommandResult
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxSetupProbeTest {
    private val callbackId = "new-callback"
    private val marker = "st-launcher-setup:unique-nonce"
    private fun success() = TermuxCommandResult(callbackId, "$marker\n", "", 0, -1, "", marker.length + 1)
    private fun classify(result: TermuxCommandResult) = TermuxSetupProbe.classify(callbackId, marker, result)

    @Test
    fun missingAppAndPermissionAreSeparatePrerequisites() {
        assertEquals(TermuxSetupStatus.TERMUX_MISSING, TermuxSetupProbe.prerequisites(false, false)?.status)
        assertEquals(TermuxSetupStatus.TERMUX_MISSING, TermuxSetupProbe.prerequisites(false, true)?.status)
        assertEquals(TermuxSetupStatus.PERMISSION_REQUIRED, TermuxSetupProbe.prerequisites(true, false)?.status)
        assertNull(TermuxSetupProbe.prerequisites(true, true))
    }

    @Test
    fun matchingSuccessfulCallbackAndSingleMarkerLineProveReadiness() {
        assertTrue(classify(success()).verified)
        assertEquals(TermuxSetupStatus.READY, classify(success()).status)
        for (ending in listOf("", "\n", "\r\n")) {
            val output = marker + ending
            assertTrue(classify(success().copy(stdout = output, stdoutOriginalLength = output.length)).verified)
        }
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
        for (output in listOf(
            "", "ok", "$marker\n\n", "$marker\r", "$marker\r\n\r\n", marker + marker,
            "$marker\n$marker\n", "welcome\n$marker\n", "\n$marker\n", "$marker\nextra",
            " $marker", "$marker ", "\t$marker\n",
        )) {
            assertFalse(classify(success().copy(stdout = output, stdoutOriginalLength = output.length)).verified)
        }
        assertFalse(classify(success().copy(stdoutOriginalLength = marker.length + 10)).verified)
        assertFalse(classify(success().copy(stdout = marker, stdoutOriginalLength = marker.length + 1)).verified)
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
        assertEquals("printf '%s\\n' '$marker'", TermuxSetupProbe.command(marker))
    }

    @Test
    fun genuineBashOutputReproducesTermuxAddingTheMissingNewline() {
        for ((format, rawEnding) in listOf("%s" to "", "%s\\n" to "\n", "%s\\r\\n" to "\r\n")) {
            val (rawStdout, result) = runThroughTermuxTransport("printf '$format' '$marker'")
            assertEquals(marker + rawEnding, rawStdout)
            assertEquals("$marker\n", result.stdout)
            assertEquals(marker.length + 1, result.stdoutOriginalLength)
            // The v0.3.1 equality check rejected every one of these successful callbacks.
            assertFalse(result.stdout == marker)
            assertEquals(TermuxSetupStatus.READY, classify(result).status)
        }
    }

    @Test
    fun productionProbeCommandPassesThroughTermuxTransport() {
        val (rawStdout, result) = runThroughTermuxTransport(TermuxSetupProbe.command(marker))
        assertEquals("$marker\n", rawStdout)
        assertEquals("$marker\n", result.stdout)
        assertTrue(result.isSuccess)
        assertFalse(result.stdoutTruncated)
        assertTrue(classify(result).verified)
    }

    @Test
    fun realTransportDoesNotHideExtraOutputOrFailedExit() {
        for (command in listOf(
            "printf '%s\\n%s\\n' '$marker' '$marker'",
            "printf 'welcome\\n%s\\n' '$marker'",
            "printf '%s\\nextra\\n' '$marker'",
            "printf '%s\\n\\n' '$marker'",
            "printf '%s \\n' '$marker'",
            TermuxSetupProbe.command(marker) + "; exit 7",
        )) {
            assertFalse(classify(runThroughTermuxTransport(command).second).verified)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun probeCommandRejectsShellSyntax() {
        TermuxSetupProbe.command("'; touch unexpected; '")
    }

    private fun runThroughTermuxTransport(command: String): Pair<String, TermuxCommandResult> {
        val gitBash = File("C:/Program Files/Git/bin/bash.exe")
        val bash = if (System.getProperty("os.name").startsWith("Windows") && gitBash.isFile) gitBash.path else "bash"
        val process = ProcessBuilder(bash, "--noprofile", "--norc", "-c", command).start()
        val rawStdout = process.inputStream.use { it.readBytes() }
        val stderr = process.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val exitCode = process.waitFor()
        // Reproduce the actual line-based transport, not an invented callback string:
        // https://github.com/termux/termux-app/blob/v0.118.3/termux-shared/src/main/java/com/termux/shared/shell/StreamGobbler.java#L188-L192
        val stdout = rawStdout.inputStream().bufferedReader(Charsets.UTF_8).use { reader ->
            buildString {
                while (true) {
                    val line = reader.readLine() ?: break
                    append(line).append('\n')
                }
            }
        }
        // ResultSender reports the length after StreamGobbler normalized the output.
        return rawStdout.toString(Charsets.UTF_8) to TermuxCommandResult(
            callbackId, stdout, stderr, exitCode, -1, "", stdout.length,
        )
    }
}
