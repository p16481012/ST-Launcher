package app.tavernbridge.launcher.data

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedStorageProbeTest {
    @Test fun acceptsOnlyAnUnambiguousSuccessfulRead() {
        SharedStorageProbe.requireReadable("shared_read_ready=1\nshared_read_reason=ok\n")
        for (output in listOf("", "shared_read_ready=1", "shared_read_ready=0\nshared_read_reason=ok",
            "shared_read_ready=1\nshared_read_reason=content_mismatch",
            "shared_read_ready=1\nshared_read_ready=0\nshared_read_reason=ok")) {
            assertThrows(IllegalStateException::class.java) { SharedStorageProbe.requireReadable(output) }
        }
    }

    @Test fun missingPermissionsReadFailuresAndMismatchedNonceHaveActionableErrors() {
        for (reason in listOf("storage_unavailable", "read_denied", "missing", "content_mismatch")) {
            val failure = assertThrows(IllegalStateException::class.java) {
                SharedStorageProbe.requireReadable("shared_read_ready=0\nshared_read_reason=$reason")
            }
            assertTrue(failure.message.orEmpty().startsWith("[SHARED_STORAGE_READ_FAILED]"))
            assertTrue(failure.message.orEmpty().contains("확인"))
        }
    }
}
