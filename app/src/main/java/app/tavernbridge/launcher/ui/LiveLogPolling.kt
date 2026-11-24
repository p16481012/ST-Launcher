package app.tavernbridge.launcher.ui

import kotlinx.coroutines.delay

/** Capture the last lines even after shutdown; only a live server has new lines to poll. */
internal suspend fun watchServerLog(
    running: Boolean,
    read: () -> Unit,
    waitForNext: suspend () -> Unit = { delay(2_000) },
) {
    read()
    while (running) {
        waitForNext()
        read()
    }
}
