package app.tavernbridge.launcher.model

/** Separates the initial loading UI from a real operation that owns the busy state. */
internal class EnvironmentRefreshGate {
    private var initialRefreshPending = true

    fun tryStart(appInForeground: Boolean, isWorking: Boolean, refreshActive: Boolean): Boolean {
        if (!appInForeground) return false
        // A fresh ViewModel starts with isWorking=true to keep actions disabled until
        // installation detection runs. That placeholder must not block its own check.
        // Consume this exception once; later non-refresh work must remain protected.
        if (isWorking && !refreshActive && !initialRefreshPending) return false
        initialRefreshPending = false
        return true
    }
}
