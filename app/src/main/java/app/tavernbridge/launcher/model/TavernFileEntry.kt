package app.tavernbridge.launcher.model

data class TavernFileEntry(
    val relativePath: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedAt: String,
    val sensitive: Boolean,
)
