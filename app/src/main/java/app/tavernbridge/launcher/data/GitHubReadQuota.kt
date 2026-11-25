package app.tavernbridge.launcher.data

/** One budget shared by list and optional detail reads. Reserve the last request on a shared IP.
 * Headerless/offline responses cannot cause an unlimited burst. Time is in Unix seconds. */
internal class GitHubReadQuota(private val now: () -> Long = { System.currentTimeMillis() / 1000 }) {
    private var remaining = 60
    private var reset = now() + 3600
    private var blockedUntil = 0L

    @Synchronized fun acquire(): Boolean {
        val time = now()
        if (time < blockedUntil) return false
        if (time >= reset) { remaining = 60; reset = time + 3600 }
        if (remaining <= 1) return false
        remaining--
        return true
    }

    @Synchronized fun observe(code: Int, left: String?, resetAt: String?, retryAfter: String?) {
        val time = now()
        val count = left?.trim()?.toIntOrNull()?.takeIf { it >= 0 }
        if (count != null) remaining = minOf(remaining, count)
        val resetTime = resetAt?.trim()?.toLongOrNull()?.takeIf { it > time }
        if (resetTime != null) reset = resetTime
        if (code == 429 || code == 403 && (count == 0 || retryAfter != null)) {
            val delay = retryAfter?.trim()?.toLongOrNull()?.takeIf { it in 1..(Long.MAX_VALUE - time) } ?: 60L
            blockedUntil = maxOf(blockedUntil, time + delay, if (count == 0) reset else 0L)
        }
    }
}
