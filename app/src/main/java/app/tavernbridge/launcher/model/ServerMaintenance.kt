package app.tavernbridge.launcher.model

import kotlinx.coroutines.CancellationException

enum class ServerResumeStatus { NOT_NEEDED, STARTED, LEFT_STOPPED, FAILED }
data class ServerMaintenanceResult<T>(val value: T, val resumeStatus: ServerResumeStatus)

/** Pause only a previously running server. Never turn successful data work into failure
 * because a follow-up start failed, or restart after a cancellation/unknown active writer. */
internal suspend fun <T> withPausedServer(
    wasRunning: Boolean,
    stop: suspend () -> Unit,
    canResume: suspend () -> Boolean,
    start: suspend () -> Unit,
    cancellationRequested: () -> Boolean,
    work: suspend () -> T,
): ServerMaintenanceResult<T> {
    if (wasRunning) stop()
    val result = try { work() } catch (cancelled: CancellationException) { throw cancelled
    } catch (error: Exception) {
        if (wasRunning && !cancellationRequested()) {
            try { if (canResume() && !cancellationRequested()) start() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (resumeError: Exception) { error.addSuppressed(resumeError) }
        }
        throw error
    }
    if (!wasRunning) return ServerMaintenanceResult(result, ServerResumeStatus.NOT_NEEDED)
    if (cancellationRequested()) return ServerMaintenanceResult(result, ServerResumeStatus.LEFT_STOPPED)
    return try {
        if (!canResume() || cancellationRequested()) ServerMaintenanceResult(result, ServerResumeStatus.LEFT_STOPPED)
        else { start(); ServerMaintenanceResult(result, ServerResumeStatus.STARTED) }
    } catch (_: UserOperationCancelled) {
        ServerMaintenanceResult(result, ServerResumeStatus.LEFT_STOPPED)
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) {
        ServerMaintenanceResult(result, ServerResumeStatus.FAILED)
    }
}
