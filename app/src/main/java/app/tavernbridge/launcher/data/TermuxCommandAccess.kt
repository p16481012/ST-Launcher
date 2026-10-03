package app.tavernbridge.launcher.data

import app.tavernbridge.launcher.model.TermuxSetupState
import app.tavernbridge.launcher.model.UserOperationCancelled

/** Only the exact environment inspection may establish access instead of requiring cached access. */
internal object TermuxCommandAccess {
    /** Cancelling after a verified terminal response does not undo that live connection proof. */
    fun keepsVerifiedResponse(current: TermuxSetupState, error: Exception): Boolean =
        current.verified && error is UserOperationCancelled

    fun blockedState(
        arguments: String,
        current: TermuxSetupState,
        installed: Boolean,
        permissionGranted: Boolean,
    ): TermuxSetupState? = TermuxSetupProbe.prerequisites(installed, permissionGranted)
        ?: current.takeUnless { arguments == "doctor" || it.verified }
}
