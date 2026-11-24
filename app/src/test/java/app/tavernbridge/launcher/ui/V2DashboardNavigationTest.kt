package app.tavernbridge.launcher.ui

import app.tavernbridge.launcher.model.*
import org.junit.Assert.*
import org.junit.Test

class V2DashboardNavigationTest {
    @Test fun installerIsNotAFifthTab() {
        assertEquals(listOf("홈", "백업", "도구", "설정"), launcherTabs.map { it.label })
        assertFalse(MainSection.SETUP in launcherTabs)
    }

    @Test fun skippedFirstConnectionStaysDeferredWithoutAuthorizingCommands() {
        val env = EnvironmentStatus(termuxInstalled = true, commandPermissionGranted = true)
        val waiting = LauncherUiState(section = MainSection.SETTINGS, setupDeferred = true)
            .waitForFirstConnection(env)
        assertEquals(MainSection.SETTINGS, waiting.section)
        assertFalse(waiting.termuxSetup.verified)
        assertEquals(SetupRefresh.LOCAL_ONLY, waiting.setupRefresh(false))
        assertEquals(SetupRefresh.VERIFY_INLINE, waiting.setupRefresh(true))
        assertEquals(MainSection.SETTINGS, sectionAfterEnvironmentCheck(env, true, MainSection.SETTINGS, MainSection.SETTINGS, true))
        assertEquals(MainSection.SETUP, sectionAfterEnvironmentCheck(env, true, MainSection.HOME, MainSection.HOME, false))
    }

    @Test fun dashboardOnlyListsRecognisedFinalEventsNewestFirst() {
        val lines = listOf(
            "[2026-11-22 09:12:11] [start] 작업 종료 · 상태=성공 · 소요 4초",
            "[2026-11-22 09:14:00] [backup] 압축 생성 · secret.json",
            "[2026-11-22 09:15:13] [backup] 작업 종료 · 상태=실패 · 소요 5초",
            "[2026-11-22 09:16:14] [private-name] 작업 종료 · 상태=성공",
        ).joinToString("\n")
        assertEquals(listOf("09:15" to "백업 실패", "09:12" to "서버 시작 완료"), v2RecentActivity(lines))
    }

    @Test fun noMadeUpBackupDates() {
        assertNull(v2DaysSince(0, 550))
        assertNull(v2DaysSince(551, 550))
        assertEquals("오늘", v2DaysSince(100, 100))
        assertEquals("2일 전", v2DaysSince(100, 172800100))
        assertEquals("1일 전", v2DaysSince(86_399_900, 86_400_100, java.time.ZoneOffset.UTC))
    }
}
