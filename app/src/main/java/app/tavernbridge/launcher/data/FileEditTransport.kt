package app.tavernbridge.launcher.data

import java.util.Base64

internal const val TEXT_EDIT_LIMIT = 65_536

internal fun encodeFileArgument(value: String): String =
    Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

/** Metadata only. Termux may log the command, so it must never contain the file body. */
internal fun privateFileWriteCommand(path: String, revision: String, source: OneShotSecretInput, size: Int): String {
    require(revision.matches(Regex("[0-9a-f]{64}")) && size in 0..TEXT_EDIT_LIMIT)
    return "write-st-file '${encodeFileArgument(path)}' $revision ${source.port} ${source.token} $size"
}
