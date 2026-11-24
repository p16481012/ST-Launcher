package app.tavernbridge.launcher.data

import android.content.Context
import app.tavernbridge.launcher.model.BackupAnnotation
import app.tavernbridge.launcher.model.BackupRetentionPolicy
import java.security.MessageDigest

/** App-private user notes, scoped to a configured backup location and exact name. */
internal class BackupMetadataStore(context: Context, scope: String) {
    private val preferences = context.getSharedPreferences("backup_metadata", Context.MODE_PRIVATE)
    private val prefix = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) } + ":"

    @Synchronized
    fun load(fileName: String): BackupAnnotation {
        require(BackupRetentionPolicy.validFileName(fileName))
        return runCatching {
            val memo = preferences.getString("${prefix}memo:$fileName", "").orEmpty()
            require(memo == BackupRetentionPolicy.normalizeMemo(memo))
            BackupAnnotation(preferences.getBoolean("${prefix}pin:$fileName", false), memo,
                createdRevision = preferences.getString("${prefix}created:$fileName", "").orEmpty())
        }.getOrElse { BackupAnnotation(pinned = true, trusted = false) }
    }

    @Synchronized
    fun loadAll(fileNames: Collection<String>): Map<String, BackupAnnotation> {
        require(fileNames.size <= 10000)
        return fileNames.filter(BackupRetentionPolicy::validFileName).associateWith(::load)
    }

    @Synchronized
    fun rememberCreated(output: String): String? {
        val receipt = output.lineSequence().filter { it.startsWith("backup_receipt=") }.singleOrNull()?.removePrefix("backup_receipt=") ?: return null
        val name = receipt.substringBefore('|')
        val revision = receipt.substringAfter('|', "")
        if (BackupRetentionPolicy.validFileName(name) && revision.matches(Regex("[a-f0-9]{64}"))) {
            preferences.edit().putString("${prefix}created:$name", revision).apply()
            return name
        }
        return null
    }

    @Synchronized
    fun save(fileName: String, pinned: Boolean, memo: String): BackupAnnotation {
        require(BackupRetentionPolicy.validFileName(fileName))
        val state = load(fileName).copy(pinned = pinned, memo = BackupRetentionPolicy.normalizeMemo(memo), trusted = true)
        check(preferences.edit().putBoolean("${prefix}pin:$fileName", state.pinned)
            .putString("${prefix}memo:$fileName", state.memo).commit()) { "백업 고정·메모를 저장하지 못했습니다. 다시 시도해 주세요." }
        return state
    }
}
