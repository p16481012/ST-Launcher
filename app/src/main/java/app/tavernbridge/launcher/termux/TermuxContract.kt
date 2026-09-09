package app.tavernbridge.launcher.termux

internal object TermuxContract {
    const val PACKAGE = "com.termux"
    const val SERVICE_CLASS = "com.termux.app.RunCommandService"
    const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"

    const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    const val EXTRA_COMMAND_DESCRIPTION = "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION"
    const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
    // TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE
    const val EXTRA_RESULT_BUNDLE = "result"

    const val EXTRA_CALLBACK_ID = "app.tavernbridge.launcher.CALLBACK_ID"

    const val BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"
    const val HOME_PATH = "/data/data/com.termux/files/home"
    const val MANAGER_PATH = "$HOME_PATH/.st-launcher/manager.sh"
    const val PROGRESS_PATH = "$HOME_PATH/.st-launcher/run/progress.env"
}
