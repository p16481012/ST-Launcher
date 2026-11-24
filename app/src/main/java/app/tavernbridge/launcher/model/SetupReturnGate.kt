package app.tavernbridge.launcher.model

/** A plain resume, rotation or permission dialog must never authorize a Termux probe. */
internal class SetupReturnGate {
    private var opened = false
    private var left = false
    fun launched() { opened = true; left = false }
    fun paused() { if (opened) left = true }
    fun consume(): Boolean {
        if (!opened || !left) return false
        opened = false
        left = false
        return true
    }
}
