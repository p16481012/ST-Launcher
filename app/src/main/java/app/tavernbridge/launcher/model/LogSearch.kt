package app.tavernbridge.launcher.model

data class LogSearchResult(val ranges: List<IntRange>, val capped: Boolean)
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
