package app.tavernbridge.launcher.data

import app.tavernbridge.launcher.model.SillyBranch
import app.tavernbridge.launcher.termux.TermuxCommandResult
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoctorOutputParserTest {
    private fun completeResponse(installed: Boolean = true) = """
        recovery_pending=0
        protocol=1
        manager_version=7
        termux_ready=1
        operation_active=0
        st_installed=${if (installed) 1 else 0}
        running=0
        port=8000
    """.trimIndent()

    private fun callback(output: String = completeResponse()) =
        TermuxCommandResult("doctor-callback", output, "", 0, -1, "", output.length)

    @Test
    fun verifiedDoctorDistinguishesExistingAndConfirmedAbsentInstallations() {
        for (installed in listOf(true, false)) {
            val status = DoctorOutputParser.parseVerified(callback(completeResponse(installed)))
            assertTrue(status.managerConnected)
            assertEquals(installed, status.sillyTavernInstalled)
        }
    }

    @Test
    fun missingOrInvalidRequiredFieldsCannotBecomeANewInstallation() {
        val fields = listOf("protocol", "termux_ready", "st_installed", "running", "operation_active", "recovery_pending")
        for (key in fields) {
            val missing = completeResponse().lineSequence().filterNot { it.startsWith("$key=") }.joinToString("\n")
            expectInvalid(callback(missing))
            expectInvalid(callback("$missing\n$key=unknown"))
            expectInvalid(callback(completeResponse() + "\n$key=0"))
        }
        expectInvalid(callback(""))
        expectInvalid(callback("protocol=1\nst_installed=0"))
        expectInvalid(callback(completeResponse().replace("protocol=1", "protocol=2")))
        expectInvalid(callback(completeResponse().replace("termux_ready=1", "termux_ready=0")))
    }

    @Test
    fun truncatedOrFailedCallbackCannotAuthorizeTheParsedStatus() {
        val successful = callback()
        expectInvalid(successful.copy(stdoutOriginalLength = successful.stdout.length + 1))
        expectInvalid(successful.copy(exitCode = 1))
        expectInvalid(successful.copy(errorCode = 0))
        expectInvalid(successful.copy(errorCode = 1, errorMessage = "allow-external-apps=false"))
    }

    @Test
    fun verifiedDoctorAllowsTransportLineEndingsAndUnrelatedShellOutput() {
        val status = DoctorOutputParser.parseVerified(callback(
            "shell greeting\r\n" + completeResponse().replace("\n", "\r\n") + "\r\n",
        ))
        assertTrue(status.managerConnected)
        assertTrue(status.sillyTavernInstalled)
    }

    private fun expectInvalid(result: TermuxCommandResult) {
        val failure = runCatching { DoctorOutputParser.parseVerified(result) }.exceptionOrNull()
        assertTrue("Invalid doctor response must throw instead of returning absent installation", failure is IllegalStateException)
    }

    @Test
    fun parsesExistingReleaseInstallation() {
        val result = DoctorOutputParser.parse(
            """
            protocol=1
            termux_ready=1
            git_installed=1
            git_version=2.50.1
            node_installed=1
            node_version=v22.17.0
            st_installed=1
            branch=release
            commit=abc1234
            st_version=1.18.0
            working_tree_clean=1
            modified_files_b64=cHVibGljL2N1c3RvbS5jc3MKY29uZmlnLnlhbWw=
            running=1
            operation_active=1
            port=8000
            external_access=1
            whitelist_b64=OjoxCjEyNy4wLjAuMQoxOTIuMTY4LjAuMC8xNg==
            session_started_epoch=1784160000
            session_started_at=2026-07-16 09:00:00
            last_server_action=restart
            last_server_action_at=2026-07-16 09:00:00
            """.trimIndent(),
        )

        assertTrue(result.managerConnected)
        assertTrue(result.sillyTavernInstalled)
        assertEquals(SillyBranch.RELEASE, result.branch)
        assertEquals("abc1234", result.commit)
        assertEquals("1.18.0", result.sillyTavernVersion)
        assertTrue(result.processRunning)
        assertTrue(result.operationActive)
        assertTrue(result.workingTreeClean)
        assertEquals(listOf("public/custom.css", "config.yaml"), result.modifiedFiles)
        assertEquals(1784160000L, result.sessionStartedEpoch)
        assertEquals("restart", result.lastServerAction)
        assertTrue(result.externalAccessEnabled)
        assertEquals(listOf("::1", "127.0.0.1", "192.168.0.0/16"), result.whitelist)
    }

    @Test
    fun defaultsSafelyWhenValuesAreMissing() {
        val result = DoctorOutputParser.parse("protocol=1\nst_installed=0\nworking_tree_clean=0")

        assertFalse(result.sillyTavernInstalled)
        assertFalse(result.workingTreeClean)
        assertFalse(result.recoveryPending)
        assertEquals("", result.recoveryMessage)
        assertEquals(8000, result.port)
        assertEquals(null, result.branch)
    }

    @Test
    fun reportsInterruptedRestoreWithoutPretendingItIsAnActiveOperation() {
        val message = "보호사본을 확인해 주세요. password=fixture-secret"
        val encoded = Base64.getEncoder().encodeToString(message.toByteArray(Charsets.UTF_8))
        val result = DoctorOutputParser.parse(
            "protocol=1\noperation_active=0\nrecovery_pending=1\nrecovery_error_b64=$encoded",
        )
        assertTrue(result.recoveryPending)
        assertFalse(result.operationActive)
        assertTrue(result.recoveryMessage.contains("보호사본"))
        assertFalse(result.recoveryMessage.contains("fixture-secret"))
    }

    @Test
    fun invalidRecoveryMessageDoesNotHideThePendingState() {
        val result = DoctorOutputParser.parse("protocol=1\nrecovery_pending=1\nrecovery_error_b64=!!!")
        assertTrue(result.recoveryPending)
        assertEquals("", result.recoveryMessage)
    }
}
