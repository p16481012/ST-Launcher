package app.tavernbridge.launcher.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OperationCompletionTest {
    @Test fun followUpFailuresDoNotEraseAnyCompletedMutation() = runBlocking {
        for (operation in listOf("install", "restore", "import-backup", "import-install", "update",
            "switch-branch", "reset-installation", "delete-backup", "backup", "repair", "stop", "start")) {
            val result = checkCompletedOperation("$operation already committed") {
                throw IllegalStateException("doctor timeout")
            }
            assertEquals("$operation already committed", result.value)
            assertTrue(result.refreshFailed)
        }
    }

    @Test fun backgroundStatusFailureKeepsBothPreviousReadModels() = runBlocking {
        val fallback = "previous environment" to "previous background status"
        val result = checkCompletedOperation(fallback) {
            throw SecurityException("permission changed during background status read")
        }
        assertEquals(fallback, result.value)
        assertTrue(result.refreshFailed)
    }

    @Test fun successfulReadReplacesTheFallbackWithoutFailureWarning() = runBlocking {
        val result = checkCompletedOperation("old") { "fresh" }
        assertEquals("fresh", result.value)
        assertFalse(result.refreshFailed)
    }

    @Test fun lifecycleCancellationStillPropagates() = runBlocking {
        val error = runCatching {
            checkCompletedOperation("committed result remains stored") { throw CancellationException("destroyed") }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
    }
}
