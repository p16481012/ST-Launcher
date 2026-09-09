package app.tavernbridge.launcher.data

import app.tavernbridge.launcher.model.BackupArchive
import app.tavernbridge.launcher.model.BackupCategory
import java.util.Base64

object BackupOutputParser {
    fun parse(output: String): List<BackupArchive> = output.lineSequence().mapNotNull(::parseLine)
        .sortedByDescending { it.createdAt }
        .toList()

    private fun parseLine(line: String): BackupArchive? {
        val fields = line.split('\t')
        if (fields.size < 8 || fields[0] != "backup") return null
        return runCatching {
            BackupArchive(
                fileName = decode(fields[1]),
                sizeBytes = fields[2].toLong(),
                createdAt = decode(fields[3]),
                categories = decode(fields[4]).split(',').mapNotNull(BackupCategory::fromKey).toSet(),
                customFolders = decode(fields[5]).split(',').filter(String::isNotBlank).toSet(),
                includesSecrets = fields[6] == "1",
                sillyTavernVersion = decode(fields[7]),
                branch = fields.getOrNull(8)?.let(::decode).orEmpty(),
                launcherVersion = fields.getOrNull(9)?.let(::decode).orEmpty(),
                expandedBytes = fields.getOrNull(10)?.toLongOrNull() ?: 0,
                entryCount = fields.getOrNull(11)?.toIntOrNull() ?: 0,
                integrity = fields.getOrNull(12)?.let(::decode).orEmpty(),
                requiredRestoreBytes = fields.getOrNull(13)?.toLongOrNull() ?: 0,
                availableRestoreBytes = fields.getOrNull(14)?.toLongOrNull() ?: 0,
            )
        }.getOrNull()
    }

    private fun decode(value: String): String = String(Base64.getDecoder().decode(value))
}
