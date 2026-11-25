package app.tavernbridge.launcher.automation

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.testing.*
import app.tavernbridge.launcher.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BackupWorkerSmokeTest {
    @Test fun grantedPhoneReminderPostsOnceAndIsRetractedAfterBackup() {
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        val s=BackupJobState(c)
        val manager=requireNotNull(c.getSystemService(android.app.NotificationManager::class.java))
        s.prefs.edit().putBoolean("phone",true).remove("due_token").commit()
        BackupJobs.channels(c)
        assertTrue(BackupJobs.allowed(c,BackupJobs.DUE))
        fun posted(expected: Boolean): Boolean {
            val until=android.os.SystemClock.elapsedRealtime()+3000
            do {
                val present=manager.activeNotifications.any { it.id==BackupJobs.REMINDER_ID && it.notification.contentIntent!=null }
                if(present==expected) return true
                android.os.SystemClock.sleep(40)
            } while(android.os.SystemClock.elapsedRealtime()<until)
            return false
        }
        val token="weekly-test-key"
        BackupDueWorker.deliver(c,s,token)
        assertTrue(posted(true))
        assertEquals(token,s.prefs.getString("due_token",null))
        manager.cancel(BackupJobs.REMINDER_ID)
        assertTrue(posted(false))
        BackupDueWorker.deliver(c,s,token)
        assertTrue(posted(false))
        BackupDueWorker.deliver(c,s,"after-snooze")
        assertTrue(posted(true))
        BackupDueWorker.deliver(c,s,null)
        assertTrue(posted(false))
        s.prefs.edit().remove("phone").commit()
    }
    @Test fun constrainedWorkerMakesNoZipWhenTermuxIsNotReady() {
        val c = InstrumentationRegistry.getInstrumentation().targetContext
        val state = BackupJobState(c)
        val original = state.prefs.all.toMap()
        try {
            state.prefs.edit().clear().putBoolean("enabled",true).commit()
            WorkManagerTestInitHelper.initializeTestWorkManager(c,Configuration.Builder()
                .setExecutor(SynchronousExecutor()).build())
            val wm = WorkManager.getInstance(c)
            val task = OneTimeWorkRequestBuilder<AutoBackupWorker>().setConstraints(Constraints.Builder()
                .setRequiresCharging(true).setRequiresDeviceIdle(true).setRequiresStorageNotLow(true).build()).build()
            wm.enqueue(task).result.get(10,TimeUnit.SECONDS)
            assertTrue(state.history().isEmpty())
            WorkManagerTestInitHelper.getTestDriver(c)!!.setAllConstraintsMet(task.id)
            val deadline = android.os.SystemClock.elapsedRealtime()+10000
            while (wm.getWorkInfoById(task.id).get()!!.state != WorkInfo.State.SUCCEEDED &&
                android.os.SystemClock.elapsedRealtime()<deadline) Thread.sleep(30)
            assertEquals(WorkInfo.State.SUCCEEDED,wm.getWorkInfoById(task.id).get()!!.state)
            assertEquals("not_ready",state.history().first().kind)
            assertTrue(state.receipts().isEmpty())
            repeat(12) { state.record(AutoResult(it.toLong(),"server","")) }
            assertEquals(10,state.history().size)
            assertEquals(11L,state.history().first().time)
        } finally {
            val edit=state.prefs.edit().clear()
            original.forEach { (k,v) -> when(v) { is String -> edit.putString(k,v); is Boolean -> edit.putBoolean(k,v)
                is Long -> edit.putLong(k,v); is Int -> edit.putInt(k,v); else -> Unit } }
            edit.commit()
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }
}

