package app.tavernbridge.launcher.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class BackupTransferPolicyTest {
    private val name = "SillyTavern-Launcher-20261123-123456.zip"

    @Test fun acceptsLauncherCollisionNamesButRejectsPathsAndControls() {
        assertTrue(BackupTransferPolicy.validFileName(name))
        assertTrue(BackupTransferPolicy.validFileName(name.replace(".zip", "-42 (1) (2).zip")))
        listOf("../$name", "$name\n", "x.zip", name.replace("123456", "a"), "SillyTavern-Launcher-" + "1".repeat(256) + ".zip").forEach {
            assertFalse(it, BackupTransferPolicy.validFileName(it))
        }
    }

    @Test fun rejectsWrongSelectionAndChangedSize() {
        BackupTransferPolicy.validateSource(name, 42, name, 42)
        BackupTransferPolicy.validateSource(name, 42, name, -1)
        assertThrows(IllegalArgumentException::class.java) { BackupTransferPolicy.validateSource(name, 42, "different.zip", 42) }
        assertThrows(IllegalArgumentException::class.java) { BackupTransferPolicy.validateSource(name, 42, name, 43) }
        assertThrows(IllegalArgumentException::class.java) { BackupTransferPolicy.validateSource(name, 0, name, 0) }
    }

    @Test fun checksOnlyRecognizedZipMagic() {
        assertTrue(BackupTransferPolicy.validZipSignature(byteArrayOf(0x50, 0x4b, 3, 4)))
        assertTrue(BackupTransferPolicy.validZipSignature(byteArrayOf(0x50, 0x4b, 5, 6)))
        assertFalse(BackupTransferPolicy.validZipSignature(byteArrayOf(0x50, 0x4b)))
        assertFalse(BackupTransferPolicy.validZipSignature("text".toByteArray()))
    }

    @Test fun refusesExistingOrUnknownDestinationBeforeWriting() {
        assertEquals("ok", writeSelectedBackupDocument({ 0L }, {}) { "ok" })
        listOf(-1L, 1L, 4L, Long.MAX_VALUE).forEach { size ->
            assertThrows(BackupTransferPreflightException::class.java) {
                writeSelectedBackupDocument({ size }, {}) { throw AssertionError("destination opened") }
            }
        }
    }

    @Test fun pickerDocumentSurvivesCancellationAndInterruptedWrite() {
        val destination = java.io.File.createTempFile("st-picker-user-", ".zip")
        try {
            var checks = 0
            val interrupted = java.util.concurrent.CancellationException("fixture")
            val cancelled = assertThrows(java.util.concurrent.CancellationException::class.java) {
                writeSelectedBackupDocument(destination::length, { if (++checks == 2) throw interrupted }) {
                    throw AssertionError("destination opened after cancellation")
                }
            }
            assertSame(interrupted, cancelled)
            assertTrue(destination.exists())
            assertEquals(0L, destination.length())
            assertThrows(SelectedBackupWriteException::class.java) {
                writeSelectedBackupDocument(destination::length, {}) {
                    destination.outputStream().use { it.write(42) }
                    throw IOException("provider disconnected")
                }
            }
            assertArrayEquals(byteArrayOf(42), destination.readBytes())
        } finally { destination.delete() }
    }

    @Test fun streamsExactContentsAndRealByteCounts() {
        val original = ByteArray(1024 * 1024 + 19) { (it % 251).toByte() }
        val destination = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        assertEquals(original.size.toLong(), BackupTransferPolicy.copy(ByteArrayInputStream(original), destination, original.size.toLong(), onProgress = progress::add))
        assertArrayEquals(original, destination.toByteArray())
        assertEquals(0L, progress.first())
        assertEquals(original.size.toLong(), progress.last())
        assertTrue(progress.zipWithNext().all { (before, after) -> after >= before })
    }

    @Test fun stopsBeforeWritingUnexpectedExtraData() {
        val destination = ByteArrayOutputStream()
        assertThrows(IllegalStateException::class.java) { BackupTransferPolicy.copy(ByteArrayInputStream(ByteArray(10)), destination, 9) }
        assertEquals(0, destination.size())
        assertThrows(IllegalStateException::class.java) { BackupTransferPolicy.copy(ByteArrayInputStream(ByteArray(5)), destination, 9) }
    }

    @Test fun stopsWhenCancelledAndDoesNotOwnStreams() {
        var read = false
        val input = object : ByteArrayInputStream(ByteArray(10)) {
            override fun read(bytes: ByteArray): Int { read = true; return super.read(bytes) }
        }
        val destination = ByteArrayOutputStream()
        assertThrows(IOException::class.java) { BackupTransferPolicy.copy(input, destination, 10, checkpoint = { throw IOException("cancelled") }) }
        assertFalse(read)
        assertEquals(0, destination.size())
    }

    @Test fun cancellationAfterFirstChunkDoesNotWriteAnotherChunk() {
        val original = ByteArray(512 * 1024) { 42 }
        val destination = ByteArrayOutputStream()
        var cancelled = false
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            BackupTransferPolicy.copy(ByteArrayInputStream(original), destination, original.size.toLong(),
                checkpoint = { if (cancelled) throw kotlinx.coroutines.CancellationException("cancelled") },
                onProgress = { if (it > 0) cancelled = true })
        }
        assertEquals(128 * 1024, destination.size())
        assertTrue(original.all { it == 42.toByte() })
    }

    @Test fun stalledProviderDoesNotLoopForever() {
        val stalled = object : InputStream() { override fun read() = 0; override fun read(bytes: ByteArray) = 0 }
        assertThrows(IllegalStateException::class.java) { BackupTransferPolicy.copy(stalled, ByteArrayOutputStream(), 10) }
    }

    @Test fun propagatesReadAndWriteFailuresInsteadOfReportingSuccess() {
        val unreadable = object : InputStream() { override fun read(): Int = throw IOException("read failed") }
        assertThrows(IOException::class.java) { BackupTransferPolicy.copy(unreadable, ByteArrayOutputStream(), 10) }
        val fullDestination = object : java.io.OutputStream() { override fun write(value: Int) = throw IOException("no space") }
        assertThrows(IOException::class.java) { BackupTransferPolicy.copy(ByteArrayInputStream(ByteArray(10)), fullDestination, 10) }
    }
}
