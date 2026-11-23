package app.tavernbridge.launcher.model

import org.junit.Assert.*
import org.junit.Test

class LogSearchTest {
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
