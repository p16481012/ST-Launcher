package app.tavernbridge.launcher.data

import android.app.Activity
import app.tavernbridge.launcher.termux.TermuxCommandResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CommittedUpdateFollowupTest {
    private fun result(text: String, exit: Int = 0) = TermuxCommandResult("",text,"",exit,Activity.RESULT_OK,"")

    @Test fun keepsTheGitReceiptSeparateFromTheServerResult() = runBlocking {
        val done = result("updated=1\ncommit=abc\n")
        val failed = followCommittedUpdate(done) { result("server error",9) }
        assertTrue(failed.isSuccess)
        assertTrue(failed.stdout.contains("commit=abc"))
        assertTrue(failed.stdout.contains("server_resume=FAILED"))
        val noop = followCommittedUpdate(result("updated=0")) { result("started") }
        assertTrue(noop.stdout.contains("updated=0"))
        assertTrue(noop.stdout.contains("server_resume=STARTED"))
        assertTrue(followCommittedUpdate(done) { error("lost server callback") }.stdout.contains("server_resume=FAILED"))
    }

    @Test fun neverRestartsAfterAnUncommittedFailureOrSwallowsCancellation() = runBlocking {
        val failed = result("",31)
        assertSame(failed,followCommittedUpdate(failed) { error("must not start") })
        try { followCommittedUpdate(result("updated=1")) { throw CancellationException("stop requested") }; fail("lost cancellation") }
        catch (_: CancellationException) { /* existing explicit cancellation recovery owns it */ }
        Unit
    }
}
