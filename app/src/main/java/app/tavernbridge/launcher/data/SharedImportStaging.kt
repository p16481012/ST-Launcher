package app.tavernbridge.launcher.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** App-owned transfer files only. The user-selected original is never removed here. */
internal class SharedImportStaging(private val context: Context) {
    data class Transfer(val uri: Uri, val name: String)
    data class CopyProgress(val bytes: Long, val total: Long, val name: String, val finished: Boolean = false)
    private val pending = context.getSharedPreferences("pending_import_transfers", Context.MODE_PRIVATE)

    suspend fun copy(source: Uri, backup: Boolean, onProgress: (CopyProgress) -> Unit = {}): Transfer = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IllegalStateException("파일 가져오기는 Android 10 이상에서 지원합니다.")
        }
        require(source.scheme == "content") { "파일 선택 화면에서 가져올 파일을 선택해 주세요." }
        val resolver = context.contentResolver
        onProgress(CopyProgress(0, -1, "선택한 파일 확인 중"))
        val sourceName = displayName(source)
        val declaredSize = resolver.query(source, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
        } ?: -1L
        onProgress(CopyProgress(0, declaredSize, sourceName))
        val maxBytes = 8L * 1024 * 1024 * 1024
        require(declaredSize <= maxBytes) { "가져올 파일은 8 GiB 이하여야 합니다." }
        fun available() = StatFs(Environment.getExternalStorageDirectory().path).availableBytes
        val reserve = 32L * 1024 * 1024
        require(declaredSize < 0 || declaredSize <= available() - reserve) { "파일을 가져올 저장공간이 부족합니다." }
        val name = if (backup) "SillyTavern-Import-${UUID.randomUUID()}.zip" else "SillyTavern-File-${UUID.randomUUID()}.tmp"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, if (backup) "application/zip" else "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Download 폴더에 임시 파일을 만들지 못했습니다.")
        val transfer = Transfer(target, name)
        pending.edit().putString(target.toString(), "${System.currentTimeMillis()}|$name").commit()
        try {
            val coroutine = currentCoroutineContext()
            resolver.openInputStream(source)?.use { input ->
                resolver.openOutputStream(target, "w")?.use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var total = 0L
                    var sinceCheck = 0L
                    var lastReport = 0L
                    while (true) {
                        coroutine.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(total + count <= maxBytes) { "가져올 파일은 8 GiB 이하여야 합니다." }
                        sinceCheck += count
                        if (sinceCheck >= 8 * 1024 * 1024) {
                            check(available() >= reserve) { "파일을 가져올 저장공간이 부족합니다." }
                            sinceCheck = 0
                        }
                        output.write(buffer, 0, count)
                        total += count
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= 250L) {
                            onProgress(CopyProgress(total, declaredSize, sourceName))
                            lastReport = now
                        }
                    }
                    check(declaredSize < 0 || total == declaredSize) { "가져오는 동안 원본 파일 크기가 바뀌었습니다. 다시 선택해 주세요." }
                    output.flush()
                    onProgress(CopyProgress(total, if (declaredSize >= 0) declaredSize else total, sourceName, true))
                } ?: error("임시 파일에 쓸 수 없습니다.")
            } ?: error("선택한 파일을 읽을 수 없습니다.")
            resolver.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            transfer
        } catch (error: Exception) {
            remove(transfer)
            throw error
        }
    }

    fun displayName(source: Uri): String = context.contentResolver.query(
        source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
    )?.use { if (it.moveToFirst()) it.getString(0) else null }
        ?: throw IllegalArgumentException("파일 이름을 확인할 수 없습니다.")

    fun remove(transfer: Transfer) {
        // Best-effort cleanup must not turn a completed restore into a false failure.
        runCatching {
            context.contentResolver.delete(transfer.uri, null, null)
            pending.edit().remove(transfer.uri.toString()).apply()
        }
    }

    /** Never clean a live transfer. Called only after doctor confirms the manager is idle. */
    suspend fun cleanAbandoned() = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60_000L
        pending.all.forEach { (rawUri, rawRecord) ->
            val record = rawRecord as? String ?: return@forEach
            val time = record.substringBefore('|').toLongOrNull() ?: return@forEach
            val name = record.substringAfter('|')
            if (time > cutoff || !name.matches(Regex("SillyTavern-(Import|File)-[0-9a-f-]{36}\\.(zip|tmp)"))) return@forEach
            val uri = Uri.parse(rawUri)
            if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY) return@forEach
            runCatching {
                if (displayName(uri) == name) remove(Transfer(uri, name))
            }
        }
    }
}
