#!/usr/bin/env bash
# Sourced by test-manager.sh. All mutations stay inside its mktemp fixture.

if [[ "$OSTYPE" == msys* ]]; then
    echo "NOTE: MSYS uses atomic marker emulation; Linux CI verifies real lock symlinks."
    # Git Bash without Windows symlink privileges cannot create a dangling
    # symlink. Emulate only this atomic marker; Linux CI uses real ln/readlink.
    ln() {
        if [[ "${1:-}" == -s && "${3:-}" == */.reclaim ]]; then
            (set -C; printf '%s\n' "$2" > "$3")
        else
            command ln "$@"
        fi
    }
    readlink() {
        if [[ $# == 1 && "$1" == */.reclaim && -f "$1" && ! -L "$1" ]]; then
            cat "$1"
        else
            command readlink "$@"
        fi
    }
fi

regression_fixture() {
    local name="$1"
    export ST_HOME="$TEST_ROOT/regression/$name/install"
    export ST_LAUNCHER_HOME="$TEST_ROOT/regression/$name/launcher"
    export ST_DOWNLOAD_DIR="$TEST_ROOT/regression/$name/downloads"
    source "$ROOT_DIR/app/src/main/assets/manager.sh"
    mkdir -p "$ST_HOME/data" "$DOWNLOAD_DIR"
}

regression_fixture metadata
poison='BASH_VERSINFO[$(printf METADATA_PAYLOAD_EXECUTED >&2)0]'
set +e
decimal_result="$(unsigned_decimal "$poison" 2>&1)"
decimal_status=$?
set -e
[[ "$decimal_status" != 0 && -z "$decimal_result" ]] || fail "arithmetic metadata payload was evaluated"
[[ "$(unsigned_decimal 000008)" == 8 ]] || fail "numeric metadata was interpreted as octal"
if unsigned_decimal 9999999999999999999 >/dev/null; then fail "overflowing metadata was accepted"; fi
list_result="$(
    : > "$DOWNLOAD_DIR/SillyTavern-Launcher-20260101-000000.zip"
    ensure_archive_tools() { :; }
    unzip() { printf 'format=st-launcher-backup-v1\nitems=user_data\nsecrets=0\nexpanded_bytes=%s\nentry_count=1\n' "$poison"; }
    list_backups 2>&1
)"
[[ -z "$list_result" ]] || fail "list_backups evaluated or displayed unsafe numeric metadata"

regression_fixture logs
printf 'server-critical-error\n' > "$SERVER_LOG"
for line in $(seq 1 700); do printf 'access-%s\n' "$line" >> "$ST_HOME/access.log"; done
log_result="$(server_log 10)"
grep -Fq 'server-critical-error' <<< "$log_result" || fail "access.log displaced the server error"
grep -Fq 'access-700' <<< "$log_result" || fail "latest access log line was not included"
[[ "$(grep -c '^access-' <<< "$log_result")" == 2 ]] || fail "access log line budget was not bounded"
head -c 200000 /dev/zero | tr '\0' x > "$SERVER_LOG"
[[ "$(server_log 500 | wc -c)" -lt 83000 ]] || fail "server log byte budget was not bounded"

regression_fixture locks
bash -c 'trap "exit 0" TERM; while :; do sleep 1; done' "$MANAGER_SCRIPT_PATH" &
REGRESSION_MANAGER_PID=$!
for _ in $(seq 1 40); do is_manager_process "$REGRESSION_MANAGER_PID" && break; sleep 0.05; done
is_manager_process "$REGRESSION_MANAGER_PID" || fail "manager process identity was not recognized"
mkdir -p "$LOCK_DIR"
printf '%s\n' "$REGRESSION_MANAGER_PID" > "$LOCK_DIR/pid"
printf 'start\n' > "$LOCK_DIR/operation"
printf '%s\n' "$(process_start_ticks "$REGRESSION_MANAGER_PID")" > "$LOCK_DIR/start_ticks"
operation_active || fail "valid operation identity was not recognized"
printf '0\n' > "$LOCK_DIR/start_ticks"
if operation_active; then fail "operation PID start-time mismatch was accepted"; fi
rm -f "$LOCK_DIR/start_ticks"
operation_active || fail "live legacy manager did not conservatively block a new writer"
set +e
(trap - EXIT; cancel_operation) > "$TEST_ROOT/legacy-cancel.log" 2>&1
legacy_cancel_status=$?
set -e
[[ "$legacy_cancel_status" == 6 ]] || fail "legacy lock without identity could be cancelled"
kill -0 "$REGRESSION_MANAGER_PID" || fail "legacy cancellation killed a live process"
printf '%s\n' "$UNRELATED_PID" > "$LOCK_DIR/pid"
printf '%s\n' "$(process_start_ticks "$UNRELATED_PID")" > "$LOCK_DIR/start_ticks"
if operation_active; then fail "unrelated reused operation PID was treated as active"; fi
set +e
(trap - EXIT; cancel_operation) > "$TEST_ROOT/stale-cancel.log" 2>&1
stale_cancel_status=$?
set -e
[[ "$stale_cancel_status" == 6 ]] || fail "stale operation cancellation was not refused"
kill -0 "$UNRELATED_PID" || fail "stale operation cancellation killed an unrelated process"
printf '%s\n' "$REGRESSION_MANAGER_PID" > "$LOCK_DIR/pid"
printf '%s\n' "$(process_start_ticks "$REGRESSION_MANAGER_PID")" > "$LOCK_DIR/start_ticks"
kill -TERM "$REGRESSION_MANAGER_PID"
wait "$REGRESSION_MANAGER_PID" 2>/dev/null || true
REGRESSION_MANAGER_PID=""
(
    CURRENT_OPERATION=repair
    acquire_operation
    [[ "$(cat "$LOCK_DIR/pid")" == "$$" ]] || fail "stale lock was not replaced by its new owner"
) > "$TEST_ROOT/stale-acquire.log" 2>&1
[[ ! -d "$LOCK_DIR" ]] || fail "owned operation lock was not released"
printf 'owned-progress\n' > "$PROGRESS_FILE"
printf 'owned-result\n' > "$LAST_RESULT_FILE"
set +e
(trap - EXIT; CURRENT_OPERATION=restore; command_cleanup 11) > "$TEST_ROOT/busy-result.log" 2>&1
busy_status=$?
set -e
[[ "$busy_status" == 11 ]] || fail "busy request did not preserve its exit code"
grep -Fxq owned-progress "$PROGRESS_FILE" || fail "busy request overwrote active progress"
grep -Fxq owned-result "$LAST_RESULT_FILE" || fail "busy request overwrote active result"

regression_fixture lock-race
mkdir -p "$LOCK_DIR"
printf '999999999\n' > "$LOCK_DIR/pid"
printf '0\n' > "$LOCK_DIR/start_ticks"
set +e
(
    set -e
    mv() {
        command mv "$@" || return $?
        if [[ "${1:-}" == -T && "${2:-}" == "$LOCK_DIR" ]]; then
            printf 'changed-owner\n' > "$3/pid"
        fi
    }
    CURRENT_OPERATION=repair
    acquire_operation
) > "$TEST_ROOT/lock-race.log" 2>&1
race_status=$?
set -e
[[ "$race_status" == 11 ]] || fail "changed lock ownership was not rejected"
grep -Fxq changed-owner "$LOCK_DIR/pid" || fail "a changed foreign lock was deleted during stale cleanup"

prepare_full_recovery_fixture() {
    local work="$BACKUP_DIR/restore-work-$$"
    mkdir -p "$work/rollback"
    track_current_work_dir "$work"
    printf 'original-data\n' > "$ST_HOME/data/value.txt"
    printf 'original-hash\n' > "$DEPENDENCY_HASH_FILE"
    prepare_restore_transaction full "$work/rollback"
    RESTORE_HAD_INSTALLATION=1
    RESTORE_DEPENDENCY_SAVED=1
    cp -a "$DEPENDENCY_HASH_FILE" "$RESTORE_ROLLBACK_DIR/dependency-lock.sha256"
    write_restore_journal
    RESTORE_TRANSACTION_ACTIVE=1
    mv "$ST_HOME" "$RESTORE_ROLLBACK_DIR/full-install"
    RESTORE_FULL_ORIGINAL_MOVED=1
    mkdir -p "$ST_HOME/data"
    RESTORE_INSTALLATION_ID="$(restore_file_identity "$ST_HOME")"
    write_restore_journal
    printf 'new-partial-data\n' > "$ST_HOME/data/value.txt"
    printf 'new-hash\n' > "$DEPENDENCY_HASH_FILE"
    CURRENT_OPERATION=restore
    write_progress 50 applying fixture running
    trap 'operation_cleanup $?' EXIT
    trap 'exit 130' INT TERM
}

regression_fixture restore-term
set +e
(
    set -e
    prepare_full_recovery_fixture
    kill -TERM "$BASHPID"
) > "$TEST_ROOT/restore-term.log" 2>&1
restore_term_status=$?
set -e
[[ "$restore_term_status" == 130 ]] || fail "interrupted restore did not preserve interruption status"
grep -Fxq original-data "$ST_HOME/data/value.txt" || fail "TERM did not restore original installation"
grep -Fxq original-hash "$DEPENDENCY_HASH_FILE" || fail "TERM did not restore dependency hash"
[[ ! -d "$BACKUP_DIR/restore-work-$$" ]] || fail "successful restore rollback left a temporary snapshot"

regression_fixture restore-rollback-failure
set +e
(
    set -e
    prepare_full_recovery_fixture
    mv() {
        [[ "${1:-}" == "$RESTORE_ROLLBACK_DIR/full-install" ]] && return 1
        command mv "$@"
    }
    false
) > "$TEST_ROOT/restore-rollback-failure.log" 2>&1
rollback_failure_status=$?
set -e
[[ "$rollback_failure_status" == 25 ]] || fail "failed restore rollback did not report recovery required"
grep -Fxq original-data "$BACKUP_DIR/restore-work-$$/rollback/full-install/data/value.txt" || fail "failed rollback deleted its original snapshot"
[[ -f "$BACKUP_DIR/restore-work-$$/recovery.env" ]] || fail "failed rollback deleted its recovery journal"
grep -Fxq error_code=RESTORE_ROLLBACK_REQUIRED "$LAST_RESULT_FILE" || fail "failed rollback result lost its error code"

regression_fixture restore-partial-failure
set +e
(
    set -e
    work="$BACKUP_DIR/restore-work-$$"
    mkdir -p "$work/rollback"
    track_current_work_dir "$work"
    printf 'original-partial\n' > "$ST_HOME/data/value.txt"
    prepare_restore_transaction partial "$work/rollback"
    normalize_restore_paths data data/default-user/extensions data
    [[ "${#RESTORE_AFFECTED_PATHS[@]}" == 1 ]] || fail "overlapping restore paths were not normalized"
    cp -a "$ST_HOME/data" "$work/rollback/data"
    RESTORE_ORIGINAL_PATHS=(data)
    write_restore_journal
    RESTORE_TRANSACTION_ACTIVE=1
    CURRENT_OPERATION=restore
    write_progress 58 applying fixture running
    trap 'operation_cleanup $?' EXIT
    rm -rf "$ST_HOME/data"
    mkdir -p "$ST_HOME/data"
    printf 'incomplete-new-data\n' > "$ST_HOME/data/value.txt"
    false
) > "$TEST_ROOT/restore-partial-failure.log" 2>&1
partial_failure_status=$?
set -e
[[ "$partial_failure_status" != 0 ]] || fail "partial restore failure was reported as success"
grep -Fxq original-partial "$ST_HOME/data/value.txt" || fail "unexpected partial restore failure lost original data"
[[ ! -d "$BACKUP_DIR/restore-work-$$" ]] || fail "successful partial rollback left a temporary snapshot"

regression_fixture git-validation
printf '{}\n' > "$ST_HOME/package.json"
printf '// server\n' > "$ST_HOME/server.js"
printf '# start\n' > "$ST_HOME/start.sh"
if valid_full_installation "$ST_HOME"; then fail "full restore accepted a folder without Git metadata"; fi
git -C "$ST_HOME" init -q
git -C "$ST_HOME" -c core.autocrlf=false add package.json server.js start.sh
git -C "$ST_HOME" -c user.name=Test -c user.email=test@example.invalid commit -qm initial
valid_full_installation "$ST_HOME" || fail "valid Git full installation was rejected"

if command -v zip >/dev/null 2>&1; then
    source "$ROOT_DIR/scripts/test-manager-zip-regressions.sh"
fi
echo "manager metadata, lock, recovery, and log regressions passed"
