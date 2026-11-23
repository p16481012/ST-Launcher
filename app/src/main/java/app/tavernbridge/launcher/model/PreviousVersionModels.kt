package app.tavernbridge.launcher.model

data class PreviousVersion(val commit: String, val committedAtSeconds: Long, val subject: String)

data class PreviousVersionPage(
    val currentCommit: String,
    val installationRevision: String,
    val branch: String,
    /** At most 30 locally available ancestors; this does not fetch remote tags. */
    val versions: List<PreviousVersion>,
)

data class VersionSnapshot(
    val filename: String,
    val revision: String,
    val commit: String,
    val createdAtMillis: Long,
    val sizeBytes: Long,
    val version: String,
)

data class VersionSnapshotPage(val snapshots: List<VersionSnapshot>, val truncated: Boolean)

enum class VersionDataMode(val key: String) {
    KEEP_CURRENT("keep-current"), SNAPSHOT("snapshot")
}

data class PreviousVersionUiState(
    val loading: Boolean = false,
    val versions: PreviousVersionPage? = null,
    val snapshots: VersionSnapshotPage? = null,
    val error: String = "",
)
