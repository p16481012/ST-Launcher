package app.tavernbridge.launcher.data

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import java.io.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Only the decrypt staging directory is owned here. Never removes a user-selected input. */
internal class EncryptedBackupFiles(context: Context) {
    private val context = context.applicationContext
    private val resolver = context.contentResolver
    private val directory = File(context.cacheDir, "encrypted-restore")
    companion object { private val activeFiles = java.util.concurrent.ConcurrentHashMap.newKeySet<String>() }
    init {
        // A new process has no active owners. Only this feature's exact private filenames are removed.
        if (directory.isDirectory && !java.nio.file.Files.isSymbolicLink(directory.toPath())) {
            directory.listFiles()?.forEach { if (it.absolutePath !in activeFiles) remove(it) }
        }
    }

    fun remove(file: File?) = runCatching {
        if (file != null && file.parentFile?.canonicalFile == directory.canonicalFile &&
            file.name.matches(Regex("decrypt-[a-f0-9-]{36}\\.zip")) && !java.nio.file.Files.isSymbolicLink(file.toPath())) {
            file.delete(); activeFiles.remove(file.absolutePath)
        }
    }.getOrNull()
    fun uri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.private-backup", file)

    private fun preflight(condition: Boolean, message: String) {
        if (!condition) throw BackupTransferPreflightException(message)
    }

    private fun size(uri: Uri, signal: CancellationSignal): Long = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null, signal)?.use {
        if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
    } ?: -1L

    suspend fun encrypt(source: Uri, target: Uri, password: CharArray, progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        var owned = false
        val signal = CancellationSignal()
        val reading = AtomicReference<InputStream?>(null)
        val writing = AtomicReference<OutputStream?>(null)
        val watcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { signal.cancel(); runCatching { reading.get()?.close() }; runCatching { writing.get()?.close() } }
        }
        try {
            preflight(source.scheme == "content" && target.scheme == "content" && source != target && DocumentsContract.isDocumentUri(context, target), "새 저장 파일을 선택해 주세요.")
            preflight(!(source.authority == target.authority && runCatching { DocumentsContract.getDocumentId(source) == DocumentsContract.getDocumentId(target) }.getOrDefault(false)), "원본에는 덮어쓰지 않습니다. 다른 새 파일을 선택해 주세요.")
            val bytes = size(source, signal)
            preflight(bytes >= 4, "원본 ZIP 크기를 확인할 수 없습니다. 기기에 저장된 ZIP을 선택해 주세요.")
            BackupTransferLimits.checkEncryptedSize(bytes)
            preflight(size(target, signal) == 0L, "비어 있는 새 파일만 저장 위치로 사용할 수 있습니다.")
            owned = true
            val coroutine = currentCoroutineContext()
            resolver.openAssetFileDescriptor(source, "r", signal)?.use { descriptor ->
                PushbackInputStream(descriptor.createInputStream(), 4).use { input ->
                    reading.set(input)
                    val signature = ByteArray(4)
                    var offset = 0
                    while (offset < 4) { val n = input.read(signature, offset, 4 - offset); require(n > 0) { "ZIP을 읽지 못했습니다." }; offset += n }
                    preflight(signature[0] == 80.toByte() && signature[1] == 75.toByte() &&
                        ((signature[2] == 3.toByte() && signature[3] == 4.toByte()) ||
                         (signature[2] == 5.toByte() && signature[3] == 6.toByte()) ||
                         (signature[2] == 7.toByte() && signature[3] == 8.toByte())), "ZIP 백업을 선택해 주세요.")
                    input.unread(signature)
                    resolver.openAssetFileDescriptor(target, "w", signal)?.use { destination ->
                        destination.createOutputStream().use { output ->
                            writing.set(output)
                            EncryptedBackupCodec.encrypt(input, output, password, bytes, { coroutine.ensureActive() }) { progress(it, bytes) }
                        }
                    } ?: error("새 파일을 열지 못했습니다.")
                }
            } ?: error("원본을 읽지 못했습니다.")
            coroutine.ensureActive()
        } catch (error: Exception) {
            val removed = !owned || runCatching { DocumentsContract.deleteDocument(resolver, target) }.getOrDefault(false)
            currentCoroutineContext().ensureActive()
            if (error is BackupTransferPreflightException && removed) throw error
            throw IOException(if (removed) "암호화 저장을 완료하지 못했습니다. 원본 ZIP은 유지했습니다. 저장 공간과 파일 접근을 확인해 주세요."
                else "암호화 저장에 실패했습니다. 저장 위치에 미완성 파일이 남아 있습니다. 원본 ZIP은 유지했습니다.", error)
        } finally { password.fill('\u0000'); watcher.cancel(); reading.set(null); writing.set(null) }
    }

    suspend fun decrypt(source: Uri, password: CharArray, progress: (Long, Long) -> Unit): File {
      var produced: File? = null
      try { return withContext(Dispatchers.IO) {
        val signal = CancellationSignal()
        val reading = AtomicReference<InputStream?>(null)
        val watcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { signal.cancel(); runCatching { reading.get()?.close() } }
        }
        var outputFile: File? = null
        var complete = false
        try {
            require(source.scheme == "content")
            val header = resolver.openAssetFileDescriptor(source, "r", signal)?.use { fd ->
                fd.createInputStream().use { input -> reading.set(input); EncryptedBackupCodec.inspectHeader(input) }
            } ?: error("암호화 백업을 열 수 없습니다.")
            BackupTransferLimits.checkDecryptionSpace(header.plaintextBytes,
                StatFs(context.cacheDir.absolutePath).availableBytes,
                StatFs(android.os.Environment.getExternalStorageDirectory().path).availableBytes)
            check(directory.isDirectory || directory.mkdir())
            check(!java.nio.file.Files.isSymbolicLink(directory.toPath()))
            val file = File(directory, "decrypt-${UUID.randomUUID()}.zip")
            check(file.createNewFile()); outputFile = file; produced = file
            activeFiles.add(file.absolutePath)
            file.setReadable(false, false); file.setWritable(false, false); file.setReadable(true, true); file.setWritable(true, true)
            val coroutine = currentCoroutineContext()
            resolver.openAssetFileDescriptor(source, "r", signal)?.use { fd ->
                fd.createInputStream().use { input ->
                    reading.set(input)
                    FileOutputStream(file).use { output ->
                        EncryptedBackupCodec.decrypt(input, output, password, header.plaintextBytes, { coroutine.ensureActive() }) { progress(it, header.plaintextBytes) }
                        output.fd.sync()
                    }
                }
            } ?: error("암호화 백업을 읽지 못했습니다.")
            coroutine.ensureActive(); complete = true; file
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (error is EncryptedBackupException || error is BackupTransferPreflightException) throw error
            throw IOException("복호화를 완료하지 못했습니다. 설치 데이터는 변경하지 않았습니다. 앱 저장 공간과 파일 접근을 확인해 주세요.", error)
        } finally {
            password.fill('\u0000'); watcher.cancel(); reading.set(null)
            if (!complete) remove(outputFile)
        }
      } } catch (cancelled: CancellationException) {
          // withContext can discard a completed result when returning to a cancelled caller.
          remove(produced)
          throw cancelled
      } finally { password.fill('\u0000') }
    }
}
