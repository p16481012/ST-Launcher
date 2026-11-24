package app.tavernbridge.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLayoutPolicyTest {
    @Test fun phoneAndNarrowSplitScreenKeepBottomNavigation() {
        listOf(320f, 360f, 411f, 599f, 720f, 839f).forEach {
            assertEquals(LauncherNavigationMode.BOTTOM_BAR, chooseLauncherNavigation(it))
        }
    }

    @Test fun landscapeAndTabletUseRailAtMediumWindowWidth() {
        listOf(840f, 1024f, 1280f).forEach {
            assertEquals(LauncherNavigationMode.RAIL, chooseLauncherNavigation(it))
        }
    }

    @Test fun infoRowsStackForNarrowContainersAndLargeFonts() {
        assertTrue(shouldStackInfoRow(359f, 1f))
        assertFalse(shouldStackInfoRow(360f, 1f))
        assertFalse(shouldStackInfoRow(480f, 1.29f))
        assertTrue(shouldStackInfoRow(480f, 1.3f))
        assertTrue(shouldStackInfoRow(1280f, 2f))
    }

    @Test fun invalidMetricsUseConservativeLayouts() {
        for (width in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f)) {
            assertEquals(LauncherNavigationMode.BOTTOM_BAR, chooseLauncherNavigation(width))
            assertTrue(shouldStackInfoRow(width, 1f))
        }
        assertTrue(shouldStackInfoRow(480f, Float.NaN))
        assertTrue(shouldStackInfoRow(480f, 0f))
    }
}
