package app.tavernbridge.launcher.model

import org.junit.Assert.*
import org.junit.Test
import app.tavernbridge.launcher.ui.noticeTimeout

class PrototypeInteractionTest {
    @Test fun onlyAnArmedTermuxRoundTripCanConsumeOneCheck() {
        val trip = SetupReturnGate()
        trip.paused(); assertFalse(trip.consume())
        trip.launched(); assertFalse(trip.consume())
        trip.paused(); assertTrue(trip.consume()); assertFalse(trip.consume())
        trip.paused(); assertFalse(trip.consume())
        trip.launched(); trip.paused(); assertTrue(trip.consume())
    }
    @Test fun reselectingTabResetsOnlyItsOwnVisitsAndScrollKey() {
        val tools = LauncherUiState().visit(MainSection.LOGS)
        val detail = tools.visit(MainSection.LOGS, ToolPage.LOGS)
        assertEquals(tools.navigation.entryId, detail.navigation.masterEntry(MainSection.LOGS))
        val top = detail.reselectRoot(MainSection.LOGS)
        assertNotEquals(tools.navigation.entryId, top.navigation.entryId)
        assertEquals(ToolPage.INDEX, top.toolPage)
        assertEquals(MainSection.HOME, top.previousScreen().section)
    }
    @Test fun accessiblyTimedNoticesKeepActionsAvailableLonger() {
        assertEquals(8000L, noticeTimeout("휴지통으로 옮겼어요", "실행 취소"))
        assertEquals(2000L, noticeTimeout("되돌렸어요", null))
        assertEquals(5000L, noticeTimeout("복사했어요", null))
    }
}
