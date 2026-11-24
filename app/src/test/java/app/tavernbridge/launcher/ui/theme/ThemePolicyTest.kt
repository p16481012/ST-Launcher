package app.tavernbridge.launcher.ui.theme

import app.tavernbridge.launcher.model.LauncherAccent
import app.tavernbridge.launcher.model.LauncherTextSize
import app.tavernbridge.launcher.model.ServerReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePolicyTest {
    @Test fun explicitAccentHasReadableLightAndDarkVariants() {
        LauncherAccent.entries.forEach {
            assertTrue(statusColorContrast(statusColorLuminance(it.lightArgb), statusColorLuminance(0xfff4f5f7)) >= 4.5)
            assertTrue(statusColorContrast(statusColorLuminance(it.darkArgb), statusColorLuminance(0xff202328)) >= 4.5)
        }
    }

    @Test fun textSizeMultipliesDeviceScaleAndCapsAtTwo() {
        assertEquals(1.38f, LauncherTextSize.LARGE.applyTo(1.2f), .001f)
        assertEquals(2f, LauncherTextSize.MAXIMUM.applyTo(1.8f), .001f)
        assertEquals(.9f, LauncherTextSize.SMALL.applyTo(1f), .001f)
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
