package app.tavernbridge.launcher.data

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import app.tavernbridge.launcher.MainActivity
import app.tavernbridge.launcher.R
import app.tavernbridge.launcher.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.TimeUnit

internal object BackupJobs {
    const val OPEN = "app.tavernbridge.launcher.OPEN_BACKUPS"
    const val DUE = "backup-due"
    const val AUTO = "backup-activity"
    const val FAILURE = "backup-failure"
    const val REMINDER_ID = 2131
    private const val AUTO_WORK = "auto-backup"
    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        listOf(Triple(DUE,"백업 시기 알림",NotificationManager.IMPORTANCE_DEFAULT),
            Triple(AUTO,"자동 백업 진행",NotificationManager.IMPORTANCE_LOW),
            Triple(FAILURE,"자동 백업 실패",NotificationManager.IMPORTANCE_DEFAULT)).forEach { (id,label,level) ->
            nm.createNotificationChannel(NotificationChannel(id,label,level))
        }
    }
    fun allowed(c: Context, channel: String): Boolean {
        val nm = c.getSystemService(NotificationManager::class.java)
        return (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(c,Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            nm.areNotificationsEnabled() && nm.getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    fun builder(c: Context, id: String, title: String, body: String): NotificationCompat.Builder {
        channels(c)
        val intent = Intent(c,MainActivity::class.java).setAction(OPEN).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return NotificationCompat.Builder(c,id).setSmallIcon(R.drawable.ic_launcher_tile).setContentTitle(title).setContentText(body)
            .setContentIntent(PendingIntent.getActivity(c,2161,intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true).setOnlyAlertOnce(true)
    }
    @Suppress("MissingPermission")
    fun notify(c: Context, id: Int, channel: String, title: String, body: String): Boolean {
        channels(c)
        if (!allowed(c,channel)) return false
        return runCatching { c.getSystemService(NotificationManager::class.java).notify(id,builder(c,channel,title,body).build()) }.isSuccess
    }
    fun reconcile(c: Context, update: Boolean = false) {
        val wm = WorkManager.getInstance(c)
        val store = BackupJobState(c)
        if (store.enabled) {
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(7,TimeUnit.DAYS,2,TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresDeviceIdle(true).setRequiresStorageNotLow(true).build()).build()
            wm.enqueueUniquePeriodicWork(AUTO_WORK,if (update) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,request)
        } else wm.cancelUniqueWork(AUTO_WORK)
        if (BackupReminderStore(c).load().enabled && store.phone) {
            wm.enqueueUniquePeriodicWork("backup-reminder",ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BackupDueWorker>(6,TimeUnit.HOURS).build())
        } else {
            wm.cancelUniqueWork("backup-reminder")
            c.getSystemService(NotificationManager::class.java).cancel(REMINDER_ID)
            store.prefs.edit().remove("due_token").apply()
        }
    }
}

class BackupDueWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c,p) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val c = applicationContext
        val s = BackupJobState(c)
        val repo = LauncherRepository(c)
        val key = JobPolicy.reminderKey(BackupReminderStore(c).load(),System.currentTimeMillis(),repo.hasKnownInstallation() && repo.baseStatus().termuxInstalled)
        if (key == null || !s.phone) {
            c.getSystemService(NotificationManager::class.java).cancel(BackupJobs.REMINDER_ID)
            s.prefs.edit().remove("due_token").apply()
        } else if (key != s.prefs.getString("due_token",null) && BackupJobs.notify(c,BackupJobs.REMINDER_ID,BackupJobs.DUE,
            "백업할 때가 됐어요","눌러서 마지막 백업을 확인해 주세요.")) s.prefs.edit().putString("due_token",key).apply()
        Result.success()
    }
}

class AutoBackupWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c,p) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val c = applicationContext
        val s = BackupJobState(c)
        if (!s.enabled) return@withContext Result.success()
        val repo = LauncherRepository(c)
        fun record(kind: String, detail: String, code: String = "", clear: Boolean = false) {
            s.record(AutoResult(System.currentTimeMillis(),kind,detail,code),clearPending=clear)
            if (kind == "failed" && s.failNotice) BackupJobs.notify(c,2133,BackupJobs.FAILURE,"자동 백업을 하지 못했어요","$detail. 눌러서 확인해 주세요.")
        }
        try {
            val base = repo.baseStatus()
            if (!base.termuxInstalled || !base.commandPermissionGranted || !repo.hasKnownInstallation()) {
                record("not_ready","Termux 연결 확인 필요"); return@withContext Result.success()
            }
            if (base.termuxBatteryUnrestricted != true) {
                record("not_ready","Termux 배터리 제한","BATTERY"); return@withContext Result.success()
            }
            setForeground(getForegroundInfo())
            val env = repo.inspect()
            val old = BackupRequestCodec.decode(s.prefs.getString("pending",null))
            val previous = old?.let { repo.automaticReceipt(it.id).also { r -> check(r.isSuccess) } }
            val delivered = previous?.stdout?.let(JobPolicy::receipt)
            if (delivered == null && (env.operationActive || env.recoveryPending)) { record("busy",""); return@withContext Result.success() }
            if (old != null && delivered == null && previous?.stdout?.lineSequence()?.any { it == "auto_skip=unconfirmed" } == true) {
                record("failed","이전 백업의 저장 결과를 확인하지 못했어요. Download 폴더를 확인해 주세요.","AUTO_UNCONFIRMED",true)
                return@withContext Result.success()
            }
            if (delivered == null && (env.processRunning || env.serverReachable)) { record("server",""); return@withContext Result.success() }
            if (delivered == null && (!env.sillyTavernInstalled || !repo.backupStorageReady())) {
                record("not_ready","백업 저장공간이나 설치 상태 확인 필요"); return@withContext Result.success()
            }
            if (!s.enabled) return@withContext Result.success()
            val request = old ?: BackupRequest(UUID.randomUUID().toString(),setOf(if (s.full) BackupCategory.FULL else BackupCategory.USER_DATA),
                ThemePreferences(c).loadDisplay().includeSecretsByDefault, emptySet())
            if (old == null) check(s.prefs.edit().putString("pending",BackupRequestCodec.encode(request)).commit())
            val result = if (delivered != null) requireNotNull(previous) else coroutineScope {
                val poll = launch { while (isActive) {
                    delay(12_000)
                    val p = try { repo.readProgress(true) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                    if (p?.backupRequestId == request.id) p.measuredPercent?.let { setForeground(progress(it)) }
                } }
                try { repo.automaticBackup(request) } finally { poll.cancel() }
            }
            when {
                result.exitCode == 11 -> record("busy","", clear=true)
                result.stdout.lineSequence().any { it == "auto_skip=server" } -> record("server","",clear=true)
                !result.isSuccess -> {
                    val code = result.stableErrorCode
                    val detail = if ("SPACE" in code || result.readableError().contains("No space",true)) "저장 공간 부족"
                        else result.readableError().lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() && !it.startsWith("[") }?.take(180) ?: "Termux 작업 실패"
                    record("failed",app.tavernbridge.launcher.security.redactSensitiveText(detail),code,clear=result.exitCode > 0)
                }
                else -> {
                    val receipt = JobPolicy.receipt(result.stdout)
                    if (receipt == null) record("failed","ZIP 저장 결과를 확인하지 못했어요. Download 폴더를 확인해 주세요.","AUTO_UNCONFIRMED",true)
                    else {
                        val now = System.currentTimeMillis()
                        val size = result.stdout.lineSequence().firstOrNull { it.startsWith("size=") }?.substringAfter('=')?.toLongOrNull() ?: 0
                        s.record(AutoResult(now,"ok",receipt.first,size=size),receipt,true)
                        c.getSystemService(NotificationManager::class.java).cancel(2133)
                        BackupReminderStore(c).recordSuccessful(request,now)
                        try { prune(repo,s,c) } catch (e: CancellationException) { throw e } catch (_: Exception) { Log.w("AutoBackup","Skipped retention; backup saved") }
                    }
                }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            record("failed","Termux 응답이나 백그라운드 실행 권한을 확인해 주세요.","AUTO_NO_RESPONSE")
        }
        Result.success()
    }
    private suspend fun prune(repo: LauncherRepository, s: BackupJobState, c: Context) {
        val archives = repo.listBackups()
        val notes = BackupMetadataStore(c,"termux:~/storage/downloads")
        BackupJobState.annotationGate.withLock {
            if (!s.enabled) return
            val victims = JobPolicy.prune(archives,s.receipts(),notes.loadAll(archives.map { it.fileName }),s.keep)
            if (victims.isNotEmpty() && victims.size <= 200 && repo.deleteBackupsChecked(victims).isSuccess) {
                val ed = s.prefs.edit()
                victims.forEach { ed.remove("auto:" + it.fileName) }
                ed.apply()
            }
        }
    }
    private fun progress(percent: Int?): ForegroundInfo {
        val n = BackupJobs.builder(applicationContext,BackupJobs.AUTO,"자동 백업 중",percent?.let { "$it%" } ?: "선택한 파일을 확인하고 있어요.")
            .setProgress(100,percent ?: 0,percent == null).setOngoing(true).setAutoCancel(false).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(2132,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(2132,n)
    }
    override suspend fun getForegroundInfo() = progress(null)
}

