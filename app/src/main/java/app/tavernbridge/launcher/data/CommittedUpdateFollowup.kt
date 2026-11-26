package app.tavernbridge.launcher.data

import app.tavernbridge.launcher.termux.TermuxCommandResult
import kotlinx.coroutines.CancellationException

/** A server/wakelock failure after Git has committed cannot un-apply the update. */
internal suspend fun followCommittedUpdate(
    update: TermuxCommandResult,
    restart: suspend () -> TermuxCommandResult,
): TermuxCommandResult {
    if (!update.isSuccess) return update
    val resumed = try { restart().isSuccess }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { false }
    return update.copy(stdout = update.stdout + "\nserver_resume=" + (if (resumed) "STARTED" else "FAILED") + "\n")
}
