# Architecture

The launcher is split into two independently versioned halves.

## Android application

- `ui/`: Compose screens and reusable result components
- `LauncherViewModel`: user actions and UI state
- `model/OperationModels`: persistent operation results and safe retry actions
- `data/LauncherRepository`: orchestration and status parsing
- `data/ManagerBootstrap`: compressed script transport and atomic installation
- `data/OperationResultStore`: process-death-safe storage for the last completed result
- `termux/LauncherErrorCode`: manager protocol error parsing and legacy exit-code fallback
- `termux/TermuxCommandAwaiter`: response deadlines without swallowing caller cancellation
- `termux/`: Termux `RUN_COMMAND` transport and callbacks
- `security/SensitiveText`: shared secret redaction for stored results and diagnostic output

The Android app never reads Termux private storage directly. All work in the Termux environment goes through the documented `RUN_COMMAND` intent.

## Termux manager

`app/src/main/assets/manager.sh` is installed as:

```text
~/.st-launcher/manager.sh
```

Command groups (arguments and defaults are defined by the dispatcher at the end of `manager.sh`):

- Installation and repair: `doctor`, `install`, `repair`, `reset-installation`
- Server lifecycle: `start`, `stop`, `restart`, `prepare-persistent-start`, `server-task`, `finish-persistent-start`
- Server settings: `save-server-connection`, `wake-lock-on`, `wake-lock-off`
- Updates: `update-preflight` (`check-update` alias), `update`, `switch-branch`, `last-update-record`
- Backups: `backup`, `backup-select`, `list-backups`, `import-backup`, `delete-backup`, `delete-backups`, `restore`
- Storage inspection: `backup-storage-status`, `list-user-folders`, `list-st-files`
- Logs and diagnostics: `server-logs` (`logs` alias), `previous-server-logs`, `history`, `diagnose`
- Operation tracking: `progress`, `cancel`

The manager lives outside `~/SillyTavern`. Replacing the manager script during launcher connection does not itself modify the SillyTavern repository or user data.
Before a new server session starts, up to 800 lines from the current server log are atomically retained at
`~/.st-launcher/logs/server-previous.log` for post-crash inspection.
Current server output and `access.log` are tailed separately so a busy access log does not displace server errors.
Every managed operation writes its final operation name, status, and stable error code to
`~/.st-launcher/run/last-result.env`. Failures also return `error_code=...` to the Android caller.

## Safety boundaries

- Manager bootstrap decodes a gzip/base64 payload into a unique temporary file beside the installed script, checks its Bash syntax, sets private executable permissions, and atomically replaces the script. A failed preparation leaves the previous script unchanged. Compression keeps the transport command below Android's single-argument size limit; atomic replacement avoids truncating a script that another Termux command is reading.
- A command-response timeout does not prove that the Termux operation stopped. The app checks the actual operation state before allowing another operation. Reconnection reports success only for an explicit successful terminal state; an interrupted or unknown result is not silently promoted to success.
- Operation locks use process identity, including PID start ticks and the manager script argument, to reject stale or reused PIDs before treating a command as active or cancelling it.
- Restore validates ZIP paths, entry types, numeric metadata, expanded size, and available space before applying files. A full-install archive must contain a usable extracted Git repository.
- Restore keeps a recovery snapshot and begins a transaction before modifying the installed data. Handled errors and `INT`/`TERM` roll back an in-progress apply. If rollback fails, the recovery directory is preserved instead of being removed as temporary work.
- `SIGKILL`, device shutdown, or Android force-stop cannot execute shell traps. Recovery files may remain, but automatic rollback after these events is not guaranteed.
- Internal safety copies live under `~/.st-launcher/backups`, may contain user data and secrets, are not encrypted, and have no automatic retention policy. They are separate from user-selected ZIP backups in Download.

## Build verification

CI runs the manager shell tests, JVM unit tests, Android lint, and both debug and unsigned release builds.
The release build exercises R8; CI does not receive a production signing key. Production APKs are signed separately before publication.
Debug builds use the `.debug` application ID suffix and the local Android debug key; they cannot replace a production installation.
CI preserves unit test and lint reports alongside the explicitly unsigned release artifact. See [release preparation](RELEASE.md).

## Maintenance

Keep shell operations in the manager and command construction/orchestration in the repository. ViewModel actions own UI state, while models and parsers should remain testable without a device. Reuse the existing operation-result and error-code paths when correcting behavior; new user-facing features are not required for this release.
