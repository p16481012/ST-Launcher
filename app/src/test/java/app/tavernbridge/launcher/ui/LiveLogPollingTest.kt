package app.tavernbridge.launcher.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveLogPollingTest {
    @Test fun stoppedServerReadsItsLastLinesOnce() = runBlocking {
        var reads = 0
        watchServerLog(false, { reads++ }, { error("No reason to schedule another Termux read") })
        assertEquals(1, reads)
    }

    @Test fun liveServerReadsOnEntryThenAtIntervalsUntilScreenCloses() = runBlocking {
        val calls = mutableListOf<String>()
        try {
            watchServerLog(true, { calls.add("read") }, {
                if (calls.size == 5) throw CancellationException("screen closed")
                calls.add("wait")
            })
        } catch (_: CancellationException) { /* LaunchedEffect cancelled on departure */ }
        assertEquals(listOf("read", "wait", "read", "wait", "read"), calls)
    }
}
