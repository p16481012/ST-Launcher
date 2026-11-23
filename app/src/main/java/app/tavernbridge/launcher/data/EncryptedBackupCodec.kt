package app.tavernbridge.launcher.data

import com.google.crypto.tink.AccessesPartialKey
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingKey
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.util.SecretBytes
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

enum class EncryptedBackupFailure {
    INVALID_FORMAT, INVALID_PASSWORD, BAD_PASSWORD_OR_DAMAGED, SOURCE_CHANGED, OUTPUT_TOO_LARGE,
}

class EncryptedBackupException(val code: EncryptedBackupFailure, message: String) : IOException(message)
data class EncryptedBackupInfo(val plaintextBytes: Long)

/**
 * Version 1 container: 8-byte STENC001 magic, big-endian plaintext length (8),
 * random PBKDF2 salt (32), then Tink AES256-GCM-HKDF streaming ciphertext (1 MiB).
 * The entire fixed header is authenticated as associated data. PBKDF2-HMAC-
 * SHA256 uses 600,000 iterations and 256-bit output, fixed by this format version.
 *
 * This is NOT ZIP password encryption and does not change a source ZIP in place.
 * Callers OWN the input/output streams and MUST discard a failed output. In
 * particular, decrypt writes only to an app-owned, unpublished staging document;
 * never pass a live installation, existing user file, or public document here.
 * Publish/import is permitted only after decrypt returns successfully and the
 * caller has durably closed its output. A matching password alone is not success.
 *
 * No password, derived key, or keyset is serialized or logged. Caller-owned
 * password arrays are cleared on every exit. JVM/Tink internal key copies cannot
 * be deterministically erased; they remain operation-local and are not retained.
 */
object EncryptedBackupCodec {
    const val EXTENSION = "stbackup"
    const val HEADER_BYTES = 48
    const val MAX_PLAINTEXT_BYTES = 1_099_511_627_776L
    private const val SALT_BYTES = 32
    private const val ITERATIONS = 600_000
    private const val BUFFER_BYTES = 128 * 1024
    private val magic = "STENC001".toByteArray(Charsets.US_ASCII)
    private val initialized by lazy { StreamingAeadConfig.register(); true }

    /** Consumes only the fixed header. Open the input anew for decrypt(). */
    fun inspectHeader(input: InputStream): EncryptedBackupInfo = readHeader(input).let { EncryptedBackupInfo(it.length) }

    fun encrypt(
        input: InputStream,
        unpublishedOutput: OutputStream,
        password: CharArray,
        plaintextBytes: Long,
        checkpoint: () -> Unit = {},
        onProgress: (Long) -> Unit = {},
    ): EncryptedBackupInfo {
        try {
            if (password.size !in 12..1024 || password.all { it.isWhitespace() }) throw EncryptedBackupException(EncryptedBackupFailure.INVALID_PASSWORD, "암호는 12자 이상 1,024자 이하로 입력해 주세요.")
            requireLength(plaintextBytes)
            checkpoint()
            val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
            val header = ByteBuffer.allocate(HEADER_BYTES).put(magic).putLong(plaintextBytes).put(salt).array()
            val aead = primitive(password, salt)
            checkpoint()
            unpublishedOutput.write(header)
            aead.newEncryptingStream(KeepOpenOutput(unpublishedOutput), header).use { encrypted ->
                copyExact(input, encrypted, plaintextBytes, checkpoint, onProgress, decrypting = false)
            }
            checkpoint()
            unpublishedOutput.flush()
            return EncryptedBackupInfo(plaintextBytes)
        } finally { password.fill('\u0000') }
    }

    fun decrypt(
        input: InputStream,
        unpublishedOutput: OutputStream,
        password: CharArray,
        maximumPlaintextBytes: Long = MAX_PLAINTEXT_BYTES,
        checkpoint: () -> Unit = {},
        onProgress: (Long) -> Unit = {},
    ): EncryptedBackupInfo {
        try {
            if (password.size !in 1..1024) throw EncryptedBackupException(EncryptedBackupFailure.INVALID_PASSWORD, "백업을 만들 때 사용한 암호를 입력해 주세요.")
            require(maximumPlaintextBytes >= 0)
            checkpoint()
            val header = readHeader(input)
            if (header.length > maximumPlaintextBytes) throw EncryptedBackupException(EncryptedBackupFailure.OUTPUT_TOO_LARGE, "복호화한 백업을 안전하게 보관할 공간이 부족하거나 허용 크기를 초과합니다.")
            val aead = primitive(password, header.salt)
            checkpoint()
            val decrypted = try { aead.newDecryptingStream(KeepOpenInput(input), header.bytes) }
            catch (_: GeneralSecurityException) { throw invalidCiphertext() }
            decrypted.use { copyExact(it, unpublishedOutput, header.length, checkpoint, onProgress, decrypting = true) }
            checkpoint()
            unpublishedOutput.flush()
            return EncryptedBackupInfo(header.length)
        } finally { password.fill('\u0000') }
    }

