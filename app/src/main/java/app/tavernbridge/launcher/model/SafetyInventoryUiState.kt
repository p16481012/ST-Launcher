package app.tavernbridge.launcher.model

data class SafetyInventoryUiState(
    val open: Boolean = false,
    val loading: Boolean = false,
    val items: List<SafetyInventoryItem> = emptyList(),
    val nextCursor: Int? = null,
    val truncated: Boolean = false,
    val mutationBlocked: Boolean = false,
    val protectedEntries: Int = 0,
    val error: String = "",
) {
    fun accept(page: SafetyInventoryPage, append: Boolean): SafetyInventoryUiState = copy(
        loading = false,
        items = ((if (append) items else emptyList()) + page.items).distinctBy { it.id },
        nextCursor = page.nextCursor,
        truncated = page.truncated,
        mutationBlocked = page.mutationBlocked,
        protectedEntries = page.protectedEntries,
        error = "",
    )
}
