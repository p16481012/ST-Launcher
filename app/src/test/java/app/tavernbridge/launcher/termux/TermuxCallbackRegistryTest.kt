package app.tavernbridge.launcher.termux

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class TermuxCallbackRegistryTest {
    private val first = "11111111-2222-3333-4444-555555555555"
    private val second = "11111111-2222-3333-4444-666666666666"

    @Test fun callbackIdentityLivesInDataUriNotJustExtrasOrCounter() {
        assertNotEquals(termuxCallbackUri(first), termuxCallbackUri(second))
        assertEquals(first, verifiedTermuxCallbackId(termuxCallbackUri(first), first))
        assertNull(verifiedTermuxCallbackId(termuxCallbackUri(first), second))
        assertNull(verifiedTermuxCallbackId(null, first))
        assertNull(verifiedTermuxCallbackId(termuxCallbackUri(first), null))
        assertNull(verifiedTermuxCallbackId(termuxCallbackUri(first) + "/extra", first))
        assertNull(verifiedTermuxCallbackId(termuxCallbackUri(first) + "?id=$second", first))
        assertNull(verifiedTermuxCallbackId("https://termux/callback/$first", first))
        assertTrue(runCatching { termuxCallbackUri("../$first") }.isFailure)
    }

    @Test fun freshProcessesDoNotAcceptPredecessorOrUnknownCallbacks() {
        val previous = TermuxCallbackRegistry()
        previous.register(first)
        val fresh = TermuxCallbackRegistry()
        fresh.register(second)
        var delivered = false
        assertFalse(fresh.deliver(first) { delivered = true; true })
        assertFalse(delivered)
        assertTrue(fresh.deliver(second) { delivered = true; true })
        assertTrue(delivered)
    }

    @Test fun repeatedIdsCannotReplaceActiveWaitersOrTheirCleanup() {
        val registry = TermuxCallbackRegistry()
        val request = registry.register(first)
        var releases = 0
        assertTrue(registry.attachCancellation(first) { releases++ })
        assertTrue(runCatching { registry.register(first) }.isFailure)
        assertFalse(registry.attachCancellation(first) { fail("replacement cleanup") })
        assertTrue(registry.isAwaiting(first))
        registry.unregister(request)
        assertEquals(1, releases)
    }

    @Test fun oneTerminalResultIsAcceptedAndTokenReleasedExactlyOnce() {
        val registry = TermuxCallbackRegistry()
        val request = registry.register(first)
        var releases = 0
        registry.attachCancellation(first) { releases++ }
        assertTrue(registry.deliver(first) { true })
        assertFalse(registry.deliver(first) { fail("duplicate result published"); true })
        assertFalse(registry.isAwaiting(first))
        registry.unregister(request)
        registry.unregister(request)
        assertEquals(1, releases)
        assertFalse(registry.deliver(first) { true })
    }

    @Test fun anOldLeaseCannotRemoveANewRegistrationWithTheSameId() {
        val registry = TermuxCallbackRegistry()
        val old = registry.register(first)
        registry.unregister(old)
        val current = registry.register(first)
        registry.unregister(old)
        assertTrue(registry.isAwaiting(first))
        registry.unregister(current)
    }

    @Test fun tokenCleanupFailureDoesNotChangeOutcomeOrKeepRegistration() {
        val registry = TermuxCallbackRegistry()
        val request = registry.register(first)
        registry.attachCancellation(first) { throw IllegalStateException("token already gone") }
        registry.unregister(request)
        assertFalse(registry.isAwaiting(first))
        assertFalse(registry.attachCancellation(first) {})
    }

    @Test fun aRejectedTransportDoesNotMarkTheResponseDelivered() {
        val registry = TermuxCallbackRegistry()
        val request = registry.register(first)
        assertFalse(registry.deliver(first) { false })
        assertTrue(registry.isAwaiting(first))
        assertTrue(registry.deliver(first) { true })
        registry.unregister(request)
    }

    @Test fun uuidRequestsRemainDistinctAcrossExecutorLifetimes() {
        val uris = (1..10_000).map { termuxCallbackUri(UUID.randomUUID().toString()) }
        assertEquals(uris.size, uris.toSet().size)
    }
}
