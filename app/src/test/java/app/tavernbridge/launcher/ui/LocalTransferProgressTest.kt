package app.tavernbridge.launcher.ui

import app.tavernbridge.launcher.model.*
import org.junit.Assert.*
import org.junit.Test

class LocalTransferProgressTest {
    private val archive = BackupArchive("example.zip", 1200, "", emptySet(), emptySet(), false, "")
    @Test fun pickerAndConsentNeverShowASecondProgressPanel() {
        for (phase in BackupTransferPhase.entries - setOf(BackupTransferPhase.COPYING, BackupTransferPhase.CHECKING)) {
            assertNull(localTransferProgress(BackupTransferState(archive, BackupTransferAction.EXPORT, phase), null))
        }
        for (phase in EncryptedBackupPhase.entries - EncryptedBackupPhase.RUNNING) {
            assertNull(localTransferProgress(null, EncryptedBackupState("id", EncryptedBackupMode.EXPORT, phase)))
        }
    }
    @Test fun knownBytesAreMeasuredAndCheckingIsIndeterminate() {
        val transfer = BackupTransferState(archive, BackupTransferAction.EXPORT, BackupTransferPhase.CHECKING)
        assertNull(localTransferProgress(transfer, null)!!.work.measuredPercent)
        val item = localTransferProgress(transfer.copy(phase = BackupTransferPhase.COPYING, bytes = 600, cancellationRequested = true), null)!!
        assertEquals(50, item.work.measuredPercent)
        assertTrue(item.work.cancellationRequested)
        assertFalse(item.encrypted)
        val encrypted = localTransferProgress(null, EncryptedBackupState("id", EncryptedBackupMode.RESTORE, EncryptedBackupPhase.RUNNING, 3, 10))!!
        assertEquals(30, encrypted.work.measuredPercent)
        assertTrue(encrypted.encrypted)
        assertFalse(encrypted.work.logText.contains("example.zip"))
    }
}
