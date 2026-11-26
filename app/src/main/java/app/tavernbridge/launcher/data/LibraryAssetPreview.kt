package app.tavernbridge.launcher.data

import java.nio.ByteBuffer
import java.util.Base64

internal data class AssetPreviewEntry(val title: String, val summary: String, val excerpt: String, val colorArgb: Long?)

/** A bounded display projection, not a renderer or a copy of the user's JSON. */
internal data class LibraryAssetPreview(val entries: List<AssetPreviewEntry>, val omitted: Int, val notice: String) {
    companion object {
        private val argb = Regex("[A-F0-9]{8}")

        private fun text(field: String, max: Int): String {
            require(field.length <= (max * 4 + 2) / 3 * 4)
            val bytes = Base64.getDecoder().decode(field)
            require(bytes.size <= max * 4 && Base64.getEncoder().encodeToString(bytes) == field)
            val value = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
            require(value.codePointCount(0, value.length) <= max && value.none {
                it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069'
            })
            return value
        }

        fun parse(stdout: String): LibraryAssetPreview {
            require(stdout.length <= 32 * 1024)
            val rows = mutableListOf<AssetPreviewEntry>()
            var omitted: Int? = null
            var notice = ""
            for (line in stdout.lineSequence().filter { it.isNotEmpty() }) {
                val c = line.split('\t')
                require(omitted == null)
                when (c[0]) {
                    "peek" -> {
                        require(c.size == 5 && rows.size < 12 && (c[4] == "-" || c[4].matches(argb)))
                        rows += AssetPreviewEntry(text(c[1],72).also { require(it.isNotBlank()) },
                            text(c[2],120), text(c[3],256), c[4].takeUnless { it == "-" }?.toLong(16))
                    }
                    "preview" -> {
                        require(c.size == 3)
                        omitted = c[1].toInt().also { require(it >= 0) }
                        notice = text(c[2],120)
                    }
                    else -> error("올바른 미리보기 응답이 아니에요.")
                }
            }
            return LibraryAssetPreview(rows.toList(), checkNotNull(omitted) { "미리보기 결과가 끝까지 도착하지 않았어요." }, notice)
        }
    }
}
