package app.tavernbridge.launcher.model

enum class SelectiveRestoreKind(val key: String, val label: String) {
    CHARACTER("character", "캐릭터"), CHAT("chat", "채팅")
}

enum class SelectiveRestoreConflict(val key: String) { SKIP("skip"), REPLACE("replace") }

data class SelectiveRestoreItem(
    val kind: SelectiveRestoreKind,
    val relativePath: String,
    val sizeBytes: Long,
    val targetExists: Boolean,
    val targetRevision: String,
    val missingCharacter: Boolean,
    val profileExists: Boolean,
    val profile: String,
    val displayName: String,
)

data class SelectiveRestorePage(
    val archiveRevision: String,
    val installationRevision: String,
    val items: List<SelectiveRestoreItem>,
    val nextCursor: Int?,
    val candidateCount: Int,
)

data class SelectiveRestoreChoice(
    val item: SelectiveRestoreItem,
    val conflict: SelectiveRestoreConflict = SelectiveRestoreConflict.SKIP,
)

data class SelectiveRestoreUiState(
    val fileName: String,
    val page: SelectiveRestorePage? = null,
    val loading: Boolean = false,
    val error: String = "",
)
