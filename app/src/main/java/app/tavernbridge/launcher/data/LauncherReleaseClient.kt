package app.tavernbridge.launcher.data

import app.tavernbridge.launcher.BuildConfig
import app.tavernbridge.launcher.model.LauncherUpdateInfo
import app.tavernbridge.launcher.model.newerReleaseVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Manual read-only check. No APK download/install, tokens or startup requests. */
internal class LauncherReleaseClient {
    suspend fun check(): LauncherUpdateInfo = withContext(Dispatchers.IO) {
        val connection = URL(API_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "ST-Launcher/${BuildConfig.VERSION_NAME}")
            when (val status = connection.responseCode) {
                200 -> Unit
                404 -> error("공개된 정식 릴리스를 찾지 못했습니다.")
                403, 429 -> error("GitHub 조회 한도에 도달했습니다. 잠시 후 다시 확인해 주세요.")
                else -> error("공개 릴리스 정보를 읽지 못했습니다. HTTP $status")
            }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 512 * 1024) { "릴리스 응답이 너무 큽니다." }
                    output.write(buffer, 0, count)
                }
            }
            val json = JSONObject(output.toString("UTF-8"))
            check(!json.getBoolean("draft") && !json.getBoolean("prerelease")) { "정식 공개 릴리스가 아닙니다." }
            val version = json.getString("tag_name")
            check(version.length <= 128 && version.none(Char::isISOControl)) { "릴리스 버전 정보를 읽지 못했습니다." }
            LauncherUpdateInfo(BuildConfig.VERSION_NAME, version,
                newerReleaseVersion(BuildConfig.VERSION_NAME, version), System.currentTimeMillis())
        } finally { connection.disconnect() }
    }
    companion object {
        const val RELEASE_URL = "https://github.com/p16481012/ST-Launcher/releases/latest"
        private const val API_URL = "https://api.github.com/repos/p16481012/ST-Launcher/releases/latest"
    }
}
