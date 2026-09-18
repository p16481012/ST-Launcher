package app.tavernbridge.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressLogHistoryTest {
    private fun progress(
        operation: String = "update",
        started: Long = 1_000L,
        phase: String = "처리 중",
        log: String,
    ) = WorkProgress(0, phase, "", operation = operation, operationStartedAtMillis = started, logText = log)

    @Test
    fun preservesBackupUpdateStartAndFinalInspectionSnapshots() {
        val history = ProgressLogHistory()
        history.merge(progress("backup", 1_000L, log = "백업 목록 확인\n백업 완료"))
        history.merge(progress("update", 2_000L, log = "패키지 설치\n업데이트 완료"))
        history.merge(progress("start", 3_000L, log = "서버 실행\nHTTP 응답 확인"))
        val result = history.merge(progress("update", 4_100L, log = "최종 환경 검사"))

        assertEquals(
            "백업 목록 확인\n백업 완료\n패키지 설치\n업데이트 완료\n서버 실행\nHTTP 응답 확인\n최종 환경 검사",
            result.logText,
        )
    }

    @Test
    fun repeatedPollAndGrowingSnapshotDoNotDuplicateLines() {
        val history = ProgressLogHistory()
        val first = progress(log = "첫 번째 파일")
        repeat(4) { assertEquals(first.logText, history.merge(first).logText) }
        val grown = first.copy(logText = "첫 번째 파일\n두 번째 파일")
        repeat(4) { assertEquals(grown.logText, history.merge(grown).logText) }
    }

    @Test
    fun phaseChangeKeepsOneSnapshotForTheSameCommand() {
        val history = ProgressLogHistory()
        history.merge(progress(phase = "안전 백업", log = "백업 완료"))
        val next = progress(phase = "패키지 설치", log = "백업 완료\n패키지 설치 중")

        assertEquals(next, history.merge(next))
    }

    @Test
    fun serverOutputGrowingBeforeOperationLogReplacesTheCurrentSnapshot() {
        val history = ProgressLogHistory()
        history.merge(progress("backup", 500L, log = "이전 단계 완료"))
        history.merge(progress("start", 1_000L, log = "서버 출력 1\n서버 응답 확인 중"))
        val next = progress("start", 1_000L, log = "서버 출력 1\n서버 출력 2\n서버 응답 확인 중")

        assertEquals("이전 단계 완료\n${next.logText}", history.merge(next).logText)
        assertEquals("이전 단계 완료\n${next.logText}", history.merge(next).logText)
    }

    @Test
    fun sourceTailTruncationDoesNotDuplicateRetainedLines() {
        val history = ProgressLogHistory()
        history.merge(progress("backup", 500L, log = "안전 백업 완료"))
        history.merge(progress(log = "파일 1\n파일 2\n파일 3"))
        val tailed = progress(log = "파일 2\n파일 3\n파일 4")

        assertEquals("안전 백업 완료\n파일 2\n파일 3\n파일 4", history.merge(tailed).logText)
    }

    @Test
    fun newGenerationOfTheSameOperationKeepsThePreviousSnapshot() {
        val history = ProgressLogHistory()
        history.merge(progress(started = 1_000L, log = "업데이트 완료"))

        assertEquals(
            "업데이트 완료\n환경 확인",
            history.merge(progress(started = 2_125L, log = "환경 확인")).logText,
        )
    }

    @Test
    fun blankSnapshotsDoNotEraseSavedLogs() {
        val history = ProgressLogHistory()
        history.merge(progress(log = "업데이트 완료"))
        assertEquals("업데이트 완료", history.merge(progress(log = "")).logText)
        assertEquals("업데이트 완료", history.merge(progress("start", 2_000L, log = "")).logText)
        assertEquals(
            "업데이트 완료\n서버 실행",
            history.merge(progress("start", 2_000L, log = "서버 실행")).logText,
        )
    }

    @Test
    fun legacySnapshotsWithoutStartTimeAreNotArchivedAtEveryPhase() {
        val history = ProgressLogHistory()
        history.merge(progress(started = 0L, phase = "준비", log = "준비 완료"))
        val next = progress(started = 0L, phase = "확인", log = "준비 완료\n확인 완료")

        assertEquals(next.logText, history.merge(next).logText)
    }

    @Test
    fun keepsOnlyTheNewestFortyThousandCharacters() {
        val history = ProgressLogHistory()
        history.merge(progress("backup", 1_000L, log = "a".repeat(30_000)))
        val result = history.merge(progress("update", 2_000L, log = "b".repeat(30_000))).logText

        assertEquals(40_000, result.length)
        assertEquals("a".repeat(9_999) + "\n" + "b".repeat(30_000), result)
    }

    @Test
    fun redactsEachSnapshotBeforeTheTailCutAndAgainAfterJoining() {
        val history = ProgressLogHistory(maxLength = 32)
        history.merge(progress("backup", 500L, log = "api_key=" + "s".repeat(100)))
        val result = history.merge(progress(log = "완료\npassword=private-value")).logText

        assertFalse(result.contains("ssss"))
        assertFalse(result.contains("private-value"))
        assertTrue(result.contains("[숨김]"))
        assertTrue(result.length <= 32)
        assertEquals(result, history.merge(progress(log = "완료\npassword=private-value")).logText)
    }

    @Test
    fun clearStartsANewTopLevelOperationEvenWhenGenerationMatches() {
        val history = ProgressLogHistory()
        history.merge(progress(log = "이전 작업"))
        history.clear()

        assertEquals("", history.merge(progress(log = "")).logText)
        assertEquals("새 작업", history.merge(progress(log = "새 작업")).logText)
    }

    @Test
    fun mergeChangesOnlyLogTextNotMeasuredProgressOrTimestamps() {
        val history = ProgressLogHistory()
        history.merge(progress("backup", 500L, log = "백업 완료"))
        val incoming = progress(log = "파일 복사 중").copy(
            percent = 25, progressMode = "bytes", completedBytes = 25, totalBytes = 100,
            completedFiles = 1, totalFiles = 4, currentItem = "example.txt",
            heartbeatAtMillis = 2_000L, activityAtMillis = 2_000L, phaseStartedAtMillis = 1_500L,
        )

        assertEquals(incoming.copy(logText = "백업 완료\n파일 복사 중"), history.merge(incoming))
        assertEquals(25, history.merge(incoming).measuredPercent)
    }
}
