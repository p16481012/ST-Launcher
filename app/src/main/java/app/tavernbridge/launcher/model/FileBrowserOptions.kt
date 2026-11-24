package app.tavernbridge.launcher.model

import java.util.Locale

enum class TavernFileSort(val label: String) {
    NAME("이름순"), MODIFIED("최근 수정순"), SIZE("큰 파일순"),
}

/** Search only the loaded current folder: no surprise recursive disk scan. */
fun visibleTavernFiles(entries: List<TavernFileEntry>, query: String, sort: TavernFileSort): List<TavernFileEntry> {
    val needle = query.trim().lowercase(Locale.ROOT)
    val filtered = if (needle.isEmpty()) entries else entries.filter { it.name.lowercase(Locale.ROOT).contains(needle) }
    val names = compareBy<TavernFileEntry> { it.name.lowercase(Locale.ROOT) }.thenBy { it.name }
    val comparator = when (sort) {
        TavernFileSort.NAME -> names
        TavernFileSort.MODIFIED -> compareByDescending<TavernFileEntry> { it.modifiedAt }.then(names)
        TavernFileSort.SIZE -> compareByDescending<TavernFileEntry> { it.sizeBytes }.then(names)
    }
    return filtered.sortedWith(compareByDescending<TavernFileEntry> { it.isDirectory }.then(comparator))
}

fun selectedTavernFiles(entries: List<TavernFileEntry>, paths: Set<String>): List<TavernFileEntry> =
    entries.filter { it.relativePath in paths }.distinctBy { it.relativePath }

fun isTavernPng(entry: TavernFileEntry): Boolean = !entry.isDirectory && entry.name.endsWith(".png", ignoreCase = true)

const val MAX_FILE_SELECTION = 200

private val fileDateStamp = Regex("""^\d{4}-(\d{2})-(\d{2})[T ](\d{2}:\d{2})(?::\d{2}.*)?$""")
internal fun fileListDate(value: String): String = fileDateStamp.matchEntire(value)?.destructured
    ?.let { (month, day, time) -> "$month/$day $time" } ?: value

fun canSelectTavernFileForTrash(entry: TavernFileEntry): Boolean =
    entry.relativePath.isNotEmpty() && entry.relativePath.split('/').none { it == ".git" || it == "node_modules" } &&
        entry.revision.matches(Regex("[a-f0-9]{64}"))

data class TavernFilePreview(
    val relativePath: String,
    val sourceBytes: Long,
    val text: String? = null,
    val pngBytes: ByteArray? = null,
    val truncated: Boolean = false,
    val cardName: String? = null,
    val cardDescription: String? = null,
)
