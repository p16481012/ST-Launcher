package app.tavernbridge.launcher

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Main-thread only. Coalesce reads behind an environment check; never enqueue a command that changes data. */
internal class VisibleReadQueue(private val scope: CoroutineScope) {
    enum class ListKind { LOG, FILES, EXTENSIONS, BACKUPS }

    private val waiting = mutableMapOf<ListKind, Job>()

    fun defer(kind: ListKind, refresh: Job?, stillWanted: () -> Boolean, read: () -> Unit): Boolean {
        waiting.remove(kind)?.cancel()
        if (refresh == null || !refresh.isActive) return false
        val job = scope.launch(start = CoroutineStart.LAZY) {
            refresh.join()
            waiting.remove(kind)
            if (!refresh.isCancelled && stillWanted()) read()
        }
        waiting[kind] = job
        job.start()
        return true
    }
}
