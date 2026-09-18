package app.tavernbridge.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkingElapsedTimeTest {
    @Test fun phaseAndCompoundOperationChangesKeepTheOriginalStart() {
        val started = 10_000L
        listOf("stop", "backup", "update", "start", "inspect").forEachIndexed { index, operation ->
            val laterStepStart = started + (index + 1) * 60_000L
            assertEquals(operation, started, resolveWorkingStartedAtMillis(started, laterStepStart, laterStepStart))
        }
    }

    @Test fun processRecoveryUsesTheReportedWholeOperationStart() {
        assertEquals(10_000L, resolveWorkingStartedAtMillis(0L, 10_000L, 600_000L))
    }

    @Test fun lateProgressAfterProcessRecoveryCanReplaceTheTemporaryFallback() {
        val fallback = resolveWorkingStartedAtMillis(0L, 0L, 600_000L)
        assertEquals(600_000L, fallback)
        assertEquals(10_000L, resolveWorkingStartedAtMillis(0L, 10_000L, fallback))
    }

    @Test fun timeoutReconnectKeepsTheKnownLocalStart() {
        assertEquals(10_000L, resolveWorkingStartedAtMillis(10_000L, 300_000L, 600_000L))
    }

    @Test fun newOperationDoesNotInheritThePreviousOperationsSavedStart() {
        assertEquals(600_000L, resolveWorkingStartedAtMillis(600_000L, 10_000L, 600_000L))
    }

    @Test fun invalidStartsDoNotCreateAnEpochSizedDuration() {
        assertEquals(20_000L, resolveWorkingStartedAtMillis(-1L, -1L, 20_000L))
        assertEquals(0L, resolveWorkingStartedAtMillis(0L, 0L, -1L))
        assertEquals("경과 시간 확인 중", workingElapsedLabel(0L, 600_000L))
    }

    @Test fun elapsedLabelIncludesSecondsMinutesAndHours() {
        assertEquals("0초 경과", workingElapsedLabel(10_000L, 10_999L))
        assertEquals("59초 경과", workingElapsedLabel(10_000L, 69_000L))
        assertEquals("1분 1초 경과", workingElapsedLabel(10_000L, 71_000L))
        assertEquals("1시간 2분 3초 경과", workingElapsedLabel(10_000L, 3_733_000L))
    }

    @Test fun clockCorrectionCannotShowANegativeElapsedTime() {
        assertEquals("0초 경과", workingElapsedLabel(20_000L, 10_000L))
    }

    @Test fun ordinaryProgressCopiesDoNotResetTheOverallStateStart() {
        val initial = LauncherUiState(workingStartedAtMillis = 10_000L)
        val next = initial.copy(workingLabel = "다음 단계", workProgress = WorkProgress(
            percent = 0, phase = "다음 단계", detail = "", phaseStartedAtMillis = 70_000L))
        assertEquals(10_000L, next.workingStartedAtMillis)
        assertEquals("1분 0초 경과", workingElapsedLabel(next.workingStartedAtMillis, 70_000L))
    }
}
