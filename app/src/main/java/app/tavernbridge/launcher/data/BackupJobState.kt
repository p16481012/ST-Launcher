package app.tavernbridge.launcher.data

import android.content.Context
import app.tavernbridge.launcher.model.*
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject

internal data class AutoResult(val time: Long, val kind: String, val detail: String, val code: String = "", val size: Long = 0) {
    val label get() = when(kind) {
        "ok" -> "성공"
        "server" -> "건너뜀 · 서버 켜짐"
        "busy" -> "건너뜀 · 다른 작업 중"
        "not_ready" -> "건너뜀 · $detail"
        else -> "실패 · $detail"
    }
}

internal object JobPolicy {
    val revision = Regex("[a-f0-9]{64}")
    fun receipt(output: String): Pair<String, String>? {
        val raw = output.lineSequence().filter { it.startsWith("backup_receipt=") }.singleOrNull()?.substringAfter('=') ?: return null
        val name = raw.substringBefore('|')
        val rev = raw.substringAfter('|', "")
        return if (BackupRetentionPolicy.validFileName(name) && revision.matches(rev)) name to rev else null
    }
    data class Delivered(val receipt: Pair<String,String>, val atMillis: Long, val size: Long)
    fun delivered(output: String, now: Long): Delivered? {
        val r = receipt(output) ?: return null
        fun single(key: String) = output.lineSequence().filter { it.startsWith("$key=") }.singleOrNull()?.substringAfter('=')?.toLongOrNull()
        val sec = single("completed_at")?.takeIf { it in 1..(now / 1000) } ?: return null
        val bytes = single("size")?.takeIf { it > 0 } ?: return null
        return Delivered(r,sec * 1000L,bytes)
    }
    // 7-day work has a 2-day flex. A redelivery in the same window must not
    // duplicate a recent ZIP; an older recovered receipt is not this week's backup.
    fun recentPublication(at: Long, now: Long) = at > 0 && now >= at && now - at < 5L * 86_400_000
    fun freshRequest(full: Boolean, includeSecrets: Boolean) = BackupRequest(java.util.UUID.randomUUID().toString(),
        setOf(if (full) BackupCategory.FULL else BackupCategory.USER_DATA),includeSecrets,emptySet())
    fun prune(backups: List<BackupArchive>, receipts: Map<String,String>, notes: Map<String,BackupAnnotation>, keep: Int): List<BackupArchive> {
        if (keep !in listOf(2,4,8)) return emptyList()
        return backups.filter { archive -> revision.matches(archive.revision) && receipts[archive.fileName] == archive.revision &&
            notes[archive.fileName]?.let { it.trusted && !it.pinned && it.createdRevision.isEmpty() } == true }
            .sortedByDescending { it.fileName }.drop(keep)
    }
    fun repeatedSkips(rows: List<AutoResult>): Boolean = rows.takeWhile { it.kind == "server" }.let {
        it.size >= 2 && it.first().time - it.last().time >= 5L * 86_400_000
    }
    fun reminderKey(r: BackupReminderState, now: Long, installed: Boolean): String? =
        r.notice(now,installed)?.let { "${r.interval.name}:${it.lastBackupAtMillis}:${r.snoozedUntilMillis}" }
}

internal class BackupJobState(context: Context) {
    val prefs = context.getSharedPreferences("backup_jobs", Context.MODE_PRIVATE)
    val enabled get() = prefs.getBoolean("enabled",false)
    val phone get() = prefs.getBoolean("phone",true)
    val failNotice get() = prefs.getBoolean("failure_notice",false)
    val keep get() = prefs.getInt("keep",4).takeIf { it in listOf(2,4,8) } ?: 4
    val full get() = prefs.getBoolean("full",false)
    fun receipts(): Map<String,String> = prefs.all.filterKeys { it.startsWith("auto:") }
        .mapNotNull { (key,value) -> (value as? String)?.let { key.removePrefix("auto:") to it } }.toMap()
    fun history(): List<AutoResult> = runCatching {
        val rows = JSONArray(prefs.getString("history","[]"))
        (0 until minOf(rows.length(),10)).map { rows.getJSONObject(it).let { o ->
            AutoResult(o.getLong("time"),o.getString("kind"),o.getString("detail"),o.optString("code"),o.optLong("size"))
        } }
    }.getOrDefault(emptyList())
    val lastFailure get() = prefs.getLong("failure_time",0L).takeIf { it > 0 }?.let { AutoResult(it, "failed",
        prefs.getString("failure_detail","").orEmpty(),prefs.getString("failure_code","").orEmpty()) }
    fun record(row: AutoResult, receipt: Pair<String,String>? = null, clearPending: Boolean = false) {
        val rows = JSONArray()
        // Normally the newest observation goes first (also after a user clock adjustment).
        // Only a recovered publication can predate outcomes we have already observed.
        val observations = listOf(row) + history()
        val ordered = if (receipt == null) observations else observations.sortedByDescending { it.time }
        ordered.take(10).forEach { rows.put(JSONObject().put("time",it.time).put("kind",it.kind)
            .put("detail",it.detail).put("code",it.code).put("size",it.size)) }
        val edit = prefs.edit().putString("history",rows.toString())
        if (receipt != null) edit.putString("auto:" + receipt.first, receipt.second)
        if (clearPending) edit.remove("pending")
        if (row.kind == "failed") edit.putLong("failure_time",row.time).putString("failure_detail",row.detail).putString("failure_code",row.code)
        if (row.kind == "ok") {
            edit.putLong("last_publication",maxOf(row.time,prefs.getLong("last_publication",0L)))
            if (row.time >= prefs.getLong("failure_time",0L)) edit.remove("failure_time").remove("failure_detail").remove("failure_code")
        }
        check(edit.commit())
    }
    companion object { val annotationGate = Mutex(); val receiptGate = Mutex() }
}

