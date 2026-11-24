package app.tavernbridge.launcher.model

internal val LauncherUiState.visibleOperation: String?
    get() = if (!isWorking) null else if (dispatchedAtMillis == workingStartedAtMillis)
        dispatchedOperation else workProgress?.operation

internal enum class HomePrimary { START, STARTING, CHAT, LOGS, PORT }
internal enum class HomeStop { HIDDEN, STOP, CANCEL_START, STOPPING, BLOCKED }

internal data class HomeServerActions(val status: ServerReadiness, val primary: HomePrimary,
    val stop: HomeStop, val canPrimary: Boolean, val otherWork: Boolean)

internal fun homeServerActions(readiness: ServerReadiness, processRunning: Boolean, working: Boolean,
    operation: String?, cancelling: Boolean): HomeServerActions {
    val starting = working && operation == "start"
    val stopping = working && operation == "stop"
    val other = working && !starting && !stopping
    val status = if (starting && readiness !in setOf(ServerReadiness.PORT_CONFLICT, ServerReadiness.UNRESPONSIVE))
        ServerReadiness.STARTING else readiness
    val primary = when (status) {
        ServerReadiness.STARTING -> HomePrimary.STARTING
        ServerReadiness.READY -> HomePrimary.CHAT
        ServerReadiness.UNRESPONSIVE -> HomePrimary.LOGS
        ServerReadiness.PORT_CONFLICT -> HomePrimary.PORT
        ServerReadiness.OFFLINE -> HomePrimary.START
    }
    val stop = when {
        stopping || (starting && cancelling) -> HomeStop.STOPPING
        starting -> HomeStop.CANCEL_START
        !processRunning -> HomeStop.HIDDEN
        other -> HomeStop.BLOCKED
        else -> HomeStop.STOP
    }
    return HomeServerActions(status, primary, stop,
        primary != HomePrimary.STARTING && !stopping && (primary != HomePrimary.START || !working), other)
}
