package app.tavernbridge.launcher.ui.theme

import app.tavernbridge.launcher.model.LauncherAccent
import app.tavernbridge.launcher.model.LauncherTextSize
import app.tavernbridge.launcher.model.ServerReadiness
import app.tavernbridge.launcher.model.AppTheme
import app.tavernbridge.launcher.model.readAccent
import app.tavernbridge.launcher.model.readBrightness
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

    @Test fun allSixteenCombinationsKeepTextLegibleAndStatusIndependentOfAccent() {
        for (dark in listOf(false, true)) for (highContrast in listOf(false, true)) {
            val amber = launcherColors(dark, LauncherAccent.AMBER, highContrast)
            for (accent in LauncherAccent.entries) {
                val c = launcherColors(dark, accent, highContrast)
                assertEquals(amber.live, c.indicator(ServerReadiness.READY))
                assertEquals(amber.danger, c.indicator(ServerReadiness.PORT_CONFLICT))
                assertEquals(c.accent, c.indicator(ServerReadiness.STARTING))
                for (background in listOf(c.surface, c.canvas)) {
                    for (foreground in listOf(c.ink, c.ink2, c.ink3, c.accent, c.live, c.danger, c.disabledInk)) {
                        assertTrue("$accent on $background (highContrast=$highContrast)", contrastRatio(foreground, background) >= 4.5)
                    }
                }
                assertTrue(contrastRatio(c.onAccentWash, c.accentWash) >= 4.5)
                assertTrue(contrastRatio(c.danger, c.dangerWash) >= 4.5)
                assertTrue(contrastRatio(c.onInk, c.ink) >= 4.5)
            }
        }
    }

    @Test fun oldPaletteMigratesWithoutOverridingAnExplicitAccent() {
        assertEquals(AppTheme.LIGHT, readBrightness("AVOCADO"))
        assertEquals(LauncherAccent.GREEN, readAccent(null, "AVOCADO"))
        assertEquals(LauncherAccent.SLATE, readAccent(null, "BLUEBERRY"))
        assertEquals(LauncherAccent.WINE, readAccent("WINE", "AVOCADO"))
        assertEquals(AppTheme.SYSTEM, readBrightness(null))
        assertEquals(LauncherAccent.AMBER, readAccent(null, null))
    }
}
