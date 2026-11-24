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

internal enum class LogSource { SERVER, ACCESS, TEXT }
internal data class LiveLogLine(val id: Long, val source: LogSource, val text: String)
internal data class LogFrame(val rows: List<LiveLogLine>, val firstNewId: Long)

/** server-logs emits two independent bounded tails, not an append-only concatenated text. */
internal class LiveLogTimeline {
    private var ordered = emptyList<LiveLogLine>()
    private var nextId = 1L
    private var initialized = false
    fun update(raw: String, combined: Boolean): LogFrame {
        val serverMarker = "===== SillyTavern server.log ====="
        val accessMarker = "===== SillyTavern access.log ====="
        val lines = raw.trimEnd('\r', '\n').lineSequence().toList().takeUnless { raw.isBlank() }.orEmpty()
        val streams = if (combined && lines.firstOrNull() == serverMarker) {
            val split = lines.indexOf(accessMarker)
            if (split < 0) mapOf(LogSource.SERVER to lines.drop(1))
            else {
                val server = lines.subList(1, split)
                mapOf(LogSource.SERVER to (if (server.lastOrNull() == "") server.dropLast(1) else server),
                    LogSource.ACCESS to lines.drop(split + 1))
            }
        } else mapOf(LogSource.TEXT to lines)
        val keep = mutableSetOf<Long>()
        val added = mutableListOf<LiveLogLine>()
        val firstNew = if (initialized) nextId else Long.MAX_VALUE
        for ((source, incoming) in streams) {
            val old = ordered.filter { it.source == source }
            val retained = retainedLogPrefix(old.map { it.text }, incoming)
            old.takeLast(retained).forEach { keep += it.id }
            incoming.drop(retained).forEach { added += LiveLogLine(nextId++, source, it) }
        }
        ordered = ordered.filter { it.id in keep } + added
        initialized = ordered.isNotEmpty()
        return LogFrame(ordered, firstNew)
    }
}

internal fun LauncherUiState.canReadLiveServerLog(): Boolean =
    !isWorking || (environment.managerConnected && termuxSetup.verified && visibleOperation in
        setOf("backup", "diagnose", "update-preflight", "start", "stop", "restart"))
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
