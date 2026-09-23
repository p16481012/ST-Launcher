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

private fun bootstrapQuote(value: String) = "'${value.replace("'", "'\\''")}'"

internal fun createManagerBundleBootstrapCommand(directory: String, scripts: Map<String, String>): String {
    require(scripts.isNotEmpty() && scripts.keys.all { it.matches(Regex("[A-Za-z0-9_-]+\\.sh")) })
    // Compress the scripts together, so shared helper code is only transported
    // once. Keep the existing per-file syntax check and atomic replacement.
    val installer = scripts.entries.joinToString(" &&\n") { (name, script) ->
        val target = "$directory/$name"
        """
            (
              umask 077
              launcher_asset_tmp=${'$'}(mktemp ${bootstrapQuote("$target.tmp.XXXXXXXXXX")}) || exit ${'$'}?
              trap 'rm -f -- "${'$'}launcher_asset_tmp"' EXIT
              printf '%s' ${bootstrapQuote(script)} > "${'$'}launcher_asset_tmp" || exit ${'$'}?
              bash -n "${'$'}launcher_asset_tmp" || exit ${'$'}?
              chmod 700 "${'$'}launcher_asset_tmp" || exit ${'$'}?
              mv -fT -- "${'$'}launcher_asset_tmp" ${bootstrapQuote(target)}
            )
        """.trimIndent()
    }
    return createEncodedManagerBundleCommand(directory, encodeManagerScript(installer))
}

internal fun createEncodedManagerBundleCommand(directory: String, encodedInstaller: String): String = """
    (
      set -o pipefail
      umask 077
      mkdir -p ${bootstrapQuote(directory)} || exit ${'$'}?
      launcher_bundle_tmp=${'$'}(mktemp ${bootstrapQuote("$directory/.bundle.XXXXXXXXXX")}) || exit ${'$'}?
      trap 'rm -f -- "${'$'}launcher_bundle_tmp"' EXIT
      printf '%s' ${bootstrapQuote(encodedInstaller)} | base64 -d | gzip -d > "${'$'}launcher_bundle_tmp" || exit ${'$'}?
      bash -n "${'$'}launcher_bundle_tmp" || exit ${'$'}?
      bash "${'$'}launcher_bundle_tmp"
    )
""".trimIndent()

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
