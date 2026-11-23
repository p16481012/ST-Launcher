package app.tavernbridge.launcher.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import app.tavernbridge.launcher.model.BackupArchive
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Termux owns its Download ZIPs. A path or MediaStore lookup does not grant this app access.
 * Only a read grant explicitly selected through ACTION_OPEN_DOCUMENT is retained here.
 * No backup is staged in app storage, recompressed, uploaded, or deleted from its source.
 */
internal class BackupTransferController(context: Context) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val grants = this.context.getSharedPreferences("backup_transfer_read_grants", Context.MODE_PRIVATE)

    data class Source(val uri: Uri, val fileName: String, val sizeBytes: Long)
    data class Progress(val bytes: Long, val total: Long)

    suspend fun findReadable(backup: BackupArchive): Source? = withContext(Dispatchers.IO) {
        val raw = grants.getString(backup.fileName, null) ?: return@withContext null
        try {
            withSourceAccess { signal, reading -> inspectSource(Uri.parse(raw), backup, signal, reading) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            grants.edit().remove(backup.fileName).apply()
            releaseUnusedGrant(raw)
            null
        }
    }

    /** Call only for a URI returned by this screen's OpenDocument request. */
    suspend fun selectSource(uri: Uri, backup: BackupArchive): Source = withSourceAccess { signal, reading ->
        val source = inspectSource(uri, backup, signal, reading)
        val coroutine = currentCoroutineContext()
        coroutine.ensureActive()
        val oldUri = grants.getString(backup.fileName, null)
        var grantTaken = false
        try {
            // Some providers offer only a temporary grant: it still works for this interaction.
            grantTaken = runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
            coroutine.ensureActive()
            if (grantTaken) {
                grants.edit().putString(backup.fileName, uri.toString()).apply()
                coroutine.ensureActive()
                if (oldUri != null && oldUri != uri.toString()) releaseUnusedGrant(oldUri)
                // Keep a small URI-only cache; never request write access to an original backup.
                grants.all.keys.filter { it != backup.fileName }.drop(19).forEach { key ->
                    val old = grants.getString(key, null)
                    grants.edit().remove(key).apply()
                    if (old != null) releaseUnusedGrant(old)
                }
            }
            source
        } catch (cancelled: CancellationException) {
            // If cancellation raced with the permission call/cache write, keep no late grant.
            // An older binding is preserved rather than accidentally revoking its access.
            if (grantTaken && oldUri != uri.toString()) {
                if (grants.getString(backup.fileName, null) == uri.toString()) {
                    grants.edit().apply {
                        if (oldUri == null) remove(backup.fileName) else putString(backup.fileName, oldUri)
                    }.apply()
                }
                releaseUnusedGrant(uri.toString())
            }
            throw cancelled
        }
    }

    suspend fun shareIntent(source: Source, backup: BackupArchive): Intent = withSourceAccess { signal, reading ->
        inspectSource(source.uri, backup, signal, reading)
        currentCoroutineContext().ensureActive()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, source.uri)
            clipData = ClipData.newRawUri(backup.fileName, source.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Intent.createChooser(send, "백업 공유").apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /**
     * newDestination MUST come from ACTION_CREATE_DOCUMENT, never OpenDocument or a saved URI.
     * A confirmed empty document is required before opening a write stream. If the provider
     * cannot establish this, it is left untouched. Failed/cancelled copies remove only that
     * newly-created empty destination, never the source or an existing nonempty document.
     */
    suspend fun export(
        source: Source,
        newDestination: Uri,
        backup: BackupArchive,
        onProgress: (Progress) -> Unit = {},
    ): Long = withContext(Dispatchers.IO) {
        require(newDestination.scheme == "content" && DocumentsContract.isDocumentUri(context, newDestination)) {
            "저장 위치 선택 화면에서 새 파일을 만들어 주세요."
        }
        require(!sameDocument(source.uri, newDestination)) { "원본 백업에는 덮어쓸 수 없습니다. 다른 위치에 새 파일을 만들어 주세요." }
        val signal = CancellationSignal()
        val reading = AtomicReference<InputStream?>(null)
        val writing = AtomicReference<OutputStream?>(null)
        // A separate child reacts even when a cloud provider blocks a read/write call.
        val watcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally {
                runCatching { signal.cancel() }
                runCatching { reading.get()?.close() }
                runCatching { writing.get()?.close() }
            }
        }
        var ownedEmptyDestination = false
        try {
            val checked = inspectSource(source.uri, backup, signal, reading)
            val targetSize = documentSize(newDestination, signal).takeIf { it >= 0 }
                ?: resolver.openFileDescriptor(newDestination, "r", signal)?.use { it.statSize } ?: -1L
            BackupTransferPolicy.requireEmptyDestination(targetSize)
            ownedEmptyDestination = true
            val coroutine = currentCoroutineContext()
            var lastReport = 0L
            val copied = resolver.openAssetFileDescriptor(checked.uri, "r", signal)?.use { descriptor ->
                descriptor.createInputStream().use { input ->
                    reading.set(input)
                    resolver.openAssetFileDescriptor(newDestination, "w", signal)?.use { targetDescriptor ->
                        targetDescriptor.createOutputStream().use { output ->
                            writing.set(output)
                            BackupTransferPolicy.copy(input, output, checked.sizeBytes, checkpoint = { coroutine.ensureActive() }) { bytes ->
                                val now = android.os.SystemClock.elapsedRealtime()
                                if (bytes == 0L || bytes == checked.sizeBytes || now - lastReport >= 250L) {
                                    onProgress(Progress(bytes, checked.sizeBytes))
                                    lastReport = now
                                }
                            }
                        }
                    } ?: error("선택한 저장 위치에 파일을 쓸 수 없습니다.")
                }
            } ?: error("원본 백업 파일을 읽을 수 없습니다.")
            coroutine.ensureActive()
            val writtenSize = documentSize(newDestination, signal)
            check(writtenSize < 0 || writtenSize == copied) { "저장된 파일 크기가 원본과 다릅니다. 저장 공간이나 연결을 확인해 주세요." }
            copied
        } catch (error: Exception) {
            val removed = !ownedEmptyDestination || runCatching { DocumentsContract.deleteDocument(resolver, newDestination) }.getOrDefault(false)
            // A blocked provider may throw IOException after the cancellation watcher closes it.
            currentCoroutineContext().ensureActive()
            if (!removed) throw IllegalStateException("${error.message ?: "내보내기에 실패했습니다."}\n저장 위치에 미완성 파일이 남아 있을 수 있습니다. 확인 후 삭제해 주세요.", error)
            throw error
        } finally {
            watcher.cancel()
            reading.set(null)
            writing.set(null)
        }
    }

    /** Includes both metadata queries and the small ZIP probe in preparation cancellation. */
    private suspend fun <T> withSourceAccess(
        block: suspend (CancellationSignal, AtomicReference<InputStream?>) -> T,
    ): T = withContext(Dispatchers.IO) {
        val signal = CancellationSignal()
        val reading = AtomicReference<InputStream?>(null)
        val watcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally {
                runCatching { signal.cancel() }
                runCatching { reading.get()?.close() }
            }
        }
        try {
            currentCoroutineContext().ensureActive()
            block(signal, reading).also { currentCoroutineContext().ensureActive() }
        } catch (error: Exception) {
            // Providers commonly report IOException / OperationCanceledException on close.
            // Preserve coroutine cancellation instead of opening another picker or showing failure.
            currentCoroutineContext().ensureActive()
            throw error
        } finally {
            watcher.cancel()
            reading.set(null)
        }
    }

    private suspend fun inspectSource(
        uri: Uri,
        backup: BackupArchive,
        signal: CancellationSignal,
        reading: AtomicReference<InputStream?>,
    ): Source {
        currentCoroutineContext().ensureActive()
        require(uri.scheme == "content") { "파일 선택 화면에서 백업 ZIP을 선택해 주세요." }
        val metadata = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null, signal)?.use {
            check(it.moveToFirst()) { "선택한 백업을 찾을 수 없습니다." }
            it.getString(0) to if (it.isNull(1)) -1L else it.getLong(1)
        } ?: error("선택한 백업의 정보를 읽을 수 없습니다.")
        BackupTransferPolicy.validateSource(backup.fileName, backup.sizeBytes, metadata.first, metadata.second)
        val signature = resolver.openAssetFileDescriptor(uri, "r", signal)?.use { descriptor ->
            descriptor.createInputStream().use { input ->
                reading.set(input)
                // readNBytes is unavailable on older supported Android releases.
                val bytes = ByteArray(4)
                var size = 0
                while (size < bytes.size) {
                    currentCoroutineContext().ensureActive()
                    val next = input.read()
                    if (next < 0) break
                    bytes[size++] = next.toByte()
                }
                reading.set(null)
                bytes.copyOf(size)
            }
        } ?: error("선택한 백업을 읽을 수 없습니다. 파일을 다시 선택해 주세요.")
        require(BackupTransferPolicy.validZipSignature(signature)) { "ZIP 백업이 아닙니다. 선택한 파일을 확인해 주세요." }
        currentCoroutineContext().ensureActive()
        return Source(uri, backup.fileName, backup.sizeBytes)
    }

    private fun sameDocument(first: Uri, second: Uri): Boolean {
        if (first.normalizeScheme() == second.normalizeScheme()) return true
        if (first.authority != second.authority) return false
        return runCatching { DocumentsContract.getDocumentId(first) == DocumentsContract.getDocumentId(second) }.getOrDefault(false)
    }

    private fun documentSize(uri: Uri, signal: CancellationSignal): Long = resolver.query(
        uri, arrayOf(OpenableColumns.SIZE), null, null, null, signal,
    )?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L } ?: -1L

    private fun releaseUnusedGrant(raw: String) {
        if (grants.all.values.any { it == raw }) return
        runCatching { resolver.releasePersistableUriPermission(Uri.parse(raw), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
}
