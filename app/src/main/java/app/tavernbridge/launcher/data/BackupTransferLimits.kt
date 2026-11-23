package app.tavernbridge.launcher.data

import java.io.IOException

internal class BackupTransferPreflightException(message: String) : IOException(message)

internal object BackupTransferLimits {
    const val MAX_BYTES = 8L * 1024 * 1024 * 1024
    private const val RESERVE_BYTES = 128L * 1024 * 1024

    fun checkEncryptedSize(bytes: Long) {
        if (bytes !in 4..MAX_BYTES) throw BackupTransferPreflightException("암호화 백업 가져오기는 원본 ZIP 8 GiB까지 지원합니다. 필요한 항목만 나누어 백업해 주세요.")
    }

    fun checkDecryptionSpace(bytes: Long, privateFree: Long, sharedFree: Long) {
        checkEncryptedSize(bytes)
        // Android emulated storage generally shares physical capacity with app data.
        // Keep room for both the authenticated private ZIP and the Termux transfer ZIP.
        if (privateFree < bytes * 2 + RESERVE_BYTES || sharedFree < bytes + RESERVE_BYTES) {
            throw BackupTransferPreflightException("복호화·전달용 임시 ZIP 두 개를 보관할 공간이 부족합니다. ZIP 크기의 약 두 배와 해제 공간을 확보해 주세요.")
        }
    }
}
