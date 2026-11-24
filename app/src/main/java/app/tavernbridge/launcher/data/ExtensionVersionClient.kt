package app.tavernbridge.launcher.data

import app.tavernbridge.launcher.model.ManagedExtension
import app.tavernbridge.launcher.model.ExtensionUpdateMetadata
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read two bounded metadata documents from fixed public GitHub endpoints. No redirects,
 * credentials, manifest execution, or URLs supplied by an extension. */
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

    internal fun manifestUrl(item: ManagedExtension): URL? {
        if (compareUrl(item) == null) return null
        val branch = URLEncoder.encode(item.gitBranch, "UTF-8").replace("+", "%20")
        return URL("https://api.github.com/repos/${item.gitHubRepository}/contents/manifest.json?ref=$branch")
    }

    suspend fun inspect(item: ManagedExtension): ExtensionUpdateMetadata? = withContext(Dispatchers.IO) {
        val url = compareUrl(item) ?: return@withContext null
        val ahead = readMetadata(url, "application/vnd.github+json", ::parseComparison) ?: return@withContext null
        val version = if (ahead > 0) manifestUrl(item)?.let {
            readMetadata(it, "application/vnd.github.raw+json", ::parseManifestVersion)
        } else null
        ExtensionUpdateMetadata(ahead, version)
    }

    private fun <T> readMetadata(url: URL, accept: String, parse: (java.io.Reader) -> T?): T? {
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 6_000
            connection.setRequestProperty("Accept", accept)
            if (connection.responseCode != 200) return null
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
            return bounded.bufferedReader(Charsets.UTF_8).use(parse)
        } catch (_: java.io.IOException) { return null }
        finally { connection.disconnect() }
    }

    private fun parseManifestVersion(input: java.io.Reader): String? {
        val reader = android.util.JsonReader(input)
        return try {
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() == "version" && reader.peek() == android.util.JsonToken.STRING) {
                    return reader.nextString().takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9.+_-]{0,63}")) }
                }
                reader.skipValue()
            }
            null
        } catch (_: Exception) { null } finally { reader.close() }
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
