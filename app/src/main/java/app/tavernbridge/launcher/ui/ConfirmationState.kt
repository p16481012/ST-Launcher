package app.tavernbridge.launcher.ui

import androidx.compose.runtime.saveable.Saver
import java.net.URI
import java.util.UUID

enum class ConfirmationAction { INSTALL, UPDATE, RESTORE_BACKUP, IMPORT_BACKUP, DELETE_BACKUPS, SWITCH_BRANCH, REPAIR, RESET }

/** A restored request is only a question. It carries no executable callback or Activity reference. */
data class Confirmation(
    val title: String,
    val description: String,
    val confirmLabel: String,
    val action: ConfirmationAction,
    val arguments: List<String> = emptyList(),
    val allowBackup: Boolean = false,
    val backupBefore: Boolean = true,
    val withoutBackupLabel: String = "백업 없이 복원",
    val id: String = UUID.randomUUID().toString(),
)

object ConfirmationCodec {
    private const val MAX_SAVED_CHARACTERS = 65_536

    fun encode(value: Confirmation): List<String> {
        val fields = listOf("1", value.id, value.action.name, value.title.take(512), value.description.take(8192),
            value.confirmLabel.take(256), value.allowBackup.toString(), value.backupBefore.toString(),
            value.withoutBackupLabel.take(256)) + value.arguments
        return fields.takeIf { decode(it) != null } ?: emptyList()
    }

    fun decode(fields: List<String>): Confirmation? = runCatching {
        if (fields.size !in 9..265 || fields.sumOf { it.length.toLong() } > MAX_SAVED_CHARACTERS) return null
        if (fields[0] != "1" || !fields[1].matches(Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"))) return null
        val action = ConfirmationAction.entries.firstOrNull { it.name == fields[2] } ?: return null
        if (fields[3].isBlank() || fields[3].length > 512 || fields[4].length > 8192 || fields[5].isBlank() || fields[5].length > 256 || fields[8].length > 256) return null
        val allowBackup = fields[6].toBooleanStrictOrNull() ?: return null
        val backupBefore = fields[7].toBooleanStrictOrNull() ?: return null
        if (allowBackup && action !in setOf(ConfirmationAction.RESTORE_BACKUP, ConfirmationAction.IMPORT_BACKUP, ConfirmationAction.RESET)) return null
        val arguments = fields.drop(9)
        fun fileName(value: String) = value.length in 1..255 && value.startsWith("SillyTavern-Launcher-") &&
            value.endsWith(".zip") && value.none { it.isISOControl() || it == '/' || it == '\\' }
        val valid = when (action) {
            ConfirmationAction.INSTALL, ConfirmationAction.SWITCH_BRANCH -> arguments.size == 1 && arguments[0] in setOf("release", "staging")
            ConfirmationAction.RESTORE_BACKUP -> arguments.size == 1 && fileName(arguments[0])
            // Old filename-only delete prompts cannot identify a replaced file; expire safely.
            ConfirmationAction.DELETE_BACKUPS -> app.tavernbridge.launcher.model.BackupDeleteSelection.valid(arguments)
            ConfirmationAction.IMPORT_BACKUP -> arguments.size == 1 && arguments[0].length <= 8192 &&
                URI(arguments[0]).let { it.scheme == "content" && !it.authority.isNullOrBlank() && it.fragment == null }
            else -> arguments.isEmpty()
        }
        if (!valid) return null
        Confirmation(fields[3], fields[4], fields[5], action, arguments, allowBackup, backupBefore, fields[8], fields[1])
    }.getOrNull()
}

internal val ConfirmationSaver = Saver<Confirmation?, List<String>>(
    save = { it?.let(ConfirmationCodec::encode) ?: emptyList() },
    restore = ConfirmationCodec::decode,
)
