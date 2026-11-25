package app.tavernbridge.launcher.data

import android.content.Context
import android.util.AtomicFile
import app.tavernbridge.launcher.model.SafetyDeleteReceipt
import java.io.File
import java.io.FileNotFoundException

/** Local-only atomic count pair. No item identifiers, paths, revisions, or commands are retained. */
internal class SafetyDeleteReceiptStore(path: File) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "safety-delete-counts"))
    private val file = AtomicFile(path)
    @Synchronized fun read(): SafetyDeleteReceipt? = try {
        file.openRead().use { input ->
            val buffer = ByteArray(16)
            var used = 0
            while (used < buffer.size) {
                val n = input.read(buffer, used, buffer.size - used)
                if (n < 0) break
                if (n == 0) return null
                used += n
            }
            val pair = String(buffer, 0, used, Charsets.US_ASCII).split(':')
            if (used == buffer.size || pair.size != 2) null
            else pair[0].toIntOrNull()?.let { n -> pair[1].toIntOrNull()?.takeIf { n in 2..1000 && it in 0..n }?.let { SafetyDeleteReceipt(n, it) } }
        }
    } catch (_: FileNotFoundException) { null }
      catch (_: java.io.IOException) { null }

    @Synchronized fun save(receipt: SafetyDeleteReceipt) {
        val output = file.startWrite()
        try {
            output.write("${receipt.total}:${receipt.confirmed}".toByteArray(Charsets.US_ASCII))
            output.fd.sync()
            file.finishWrite(output)
        } catch (e: Exception) { file.failWrite(output); throw e }
        check(read() == receipt) { "일괄 삭제 기록을 저장하지 못해 추가 삭제를 중단했어요." }
    }
    @Synchronized fun clear() = file.delete()
}
