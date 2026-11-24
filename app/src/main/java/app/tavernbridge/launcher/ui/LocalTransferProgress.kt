package app.tavernbridge.launcher.ui

import app.tavernbridge.launcher.model.*

/** A Storage Access Framework transfer has no Termux command ID. Adapt its real counters
 * to the same progress UI without dispatching Termux cancellation or retaining secrets. */
internal data class LocalTransferProgress(val key: String, val encrypted: Boolean, val work: WorkProgress)

internal fun localTransferProgress(plain: BackupTransferState?, encrypted: EncryptedBackupState?): LocalTransferProgress? {
    fun progress(phase: String, bytes: Long, total: Long, detail: String, cancelPending: Boolean = false) =
        WorkProgress(0, phase, detail, progressMode = if (total > 0) "bytes" else "indeterminate",
            completedBytes = bytes, totalBytes = total, logText = detail, cancellationRequested = cancelPending)
    if (encrypted?.phase == EncryptedBackupPhase.RUNNING) {
        val exporting = encrypted.mode == EncryptedBackupMode.EXPORT
        return LocalTransferProgress(encrypted.id, true, progress(if (exporting) "백업 암호화 저장 중" else "백업 복호화·인증 중",
            encrypted.bytes, encrypted.total, "파일 처리가 끝난 뒤 인증과 저장 결과를 확인해요."))
    }
    if (plain == null || plain.phase !in setOf(BackupTransferPhase.CHECKING, BackupTransferPhase.COPYING)) return null
    val copying = plain.phase == BackupTransferPhase.COPYING
    val detail = when {
        plain.cancellationRequested -> "중단 요청을 보냈어요. 미완성 파일을 정리하고 있어요."
        !copying -> "선택한 ZIP을 읽을 수 있는지 확인하고 있어요."
        plain.total > 0 && plain.bytes >= plain.total -> "저장이 끝났는지 확인하고 있어요."
        else -> "원본 ZIP은 그대로 두고 선택한 위치에 복사해요."
    }
    return LocalTransferProgress("plain:${plain.archive.fileName}", false, progress(if (copying) "백업 저장 중" else "백업 확인 중",
        plain.bytes, if (copying) plain.total else 0L, detail, plain.cancellationRequested))
}
