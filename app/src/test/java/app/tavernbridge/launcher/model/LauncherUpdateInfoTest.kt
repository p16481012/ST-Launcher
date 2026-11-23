package app.tavernbridge.launcher.model

import org.junit.Assert.*
import org.junit.Test

class LauncherUpdateInfoTest {
    @Test fun comparesNumericVersionsNotStrings() {
        assertEquals(true, newerReleaseVersion("0.3.2", "v0.3.10"))
        assertEquals(false, newerReleaseVersion("0.3.2", "0.3.2"))
        assertEquals(false, newerReleaseVersion("1.0.0", "0.99.99"))
        assertEquals(true, newerReleaseVersion("0.3.2", "1.0.0"))
    }
    @Test fun ambiguousOrPrereleaseVersionsAreNotClaimedNewer() {
        listOf("nightly", "v0.4.0-beta", "0.4", "0.04.0", "99999999999999999999999.0.0").forEach {
            assertNull(newerReleaseVersion("0.3.2", it))
        }
        assertNull(newerReleaseVersion("0.3.2-dev", "0.3.3"))
    }
}
