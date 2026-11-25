package app.tavernbridge.launcher.ui

/** Only resume a picker, a read-only preview or an independently confirmed action.
 * A missing gate while the server is running must never fall back to [next]. */
internal fun enterStoppedAction(
    running: Boolean,
    stop: ((String, () -> Unit) -> Unit)?,
    label: String,
    next: () -> Unit,
) {
    if (running) stop?.invoke(label, next) else next()
}
