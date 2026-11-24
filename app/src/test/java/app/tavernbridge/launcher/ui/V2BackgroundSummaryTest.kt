package app.tavernbridge.launcher.ui

import app.tavernbridge.launcher.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class V2BackgroundSummaryTest {
    private val healthy = LauncherUiState(backupStorageReady = true, environmentChecked = true,
        environment = EnvironmentStatus(termuxInstalled = true),
        backgroundStatus = BackgroundStatus(termuxBatteryUnrestricted = true, wakeLockEnabled = true))

    @Test fun choosingNotToUseWakeLockIsNotABackgroundFailure() {
        assertEquals(BackgroundAssessment.READY, healthy.backgroundAssessment())
        assertEquals(BackgroundAssessment.READY, healthy.copy(backgroundStatus = healthy.backgroundStatus.copy(wakeLockEnabled = false)).backgroundAssessment())
    }

    @Test fun absentMeasurementsAreNotFailuresAndMissingTermuxCannotBeReportedHealthy() {
        assertEquals(BackgroundAssessment.UNKNOWN, healthy.copy(backupStorageReady = null).backgroundAssessment())
        assertEquals(BackgroundAssessment.UNKNOWN, healthy.copy(backgroundStatus = BackgroundStatus()).backgroundAssessment())
        assertEquals(BackgroundAssessment.UNKNOWN, LauncherUiState().backgroundAssessment())
        assertEquals(BackgroundAssessment.NOT_INSTALLED, healthy.copy(environment = EnvironmentStatus()).backgroundAssessment())
    }

    @Test fun realRestrictionsAreStillActionable() {
        assertEquals(BackgroundAssessment.ATTENTION, healthy.copy(backupStorageReady = false).backgroundAssessment())
        assertEquals(BackgroundAssessment.ATTENTION,
            healthy.copy(backupStorageReady = null, backgroundStatus = BackgroundStatus(termuxBatteryUnrestricted = false)).backgroundAssessment())
    }
}
