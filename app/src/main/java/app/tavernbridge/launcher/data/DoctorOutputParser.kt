package app.tavernbridge.launcher.data

import app.tavernbridge.launcher.model.EnvironmentStatus
import app.tavernbridge.launcher.model.SillyBranch
import app.tavernbridge.launcher.security.redactSensitiveText
import java.util.Base64

object DoctorOutputParser {
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
