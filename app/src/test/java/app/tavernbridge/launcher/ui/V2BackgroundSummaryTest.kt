package app.tavernbridge.launcher.ui

import app.tavernbridge.launcher.model.BackgroundStatus
import app.tavernbridge.launcher.model.LauncherUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V2BackgroundSummaryTest {
    private val healthy = LauncherUiState(backupStorageReady = true,
        backgroundStatus = BackgroundStatus(termuxBatteryUnrestricted = true, wakeLockEnabled = true))

    @Test fun summaryCannotReportHealthyWhileADetailStillNeedsAttention() {
        assertFalse(needsBackgroundAttention(healthy, 30))
        assertTrue(needsBackgroundAttention(healthy, 31))
        assertTrue(needsBackgroundAttention(healthy.copy(backupStorageReady = null), 30))
        assertTrue(needsBackgroundAttention(healthy.copy(backupStorageReady = false), 30))
        assertTrue(needsBackgroundAttention(healthy.copy(backgroundStatus = healthy.backgroundStatus.copy(wakeLockEnabled = false)), 30))
        assertTrue(needsBackgroundAttention(healthy.copy(backgroundStatus = healthy.backgroundStatus.copy(termuxBatteryUnrestricted = false)), 30))
    }
}
