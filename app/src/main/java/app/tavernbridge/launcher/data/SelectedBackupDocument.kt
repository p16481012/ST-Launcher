package app.tavernbridge.launcher.data

import java.io.IOException
import java.util.concurrent.CancellationException

internal class SelectedBackupWriteException(cause: Exception) :
    IOException("저장을 마치지 못했어요. 원본 ZIP은 그대로예요. 선택한 위치에 미완성 파일이 있으면 확인 후 삭제해 주세요.", cause)

/** SAF's zero-byte metadata does NOT prove ownership. Never unlink a picker-returned document. */
internal inline fun <T> writeSelectedBackupDocument(
    size: () -> Long,
    checkpoint: () -> Unit,
    write: () -> T,
): T {
    checkpoint()
    if (size() != 0L) throw BackupTransferPreflightException("비어 있는 새 파일인지 확인할 수 없어요. 다른 이름이나 저장 위치를 선택해 주세요.")
    checkpoint()
    return try { write() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (e: Exception) { throw SelectedBackupWriteException(e) }
}
