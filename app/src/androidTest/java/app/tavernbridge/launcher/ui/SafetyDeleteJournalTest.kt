package app.tavernbridge.launcher.ui

import androidx.test.platform.app.InstrumentationRegistry
import app.tavernbridge.launcher.data.SafetyDeleteReceiptStore
import app.tavernbridge.launcher.model.SafetyDeleteReceipt
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SafetyDeleteJournalTest {
    @Test fun reopensOnlyAcknowledgedCountsAndSuccessfulCompletionLeavesNoPendingSelection() {
        val file = File.createTempFile("test-safety-counts-", ".txt",
            InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir)
        val first = SafetyDeleteReceiptStore(file)
        try {
            val start = SafetyDeleteReceipt(3)
            first.save(start)
            assertEquals(start, SafetyDeleteReceiptStore(file).read())
            first.save(start.acknowledge())
            // The next command may already have reached Termux, but the process died before its reply.
            val restarted = SafetyDeleteReceiptStore(file)
            assertEquals(SafetyDeleteReceipt(3, 1), restarted.read())
            assertEquals("3:1", file.readText())
            assertFalse(restarted.read()!!.summary(20, "date").canRetry)
            restarted.save(SafetyDeleteReceipt(3, 3))
            assertTrue(SafetyDeleteReceiptStore(file).read()!!.summary(21, "date").succeeded)
            restarted.clear()
            assertNull(SafetyDeleteReceiptStore(file).read())
        } finally { first.clear() }
    }
}
