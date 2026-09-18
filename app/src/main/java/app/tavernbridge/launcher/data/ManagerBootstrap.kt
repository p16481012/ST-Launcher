package app.tavernbridge.launcher.data

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

// Keep production deployment and payload-size regression tests on the same asset list.
internal val managerAssetNames = listOf(
    "progress.sh", "archive-progress.sh", "safe-backup.sh", "install-validation.sh", "manager.sh",
)

internal fun encodeManagerScript(script: String): String {
    // Keep RUN_COMMAND's single shell argument below Linux's per-argument limit.
    val compressed = ByteArrayOutputStream().apply {
        GZIPOutputStream(this).use { it.write(script.toByteArray(Charsets.UTF_8)) }
    }.toByteArray()
    return Base64.getEncoder().encodeToString(compressed)
}

internal fun createManagerBootstrapCommand(managerPath: String, encodedScript: String): String {
    fun quote(value: String) = "'${value.replace("'", "'\\''")}'"
    val directory = managerPath.substringBeforeLast('/')
    // Explicit guards are needed: the caller chains this subshell with &&, which
    // disables Bash errexit even if the subshell enables set -e.
    return """
        (
          set -o pipefail
          umask 077
          mkdir -p ${quote(directory)} || exit ${'$'}?
          launcher_manager_tmp=${'$'}(mktemp ${quote("$managerPath.tmp.XXXXXXXXXX")}) || exit ${'$'}?
          trap 'rm -f -- "${'$'}launcher_manager_tmp"' EXIT
          printf '%s' ${quote(encodedScript)} | base64 -d | gzip -d > "${'$'}launcher_manager_tmp" || exit ${'$'}?
          bash -n "${'$'}launcher_manager_tmp" || exit ${'$'}?
          chmod 700 "${'$'}launcher_manager_tmp" || exit ${'$'}?
          mv -fT -- "${'$'}launcher_manager_tmp" ${quote(managerPath)}
        )
    """.trimIndent()
}
