package app.tavernbridge.launcher.model

import app.tavernbridge.launcher.security.redactSensitiveText
import java.util.Base64
import java.util.UUID

/** Only the selected scope is persisted, never backup contents or credentials. */
data class BackupRequest(
    val id: String,
    val categories: Set<BackupCategory>,
    val includeSecrets: Boolean,
    val customFolders: Set<String>,
) {
    fun newAttempt(): BackupRequest = copy(id = UUID.randomUUID().toString())
}

internal object BackupRequestCodec {
    private val identifier = Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")

    fun encode(request: BackupRequest): String {
        val folders = request.customFolders.sorted().joinToString(",") {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8))
        }
        return listOf("1", request.id, request.categories.sortedBy { it.key }.joinToString(",") { it.key },
            if (request.includeSecrets) "1" else "0", folders).joinToString("|")
    }

    fun decode(value: String?): BackupRequest? = runCatching {
        if (value == null || value.length > 32_768) return null
        val fields = value.split('|')
        if (fields.size != 5 || fields[0] != "1" || !identifier.matches(fields[1])) return null
        val keys = fields[2].split(',')
        val categories = keys.map { BackupCategory.fromKey(it) ?: return null }.toSet()
        if (categories.isEmpty() || categories.size != keys.size ||
            (BackupCategory.FULL in categories && categories.size != 1)) return null
        val secrets = when (fields[3]) { "0" -> false; "1" -> true; else -> return null }
        if (secrets && BackupCategory.USER_DATA !in categories && BackupCategory.FULL !in categories) return null
        val folders = if (fields[4].isEmpty()) emptySet() else fields[4].split(',').map {
            String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8)
        }.toSet()
        if (folders.size > 512 || folders.any { folder -> folder.isBlank() || folder.length > 255 ||
                folder.any { it.isISOControl() || it == '/' || it == '\\' || it == ',' } || folder in setOf(".", "..") }) return null
        if ((BackupCategory.CUSTOM in categories) != folders.isNotEmpty()) return null
        BackupRequest(fields[1], categories, secrets, folders)
    }.getOrNull()

    fun matching(saved: BackupRequest?, progress: WorkProgress?): BackupRequest? = saved?.takeIf {
        progress?.operation == "backup" && identifier.matches(progress.backupRequestId) &&
            progress.backupRequestId == it.id
    }
}

/** Redact the whole text before truncation, keeping the actual failure at the end of the log. */
internal fun backupFailureDetail(detail: String, logText: String): String {
    val message = redactSensitiveText(detail, maxLength = null).trim().take(700)
        .ifBlank { "백업 생성에 실패했습니다. 작업 로그를 확인해 주세요." }
    val lines = redactSensitiveText(logText, maxLength = null).trim().lineSequence().filter { it.isNotBlank() }.toList()
    val causes = lines.filter {
        backupErrorLine.containsMatchIn(it) && !it.contains("작업 종료 · 상태=") &&
            !it.contains("작업이 중단되었습니다. 로그를 확인해 주세요.")
    }.takeLast(5)
    val selected = if (causes.isEmpty()) lines.takeLast(6) else (causes + lines.takeLast(1)).distinct()
    val log = selected.joinToString("\n") {
        if (it.length <= 190) it else "${it.take(85)} … ${it.takeLast(100)}"
    }.take(1_200)
    return if (log.isBlank()) message else "$message\n\n최근 백업 처리 내역\n$log"
}

private val backupErrorLine = Regex(
    "zip (?:error|warning)|I/O error|zip_exit_code|E(?:NOSPC|ACCES|IO|NOENT|PERM)|permission denied|no space left|" +
        "BACKUP_[A-Z_]+|읽지 못|변경되었|공간.*부족|압축.*실패|백업.*실패",
    RegexOption.IGNORE_CASE,
)

internal fun backupFailureLog(detail: String, logText: String): String =
    redactSensitiveText(logText, maxLength = null).trim().takeLast(40_000)
        .ifBlank { backupFailureDetail(detail, "") }

internal data class BackupCompletionCheck<T>(val value: T, val refreshFailed: Boolean)

/** A failed status refresh does not undo an already verified backup. */
internal suspend fun <T> checkCompletedBackup(fallback: T, inspect: suspend () -> T): BackupCompletionCheck<T> =
    try { BackupCompletionCheck(inspect(), false) }
    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (_: Exception) { BackupCompletionCheck(fallback, true) }
