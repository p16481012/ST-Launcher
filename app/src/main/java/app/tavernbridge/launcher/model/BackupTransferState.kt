package app.tavernbridge.launcher.model

enum class BackupTransferAction(val label: String) { SHARE("공유"), EXPORT("다른 위치에 저장") }
enum class BackupTransferPhase { CONFIRM, CHECKING, PICK_SOURCE, SOURCE_OPEN, PICK_DESTINATION, DESTINATION_OPEN, SHARE_READY, COPYING }

data class BackupTransferState(
    val archive: BackupArchive,
    val action: BackupTransferAction,
    val phase: BackupTransferPhase = BackupTransferPhase.CONFIRM,
    val bytes: Long = 0L,
    val total: Long = archive.sizeBytes,
    val cancellationRequested: Boolean = false,
)
