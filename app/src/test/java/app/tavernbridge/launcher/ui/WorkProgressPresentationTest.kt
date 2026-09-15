package app.tavernbridge.launcher.ui

import app.tavernbridge.launcher.model.WorkProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkProgressPresentationTest {
    @Test
    fun liveMonitorWithNoNewBytesIsNotClassifiedAsActiveProcessing() {
        val progress = WorkProgress(0, "압축 해제", "", heartbeatAtMillis = 90_000L, activityAtMillis = 40_000L)
        assertEquals(ProgressActivityState.NO_RECENT_ACTIVITY, progress.activityState(100_000L))
    }

    @Test
    fun staleMonitorIsSeparateFromSlowProcessing() {
        val progress = WorkProgress(0, "압축 해제", "", heartbeatAtMillis = 70_000L, activityAtMillis = 90_000L)
        assertEquals(ProgressActivityState.RESPONSE_STALE, progress.activityState(100_000L))
    }

    @Test
    fun missingTelemetryWaitsInsteadOfClaimingWorkIsOngoing() {
        val progress = WorkProgress(0, "준비", "")
        assertEquals(ProgressActivityState.WAITING_FOR_RESPONSE, progress.activityState(100_000L))
    }

    @Test
    fun freshProcessingAndMonitorAreActive() {
        val progress = WorkProgress(0, "압축 해제", "", heartbeatAtMillis = 100_000L, activityAtMillis = 99_000L)
        assertEquals(ProgressActivityState.ACTIVE, progress.activityState(100_000L))
    }

    @Test
    fun backwardClockMovementDoesNotProduceNegativeDuration() {
        val progress = WorkProgress(0, "압축 해제", "", heartbeatAtMillis = 200_000L, activityAtMillis = 200_000L)
        assertEquals(ProgressActivityState.ACTIVE, progress.activityState(100_000L))
        assertEquals("0초", formatProgressDuration(-100L))
    }

    @Test
    fun formatsByteCountsAndLongDurations() {
        assertEquals("0 B", formatProgressBytes(-1L))
        assertEquals("512 B", formatProgressBytes(512L))
        assertEquals("1.5 KiB", formatProgressBytes(1_536L))
        assertEquals("2.0 MiB", formatProgressBytes(2_097_152L))
        assertEquals("1.0 GiB", formatProgressBytes(1_073_741_824L))
        assertEquals("30분 5초", formatProgressDuration(1_805L))
    }

    @Test
    fun untrustedLocalProviderNamesCannotSpoofStatusOrDirection() {
        assertEquals("data/secret.json", formatProgressItem("data/\u202Esecret\n\r\t.json"))
        assertEquals(1_024, formatProgressItem("a".repeat(8_192)).length)
    }

    @Test
    fun ordinarySensitiveFileNamesAreShownButCredentialValuesAreRedacted() {
        assertEquals("data/default-user/secrets.json", formatProgressItem("data/default-user/secrets.json"))
        assertEquals("data/[숨김]", formatProgressItem("data/sk-1234567890abcdefgh"))
        assertEquals("api_key=[숨김]", formatProgressItem("api_key=test-secret-value"))
    }

    @Test
    fun recentHistorySkipsBlankAndSeparatorLinesAndKeepsLatestFiveEvents() {
        val log = """
            [00:01] first

            [00:02] second
            ===== SillyTavern 서버 출력 =====
            ---
            [00:03] third
            [00:04] fourth
            [00:05] fifth
            [00:06] sixth
            ___
        """.trimIndent()
        assertEquals(
            "[00:02] second\n[00:03] third\n[00:04] fourth\n[00:05] fifth\n[00:06] sixth",
            formatRecentProgressLog(log),
        )
    }

    @Test
    fun recentHistoryRedactsCredentialValuesAndRemovesControlSequences() {
        assertEquals(
            "[00:01] api_key=[숨김]\n[00:02] data/secrets.json\n[00:03] [숨김]",
            formatRecentProgressLog("[00:01] api_key=test-secret\n\u001b[31m[00:02] data/\u202Esecrets.json\u001b[0m\n[00:03] sk-1234567890abcdefgh"),
        )
    }

    @Test
    fun recentHistoryIsBoundedAndEmptyLogsRemainEmpty() {
        assertEquals("", formatRecentProgressLog("\n---\n===\n\t\n"))
        assertEquals(400, formatRecentProgressLog("a".repeat(20_000)).length)
    }

}
