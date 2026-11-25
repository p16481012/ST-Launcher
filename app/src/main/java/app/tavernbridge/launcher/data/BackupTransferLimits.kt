package app.tavernbridge.launcher.data

import java.io.IOException

internal class BackupTransferPreflightException(message: String) : IOException(message)

internal object BackupTransferLimits {
    const val MAX_BYTES = 8L * 1024 * 1024 * 1024
    private const val RESERVE_BYTES = 128L * 1024 * 1024
    private const val STAGING_RESERVE_BYTES = 32L * 1024 * 1024

    /** remaining bytes may be unknown (e.g. a document provider streaming a ZIP). */
    fun checkDownloadStagingSpace(remaining: Long?, available: Long) {
        require(remaining == null || remaining in 0..MAX_BYTES)
        val minimum = (remaining ?: 0L) + STAGING_RESERVE_BYTES
        if (available < minimum) throw BackupTransferPreflightException("[IMPORT_STAGING_NO_SPACE]\n" +
            if (remaining == null) "다운로드 폴더의 예비 공간이 부족합니다. 전체 파일 크기는 확인되지 않았습니다."
            else "Download 저장 공간이 부족합니다. 필요 ${minimum}바이트, 여유 ${available}바이트")
    }

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

// Wrap only destination writes. An exception from a source document provider must retain its own cause.
internal inline fun <T> writeToDownload(write: () -> T): T = try {
    write()
} catch (error: IOException) {
    if (!generateSequence<Throwable>(error) { it.cause }.take(8).any {
            downloadFull.containsMatchIn(it.message.orEmpty())
        }) throw error
    throw IOException("[IMPORT_STAGING_NO_SPACE]\n다운로드 폴더에 임시 파일을 쓰는 중 공간이 부족해졌습니다.", error)
}

private val downloadFull = Regex("""\bno space left on device\b|\bENOSPC\s*(?:\(|$)""", RegexOption.IGNORE_CASE)
