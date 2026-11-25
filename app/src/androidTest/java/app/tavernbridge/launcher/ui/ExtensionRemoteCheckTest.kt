package app.tavernbridge.launcher.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tavernbridge.launcher.data.ExtensionVersionClient
import app.tavernbridge.launcher.data.GitHubReadQuota
import app.tavernbridge.launcher.model.*
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the production bounded Android JSON reader and HTTP classification, entirely offline. */
@RunWith(AndroidJUnit4::class)
class ExtensionRemoteCheckTest {
    private val local = "a".repeat(40)
    private val remote = "b".repeat(40)
    private val item = ManagedExtension("id", "rev", "Example", "1.0", "user", false, "clean", local, true, "",
        "owner/example", "release/mobile")
    private fun ref(commit: String, branch: String = "release/mobile") =
        """{"ref":"refs/heads/$branch","object":{"type":"commit","sha":"$commit"}}"""
    private class Reply(url: URL, val code: Int, val json: String, val limit: Boolean = false,
        val headers: Map<String, String> = emptyMap()) : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getHeaderField(name: String?) = if (limit && name == "X-RateLimit-Remaining") "0" else headers[name]
        override fun getInputStream() = json.byteInputStream()
    }

    @Test fun identicalOnlyForExactShaAndMatchingBranch() = runBlocking {
        var calls = 0
        val result = ExtensionVersionClient.inspect(item) { url ->
            calls++; assertEquals("/repos/owner/example/git/ref/heads/release/mobile", url.path)
            Reply(url, 200, ref(local))
        }
        assertEquals(1, calls)
        assertEquals(0, result.ahead)
        assertNull(result.issue)
        assertNull(ExtensionVersionClient.inspect(item) { Reply(it, 200, ref(local, "main")) }.ahead)
    }

    @Test fun listingDoesNotFetchManifestAndDetailVersionIsOptional() = runBlocking {
        for (manifestCode in listOf(200, 404)) {
            val seen = mutableListOf<String>()
            val quota = GitHubReadQuota()
            val result = ExtensionVersionClient.inspect(item.copy(canUpdate = false), quota) { url ->
                seen += url.path
                when {
                    "/git/ref/" in url.path -> Reply(url, 200, ref(remote))
                    "/compare/" in url.path -> Reply(url, 200, """{"behind_by":0,"ahead_by":2}""")
                    else -> error("List unexpectedly fetched manifest")
                }
            }
            assertEquals(2, seen.size)
            assertTrue(seen.any { it.endsWith("/$local...$remote") })
            assertEquals(2, result.ahead)
            assertEquals(remote, result.remoteCommit)
            val version = ExtensionVersionClient.detailVersion(item.copy(checkedRemoteCommit = result.remoteCommit), quota) { url ->
                assertTrue(url.path.endsWith("/contents/manifest.json")); assertEquals("ref=$remote", url.query)
                Reply(url, manifestCode, """{"version":"1.0"}""")
            }
            assertEquals(if (manifestCode == 200) "1.0" else null, version)
            assertNull(result.issue)
        }
    }

    @Test fun lowBudgetStopsBetweenRefAndComparisonAndBlocksNewTapsUntilReset() = runBlocking {
        var time = 100L
        var requests = 0
        val quota = GitHubReadQuota { time }
        val first = ExtensionVersionClient.inspect(item, quota) {
            requests++; Reply(it, 200, ref(remote), headers = mapOf("X-RateLimit-Remaining" to "1", "X-RateLimit-Reset" to "120"))
        }
        assertNull(first.ahead); assertEquals(ExtensionCheckIssue.RATE_LIMIT, first.issue)
        assertEquals(1, requests)
        assertEquals(ExtensionCheckIssue.RATE_LIMIT, ExtensionVersionClient.inspect(item, quota) { error("New tap bypassed quota") }.issue)
        time = 120
        assertEquals(0, ExtensionVersionClient.inspect(item, quota) { Reply(it, 200, ref(local)) }.ahead)
    }

    @Test fun nonFastForwardRateLimitedOfflineAndInaccessibleAreNeverIdentical() = runBlocking {
        for ((counts, expected) in listOf("""{"ahead_by":0,"behind_by":1}""" to ExtensionCheckIssue.DIVERGED,
            """{"ahead_by":1,"behind_by":1}""" to ExtensionCheckIssue.DIVERGED,
            """{"ahead_by":0,"behind_by":0}""" to ExtensionCheckIssue.UNVERIFIED)) {
            val checked = ExtensionVersionClient.inspect(item) { url ->
                Reply(url, 200, if ("/git/ref/" in url.path) ref(remote) else counts)
            }
            assertNull(checked.ahead); assertEquals(expected, checked.issue)
        }
        for ((code, expected) in listOf(404 to ExtensionCheckIssue.UNAVAILABLE, 403 to ExtensionCheckIssue.RATE_LIMIT,
            429 to ExtensionCheckIssue.RATE_LIMIT, 503 to ExtensionCheckIssue.NETWORK)) {
            val checked = ExtensionVersionClient.inspect(item) { Reply(it, code, "", limit = code == 403) }
            assertNull(checked.ahead); assertEquals(expected, checked.issue)
        }
        assertEquals(ExtensionCheckIssue.NETWORK, ExtensionVersionClient.inspect(item) { throw java.io.IOException("offline") }.issue)
    }
}
