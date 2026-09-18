package app.tavernbridge.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class WorkProgressParserTest {
    @Test
    fun verifiedQuietCommandAddsOnlyADetailLogNotFakeMeasuredActivity() {
        val detail = Base64.getEncoder().encodeToString("프로세스 실행 중 · 새 처리 출력 없이 명령 완료 대기".toByteArray())
        val header = "operation=update\noperation_started_at=100\nphase_started_at=120\nphase=용량 계산\nmonitor_operation=update\nmonitor_started_at=100\nmonitor_heartbeat_at=150\nobserved_activity_at=120\nmonitor_wait_seconds=30\nmonitor_wait_detail_b64=$detail"
        val running = WorkProgressParser.parse("$header\nstatus=running\n__ST_LAUNCHER_LOG__\n검사 시작")!!
        assertTrue(running.logText.contains("[대기 확인] 용량 계산"))
        assertTrue(running.logText.contains("명령 완료 대기"))
        assertNull(running.measuredPercent)
        assertEquals(120_000L, running.activityAtMillis)
        val finished = WorkProgressParser.parse("$header\nstatus=success\n__ST_LAUNCHER_LOG__\n검사 완료")!!
        assertFalse(finished.logText.contains("[대기 확인]"))
        val stale = WorkProgressParser.parse(header.replace("monitor_started_at=100", "monitor_started_at=90"))!!
        assertFalse(stale.logText.contains("[대기 확인]"))
    }

    @Test
    fun wholeOperationStartSurvivesPhaseAndHelperTransitions() {
        val direct = WorkProgressParser.parse("operation=update\noperation_started_at=90\nphase_started_at=120")!!
        val helper = WorkProgressParser.parse("operation=update\nphase_started_at=140\nmonitor_operation=update\nmonitor_started_at=90\nmonitor_heartbeat_at=150")!!
        assertEquals(90_000L, direct.operationStartedAtMillis)
        assertEquals(90_000L, helper.operationStartedAtMillis)
        assertEquals(140_000L, helper.phaseStartedAtMillis)
    }

    @Test
    fun staleOrMalformedStartMetadataCannotRestoreAnOldTimer() {
        listOf(
            "operation_started_at=9223372036854775807",
            "operation_started_at=-1",
            "operation_started_at=201",
            "monitor_operation=backup\nmonitor_started_at=100\nmonitor_heartbeat_at=200",
            "monitor_operation=update\nmonitor_started_at=100\nmonitor_heartbeat_at=150",
            "monitor_operation=update\nmonitor_started_at=201\nmonitor_heartbeat_at=202",
        ).forEach { header ->
            val result = WorkProgressParser.parse("operation=update\nphase_started_at=200\n$header")!!
            assertEquals(header, 0L, result.operationStartedAtMillis)
        }
        val staleMonitor = WorkProgressParser.parse("operation=update\noperation_started_at=190\nphase_started_at=200\nmonitor_operation=update\nmonitor_started_at=100\nmonitor_heartbeat_at=210\nobserved_activity_at=210")!!
        assertEquals(190_000L, staleMonitor.operationStartedAtMillis)
        assertEquals(0L, staleMonitor.heartbeatAtMillis)
        assertEquals(0L, staleMonitor.activityAtMillis)
    }

    @Test
    fun unrelatedServerLogsNeverAppearDuringTheCurrentBackupOrUpdate() {
        listOf("update" to 90, "start" to 90, "start" to 100, "backup" to 100).forEach { (serverOperation, serverStart) ->
            val result = WorkProgressParser.parse(
                "operation=update\noperation_started_at=100\nphase_started_at=120\nserver_operation=$serverOperation\nserver_operation_started_at=$serverStart\n__ST_LAUNCHER_SERVER_LOG__\nOLD SERVER OUTPUT\n__ST_LAUNCHER_LOG__\n압축 16 MiB / 64 MiB",
            )!!
            assertFalse(result.logText.contains("OLD SERVER OUTPUT"))
            assertEquals("압축 16 MiB / 64 MiB", result.logText)
        }
    }

    @Test
    fun currentStartupOutputIsRedactedAndActualOperationHistoryComesLast() {
        val result = WorkProgressParser.parse(
            "operation=start\noperation_started_at=100\nphase_started_at=120\nserver_operation=start\nserver_operation_started_at=100\n__ST_LAUNCHER_SERVER_LOG__\nserver boot\napi_key=private-value\n__ST_LAUNCHER_LOG__\nHTTP 응답 확인 완료",
        )!!
        assertTrue(result.logText.contains("server boot"))
        assertFalse(result.logText.contains("private-value"))
        assertTrue(result.logText.endsWith("HTTP 응답 확인 완료"))
    }

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
