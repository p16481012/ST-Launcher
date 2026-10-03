package app.tavernbridge.launcher.data

import app.tavernbridge.launcher.model.EnvironmentStatus
import app.tavernbridge.launcher.model.SillyBranch
import app.tavernbridge.launcher.security.redactSensitiveText
import app.tavernbridge.launcher.termux.TermuxCommandResult
import java.util.Base64

object DoctorOutputParser {
    /** Never interpret a missing/truncated installation flag as confirmation that it is absent. */
    fun parseVerified(result: TermuxCommandResult): EnvironmentStatus {
        check(result.isSuccess) { result.readableError() }
        check(!result.stdoutTruncated) { "[TERMUX_STATUS_INVALID]\nTermux 환경 검사 응답이 잘렸습니다. 다시 확인해 주세요." }
        val required = mapOf(
            "protocol" to setOf("1"),
            "termux_ready" to setOf("1"),
            "st_installed" to setOf("0", "1"),
            "running" to setOf("0", "1"),
            "operation_active" to setOf("0", "1"),
            "recovery_pending" to setOf("0", "1"),
        )
        val lines = result.stdout.lineSequence().toList()
        check(required.all { (key, allowed) ->
            val values = lines.filter { it.startsWith("$key=") }.map { it.substringAfter('=') }
            values.size == 1 && values.single() in allowed
        }) { "[TERMUX_STATUS_INVALID]\nTermux 환경 검사 응답이 완전하지 않습니다. 기존 설치 상태를 변경하지 않고 다시 확인해 주세요." }
        return parse(result.stdout)
    }

    fun parse(output: String): EnvironmentStatus {
        val values = output.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
            }
            .toMap()
        return EnvironmentStatus(
            termuxInstalled = true,
            commandPermissionGranted = true,
            managerConnected = values["protocol"] == "1",
            managerVersion = values["manager_version"].orEmpty(),
            gitInstalled = values["git_installed"] == "1",
            gitVersion = values["git_version"].orEmpty(),
            nodeInstalled = values["node_installed"] == "1",
            nodeVersion = values["node_version"].orEmpty(),
            sillyTavernInstalled = values["st_installed"] == "1",
            branch = SillyBranch.from(values["branch"]),
            commit = values["commit"].orEmpty(),
            sillyTavernVersion = values["st_version"].orEmpty(),
            workingTreeClean = values["working_tree_clean"] != "0",
            modifiedFiles = runCatching {
                String(Base64.getDecoder().decode(values["modified_files_b64"].orEmpty()))
            }.getOrDefault("").lineSequence().filter(String::isNotBlank).toList(),
            processRunning = values["running"] == "1",
            operationActive = values["operation_active"] == "1",
            recoveryPending = values["recovery_pending"] == "1",
            recoveryMessage = redactSensitiveText(runCatching {
                String(Base64.getDecoder().decode(values["recovery_error_b64"].orEmpty()), Charsets.UTF_8)
            }.getOrDefault(""), maxLength = 4_000),
            portListening = values["port_listening"] == "1",
            port = values["port"]?.toIntOrNull() ?: 8000,
            externalAccessEnabled = values["external_access"] == "1",
            whitelist = runCatching {
                String(Base64.getDecoder().decode(values["whitelist_b64"].orEmpty()))
            }.getOrDefault("").lineSequence().filter(String::isNotBlank).toList()
                .ifEmpty { listOf("::1", "127.0.0.1") },
            sessionStartedEpoch = values["session_started_epoch"]?.toLongOrNull() ?: 0,
            sessionStartedAt = values["session_started_at"].orEmpty(),
            lastServerAction = values["last_server_action"].orEmpty(),
            lastServerActionAt = values["last_server_action_at"].orEmpty(),
        )
    }
}
