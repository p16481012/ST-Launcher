package app.tavernbridge.launcher

import app.tavernbridge.launcher.VisibleReadQueue.ListKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class VisibleReadQueueTest {
    private val reads = VisibleReadQueue(CoroutineScope(Dispatchers.Unconfined))

    @Test fun onlyNewestReadOfTheSameKindRunsOnceAfterCheck() {
        val refresh = Job()
        val actual = mutableListOf<String>()
        assertTrue(reads.defer(ListKind.LOG, refresh, { true }) { actual += "current" })
        assertTrue(reads.defer(ListKind.LOG, refresh, { true }) { actual += "previous" })
        assertTrue(actual.isEmpty())
        refresh.complete()
        assertEquals(listOf("previous"), actual)
    }

    @Test fun leavingOrCancellingCheckDoesNotOpenOldScreenOrDispatchWork() {
        var visible = true
        val completed = Job()
        reads.defer(ListKind.FILES, completed, { visible }) { fail("reopened after leaving") }
        visible = false
        completed.complete()
        val cancelled = Job()
        reads.defer(ListKind.EXTENSIONS, cancelled, { true }) { fail("ran after cancelled refresh") }
        cancelled.cancel()
    }

    @Test fun independentReadKindsAndAlreadyCompletedChecksDoNotBlock() {
        val refresh = Job()
        val actual = mutableListOf<ListKind>()
        listOf(ListKind.BACKUPS, ListKind.LOG).forEach { kind ->
            assertTrue(reads.defer(kind, refresh, { true }) { actual += kind })
        }
        refresh.complete()
        assertEquals(setOf(ListKind.BACKUPS, ListKind.LOG), actual.toSet())
        assertFalse(reads.defer(ListKind.BACKUPS, refresh, { true }) { fail("should run inline in caller") })
        assertFalse(reads.defer(ListKind.FILES, null, { true }) { fail("no refresh") })
    }
}
