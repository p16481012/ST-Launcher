package app.tavernbridge.launcher.model

data class DirectoryAppOption(
    val packageName: String,
    val activityName: String,
    val label: String,
    val hasDocumentAccess: Boolean,
    val isRecommended: Boolean = false,
) {
    val id: String get() = "$packageName/$activityName"
}

fun rankDirectoryApps(candidates: List<DirectoryAppOption>): List<DirectoryAppOption> {
    val ordered = candidates
        .filter { it.packageName.isNotBlank() && it.activityName.isNotBlank() }
        .distinctBy { it.id }
        .sortedByDescending { it.hasDocumentAccess }
    return ordered.mapIndexed { index, option ->
        option.copy(isRecommended = index == 0 && option.hasDocumentAccess)
    }
}
