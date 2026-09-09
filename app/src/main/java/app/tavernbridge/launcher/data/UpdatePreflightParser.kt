package app.tavernbridge.launcher.data

import app.tavernbridge.launcher.model.UpdatePreflight
import app.tavernbridge.launcher.model.UpdateRecord
import java.util.Base64

object UpdatePreflightParser {
    fun parse(output: String): UpdatePreflight {
        val values = output.lineSequence().mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
        }.toMap()
        fun value(key: String) = values[key].orEmpty()
        fun enabled(key: String) = value(key) == "1"
        val dirtyFiles = runCatching {
            String(Base64.getDecoder().decode(value("dirty_files_b64")))
        }.getOrDefault("").lineSequence().filter(String::isNotBlank).toList()
        return UpdatePreflight(
            branch = value("branch"),
            currentCommit = value("current_commit"),
            targetCommit = value("remote_commit"),
            currentVersion = value("current_version"),
            targetVersion = value("target_version"),
            updateAvailable = enabled("update_available"),
            commitsBehind = value("commits_behind").toIntOrNull()?.coerceAtLeast(0) ?: 0,
            nodeVersion = value("node_version"),
            nodeRequired = value("node_required"),
            nodeCompatible = enabled("node_compatible"),
            freeBytes = value("free_bytes").toLongOrNull() ?: 0L,
            requiredBytes = value("required_bytes").toLongOrNull() ?: 0L,
            spaceReady = enabled("space_ready"),
            modifiedFiles = dirtyFiles,
            backupStorageReady = enabled("backup_storage_ready"),
        )
    }
}

object UpdateRecordParser {
    fun parse(output: String): UpdateRecord? {
        if (output.isBlank()) return null
        val values = output.lineSequence().mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
        }.toMap()
        return UpdateRecord(
            createdAt = values["created_at"].orEmpty(),
            branch = values["branch"].orEmpty(),
            oldCommit = values["old_commit"].orEmpty(),
            targetCommit = values["target_commit"].orEmpty(),
            newCommit = values["new_commit"].orEmpty(),
            oldVersion = values["old_version"].orEmpty(),
            newVersion = values["new_version"].orEmpty(),
            result = values["result"].orEmpty(),
        )
    }
}
