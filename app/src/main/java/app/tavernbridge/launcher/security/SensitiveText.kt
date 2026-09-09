package app.tavernbridge.launcher.security

// Quoted values may contain whitespace and escaped quotes. Accept an unfinished
// quote too, since captured log tails may end in the middle of a secret value.
private const val SECRET_VALUE = """(?:\[숨김\]|"(?:\\.|[^"\\\r\n])*"?|'(?:\\.|[^'\\\r\n])*'?|[^\s,;&}\]]+)"""

private val authorizationPattern = Regex(
    """(?im)(?<![\w-])(["']?(?:proxy-)?authorization["']?[ \t]*[:=][ \t]*)(?:\[숨김\]|"(?:\\.|[^"\\\r\n])*"?|'(?:\\.|[^'\\\r\n])*'?|[^\r\n]+)""",
)
private val namedSecretPattern = Regex(
    """(?i)(?<![\w-])(["']?(?:[a-z0-9]+[_-])?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|secret|password)(?:[_-][a-z0-9]+)*["']?[ \t]*[:=][ \t]*)$SECRET_VALUE""",
)
private val bearerSecretPattern = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{8,}""")
private val commonSecretPattern = Regex(
    """(?i)\b(?:sk|rk|pk)-[A-Za-z0-9._-]{8,}\b|\bhf_[A-Za-z0-9._-]{8,}\b|\bAIza[A-Za-z0-9_-]{20,}\b""",
)
private val urlCredentialPattern = Regex("""(?i)(\bhttps?://)[^/\s:@]+:[^/\s@]+@""")

internal fun redactSensitiveText(text: String, maxLength: Int? = 600): String {
    val redacted = text
        .replace(authorizationPattern, "$1[숨김]")
        .replace(namedSecretPattern, "$1[숨김]")
        .replace(bearerSecretPattern, "Bearer [숨김]")
        .replace(commonSecretPattern, "[숨김]")
        .replace(urlCredentialPattern, "$1[숨김]@")
    return if (maxLength == null) redacted else redacted.take(maxLength)
}
