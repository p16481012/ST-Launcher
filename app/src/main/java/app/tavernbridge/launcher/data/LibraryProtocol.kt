package app.tavernbridge.launcher.data

import java.util.Base64

internal data class LibraryItem(val path: String, val label: String, val bytes: Long, val chats: Int,
    val modified: Long, val revision: String, val protected: Boolean, val fileBytes: Long = bytes,
    val artRevision: String = "")
internal data class LibraryPage(val items: List<LibraryItem>, val next: String)
internal data class LibraryGroup(val id: String, val date: Long, val count: Int)

internal val librarySha = Regex("[0-9a-f]{64}")
internal val libraryGroupId = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
internal fun libraryEncode(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
internal fun libraryDecode(s: String) = String(Base64.getDecoder().decode(s), Charsets.UTF_8)

internal fun String.safeLibraryRelative() = isNotEmpty() && length <= 4096 && !contains('\u0000') &&
    split('/').none { it.isEmpty() || it == "." || it == ".." }

internal object LibraryProtocol {
    fun picture(b: ByteArray): String? {
        fun at(offset: Int, text: String) = offset >= 0 && offset + text.length <= b.size &&
            text.indices.all { (b[offset+it].toInt() and 255) == text[it].code }
        return when {
            b.size >= 33 && at(0,"\u0089PNG\r\n\u001a\n") && at(12,"IHDR") -> "png"
            b.size >= 4 && at(0,"\u00ff\u00d8\u00ff") -> "jpg"
            b.size >= 12 && at(0,"RIFF") && at(8,"WEBP") -> "webp"
            b.size >= 13 && (at(0,"GIF87a") || at(0,"GIF89a")) -> "gif"
            b.size >= 26 && at(0,"BM") -> "bmp"
            b.size >= 16 && at(4,"ftyp") && (8..minOf(b.size - 4,1020) step 4).any {
                it != 12 && (at(it,"avif") || at(it,"avis"))
            } -> "avif"
            else -> null
        }
    }
    fun page(stdout: String): LibraryPage {
        val rows = mutableListOf<LibraryItem>()
        var next: String? = null
        stdout.lineSequence().forEach { line ->
            if (line.startsWith("item\t")) {
                val c = line.split('\t')
                require(c.size in 8..10 && librarySha.matches(c[6]) && c[7] in setOf("0","1"))
                val path = libraryDecode(c[1]); require(path.safeLibraryRelative())
                val bytes = c[3].toLong().also { require(it >= 0) }
                rows += LibraryItem(path, libraryDecode(c[2]), bytes,
                    c[4].toInt().also { require(it >= 0) }, c[5].toLong(), c[6], c[7] == "1",
                    c.getOrNull(8)?.toLong()?.also { require(it in 0L..bytes) } ?: bytes,
                    c.getOrNull(9)?.also { require(it.isEmpty() || librarySha.matches(it)) }.orEmpty())
            } else if (line.startsWith("next_cursor=")) {
                require(next == null)
                next = line.substringAfter('=').also { require(it.length <= 8192 && it.matches(Regex("[A-Za-z0-9+/=_-]*"))) }
            }
        }
        return LibraryPage(rows, checkNotNull(next) { "목록 응답이 끝까지 도착하지 않았어요." })
    }
    fun groups(stdout: String): List<LibraryGroup> = stdout.lineSequence().filter { it.startsWith("group\t") }.map {
        val c = it.split('\t')
        require(c.size == 4 && libraryGroupId.matches(c[1]))
        LibraryGroup(c[1], c[2].toLong(), c[3].toInt().also { n -> require(n >= 0) })
    }.sortedByDescending { it.date }.toList()

    /** Picker hint. The helper independently verifies the file and the selected destination. */
    fun jsonLibrary(k: Map<String, String>): String? {
        if (listOf("instruct","context","sysprompt","preset").count { k[it] == "BEGIN_OBJECT" } >= 2) return null
        return buildList {
            if (k["prompts"] == "BEGIN_ARRAY" || k["chat_completion_source"] == "STRING" || k["openai_max_context"] == "NUMBER") add("presets")
            if (k["qrList"] == "BEGIN_ARRAY" || k["quickReplySlots"] == "BEGIN_ARRAY") add("quickreplies")
            if (k["main_text_color"] == "STRING" || k["blur_strength"] == "NUMBER") add("themes")
        }.singleOrNull()
    }
}
