package app.tavernbridge.launcher.model

data class UpdatePreflight(
    val branch: String,
    val currentCommit: String,
    val targetCommit: String,
    val currentVersion: String,
    val targetVersion: String,
    val updateAvailable: Boolean,
    val commitsBehind: Int,
    val nodeVersion: String,
    val nodeRequired: String,
    val nodeCompatible: Boolean,
    val freeBytes: Long,
    val requiredBytes: Long,
    val spaceReady: Boolean,
    val modifiedFiles: List<String>,
    val backupStorageReady: Boolean,
    val changes: List<UpdateCommit> = emptyList(),
    val changesAvailable: Boolean = false,
    val changesTruncated: Boolean = false,
)

data class UpdateCommit(val commit: String, val subject: String)

data class UpdateRecord(
    val createdAt: String,
    val branch: String,
    val oldCommit: String,
    val targetCommit: String,
    val newCommit: String,
    val oldVersion: String,
    val newVersion: String,
    val result: String,
)
