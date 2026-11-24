package app.tavernbridge.launcher.model

/** Only receipts returned by Termux; no user-provided path is accepted for undo. */
internal object TrashUndoGroup {
    private val receipt = Regex("[0-9]{1,24}-[a-f0-9-]{36}")
    fun parse(raw: String): List<String> = raw.take(16_000).split('\n').filter(receipt::matches).distinct().take(MAX_FILE_SELECTION)
    fun success(previous: List<String>, id: String, append: Boolean): List<String> =
        if (!receipt.matches(id)) previous else ((if (append) previous else emptyList()) + id).distinct().takeLast(MAX_FILE_SELECTION)
}
