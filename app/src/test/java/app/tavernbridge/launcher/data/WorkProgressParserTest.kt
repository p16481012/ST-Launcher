package app.tavernbridge.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class WorkProgressParserTest {
    @Test
    fun reportsByteRatioInsteadOfLegacyWeightedPercent() {
        val item = Base64.getEncoder().encodeToString("data/기본 사용자/chats/test.jsonl".toByteArray())
        val result = WorkProgressParser.parse(
            """
            operation=import-backup
            phase=압축 해제
            percent=26
            status=running
            progress_mode=bytes
            completed_bytes=3145728
            total_bytes=4194304
            completed_files=2
            total_files=8
            current_item_b64=$item
            heartbeat_at=120
            activity_at=118
            phase_started_at=100
            __ST_LAUNCHER_LOG__
            extracting...
            """.trimIndent(),
        )!!
        assertEquals(75, result.measuredPercent)
        assertEquals(3_145_728L, result.completedBytes)
        assertEquals(4_194_304L, result.totalBytes)
        assertEquals(2L, result.completedFiles)
        assertEquals("data/기본 사용자/chats/test.jsonl", result.currentItem)
        assertEquals(120_000L, result.heartbeatAtMillis)
        assertEquals(118_000L, result.activityAtMillis)
        assertEquals(100_000L, result.phaseStartedAtMillis)
        assertEquals("extracting...", result.logText)
    }

    @Test
    fun legacyFixedPercentIsIndeterminateEvenAtOneHundredWhileRunning() {
        listOf(26, 100).forEach { percent ->
            val result = WorkProgressParser.parse("phase=복원\noperation=restore\npercent=$percent\nstatus=running")!!
            assertEquals("indeterminate", result.progressMode)
            assertNull(result.measuredPercent)
        }
    }

    @Test
    fun unknownAndZeroTotalsStayIndeterminate() {
        val unknown = WorkProgressParser.parse("operation=restore\nprogress_mode=bytes\ncompleted_bytes=100")!!
        val invalidMode = WorkProgressParser.parse("operation=restore\nprogress_mode=weighted\npercent=72")!!
        assertNull(unknown.measuredPercent)
        assertNull(invalidMode.measuredPercent)
        assertEquals("indeterminate", invalidMode.progressMode)
    }

    @Test
    fun phaseCompletionDoesNotChangeOperationStatus() {
        val result = WorkProgressParser.parse(
            "operation=restore\nstatus=running\nprogress_mode=files\ncompleted_files=9\ntotal_files=9",
        )!!
        assertEquals(100, result.measuredPercent)
        assertEquals("running", result.status)
    }

    @Test
    fun onlySuccessfulCompletionIsOneHundredWithoutMeasurements() {
        val success = WorkProgressParser.parse("operation=restore\nstatus=success\nprogress_mode=complete")!!
        val error = WorkProgressParser.parse("operation=restore\nstatus=error\nprogress_mode=complete\npercent=100")!!
        val legacySuccess = WorkProgressParser.parse("operation=restore\nstatus=success\npercent=100")!!
        assertEquals(100, success.measuredPercent)
        assertEquals(100, legacySuccess.measuredPercent)
        assertNull(error.measuredPercent)
    }

    @Test
    fun malformedValuesCannotCrashOrCreateNegativeCounts() {
        val result = WorkProgressParser.parse(
            """
            operation=restore
            progress_mode=bytes
            completed_bytes=-4
            total_bytes=invalid
            completed_files=9223372036854775808
            current_item_b64=not%base64
            heartbeat_at=9223372036854775807
            activity_at=-1
            """.trimIndent(),
        )!!
        assertEquals(0L, result.completedBytes)
        assertEquals(0L, result.totalBytes)
        assertEquals(0L, result.completedFiles)
        assertEquals(0L, result.heartbeatAtMillis)
        assertEquals(0L, result.activityAtMillis)
        assertEquals("", result.currentItem)
        assertNull(result.measuredPercent)
    }

    @Test
    fun byteRatioDoesNotOverflowLong() {
        val result = WorkProgressParser.parse(
            "operation=restore\nprogress_mode=bytes\ncompleted_bytes=4611686018427387903\ntotal_bytes=9223372036854775807",
        )!!
        assertEquals(50, result.measuredPercent)
    }

    @Test
    fun actualItemCannotInjectMultilineStatusText() {
        val item = Base64.getEncoder().encodeToString("file\nstatus=success\u001b[31m".toByteArray())
        val result = WorkProgressParser.parse("operation=restore\ncurrent_item_b64=$item")!!
        assertEquals("filestatus=success[31m", result.currentItem)
    }

    @Test
    fun monitorHeartbeatDoesNotImplyProcessingActivity() {
        val result = WorkProgressParser.parse(
            """
            operation=restore
            phase_started_at=100
            heartbeat_at=101
            activity_at=102
            monitor_operation=restore
            monitor_heartbeat_at=200
            observed_activity_at=90
            """.trimIndent(),
        )!!
        assertEquals(200_000L, result.heartbeatAtMillis)
        assertEquals(102_000L, result.activityAtMillis)
    }

    @Test
    fun onlyMatchingOperationAndCurrentPhaseMaySupplyObservedActivity() {
        fun parse(monitorOperation: String) = WorkProgressParser.parse(
            "operation=restore\nphase_started_at=100\nmonitor_operation=$monitorOperation\nmonitor_heartbeat_at=140\nobserved_activity_at=130",
        )!!
        val matching = parse("restore")
        val stale = parse("install")
        assertEquals(140_000L, matching.heartbeatAtMillis)
        assertEquals(130_000L, matching.activityAtMillis)
        assertEquals(0L, stale.heartbeatAtMillis)
        assertEquals(0L, stale.activityAtMillis)
    }

    @Test
    fun absentProgressDoesNotFabricateStateFromLog() {
        assertNull(WorkProgressParser.parse("\n__ST_LAUNCHER_LOG__\nprogress_mode=bytes"))
    }

    @Test
    fun entireParsedLogIsSafeForExpandedViewAndClipboardNotOnlyRecentPreview() {
        val rawLog = buildString {
            append("\u001B[31mapi_\u202Ekey=private-api-value\u001B[0m\n")
            append("Authorization: Bearer private-bearer-value\n")
            append("password=\"private password with spaces\"\n")
            append("https://username:private-url-password@example.invalid/path\n")
            append("\u001B]0;private-terminal-title\u0007")
            append("공개\t처리 내역\u0000\u0008\n")
            repeat(12) { append("파일 검사 $it\n") }
        }
        val result = WorkProgressParser.parse("operation=start\nphase=서버 시작\n__ST_LAUNCHER_LOG__\n$rawLog")!!
        listOf("private-api-value", "private-bearer-value", "private password with spaces",
            "private-url-password", "private-terminal-title").forEach { secret ->
            assertFalse("Expanded/copy log leaked $secret", result.logText.contains(secret))
        }
        assertTrue(result.logText.contains("api_key=[숨김]"))
        assertTrue(result.logText.contains("공개\t처리 내역\n"))
        assertTrue(result.logText.contains("파일 검사 11"))
        assertFalse(result.logText.any { (it.isISOControl() && it != '\n' && it != '\t') || Character.getType(it) == Character.FORMAT.toInt() })
    }

    @Test
    fun fullLogIsRedactedBeforeApplyingTheFortyThousandCharacterTail() {
        val log = "token=${"PRIVATE".repeat(8_000)}\n" + "normal\n".repeat(8_000)
        val result = WorkProgressParser.parse("operation=restore\n__ST_LAUNCHER_LOG__\n$log")!!
        assertTrue(result.logText.length <= 40_000)
        assertFalse(result.logText.contains("PRIVATE"))
        assertTrue(result.logText.endsWith("normal"))
    }
}
