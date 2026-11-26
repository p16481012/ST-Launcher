package app.tavernbridge.launcher.data

import java.io.InputStream
import java.io.OutputStream

/** Shared with the Termux filename grammar. Transfer never accepts an arbitrary path. */
internal object BackupTransferPolicy {
    private val fileName = Regex("SillyTavern-Launcher-[0-9]{8}-[0-9]{6}(-[0-9]+)?( \\([0-9]+\\))*\\.zip")

    fun validFileName(value: String): Boolean = value.length <= 255 && fileName.matches(value)

    fun validateSource(expectedName: String, expectedSize: Long, actualName: String, actualSize: Long) {
        require(validFileName(expectedName) && expectedSize >= 4) { "백업 목록을 새로 고친 뒤 다시 선택해 주세요." }
        require(actualName == expectedName) { "선택한 백업과 이름이 다릅니다. $expectedName 파일을 선택해 주세요." }
        require(actualSize < 0 || actualSize == expectedSize) { "백업 파일 크기가 바뀌었습니다. 목록을 새로 고친 뒤 다시 선택해 주세요." }
    }

    fun validZipSignature(bytes: ByteArray): Boolean = bytes.size == 4 && bytes[0] == 0x50.toByte() &&
        bytes[1] == 0x4b.toByte() && ((bytes[2] == 3.toByte() && bytes[3] == 4.toByte()) ||
        (bytes[2] == 5.toByte() && bytes[3] == 6.toByte()))

    /** Bounded memory, Long counters; never write past the selected archive's known size. */
    fun copy(
        input: InputStream,
        output: OutputStream,
        expectedSize: Long,
        checkpoint: () -> Unit = {},
        onProgress: (Long) -> Unit = {},
    ): Long {
        require(expectedSize >= 4) { "백업 파일 크기를 확인할 수 없습니다." }
        val buffer = ByteArray(128 * 1024)
        var copied = 0L
        var emptyReads = 0
        onProgress(0)
        while (true) {
            checkpoint()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                check(++emptyReads < 16) { "파일 제공 앱이 데이터를 보내지 않습니다. 다시 선택해 주세요." }
                continue
            }
            emptyReads = 0
            check(count.toLong() <= expectedSize - copied) { "복사하는 동안 원본 파일 크기가 바뀌었습니다." }
            checkpoint()
            output.write(buffer, 0, count)
            copied += count
            onProgress(copied)
        }
        check(copied == expectedSize) { "원본을 끝까지 읽지 못했습니다. 백업 파일을 다시 선택해 주세요." }
        checkpoint()
        output.flush()
        checkpoint()
        return copied
    }
}
