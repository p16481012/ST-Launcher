package app.tavernbridge.launcher.model

import kotlinx.coroutines.CancellationException

internal data class OperationCompletionCheck<T>(val value: T, val refreshFailed: Boolean)

/** Follow-up UI reads cannot change the outcome of an already committed mutation. */
internal suspend fun <T> checkCompletedOperation(fallback: T, inspect: suspend () -> T): OperationCompletionCheck<T> =
    try { OperationCompletionCheck(inspect(), false) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { OperationCompletionCheck(fallback, true) }
