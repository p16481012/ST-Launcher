package app.tavernbridge.launcher.model

import kotlin.math.ceil

/** Estimate only the current measured phase; never extrapolate an entire multi-phase operation. */
internal class PhaseEta {
    companion object { const val STALE_MS = 15_000L }
    private var start = -1L
    private var first = 0L
    private var last = 0L
    private var advancedAt = 0L

    fun observe(now: Long, done: Long, total: Long): Long? {
        if (total <= 0 || done !in 0 until total) return null
        if (start < 0 || done < last || now < start) {
            start = now; first = done; last = done; advancedAt = now
            return null
        }
        if (done > last) { last = done; advancedAt = now }
        val sampled = advancedAt - start
        if (done <= first || sampled < 2_000 || now - advancedAt > STALE_MS) return null
        val seconds = ceil((total - done).toDouble() / (done - first) * sampled / 1000)
        return seconds.toLong().coerceAtLeast(1).takeIf { it <= 7 * 86_400 }
    }
}
