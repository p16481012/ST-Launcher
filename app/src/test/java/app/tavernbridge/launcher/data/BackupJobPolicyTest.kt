package app.tavernbridge.launcher.data

import app.tavernbridge.launcher.model.*
import org.junit.Assert.*
import org.junit.Test

class BackupJobPolicyTest {
    private val rev = "a".repeat(64)
    private fun a(i: Int) = BackupArchive("SillyTavern-Launcher-20261125-00000$i.zip",10,"",setOf(BackupCategory.USER_DATA),emptySet(),false,"",revision=rev)
    @Test fun retentionNeedsExactPrivateReceiptAndPreservesManualAndFixed() {
        val rows = (1..7).map(::a)
        val receipts = rows.filterNot { it==a(2) }.associate { it.fileName to if(it==a(3)) "b".repeat(64) else rev }
        val notes = rows.associate { it.fileName to BackupAnnotation(pinned=it==a(1),createdRevision=if(it==a(4)) rev else "") }
        assertEquals(listOf(a(5)),JobPolicy.prune(rows,receipts,notes,2))
        assertTrue(JobPolicy.prune(rows,receipts,emptyMap(),2).isEmpty())
        assertNull(JobPolicy.receipt("backup_receipt=../test|$rev"))
        assertNull(JobPolicy.receipt("backup_receipt=${a(1).fileName}|short"))
    }
    @Test fun regularReminderHasOneKeyPerDueDateAndSnoozingClearsIt() {
        val day=86400000L
        val r=BackupReminderState(lastBackupAtMillis=day,interval=BackupInterval.WEEKLY)
        assertNull(JobPolicy.reminderKey(r,7*day,true))
        val key=JobPolicy.reminderKey(r,8*day,true)
        assertNotNull(key)
        assertEquals(key,JobPolicy.reminderKey(r,9*day,true))
        assertNull(JobPolicy.reminderKey(r.snooze(8*day),8*day,true))
        assertNull(JobPolicy.reminderKey(r,8*day,false))
        assertNull(JobPolicy.reminderKey(r.copy(enabled=false),8*day,true))
    }
    @Test fun recoveryUsesOriginalPublicationTimeNotObservationTime() {
        val receipt="backup_receipt=${a(1).fileName}|$rev\nsize=100\ncompleted_at=90"
        val now=8*86400000L
        val event=requireNotNull(JobPolicy.delivered(receipt,now))
        assertEquals(90000L,event.atMillis)
        assertFalse(JobPolicy.recentPublication(event.atMillis,now))
        assertTrue(JobPolicy.recentPublication(event.atMillis,100000L))
        assertNull(JobPolicy.delivered(receipt.substringBefore("completed_at="),now))
        assertNull(JobPolicy.delivered(receipt,89999L))
        assertNull(JobPolicy.delivered(receipt.replace("size=100","size=0"),now))
    }
    @Test fun newPublicationUsesCurrentScopeAndNeverReusesPreviousConsentOrId() {
        val old = JobPolicy.freshRequest(full=true,includeSecrets=true)
        val next = JobPolicy.freshRequest(full=false,includeSecrets=false)
        assertNotEquals(old.id,next.id)
        assertEquals(setOf(BackupCategory.USER_DATA),next.categories)
        assertFalse(next.includeSecrets)
    }
    @Test fun serverSkipNoticeDoesNotCountRepeatedAttemptsInOneDay() {
        val first=AutoResult(1,"server","")
        assertFalse(JobPolicy.repeatedSkips(listOf(first.copy(time=6000),first)))
        assertTrue(JobPolicy.repeatedSkips(listOf(first.copy(time=7*86400000L),first)))
        assertFalse(JobPolicy.repeatedSkips(listOf(first.copy(time=7*86400000L),first.copy(kind="ok"),first)))
    }
}

