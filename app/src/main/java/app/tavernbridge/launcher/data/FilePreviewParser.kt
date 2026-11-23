package app.tavernbridge.launcher.data

import app.tavernbridge.launcher.model.TavernFilePreview
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** Bounded callback payloads only; never use these read-only contents as an editor document. */
object FilePreviewParser {
    const val MAX_PREVIEW_BYTES = 24 * 1024

    fun parse(relativePath: String, output: String): TavernFilePreview {
        require(output.length <= 40 * 1024) { "미리보기 응답이 너무 큽니다." }
        val fields = output.lineSequence().filter { '=' in it }
            .groupBy({ it.substringBefore('=') }, { it.substringAfter('=') })
        fun field(name: String): String = fields[name]?.singleOrNull() ?: error("미리보기 응답이 올바르지 않습니다.")
        val sourceBytes = field("source_bytes").toLong().also { require(it >= 0) }
        if (field("preview_kind") == "card") {
            fun description(name: String, limit: Int): String {
                val encoded = field(name)
                require(encoded.length <= limit * 4)
                val bytes = Base64.getDecoder().decode(encoded)
                require(bytes.size <= limit)
                return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString().filterNot { it.isISOControl() && it != '\n' && it != '\t' }
            }
            return TavernFilePreview(relativePath, sourceBytes, cardName = description("name_b64", 1024),
                cardDescription = description("description_b64", 8192), truncated = true)
        }
        val encoded = field("content_b64")
        require(encoded.length <= ((MAX_PREVIEW_BYTES + 2) / 3) * 4)
        val bytes = Base64.getDecoder().decode(encoded).also { require(it.size <= MAX_PREVIEW_BYTES) }
        return when (field("preview_kind")) {
            "text" -> {
                val truncated = field("truncated").also { require(it == "0" || it == "1") } == "1"
                require(sourceBytes >= bytes.size && (truncated || sourceBytes == bytes.size.toLong()))
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                require(text.none { it.code in 0..8 || it.code in 11..12 || it.code in 14..31 }) { "텍스트 파일만 미리 볼 수 있습니다." }
                TavernFilePreview(relativePath, sourceBytes, text = text, truncated = truncated)
            }
            "png" -> {
                require(sourceBytes == bytes.size.toLong())
                require(bytes.size >= 24 && bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map(Int::toByte))
                val header = ByteBuffer.wrap(bytes)
                require(header.getInt(8) == 13 && String(bytes, 12, 4, Charsets.US_ASCII) == "IHDR")
                val width = header.getInt(16)
                val height = header.getInt(20)
                require(width in 1..512 && height in 1..512 && width.toLong() * height <= 262144)
                TavernFilePreview(relativePath, sourceBytes, pngBytes = bytes)
            }
            else -> error("지원하지 않는 미리보기 형식입니다.")
        }
    }
}
