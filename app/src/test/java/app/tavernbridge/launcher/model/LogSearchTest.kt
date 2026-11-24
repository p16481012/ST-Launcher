package app.tavernbridge.launcher.model

import org.junit.Assert.*
import org.junit.Test

class LogSearchTest {
    @Test fun liveServerAndAccessTailsRollIndependentlyWithoutRekeyingRetainedLines() {
        fun raw(server: IntRange, access: IntRange) = "===== SillyTavern server.log =====\n" +
            server.joinToString("\n") { "server #$it" } + "\n\n===== SillyTavern access.log =====\n" +
            access.joinToString("\n") { "access #$it" } + "\n"
        val timeline = LiveLogTimeline()
        val initial = timeline.update(raw(1..400, 1..100), true)
        val next = timeline.update(raw(2..401, 2..101), true)
        assertEquals(500, next.rows.size)
        assertEquals(initial.rows.first { it.text == "server #200" }.id, next.rows.first { it.text == "server #200" }.id)
        assertEquals(initial.rows.first { it.text == "access #2" }.id, next.rows.first { it.text == "access #2" }.id)
        assertEquals(listOf("server #401", "access #101"), next.rows.filter { it.id >= next.firstNewId }.map { it.text })
        val serverOnly = timeline.update(raw(3..402, 2..101), true)
        assertEquals("server #402", serverOnly.rows.last().text)
        assertEquals(1, serverOnly.rows.count { it.id >= serverOnly.firstNewId })
        val same = timeline.update(raw(3..402, 2..101), true)
        assertEquals(0, same.rows.count { it.id >= same.firstNewId })
    }
    @Test fun onlyReadOnlyTailDuringKnownNonReplacingOperations() {
        val busy = LauncherUiState(isWorking = true, workingStartedAtMillis = 5, dispatchedAtMillis = 5,
            environment = EnvironmentStatus(managerConnected = true), termuxSetup = TermuxSetupState(TermuxSetupStatus.READY))
        for (op in listOf("backup", "diagnose", "start")) assertTrue(busy.copy(dispatchedOperation = op).canReadLiveServerLog())
        for (op in listOf("", "restore", "update", "import-install", "repair", "reset-installation"))
            assertFalse(busy.copy(dispatchedOperation = op).canReadLiveServerLog())
        assertFalse(busy.copy(dispatchedOperation = "backup", termuxSetup = TermuxSetupState()).canReadLiveServerLog())
    }
    @Test fun rollingTailsAnimateOnlyActuallyNewSuffixes() {
        assertEquals(2, retainedLogPrefix(listOf("x", "a", "a"), listOf("a", "a", "new")))
        assertEquals(2, retainedLogPrefix(listOf("a", "a"), listOf("a", "a")))
        assertEquals(0, retainedLogPrefix(listOf("x", "a"), listOf("b")))
        assertEquals(1, retainedLogPrefix(listOf("a", "b", "a"), listOf("a")))
        assertEquals(0, retainedLogPrefix(emptyList(), listOf("new")))
    }
    @Test fun koreanCaseInsensitiveAndRegexPunctuationAreLiteral() {
        val text = "ERROR 오류 [a.*]\nerror 오류"
        assertEquals(listOf(0..4, 15..19), searchLog(text, "error").ranges)
        assertEquals(2, searchLog(text, "오류").ranges.size)
        assertEquals(1, searchLog(text, "[a.*]").ranges.size)
        assertTrue(searchLog(text, "^.*$").ranges.isEmpty())
    }
    @Test fun boundedSearchAndEmptyQueryCannotAllocateUnboundedRanges() {
        val result = searchLog("a".repeat(10000), "a", 10)
        assertEquals(10, result.ranges.size)
        assertTrue(result.capped)
        assertTrue(searchLog("text", "").ranges.isEmpty())
        assertTrue(searchLog("text", "a".repeat(201)).ranges.isEmpty())
    }
}
