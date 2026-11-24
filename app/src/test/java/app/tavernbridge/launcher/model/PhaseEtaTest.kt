package app.tavernbridge.launcher.model

import org.junit.Assert.*
import org.junit.Test

class PhaseEtaTest {
    @Test fun onlyLiveMeasuredWorkHasAnEstimate() {
        val phase = PhaseEta()
        assertNull(phase.observe(0, 10, 100))
        assertNull(phase.observe(900, 20, 100))
        assertEquals(6L, phase.observe(2_000, 35, 100))
        assertNull(phase.observe(18_000, 35, 100))
        assertNull(phase.observe(19_000, 100, 100))
        assertNull(phase.observe(20_000, 3, 100))
    }
}
