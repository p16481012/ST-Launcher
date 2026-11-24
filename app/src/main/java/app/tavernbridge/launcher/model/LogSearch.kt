package app.tavernbridge.launcher.model

data class LogSearchResult(val ranges: List<IntRange>, val capped: Boolean)

/** Number of retained lines at the start of a polled tail (handles shifted windows and duplicate lines in O(n)). */
internal fun retainedLogPrefix(previous: List<String>, next: List<String>): Int {
    if (previous.isEmpty() || next.isEmpty()) return 0
    val prefix = IntArray(next.size)
    for (i in 1 until next.size) {
        var k = prefix[i - 1]
        while (k > 0 && next[i] != next[k]) k = prefix[k - 1]
        if (next[i] == next[k]) k++
        prefix[i] = k
    }
    var k = 0
    for (line in previous.takeLast(next.size)) {
        while (k > 0 && (k == next.size || next[k] != line)) k = prefix[k - 1]
        if (next[k] == line) k++
    }
    return k
}
fun searchLog(text: String, query: String, limit: Int = 500): LogSearchResult {
    if (query.isEmpty() || query.length > 200 || limit !in 1..1000) return LogSearchResult(emptyList(), false)
    val ranges = mutableListOf<IntRange>()
    var offset = 0
    while (offset <= text.length - query.length) {
        val found = text.indexOf(query, offset, ignoreCase = true)
        if (found < 0) break
        if (ranges.size == limit) return LogSearchResult(ranges, true)
        ranges += found until found + query.length
        offset = found + query.length
    }
    return LogSearchResult(ranges, false)
}
