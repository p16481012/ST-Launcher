package app.tavernbridge.launcher.model

data class LauncherUpdateInfo(val installedVersion: String, val publishedVersion: String,
    val newerVersionAvailable: Boolean?, val checkedAtMillis: Long)
data class NodePackageInfo(val installed: String, val candidate: String)

fun newerReleaseVersion(installed: String, published: String): Boolean? {
    fun parts(value: String): List<Long>? {
        val match = Regex("^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$").matchEntire(value) ?: return null
        return match.groupValues.drop(1).map { it.toLongOrNull() ?: return null }
    }
    val current = parts(installed) ?: return null
    val remote = parts(published) ?: return null
    for (index in 0..2) if (current[index] != remote[index]) return remote[index] > current[index]
    return false
}
