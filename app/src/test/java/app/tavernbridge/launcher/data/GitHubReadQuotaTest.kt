package app.tavernbridge.launcher.data

import org.junit.Assert.*
import org.junit.Test

class GitHubReadQuotaTest {
    @Test fun remainingAndSecondaryRetryAreRespectedAcrossManualChecks() {
        var now = 100L
        val quota = GitHubReadQuota { now }
        assertTrue(quota.acquire())
        quota.observe(200, "3", "115", null)
        repeat(2) { assertTrue(quota.acquire()) }
        assertFalse(quota.acquire())
        now = 115
        assertTrue(quota.acquire())
        quota.observe(429, null, null, "120")
        now = 234
        assertFalse(quota.acquire())
        now = 235
        assertTrue(quota.acquire())
    }
    @Test fun sparseResponsesStillHaveAFiniteBudgetAndRegular403IsNotRateLimit() {
        val quota = GitHubReadQuota { 100L }
        quota.observe(403, null, null, null)
        repeat(59) { assertTrue(quota.acquire()); quota.observe(200, "malformed", "??", null) }
        assertFalse(quota.acquire())
    }
}
