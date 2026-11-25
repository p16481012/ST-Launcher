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
    val totals: Map<SafetyItemKind, Int>? = null,
    val hasLoaded: Boolean = false,
    val configFirst: Boolean = false,
) {
    fun totalFor(kind: SafetyItemKind): Int? = when {
        truncated || error.isNotBlank() -> null
        totals != null -> totals[kind]
        hasLoaded && !loading && nextCursor == null -> items.count { it.kind == kind }
        else -> null
    }

    fun accept(page: SafetyInventoryPage, append: Boolean): SafetyInventoryUiState = copy(
        loading = false,
        items = ((if (append) items else emptyList()) + page.items).distinctBy { it.id },
        nextCursor = page.nextCursor,
        truncated = page.truncated,
        mutationBlocked = page.mutationBlocked,
        protectedEntries = page.protectedEntries,
        totals = page.totals ?: if (append) totals else null,
        hasLoaded = true,
        error = "",
    )
}
