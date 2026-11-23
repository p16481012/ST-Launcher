package app.tavernbridge.launcher.model

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

data class BackupAnnotation(val pinned: Boolean = false, val memo: String = "", val trusted: Boolean = true)
data class BackupRetentionOptions(val keepLatest: Int = 3, val olderThanDays: Int = 30)
data class BackupRetentionPreview(
    val candidates: List<BackupArchive>,
    val protectedCount: Int,
    val unknownDateCount: Int,
    val reclaimableBytes: Long,
)

/** Preview only. Nothing here schedules, starts, or authorizes a deletion. */
object BackupRetentionPolicy {
    private val fileName = Regex("SillyTavern-Launcher-[0-9]{8}-[0-9]{6}(-[0-9]+)?( \\([0-9]+\\))*\\.zip")
    private val dateFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)
    private const val DAY_MILLIS = 86_400_000L

    fun validFileName(value: String): Boolean = value.length <= 255 && fileName.matches(value)

    fun normalizeMemo(value: String): String = value.filter { !it.isISOControl() || it == '\n' || it == '\t' }.take(500)

    fun preview(
        backups: List<BackupArchive>,
        annotations: Map<String, BackupAnnotation>,
        options: BackupRetentionOptions = BackupRetentionOptions(),
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): BackupRetentionPreview {
        require(options.keepLatest in 1..1000 && options.olderThanDays in 1..36500 && nowMillis > 0)
        require(backups.size <= 10000 && backups.map { it.fileName }.distinct().size == backups.size)
        val dated = backups.mapNotNull { archive ->
            if (!validFileName(archive.fileName) || archive.sizeBytes < 0) return@mapNotNull null
            val timestamp = runCatching { LocalDateTime.parse(archive.createdAt, dateFormat).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
            timestamp?.takeIf { it in 1L..nowMillis }?.let { archive to it }
        }.sortedWith(compareByDescending<Pair<BackupArchive, Long>> { it.second }.thenBy { it.first.fileName })
        val unpinned = dated.filter { (archive, _) -> annotations[archive.fileName]?.let { !it.pinned && it.trusted } ?: true }
        val retained = unpinned.take(options.keepLatest).mapTo(mutableSetOf()) { it.first.fileName }
        val cutoff = (nowMillis - options.olderThanDays.toLong() * DAY_MILLIS).coerceAtLeast(0)
        val candidates = unpinned.filter { (archive, created) -> archive.fileName !in retained && created < cutoff }.map { it.first }
        val reclaimable = candidates.fold(0L) { total, archive -> if (archive.sizeBytes > Long.MAX_VALUE - total) Long.MAX_VALUE else total + archive.sizeBytes }
        return BackupRetentionPreview(candidates, backups.size - candidates.size, backups.size - dated.size, reclaimable)
    }

    /** Recheck fresh metadata and pins immediately before each selected delete.
     * The backend still owns filesystem identity/recovery/operation-lock checks.
     */
    fun selectedForDeletion(
        preview: BackupRetentionPreview,
        selectedNames: Set<String>,
        currentBackups: List<BackupArchive>,
        currentAnnotations: Map<String, BackupAnnotation>,
    ): List<BackupArchive> {
        require(selectedNames.all(::validFileName))
        val proposed = preview.candidates.associateBy { it.fileName }
        require(selectedNames.all(proposed::containsKey)) { "미리보기에서 선택한 백업만 정리할 수 있습니다." }
        require(currentBackups.map { it.fileName }.distinct().size == currentBackups.size)
        val current = currentBackups.associateBy { it.fileName }
        return selectedNames.map { name ->
            val annotation = currentAnnotations[name] ?: BackupAnnotation()
            check(!annotation.pinned && annotation.trusted) { "고정했거나 보호 상태를 확인할 수 없는 백업은 정리하지 않습니다." }
            val original = proposed.getValue(name)
            val actual = requireNotNull(current[name]) { "백업 목록이 변경되었습니다. 미리보기를 다시 열어 주세요." }
            check(actual.copy(requiredRestoreBytes = 0, availableRestoreBytes = 0) ==
                original.copy(requiredRestoreBytes = 0, availableRestoreBytes = 0)) { "백업 정보가 변경되었습니다. 미리보기를 다시 열어 주세요." }
            actual
        }
    }
}
