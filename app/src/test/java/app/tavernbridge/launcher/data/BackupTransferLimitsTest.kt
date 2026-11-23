package app.tavernbridge.launcher.data

import org.junit.Assert.assertThrows
import org.junit.Test

class BackupTransferLimitsTest {
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
