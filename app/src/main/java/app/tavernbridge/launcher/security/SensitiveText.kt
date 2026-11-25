package app.tavernbridge.launcher.security

// Quoted values may contain whitespace and escaped quotes. Accept an unfinished
// quote too, since captured log tails may end in the middle of a secret value.
private const val SECRET_VALUE = """(?:\[숨김\]|"(?:\\.|[^"\\\r\n])*"?|'(?:\\.|[^'\\\r\n])*'?|[^\s,;&}\]]+)"""

private val sensitiveHeaderPattern = Regex(
    """(?im)(?<![\w-])(["']?(?:(?:proxy-)?authorization|(?:set-)?cookie)["']?[ \t]*[:=][ \t]*)(?:\[숨김\]|"(?:\\.|[^"\\\r\n])*"?|'(?:\\.|[^'\\\r\n])*'?|[^\r\n]+)""",
)
private val namedSecretPattern = Regex(
    """(?i)(?<![\w-])(["']?(?:[a-z0-9][a-z0-9_-]*[_-])?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|secret|password|passphrase)(?:[_-][a-z0-9][a-z0-9_-]*)?["']?[ \t]*[:=][ \t]*)$SECRET_VALUE""",
)
private val bearerSecretPattern = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{8,}""")
private val basicCredentialPattern = Regex("""(?i)\bBasic[ \t]+([A-Za-z0-9+/]{4,}={0,2})""")
private val commonSecretPattern = Regex(
    """(?i)\b(?:sk|rk|pk)-[A-Za-z0-9._-]{8,}\b|\b(?:hf_|ghp_|github_pat_)[A-Za-z0-9._-]{8,}\b|\bAIza[A-Za-z0-9_-]{20,}\b|\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""",
)
private val urlCredentialPattern = Regex("""(?i)(\bhttps?://)[^/\s:@]+:[^/\s@]+@""")

internal fun redactSensitiveText(text: String, maxLength: Int? = 600): String {
    val redacted = text
        .replace(sensitiveHeaderPattern, "$1[숨김]")
        .replace(namedSecretPattern, "$1[숨김]")
        .replace(bearerSecretPattern, "Bearer [숨김]")
        .replace(basicCredentialPattern) { match ->
            // Do not mask ordinary text such as "Basic configuration".
            val bytes = runCatching { java.util.Base64.getDecoder().decode(match.groupValues[1]) }.getOrNull()
            val credential = bytes != null && ':'.code.toByte() in bytes
            bytes?.fill(0)
            if (credential) "Basic [숨김]" else match.value
        }
        .replace(commonSecretPattern, "[숨김]")
        .replace(urlCredentialPattern, "$1[숨김]@")
    return if (maxLength == null) redacted else redacted.take(maxLength)
}
