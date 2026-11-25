package app.tavernbridge.launcher.ui

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.tavernbridge.launcher.LauncherViewModel
import app.tavernbridge.launcher.data.*
import app.tavernbridge.launcher.model.*
import java.text.DateFormat
import java.util.Date

@Composable internal fun rememberBackupJobs(): BackupJobState {
    val c = LocalContext.current.applicationContext
    val base = remember(c) { BackupJobState(c) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(base) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _,_ -> revision++ }
        base.prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { base.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return remember(c,revision) { BackupJobState(c) }
}

@Composable private fun resumeCount(): Int {
    var count by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _,event -> if (event == Lifecycle.Event.ON_RESUME) count++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return count
}

private fun notificationSettings(c: Context, channel: String) {
    c.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,c.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID,channel))
}
@Composable private fun permissionAction(s: BackupJobState, channel: String): () -> Unit {
    val c = LocalContext.current
    val pref = if (channel == BackupJobs.DUE) "phone" else "failure_notice"
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        s.prefs.edit().putBoolean(pref,ok).apply()
        BackupJobs.reconcile(c)
    }
    return {
        BackupJobs.channels(c)
        s.prefs.edit().putBoolean(pref,true).apply()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c,Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            val activity = c as? android.app.Activity
            if (s.prefs.getBoolean("asked_system",false) && activity != null && !activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
                c.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,c.packageName))
            else {
                s.prefs.edit().putBoolean("asked_system",true).apply()
                ask.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else if (!BackupJobs.allowed(c,channel)) notificationSettings(c,channel)
        BackupJobs.reconcile(c)
    }
}

@Composable internal fun BackupPhoneSettings(state: LauncherUiState) {
    val c = LocalContext.current
    val s = rememberBackupJobs()
    val resume = resumeCount()
    SettingsGroup {
        val rows = buildList { if (state.backupReminder.enabled) add(BackupJobs.DUE); if (s.enabled) add(BackupJobs.FAILURE) }
        rows.forEachIndexed { i,ch ->
            val enabled = if (ch == BackupJobs.DUE) s.phone else s.failNotice
            val on = enabled && remember(ch,s,resume) { BackupJobs.allowed(c,ch) }
            val enable = permissionAction(s,ch)
            if (i != 0) SettingsDivider()
            SettingsRow(if (ch == BackupJobs.DUE) "휴대폰 백업 알림" else "자동 백업 실패 알림",
                caption = if (ch == BackupJobs.DUE) "백업 시기가 되면 알려줘요. 백업을 실행하지는 않아요." else "실패할 때만 알려줘요.",
                checked = on, onClick = {
                    if (on) {
                        s.prefs.edit().putBoolean(if (ch == BackupJobs.DUE) "phone" else "failure_notice",false).apply()
                        c.getSystemService(android.app.NotificationManager::class.java).cancel(if (ch == BackupJobs.DUE) BackupJobs.REMINDER_ID else 2133)
                        BackupJobs.reconcile(c)
                    } else enable()
                })
        }
    }
}

