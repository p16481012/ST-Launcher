package app.tavernbridge.launcher.data

import app.tavernbridge.launcher.model.ManagedExtension
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Public commit graph only. No credentials, local path, redirect, Git hooks or manifest code. */
internal object ExtensionVersionClient {
    fun compareUrl(item: ManagedExtension): URL? {
        val parts = item.gitHubRepository.split('/')
        if (!item.canUpdate || parts.size != 2 || parts.any {
                it in setOf(".", "..") || !it.matches(Regex("[A-Za-z0-9_.-]{1,100}"))
            } || !item.commit.matches(Regex("[a-f0-9]{40,64}")) ||
            item.gitBranch.length !in 1..200 || item.gitBranch.any { it.isISOControl() }) return null
        val branch = URLEncoder.encode(item.gitBranch, "UTF-8").replace("+", "%20")
        return URL("https://api.github.com/repos/${parts[0]}/${parts[1]}/compare/${item.commit}...$branch?per_page=1")
    }

    suspend fun ahead(item: ManagedExtension): Int? = withContext(Dispatchers.IO) {
        val url = compareUrl(item) ?: return@withContext null
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 6_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            if (connection.responseCode != 200) return@withContext null
            // Compare responses can contain a very large "files" array. Read just the
            // leading status metadata, never allocate or download that array in full.
            val bounded = object : java.io.FilterInputStream(connection.inputStream) {
                var remaining = 65536
                override fun read(target: ByteArray, off: Int, len: Int): Int {
                    if (remaining <= 0) return -1
                    return super.read(target, off, minOf(len, remaining)).also { if (it > 0) remaining -= it }
                }
                override fun read(): Int = if (remaining-- > 0) super.read() else -1
            }
            bounded.bufferedReader(Charsets.UTF_8).use(::parseComparison)
        } catch (_: java.io.IOException) { null }
        finally { connection.disconnect() }
    }

    private fun parseComparison(input: java.io.Reader): Int? {
        val reader = android.util.JsonReader(input)
        return try {
            var ahead: Int? = null; var behind: Int? = null
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) { "ahead_by" -> ahead = reader.nextInt(); "behind_by" -> behind = reader.nextInt(); else -> reader.skipValue() }
                if (ahead != null && behind != null) return ahead.takeIf { it >= 0 && behind == 0 }
            }
            null
        } catch (_: Exception) { null } finally { reader.close() }
    }
}
