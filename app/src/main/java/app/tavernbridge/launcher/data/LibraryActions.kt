package app.tavernbridge.launcher.data

import java.text.Normalizer

internal enum class AssetUse(val label: String) { USED("사용 중"), NONE("확인된 사용처 없음"), UNKNOWN("확인 못 함") }
internal data class AssetUsage(val state: AssetUse, val detail: String) { val label get() = state.label }
internal enum class AssetChange(val wire: String, val label: String) {
    RENAME("rename", "이름 변경"), COPY("copy", "새 이름으로 복사"), TRASH("trash", "휴지통으로 옮기기")
}

internal fun assetStem(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

/** The editable field is only a stem. Never silently sanitize a user's intended destination. */
internal fun assetNameError(row: LibraryItem, newStem: String): String? {
    val stem = assetStem(newStem)
    val name = row.path.substringAfterLast('/')
    val ext = name.substringAfterLast('.', "")
    return when {
        stem.isBlank() -> "새 이름을 입력해 주세요."
        stem != stem.trim() || stem.startsWith('.') || stem.endsWith('.') -> "이름 앞뒤의 공백이나 점을 빼 주세요."
        stem.any { it.code < 32 || it.code == 127 || it in "/\\:*?\"<>|" } -> "파일 이름에 사용할 수 없는 문자가 있어요."
        ext.isEmpty() -> "파일 확장자를 확인할 수 없어요."
        "$stem.$ext".toByteArray(Charsets.UTF_8).size > 240 -> "파일 이름이 너무 길어요."
        assetStem(name) == "$stem.$ext" -> "지금 이름과 다른 이름을 입력해 주세요."
        else -> null
    }
}

internal fun LibraryItem.isManagedAsset(): Boolean {
    val parts = path.split('/')
    if (parts.size != 2 || !path.safeLibraryRelative() || parts[1].startsWith('.') ||
        parts[1].any { it.code < 32 || it.code == 127 || it == '\\' } || !librarySha.matches(revision)) return false
    val ext = parts[1].substringAfterLast('.', "").lowercase()
    return when (parts[0]) {
        "backgrounds" -> ext in setOf("png","jpg","jpeg","gif","webp","bmp","avif")
        "worlds", "OpenAI Settings", "QuickReplies", "themes" -> ext == "json"
        else -> false
    }
}
