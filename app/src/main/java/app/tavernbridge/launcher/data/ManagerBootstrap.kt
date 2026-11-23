package app.tavernbridge.launcher.data

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream
import java.security.MessageDigest

// Keep production deployment and payload-size regression tests on the same asset list.
internal val managerAssetNames = listOf(
    "progress.sh", "archive-progress.sh", "safe-backup.sh", "install-validation.sh", "transaction-recovery.sh", "manager.sh",
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
    return createEncodedManagerBundleCommand(directory, encodeManagerScript(managerBundleInstaller(directory, scripts)))
}

private fun managerBundleInstaller(directory: String, scripts: Map<String, String>): String {
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
    return installer
}

internal const val MAX_TERMUX_COMMAND_BYTES = 120_000

internal fun checkedTermuxCommand(command: String): String {
    require(command.toByteArray(Charsets.UTF_8).size < MAX_TERMUX_COMMAND_BYTES) {
        "[TERMUX_COMMAND_TOO_LARGE]\n명령이 너무 큽니다. 선택 항목을 나누어 다시 시도해 주세요."
    }
    return command
}

internal data class ManagerDeployment(val probe: String, val commands: List<String>, val cleanup: String)

/** Bounded transport, with a checksum probe so unchanged assets are not rewritten on app restart. */
internal fun createManagerDeployment(directory: String, scripts: Map<String, String>, token: String): ManagerDeployment {
    require(token.matches(Regex("[0-9a-fA-F-]{36}")))
    val installer = managerBundleInstaller(directory, scripts)
    fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    val fingerprint = sha(installer)
    val receipt = "$directory/.assets-sha256"
    val checksums = scripts.entries.joinToString("\n") { (name, text) -> "${sha(text)}  $name" } + "\n"
    val assetsSafe = scripts.keys.joinToString(" && ") {
        "[ -f ${bootstrapQuote("$directory/$it")} ] && [ ! -L ${bootstrapQuote("$directory/$it")} ] && [ -x ${bootstrapQuote("$directory/$it")} ]"
    }
    val probe = "if [ ! -L ${bootstrapQuote(receipt)} ] && [ \"\$(cat ${bootstrapQuote(receipt)} 2>/dev/null)\" = '$fingerprint' ] && $assetsSafe && " +
        "(cd ${bootstrapQuote(directory)} && printf '%s' ${bootstrapQuote(checksums)} | sha256sum -c --status); then printf 'assets_ready=1\\n'; else printf 'assets_ready=0\\n'; fi"
    val staging = "$directory/.deploy-$token"
    val payload = "$staging/bundle.b64"
    val commands = mutableListOf("umask 077; mkdir -p ${bootstrapQuote(directory)} && mkdir -m 700 -- ${bootstrapQuote(staging)}")
    encodeManagerScript(installer).chunked(24_000).forEach { chunk ->
        commands += "[ -d ${bootstrapQuote(staging)} ] && [ ! -L ${bootstrapQuote(staging)} ] && printf '%s' '$chunk' >> ${bootstrapQuote(payload)}"
    }
    commands += """
        (
          set -e -o pipefail
          umask 077
          [ -d ${bootstrapQuote(staging)} ] && [ ! -L ${bootstrapQuote(staging)} ]
          base64 -d ${bootstrapQuote(payload)} | gzip -d > ${bootstrapQuote("$staging/install.sh")}
          bash -n ${bootstrapQuote("$staging/install.sh")}
          bash ${bootstrapQuote("$staging/install.sh")}
          (cd ${bootstrapQuote(directory)} && printf '%s' ${bootstrapQuote(checksums)} | sha256sum -c --status)
          printf '%s' '$fingerprint' > ${bootstrapQuote("$staging/receipt")}
          mv -fT -- ${bootstrapQuote("$staging/receipt")} ${bootstrapQuote(receipt)}
          rm -rf -- ${bootstrapQuote(staging)}
        )
    """.trimIndent()
    commands.forEach(::checkedTermuxCommand)
    return ManagerDeployment(probe, commands, "[ ! -L ${bootstrapQuote(staging)} ] && rm -rf -- ${bootstrapQuote(staging)}")
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
