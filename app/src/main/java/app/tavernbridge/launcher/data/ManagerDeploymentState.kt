package app.tavernbridge.launcher.data

/** A missing callback never proves a Termux writer has stopped using its staging directory. */
internal class ManagerDeploymentState {
    private var ownsStaging = false
    private var awaitingTerminalResult = false

    val canCleanStaging: Boolean get() = ownsStaging && !awaitingTerminalResult

    fun dispatching() {
        awaitingTerminalResult = true
    }

    fun acknowledged(createsStaging: Boolean, succeeded: Boolean, removesStaging: Boolean = false) {
        // A failed installer shell can leave a child reading the staging script.
        // Retain that small directory rather than racing a potentially live reader.
        awaitingTerminalResult = removesStaging && !succeeded
        // mkdir must have succeeded exclusively. A colliding directory does not belong to us.
        if (createsStaging && succeeded) ownsStaging = true
        if (removesStaging && succeeded) ownsStaging = false
    }
}
