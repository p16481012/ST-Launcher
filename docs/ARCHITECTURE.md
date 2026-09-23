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

- Manager bootstrap compresses the scripts together to share repeated helper text without increasing Android's single-argument size budget. It decodes the whole gzip/base64 installer into a private unique temporary file and validates it before execution. Each asset is then staged separately, syntax-checked, set to private executable permissions, and atomically replaced. Failed preparation leaves that asset's previous version unchanged; an invalid bundle is never executed as a partial decode.
- A command-response timeout does not prove that the Termux operation stopped. The app checks the actual operation state before allowing another operation. Reconnection reports success only for an explicit successful terminal state; an interrupted or unknown result is not silently promoted to success.
- Operation locks use process identity, including PID start ticks and the manager script argument, to reject stale or reused PIDs before treating a command as active or cancelling it.
- Restore validates ZIP paths, entry types, numeric metadata, expanded size, and available space before applying files. A full-install archive must contain a usable extracted Git repository.
- Native backup manifests retain their selected restore scope through unambiguous wrapper directories. Ambiguous nested manifests and pre-1.12 `public/` user-data layouts are rejected before changing the installation, rather than silently broadening or partially applying the restore. Created backup manifests list only categories actually included in the archive.
- Backup creation checks the selected source entries and conservative uncompressed size plus ZIP/scratch overhead against the actual destination and work filesystems. The data ZIP is written once in a unique hidden Download staging directory; the previously absent manifest is appended with `zip -g`, not updated by rewriting the whole archive. Only a validated ZIP with unchanged source metadata is published under the final backup filename. Handled failures remove temporary output, not source data. Hard process termination can leave the hidden staging directory behind; unverified directories are not automatically deleted.
- Backup errors distinguish insufficient space (`BACKUP_NO_SPACE`), creation/read/write failure (`BACKUP_CREATE_FAILED`), and changed source (`BACKUP_SOURCE_CHANGED`). Original ZIP diagnostics and its exit code are retained. Failure recovery uses the actual operation log, not the server log. Retry metadata contains only selections and an include-secrets boolean; matching request IDs are required to retry a restored selection, and old or mismatched records return to the selection screen. A failed status refresh does not overturn a successfully created backup.
- `install-validation.sh` checks full-install Node requirements using the installed npm's bundled semver parser before replacing data. An unavailable parser or invalid requirement fails closed. Installation migration hashes original file contents and metadata (including ctime and excluded dependencies) before permitting original-folder deletion; any change or read failure preserves the original. This is a defensive check, not a filesystem-wide lock against other apps writing concurrently.
- Restore keeps a recovery snapshot and begins a transaction before modifying the installed data. Handled errors and `INT`/`TERM` roll back an in-progress apply. If rollback fails, the recovery directory is preserved instead of being removed as temporary work.
- `SIGKILL` and Android force-stop cannot execute shell traps. Atomically published recovery journals are detected on the next launch. `doctor` and diagnosis only report pending recovery; a subsequent modifying command acquires the operation lock and validates the journal and protected paths before attempting rollback. The UI exposes the existing repair action instead of suggesting a fresh installation. Recovery is refused while a server is running.
- Invalid, legacy, ambiguous, or unverifiable recovery records block modifying operations and retain the protected copies for inspection. Committed and completed-rollback journals do not cause a second rollback. Recovery interruption, missing copies, path substitution, and storage/device failure can still require manual recovery; do not delete the reported recovery directory.
- Internal safety copies live under `~/.st-launcher/backups`, may contain user data and secrets, are not encrypted, and have no automatic retention policy. They are separate from user-selected ZIP backups in Download.

## Operation cancellation

The main operation panel and file-management progress panels share a confirmation-based cancellation control. A request remains pending until the worker and any required cleanup have finished; accepting a request is not a completion result. A user cancellation is presented separately from an error.

Cancellation follows the user operation across its Android transfers and Termux subcommands. Android uses cooperative checkpoints rather than cancelling the callback-waiting coroutine while a Termux command may still be writing. A request between subcommands prevents the remaining stages from being dispatched. Reconnected work is targeted by the manager's recorded process identity, not by a guessed PID or operation name.

The manager exposes a process-identity `operation_id` and cancellation state with progress. Disposable compression, transfer and inspection workers may be stopped after identifying their process tree. Package application and installation-data replacement use deferred cancellation and explicit safe boundaries. Cleanup must finish after writers have stopped; rollback failures retain their recovery state and must not be reported as a successful cancellation. A completed operation remains completed if cancellation arrives too late.

## Build verification

CI runs the manager shell tests, JVM unit tests, Android lint, and both debug and unsigned release builds.
Import normalization, ambiguous native archives, source/runtime validation, and interrupted-restore recovery each have isolated regression suites. Windows runs adapt unsupported OS resource limits only in the test harness; Linux CI keeps real limits and Unix filesystem semantics.
Cancellation simulations run twice alongside the existing backup/import/recovery regressions, including real worker processes and isolated temporary data. Android unit tests cover cancellation targeting, cooperative checkpoints, and progress/result handling. These checks do not replace Android/Termux device testing of OS scheduling and document-provider behavior.
The release build exercises R8; CI does not receive a production signing key. Production APKs are signed separately before publication.
Debug builds use the `.debug` application ID suffix and the local Android debug key; they cannot replace a production installation.
CI preserves unit test and lint reports alongside the explicitly unsigned release artifact. See [release preparation](RELEASE.md).

## Maintenance

Keep shell operations in the manager and command construction/orchestration in the repository. ViewModel actions own UI state, while models and parsers should remain testable without a device. Reuse the existing operation-result and error-code paths when correcting behavior; new user-facing features are not required for this release.
