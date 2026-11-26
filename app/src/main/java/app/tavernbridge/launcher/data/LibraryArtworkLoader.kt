package app.tavernbridge.launcher.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.LruCache
import app.tavernbridge.launcher.termux.TermuxCommandResult
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray

/** One small, serialized read-only Termux batch at a time; no public files or server required. */
internal class LibraryArtworkLoader(private val run: suspend (String, Long) -> TermuxCommandResult) {
    private data class Key(val user: String, val path: String, val revision: String, val detail: Boolean)
    private class Ticket(val key: Key, val answer: CompletableDeferred<Bitmap?> = CompletableDeferred()) {
        var readers = 0
        var inFlight = false
    }
    private val guard = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = linkedMapOf<Key, Ticket>()
    private val cache = object : LruCache<Key, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Bitmap) = value.allocationByteCount
    }
    private val misses = LruCache<Key, Long>(128)
    private var running = false

    suspend fun load(user: String, item: LibraryItem, detail: Boolean): Bitmap? {
        if (!librarySha.matches(item.artRevision) || !item.path.safeLibraryRelative()) return null
        val key = Key(user, item.path, item.artRevision, detail)
        val ticket = synchronized(guard) {
            cache.get(key)?.let { return it }
            if ((misses.get(key) ?: 0L) > SystemClock.elapsedRealtime()) return null
            if (key !in queue && queue.size >= 64) return null
            val request = queue.getOrPut(key) { Ticket(key) }
            request.readers++
            if (!running) {
                running = true
                scope.launch { drain() }
            }
            request
        }
        return try { ticket.answer.await() } finally {
            synchronized(guard) {
                ticket.readers--
                if (ticket.readers == 0 && !ticket.inFlight && queue[key] === ticket) queue.remove(key)
            }
        }
    }

    private suspend fun drain() {
        delay(60) // Collect rows composed in the same frame; discard already scrolled-away requests.
        while (true) {
            val batch = synchronized(guard) {
                val first = queue.values.firstOrNull { it.key.detail } ?: queue.values.firstOrNull()
                if (first == null) { running = false; return }
                queue.values.filter { it.key.user == first.key.user && it.key.detail == first.key.detail }
                    .take(if (first.key.detail) 1 else 4).onEach { it.inFlight = true }
            }
            val images = try { fetch(batch) } catch (_: Exception) { emptyMap() }
            synchronized(guard) {
                batch.forEachIndexed { i, t ->
                    val bitmap = images[i]
                    if (bitmap != null) cache.put(t.key, bitmap)
                    else misses.put(t.key, SystemClock.elapsedRealtime() + 15_000L)
                    queue.remove(t.key)
                    t.answer.complete(bitmap)
                }
            }
        }
    }

    private suspend fun fetch(batch: List<Ticket>): Map<Int, Bitmap> {
        val key = batch.first().key
        val payload = JSONArray().apply { batch.forEach { put(JSONArray().put(it.key.path).put(it.key.revision)) } }
        val mode = if (key.detail) "detail" else "list"
        val result = run("library-artwork '${libraryEncode(key.user)}' '$mode' '${libraryEncode(payload.toString())}'", 45_000L)
        if (!result.isSuccess || result.stdoutTruncated || result.stdout.length > 40 * 1024) return emptyMap()
        val maxEdge = if (key.detail) 576 else 192
        val maxBytes = if (key.detail) 27648 else 6144
        return buildMap {
            for (line in result.stdout.lineSequence()) {
                val fields = line.split('\t', limit = 3)
                if (fields.size != 3 || fields[0] != "art") continue
                val i = fields[1].toIntOrNull()?.takeIf { it in batch.indices } ?: continue
                if (fields[2].length > (maxBytes + 2) / 3 * 4) continue
                val bytes = try { Base64.getDecoder().decode(fields[2]) } catch (_: IllegalArgumentException) { continue }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outMimeType !in setOf("image/png", "image/jpeg") ||
                    bounds.outWidth !in 1..maxEdge || bounds.outHeight !in 1..maxEdge) continue
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { put(i, it) }
            }
        }
    }
}
