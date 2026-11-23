package app.tavernbridge.launcher.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class EncryptedBackupCodecTest {
    private fun password() = "강아지-백업-암호-123456!".toCharArray()
    private fun encrypt(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        val password = password()
        EncryptedBackupCodec.encrypt(ByteArrayInputStream(bytes), output, password, bytes.size.toLong())
        assertTrue(password.all { it == '\u0000' })
    }.toByteArray()
    private fun decrypt(bytes: ByteArray, password: CharArray = password()): ByteArray = ByteArrayOutputStream().also { output ->
        try { EncryptedBackupCodec.decrypt(ByteArrayInputStream(bytes), output, password) }
        finally { assertTrue(password.all { it == '\u0000' }) }
    }.toByteArray()

    @Test fun roundTripMultipleSegmentsAndEmptyPayloadWithoutClosingCallerStreams() {
        for (source in listOf(ByteArray(0), ByteArray(2 * 1024 * 1024 + 173) { (it * 31).toByte() })) {
            val ciphertext = encrypt(source)
            assertArrayEquals(source, decrypt(ciphertext))
            assertEquals(source.size.toLong(), EncryptedBackupCodec.inspectHeader(ByteArrayInputStream(ciphertext)).plaintextBytes)
        }
        var inputClosed = false
        var outputClosed = false
        val input = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) { override fun close() { inputClosed = true } }
        val output = object : ByteArrayOutputStream() { override fun close() { outputClosed = true } }
        EncryptedBackupCodec.encrypt(input, output, password(), 3)
        assertFalse(inputClosed)
        assertFalse(outputClosed)
    }

    @Test fun wrongPasswordHeaderTamperCipherTamperTruncationAndAppendedBytesFail() {
        val ciphertext = encrypt(ByteArray(1024 * 1024 + 20) { 42 })
        val badPassword = "different password".toCharArray()
        assertTrue(runCatching { decrypt(ciphertext, badPassword) }.exceptionOrNull() is EncryptedBackupException)
        val alteredHeader = ciphertext.copyOf().also { it[47] = (it[47].toInt() xor 1).toByte() }
        val alteredPayload = ciphertext.copyOf().also { it[it.size - 17] = (it[it.size - 17].toInt() xor 1).toByte() }
        val variants = listOf(alteredHeader, alteredPayload, ciphertext.copyOf(ciphertext.size - 1), ciphertext + byteArrayOf(1), ciphertext.copyOf(47))
        for (bad in variants) assertTrue(runCatching { decrypt(bad) }.exceptionOrNull() is EncryptedBackupException)
    }

    @Test fun identicalInputsUseFreshSaltAndCiphertext() {
        val source = "not a plaintext export".toByteArray()
        val first = encrypt(source)
        val second = encrypt(source)
        assertFalse(first.contentEquals(second))
        assertFalse(first.copyOfRange(16, 48).contentEquals(second.copyOfRange(16, 48)))
        assertArrayEquals(source, decrypt(first))
        assertArrayEquals(source, decrypt(second))
    }

    @Test fun sourceLengthChangeAndOutputFailureNeverReturnSuccessAndClearPassword() {
        for (declared in listOf(1L, 5L)) {
            val password = password()
            val failure = runCatching { EncryptedBackupCodec.encrypt(ByteArrayInputStream(byteArrayOf(1, 2, 3)), ByteArrayOutputStream(), password, declared) }.exceptionOrNull()
            assertTrue(failure is EncryptedBackupException)
            assertEquals(EncryptedBackupFailure.SOURCE_CHANGED, (failure as EncryptedBackupException).code)
            assertTrue(password.all { it == '\u0000' })
        }
        val password = password()
        val failure = runCatching {
            EncryptedBackupCodec.encrypt(ByteArrayInputStream(byteArrayOf(1)), object : OutputStream() {
                override fun write(value: Int) { throw IOException("fixture disk full") }
            }, password, 1)
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(password.all { it == '\u0000' })
    }

    @Test fun cancellationLeavesNoVerifiedReceiptAndAlwaysClearsPassword() {
        val password = password()
        var calls = 0
        val failure = runCatching {
            EncryptedBackupCodec.encrypt(ByteArrayInputStream(ByteArray(2 * 1024 * 1024)), ByteArrayOutputStream(), password, 2L * 1024 * 1024,
                checkpoint = { if (++calls > 5) throw CancellationException("fixture cancel") })
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(password.all { it == '\u0000' })
    }

    @Test fun callerSizeLimitAndInvalidHeaderRejectBeforeWritingPlaintext() {
        val ciphertext = encrypt(ByteArray(100))
        val output = ByteArrayOutputStream()
        val password = password()
        val failure = runCatching { EncryptedBackupCodec.decrypt(ByteArrayInputStream(ciphertext), output, password, maximumPlaintextBytes = 99) }.exceptionOrNull()
        assertEquals(EncryptedBackupFailure.OUTPUT_TOO_LARGE, (failure as EncryptedBackupException).code)
        assertEquals(0, output.size())
        assertTrue(password.all { it == '\u0000' })
        val forged = ciphertext.copyOf().also { ByteBuffer.wrap(it).putLong(8, Long.MAX_VALUE) }
        assertTrue(runCatching { EncryptedBackupCodec.inspectHeader(ByteArrayInputStream(forged)) }.exceptionOrNull() is EncryptedBackupException)
    }

    @Test fun encryptionReadsInBoundedChunksAndUsesLongCounters() {
        val total = 3L * 1024 * 1024 + 7
        var remaining = total
        var largestRequest = 0
        val input = object : InputStream() {
            override fun read(): Int = if (remaining-- > 0) 0 else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                largestRequest = maxOf(largestRequest, length)
                if (remaining == 0L) return -1
                val count = minOf(remaining, length.toLong()).toInt()
                buffer.fill(0, offset, offset + count)
                remaining -= count
                return count
            }
        }
        var encryptedBytes = 0L
        var progress = 0L
        EncryptedBackupCodec.encrypt(input, object : OutputStream() {
            override fun write(value: Int) { encryptedBytes++ }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { encryptedBytes += length }
        }, password(), total, onProgress = { progress = it })
        assertEquals(total, progress)
        assertTrue(encryptedBytes > total)
        assertTrue(largestRequest <= 128 * 1024)
    }
}
