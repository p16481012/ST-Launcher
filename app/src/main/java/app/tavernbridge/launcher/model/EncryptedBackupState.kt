package app.tavernbridge.launcher.model

enum class EncryptedBackupMode { EXPORT, RESTORE }
enum class EncryptedBackupPhase { PICK_SOURCE, SOURCE_OPEN, PASSWORD, PICK_DESTINATION, DESTINATION_OPEN, RUNNING, RESTORE_CONFIRM, ERROR }
data class EncryptedBackupState(
    val id: String,
    val mode: EncryptedBackupMode,
    val phase: EncryptedBackupPhase = EncryptedBackupPhase.PICK_SOURCE,
    val bytes: Long = 0,
    val total: Long = 0,
    val error: String = "",
)