    @AccessesPartialKey
    private fun primitive(password: CharArray, salt: ByteArray): StreamingAead {
        check(initialized)
        val specification = PBEKeySpec(password, salt, ITERATIONS, 256)
        val keyBytes = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).encoded }
        finally { specification.clearPassword() }
        try {
            val parameters = AesGcmHkdfStreamingParameters.builder().setKeySizeBytes(32).setDerivedAesGcmKeySizeBytes(32)
                .setHkdfHashType(AesGcmHkdfStreamingParameters.HashType.SHA256).setCiphertextSegmentSizeBytes(1024 * 1024).build()
            val key = AesGcmHkdfStreamingKey.create(parameters, SecretBytes.copyFrom(keyBytes, InsecureSecretKeyAccess.get()))
            return KeysetHandle.newBuilder().addEntry(KeysetHandle.importKey(key).withFixedId(1).makePrimary()).build()
                .getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
        } finally { keyBytes.fill(0) }
    }

    private data class Header(val bytes: ByteArray, val length: Long, val salt: ByteArray)

    private fun readHeader(input: InputStream): Header {
        val bytes = ByteArray(HEADER_BYTES)
        var offset = 0
        var emptyReads = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count < 0) throw invalidFormat()
            if (count == 0) { if (++emptyReads >= 16) throw invalidFormat(); continue }
            emptyReads = 0
            offset += count
        }
        if (!bytes.copyOfRange(0, magic.size).contentEquals(magic)) throw invalidFormat()
        val length = ByteBuffer.wrap(bytes).getLong(magic.size)
        requireLength(length)
        return Header(bytes, length, bytes.copyOfRange(16, HEADER_BYTES))
    }

    private fun copyExact(input: InputStream, output: OutputStream, expected: Long, checkpoint: () -> Unit, onProgress: (Long) -> Unit, decrypting: Boolean) {
        val buffer = ByteArray(BUFFER_BYTES)
        var copied = 0L
        var emptyReads = 0
        try {
            onProgress(0)
            while (true) {
                checkpoint()
                // Authentication errors from Tink never expose provider/key
                // details. Output I/O errors remain I/O errors, not bad passwords.
                val count = try { input.read(buffer) } catch (error: IOException) { if (decrypting) throw invalidCiphertext() else throw error }
                if (count < 0) break
                if (count == 0) { if (++emptyReads >= 16) throw IOException("파일 제공 앱이 데이터를 보내지 않습니다."); continue }
                emptyReads = 0
                if (count.toLong() > expected - copied) {
                    if (decrypting) throw invalidCiphertext()
                    throw EncryptedBackupException(EncryptedBackupFailure.SOURCE_CHANGED, "읽는 동안 원본 백업 크기가 변경되었습니다. 원본은 유지했습니다.")
                }
                checkpoint()
                output.write(buffer, 0, count)
                copied += count
                onProgress(copied)
            }
            if (copied != expected) {
                if (decrypting) throw invalidCiphertext()
                throw EncryptedBackupException(EncryptedBackupFailure.SOURCE_CHANGED, "원본 백업을 끝까지 읽지 못했습니다. 원본은 유지했습니다.")
            }
            checkpoint()
        } finally { buffer.fill(0) }
    }

    private fun requireLength(length: Long) {
        if (length !in 0..MAX_PLAINTEXT_BYTES) throw invalidFormat()
    }
    private fun invalidFormat() = EncryptedBackupException(EncryptedBackupFailure.INVALID_FORMAT, "지원하지 않거나 손상된 암호화 백업입니다.")
    private fun invalidCiphertext() = EncryptedBackupException(EncryptedBackupFailure.BAD_PASSWORD_OR_DAMAGED, "암호가 맞지 않거나 백업이 손상되었습니다. 복원하지 않았습니다.")
    private class KeepOpenOutput(output: OutputStream) : FilterOutputStream(output) {
        override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
        override fun close() = flush()
    }
    private class KeepOpenInput(input: InputStream) : FilterInputStream(input) { override fun close() = Unit }
}
