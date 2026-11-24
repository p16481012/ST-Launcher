package app.tavernbridge.launcher.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.tavernbridge.launcher.data.LauncherRepository
import app.tavernbridge.launcher.model.DiagnosticStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidLocalReportTest {
    @Test fun reportBeforeSetupCollectsLocalResultsButDoesNotInspectTermux() {
        val repository = LauncherRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        val report = runBlocking(Dispatchers.IO) { repository.diagnose(allowTermuxChecks = false) }
        assertTrue(report.items.any { it.id == "command_permission" })
        assertEquals(DiagnosticStatus.INFO, report.items.single { it.id == "termux_checks_skipped" }.status)
        assertFalse(report.items.any { it.id in setOf("git", "node", "github", "diagnose_command") })
        assertFalse(report.items.any { it.title == "빌드 코드" || it.title == "관리 스크립트" })
        assertEquals("확인할 수 없음", report.copyOnlyDetails["관리 스크립트"])
    }
}
