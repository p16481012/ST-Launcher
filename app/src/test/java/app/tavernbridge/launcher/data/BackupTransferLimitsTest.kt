package app.tavernbridge.launcher.data

import org.junit.Assert.assertThrows
import org.junit.Test

class BackupTransferLimitsTest {
    @Test fun stagingSpaceUsesMeasuredDownloadsBytesAndKeepsUnknownSizesUnknown() {
        val reserve = 32L * 1024 * 1024
        BackupTransferLimits.checkDownloadStagingSpace(10, reserve + 10)
        BackupTransferLimits.checkDownloadStagingSpace(null, reserve)
        val insufficient = assertThrows(BackupTransferPreflightException::class.java) {
            BackupTransferLimits.checkDownloadStagingSpace(10, reserve)
        }
        org.junit.Assert.assertEquals("[IMPORT_STAGING_NO_SPACE]\nDownload 저장 공간이 부족합니다. 필요 33554442바이트, 여유 33554432바이트", insufficient.message)
        val streaming = assertThrows(BackupTransferPreflightException::class.java) {
            BackupTransferLimits.checkDownloadStagingSpace(null, 1)
        }
        org.junit.Assert.assertTrue(streaming.message!!.contains("전체 파일 크기는 확인되지 않았습니다"))
    }

    @Test fun lateDestinationWriteFailurePreservesRealCauseWithoutInventingNumbers() {
        val disk = java.io.IOException("write failed: ENOSPC (No space left on device)")
        val full = assertThrows(java.io.IOException::class.java) { writeToDownload { throw disk } }
        org.junit.Assert.assertTrue(full.message!!.startsWith("[IMPORT_STAGING_NO_SPACE]"))
        org.junit.Assert.assertSame(disk, full.cause)
        val other = java.io.IOException("permission denied for ENOSPC.zip")
        org.junit.Assert.assertSame(other, assertThrows(java.io.IOException::class.java) { writeToDownload { throw other } })
    }
    @Test fun exactLimitIsAcceptedButLargerArchivesFailBeforeDecryption() {
        BackupTransferLimits.checkEncryptedSize(BackupTransferLimits.MAX_BYTES)
        assertThrows(BackupTransferPreflightException::class.java) { BackupTransferLimits.checkEncryptedSize(BackupTransferLimits.MAX_BYTES + 1) }
        assertThrows(BackupTransferPreflightException::class.java) { BackupTransferLimits.checkEncryptedSize(-1) }
    }
    @Test fun sharedStorageAndTwoPrivateZipCopiesAreChecked() {
        val gib = 1024L * 1024 * 1024
        BackupTransferLimits.checkDecryptionSpace(gib, 3 * gib, 2 * gib)
        assertThrows(BackupTransferPreflightException::class.java) { BackupTransferLimits.checkDecryptionSpace(gib, 2 * gib, 2 * gib) }
        assertThrows(BackupTransferPreflightException::class.java) { BackupTransferLimits.checkDecryptionSpace(gib, 3 * gib, gib) }
    }
}
