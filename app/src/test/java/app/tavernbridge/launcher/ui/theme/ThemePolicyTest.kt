package app.tavernbridge.launcher.ui.theme

import app.tavernbridge.launcher.model.AppTheme
import app.tavernbridge.launcher.model.ServerReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePolicyTest {
    @Test fun dynamicColorsAreOnlyForSystemThemeOnAndroid12AndLater() {
        assertFalse(shouldUseDynamicColors(AppTheme.SYSTEM, 30))
        assertTrue(shouldUseDynamicColors(AppTheme.SYSTEM, 31))
        assertTrue(shouldUseDynamicColors(AppTheme.SYSTEM, 36))
        AppTheme.entries.filter { it != AppTheme.SYSTEM }.forEach {
            assertFalse(shouldUseDynamicColors(it, 36))
        }
    }

    @Test fun serverStatesHaveDistinctSemanticMeanings() {
        assertEquals(ServerIndicatorTone.READY, serverIndicatorTone(ServerReadiness.READY))
        assertEquals(ServerIndicatorTone.WORKING, serverIndicatorTone(ServerReadiness.STARTING))
        assertEquals(ServerIndicatorTone.WARNING, serverIndicatorTone(ServerReadiness.UNRESPONSIVE))
        assertEquals(ServerIndicatorTone.ERROR, serverIndicatorTone(ServerReadiness.PORT_CONFLICT))
        assertEquals(ServerIndicatorTone.OFFLINE, serverIndicatorTone(ServerReadiness.OFFLINE))
    }

    @Test fun indicatorsKeepAtLeastThreeToOneContrastAcrossEveryBackgroundLuminance() {
        ServerReadiness.entries.forEach { readiness ->
            for (step in 0..1000) {
                val background = step / 1000.0
                val color = serverIndicatorArgb(readiness, background)
                assertTrue("$readiness contrast at $background",
                    statusColorContrast(statusColorLuminance(color), background) >= 3.0)
            }
            assertNotEquals(serverIndicatorArgb(readiness, 0.0), serverIndicatorArgb(readiness, 1.0))
        }
    }

    @Test fun invalidLuminanceFallsBackToLightSurface() {
        assertEquals(serverIndicatorArgb(ServerReadiness.READY, 1.0), serverIndicatorArgb(ServerReadiness.READY, Double.NaN))
    }
}
