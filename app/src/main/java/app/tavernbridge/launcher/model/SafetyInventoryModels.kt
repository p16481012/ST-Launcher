package app.tavernbridge.launcher.model

enum class SafetyItemKind(val key: String, val label: String) {
    ARCHIVE("archive", "내부 안전 백업"),
    CONFIG("config", "설정 사본"),
    TRASH("trash", "휴지통");

    companion object {
        fun fromKey(value: String): SafetyItemKind? = entries.firstOrNull { it.key == value }
    }
}

/** Not a native ZIP backup; the opaque ID and revision must be sent together. */
data class SafetyInventoryItem(
    val kind: SafetyItemKind,
    val id: String,
    val revision: String,
    val displayName: String,
    /** Null means a bounded scan could not calculate the whole folder size. */
    val sizeBytes: Long?,
    val modifiedAtMillis: Long,
    val trashId: String = "",
    val originalRelativePath: String = "",
    val restorable: Boolean = false,
    /** A matching completion record, not a full archive-integrity guarantee. */
    val completionRecorded: Boolean = false,
    val isDirectory: Boolean = false,
) {
    val canExport: Boolean get() = kind != SafetyItemKind.TRASH
}

data class SafetyInventoryPage(
    val items: List<SafetyInventoryItem>,
    val nextCursor: Int?,
    val truncated: Boolean,
    val mutationBlocked: Boolean,
    val protectedEntries: Int,
    val totals: Map<SafetyItemKind, Int>? = null,
)