@Composable internal fun BackupAutomationSettings(state: LauncherUiState, vm: LauncherViewModel) {
    val c = LocalContext.current
    val s = rememberBackupJobs()
    var page by rememberSaveable { mutableStateOf("") }
    var storage by remember { mutableStateOf<Boolean?>(null) }
    var checking by remember { mutableStateOf(false) }
    val resume = resumeCount()
    val battery = remember(resume,page) { c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations("com.termux") }
    val ask = permissionAction(s,BackupJobs.FAILURE)
    val enable = {
        s.prefs.edit().putBoolean("enabled",true).apply()
        BackupJobs.reconcile(c,true)
        page = if (!s.prefs.getBoolean("failure_offered",false) && Build.VERSION.SDK_INT >= 33 && !BackupJobs.allowed(c,BackupJobs.FAILURE)) "permission" else ""
        if (page == "permission") s.prefs.edit().putBoolean("failure_offered",true).apply()
        vm.showMessage("자동 백업을 켰어요")
    }
    LaunchedEffect(page,resume,state.termuxSetup.verified) {
        if (page == "ready") {
            checking = true
            storage = try { vm.checkAutoBackupStorage() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { false }
            checking = false
            if (storage == true && battery) enable()
        }
    }
    val history = s.history()
    SettingsHeading("자동 백업")
    SettingsGroup {
        SettingsRow("충전 중 자동 백업",caption="충전 중·기기 유휴·서버 꺼짐일 때 일주일에 한 번",checked=s.enabled,onClick={
            if (s.enabled) { s.prefs.edit().putBoolean("enabled",false).apply(); BackupJobs.reconcile(c)
                vm.showMessage("자동 백업 예약을 껐어요. 이미 만든 백업은 남겨요.") }
            else { storage = null; checking = true; page="ready" }
        })
        SettingsDivider()
        SettingsRow("백업 범위",value=if (s.full) "전체 백업" else "사용자 데이터만",onClick={page="scope"})
        SettingsDivider()
        SettingsRow("마지막 자동 백업",value=history.firstOrNull()?.label ?: "없음",
            caption=history.firstOrNull()?.let { date(it.time) }.orEmpty(),onClick=if (history.isEmpty()) null else ({page="history"}))
    }
    SettingsCaption("서버가 켜져 있으면 끄지 않고 건너뛰어요. 기기에 따라 실행이 며칠 늦어질 수 있어요.")
    when(page) {
        "ready" -> if (!checking || !battery) V2BottomSheet(onClose={page=""}) {
            Text("자동 백업 준비",style=MaterialTheme.typography.titleLarge)
            SettingsRow("Termux 배터리 제한 없음",value=if(battery) "정상" else "확인 필요")
            SettingsRow("백업 저장공간 연결",value=if(checking) "확인 중" else if(storage==true) "정상" else "확인 필요")
            SettingsRow("결과 알림 권한",value=if(BackupJobs.allowed(c,BackupJobs.FAILURE)) "정상" else "선택")
            if (!battery) V2SheetPrimaryButton("Termux 배터리 설정 열기",onClick=vm::openTermuxAppSettings)
            else if (!checking && storage != true) {
                SettingsCaption("Termux에서 termux-setup-storage를 입력한 뒤 돌아와 주세요.")
                V2SheetPrimaryButton("Termux 열기",onClick=vm::openTermux)
            }
            V2LinkAction("나중에",{page=""})
        }
        "scope" -> V2BottomSheet(onClose={page=""}) {
            Text("기본 백업 범위",style=MaterialTheme.typography.titleLarge)
            SettingsCaption("지금 백업과 충전 중 자동 백업에 함께 적용해요.")
            listOf(false,true).forEach { full -> SettingsRadioRow(if(full) "전체 백업" else "사용자 데이터만",s.full==full) {
                s.prefs.edit().putBoolean("full",full).apply();page=""
            } }
        }
        "history" -> AutoResultSheet(history,{page=""})
        "permission" -> V2AlertDialog(onDismissRequest={page=""},title={Text("자동 백업이 실패하면 알려 드릴까요?")},
            text={Text("실패했을 때만 휴대폰 알림을 보내요. 받지 않아도 자동 백업은 켜져 있어요.")},
            confirmButton={V2DialogAction(onClick={page="";ask()}) { Text("알림 받기") }},
            dismissButton={V2DialogAction(tone=V2DialogTone.Quiet,onClick={page=""}) { Text("받지 않기") }})
    }
}

@Composable internal fun AutoRetentionSettings() {
    val s = rememberBackupJobs()
    var open by rememberSaveable { mutableStateOf(false) }
    SettingsGroup { SettingsRow("보관할 자동 백업 수",value="${s.keep}개",onClick={open=true}) }
    if(open) V2BottomSheet(onClose={open=false}) {
        Text("보관할 자동 백업 수",style=MaterialTheme.typography.titleLarge)
        SettingsCaption("직접 만든 백업, 고정한 백업, 출처를 확인하지 못한 백업은 지우지 않아요.")
        listOf(2,4,8).forEach { n -> SettingsRadioRow("${n}개",s.keep==n) { s.prefs.edit().putInt("keep",n).apply();open=false } }
    }
}
private fun date(time: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(time))
@Composable private fun AutoResultSheet(rows: List<AutoResult>, close: ()->Unit) {
    V2BottomSheet(onClose=close) {
        Text("자동 백업 기록",style=MaterialTheme.typography.titleLarge)
        rows.forEach { r ->
            SettingsRow(r.label, caption=listOf(date(r.time),r.detail.takeIf { r.kind=="ok" }.orEmpty(),
                if(r.size>0) formatBackupBytes(r.size) else "",r.code).filter(String::isNotBlank).joinToString(" · "))
        }
    }
}
@Composable internal fun AutoBackupNotice(s: BackupJobState, manual: ()->Unit, space: ()->Unit) {
    val c = LocalContext.current
    val failed = s.lastFailure
    val rows = s.history()
    var open by rememberSaveable { mutableStateOf(false) }
    if (failed != null) {
        SettingsGroup {
            SettingsRow("지난 자동 백업을 하지 못했어요",caption=failed.detail+" · "+date(failed.time),onClick={open=true})
            SettingsDivider()
            when {
                "SPACE" in failed.code || failed.detail.contains("공간 부족") -> SettingsRow("공간 정리",onClick=space)
                failed.code == "BATTERY" -> SettingsRow("배터리 설정",onClick={ c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:com.termux"))) })
                else -> SettingsRow("지금 백업",onClick=manual)
            }
        }
    } else if (JobPolicy.repeatedSkips(rows)) SettingsGroup { SettingsRow("서버가 늘 켜져 있어 자동 백업을 못 했어요",value="지금 백업",onClick=manual) }
    if(open && failed!=null) AutoResultSheet((listOf(failed) + rows).distinctBy { it.time },{open=false})
}
@Composable internal fun autoArchiveHeading(a: BackupArchive): String {
    val s = rememberBackupJobs()
    return archiveHeading(a) + if (a.revision.isNotBlank() && s.prefs.getString("auto:"+a.fileName,null)==a.revision) " · 자동" else ""
}

