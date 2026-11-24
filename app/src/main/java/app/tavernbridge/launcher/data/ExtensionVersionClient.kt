package app.tavernbridge.launcher.data

import android.util.JsonReader
import android.util.JsonToken
import app.tavernbridge.launcher.model.ExtensionCheckIssue
import app.tavernbridge.launcher.model.ExtensionUpdateMetadata
import app.tavernbridge.launcher.model.ManagedExtension
import java.io.IOException
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only public HTTPS GitHub metadata. A branch name/manifest version is not proof of equality:
 * resolve refs/heads first and only report identical for byte-for-byte equal commit IDs. */
internal object ExtensionVersionClient {
    private val sha = Regex("(?:[a-f0-9]{40}|[a-f0-9]{64})")
    private fun base(item: ManagedExtension): String? {
        val parts = item.gitHubRepository.split('/')
        if (parts.size != 2 || parts.any { it in setOf(".", "..") || !it.matches(Regex("[A-Za-z0-9_.-]{1,100}")) } ||
            !sha.matches(item.commit) || item.gitBranch.length !in 1..200 ||
            item.gitBranch.split('/').any { it.isBlank() || it in setOf(".", "..") || it.any(Char::isISOControl) }) return null
        return "https://api.github.com/repos/${parts[0]}/${parts[1]}"
    }

    fun refUrl(item: ManagedExtension): URL? = base(item)?.let { base ->
        val path = item.gitBranch.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        URL("$base/git/ref/heads/$path")
    }

    fun compareUrl(item: ManagedExtension, remote: String): URL? =
        base(item)?.takeIf { sha.matches(remote) }?.let { URL("$it/compare/${item.commit}...$remote?per_page=1") }

    fun manifestUrl(item: ManagedExtension, remote: String): URL? =
        base(item)?.takeIf { sha.matches(remote) }?.let { URL("$it/contents/manifest.json?ref=$remote") }

    private class CheckFailed(val issue: ExtensionCheckIssue) : IOException()

    suspend fun inspect(item: ManagedExtension, open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }): ExtensionUpdateMetadata =
        withContext(Dispatchers.IO) {
            try {
                val ref = refUrl(item) ?: return@withContext ExtensionUpdateMetadata(issue = ExtensionCheckIssue.UNAVAILABLE)
                val remote = readMetadata(open(ref)) { parseRef(it, item.gitBranch) }
                if (remote == item.commit) return@withContext ExtensionUpdateMetadata(0)
                val ahead = readMetadata(open(checkNotNull(compareUrl(item, remote))), parse = ::parseComparison)
                val version = try {
                    readMetadata(open(checkNotNull(manifestUrl(item, remote))), "application/vnd.github.raw+json", ::parseManifestVersion)
                } catch (_: IOException) { null } // A missing display version does not undo the commit comparison.
                ExtensionUpdateMetadata(ahead, version)
            } catch (e: CheckFailed) { ExtensionUpdateMetadata(issue = e.issue)
            } catch (_: IOException) { ExtensionUpdateMetadata(issue = ExtensionCheckIssue.NETWORK) }
        }

    private fun <T : Any> readMetadata(connection: HttpURLConnection, accept: String = "application/vnd.github+json", parse: (Reader) -> T?): T {
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 6_000
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("Cache-Control", "no-cache")
            val status = connection.responseCode
            if (status != 200) throw CheckFailed(when {
                status == 429 || status == 403 && (connection.getHeaderField("X-RateLimit-Remaining") == "0" ||
                    connection.getHeaderField("Retry-After") != null) -> ExtensionCheckIssue.RATE_LIMIT
                status in 400..499 || status in 300..399 -> ExtensionCheckIssue.UNAVAILABLE
                else -> ExtensionCheckIssue.NETWORK
            })
            val bounded = object : java.io.FilterInputStream(connection.inputStream) {
                var remaining = 65536
                override fun read(target: ByteArray, off: Int, len: Int): Int {
                    if (remaining <= 0) return -1
                    return super.read(target, off, minOf(len, remaining)).also { if (it > 0) remaining -= it }
                }
                override fun read(): Int = if (remaining-- > 0) super.read() else -1
            }
            return bounded.bufferedReader(Charsets.UTF_8).use { reader ->
                try { parse(reader) ?: throw CheckFailed(ExtensionCheckIssue.UNVERIFIED) }
                catch (e: IllegalStateException) { throw CheckFailed(ExtensionCheckIssue.UNVERIFIED) }
                catch (e: NumberFormatException) { throw CheckFailed(ExtensionCheckIssue.UNVERIFIED) }
            }
        } finally { connection.disconnect() }
    }

    private fun parseRef(input: Reader, branch: String): String? = JsonReader(input).use { reader ->
        var ref = ""; var type = ""; var commit = ""
        reader.beginObject()
        while (reader.hasNext()) when (reader.nextName()) {
            "ref" -> ref = reader.nextString()
            "object" -> {
                reader.beginObject()
                while (reader.hasNext()) when (reader.nextName()) {
                    "type" -> type = reader.nextString(); "sha" -> commit = reader.nextString(); else -> reader.skipValue()
                }
                reader.endObject()
            }
            else -> reader.skipValue()
        }
        reader.endObject()
        commit.takeIf { ref == "refs/heads/$branch" && type == "commit" && sha.matches(it) }
    }

    private fun parseManifestVersion(input: Reader): String? = JsonReader(input).use { reader ->
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() == "version" && reader.peek() == JsonToken.STRING)
                return reader.nextString().takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9.+_-]{0,63}")) }
            reader.skipValue()
        }
        null
    }

    private fun parseComparison(input: Reader): Int? = JsonReader(input).use { reader ->
        var ahead: Int? = null; var behind: Int? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) { "ahead_by" -> ahead = reader.nextInt(); "behind_by" -> behind = reader.nextInt(); else -> reader.skipValue() }
            if (ahead != null && behind != null) {
                if (behind > 0) throw CheckFailed(ExtensionCheckIssue.DIVERGED)
                // Called only for distinct immutable SHAs. Zero is never evidence of equality here.
                return ahead.takeIf { it > 0 && behind == 0 }
            }
        }
        null
    }
}
