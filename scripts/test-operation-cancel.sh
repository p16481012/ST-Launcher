#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANAGER="$ROOT_DIR/app/src/main/assets/manager.sh"

if [[ "${1:-}" == --worker ]]; then
    case_name="$3"
    CASE_ROOT="$4"
    export ST_HOME="$CASE_ROOT/install" ST_LAUNCHER_HOME="$CASE_ROOT/launcher" ST_DOWNLOAD_DIR="$CASE_ROOT/downloads"
    export ST_OPERATION_REQUEST_ID=11111111-2222-3333-4444-555555555555
    source "$MANAGER"
    mkdir -p "$ST_HOME/data" "$DOWNLOAD_DIR"
    printf original > "$ST_HOME/data/keep"
    operation=backup
    [[ "$case_name" == restore* ]] && operation=restore
    [[ "$case_name" == start-* ]] && operation=start
    begin_operation "$operation"
    case "$case_name" in
        missing-server-metadata)
            # Linux uses real atomic symlinks. MSYS only emulates successful
            # fence publication for this optional-metadata-read regression.
            if [[ "$OSTYPE" == msys* ]]; then ln() { return 0; }; fi
            terminate_request_server "$ST_OPERATION_REQUEST_ID"
            ;;
        stdin)
            run_cancellable bash -c 'read -r value; [[ "$value" == "literal input" ]] || exit 42; printf "stdin-ok\n"' <<'INPUT'
literal input
INPUT
            run_cancellable bash -c 'read -r value; [[ "$value" == "exit-code" ]] && exit 37' <<'INPUT'
exit-code
INPUT
            ;;
        immediate)
            work="$BACKUP_DIR/backup-work-$$"; mkdir "$work"; track_current_work_dir "$work"
            CURRENT_BACKUP_OUTPUT_DIR="$(mktemp -d "$DOWNLOAD_DIR/.SillyTavern-backup.XXXXXXXX")"
            printf partial > "$CURRENT_BACKUP_OUTPUT_DIR/archive.zip"
            run_cancellable bash -c '
                trap "printf unsafe-term-handler > \"$1/term-handler\"; (sleep 100) &" TERM
                bash -c '\''echo "$BASHPID" > "$1/grandchild"; while :; do printf x >> "$1/writes"; sleep .05; done'\'' _ "$1" &
                printf ready > "$1/ready"
                wait
            ' _ "$CASE_ROOT"
            ;;
        deferred|failure)
            run_deferred bash -c 'printf ready > "$1/ready"; while [[ ! -f "$1/release" ]]; do sleep .05; done; printf complete > "$1/step-complete"; exit "$2"' _ "$CASE_ROOT" "$([[ "$case_name" == failure ]] && echo 58 || echo 0)"
            ;;
        committed)
            enter_cancellation_guard
            printf ready > "$CASE_ROOT/ready"
            while [[ ! -f "$CASE_ROOT/release" ]]; do sleep .05; done
            printf updated > "$ST_HOME/data/keep"
            write_progress 100 완료 완료 success
            ;;
        restore|restore-failed)
            work="$BACKUP_DIR/restore-work-$$"; mkdir -p "$work/rollback"; track_current_work_dir "$work"
            prepare_restore_transaction full "$work/rollback"
            RESTORE_HAD_INSTALLATION=1
            mkdir "$work/replacement-install"
            RESTORE_INSTALLATION_ID="$(restore_file_identity "$work/replacement-install")"
            write_restore_journal
            RESTORE_TRANSACTION_ACTIVE=1
            enter_cancellation_guard
            mv "$ST_HOME" "$work/rollback/full-install"; RESTORE_FULL_ORIGINAL_MOVED=1
            mv "$work/replacement-install" "$ST_HOME"; mkdir "$ST_HOME/data"
            printf replacement > "$ST_HOME/data/keep"
            if [[ "$case_name" == restore-failed ]]; then rollback_restore_transaction() { return 1; }; fi
            printf ready > "$CASE_ROOT/ready"
            while [[ ! -f "$CASE_ROOT/release" ]]; do sleep .05; done
            leave_cancellation_guard
            ;;
        success)
            write_progress 100 완료 완료 success
            printf ready > "$CASE_ROOT/ready"
            while [[ ! -f "$CASE_ROOT/release" ]]; do sleep .05; done
            ;;
        start-success|start-pending|start-existing)
            sleep 100 & server_pid=$!
            if [[ "$case_name" == start-existing ]]; then
                ST_OPERATION_REQUEST_ID=aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee write_server_pid "$server_pid"
            else write_server_pid "$server_pid"; fi
            if [[ "$case_name" == start-success ]]; then write_progress 100 완료 완료 success; fi
            printf ready > "$CASE_ROOT/ready"
            while [[ ! -f "$CASE_ROOT/release" ]]; do sleep .05; done
            if [[ "$case_name" == start-pending ]]; then
                cancellation_checkpoint
            else
                kill -0 "$server_pid" || { echo 'server incorrectly killed by late/foreign cancel' >&2; exit 99; }
                kill -TERM "$server_pid"; wait "$server_pid" 2>/dev/null || true
            fi
            ;;
        *) exit 64 ;;
    esac
    exit 0
fi

TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/st-operation-cancel.XXXXXX")"
RUNNING_PID=""
cleanup() {
    local code=$? output attempt
    trap - EXIT
    if (( code != 0 )); then
        printf 'Cancellation test failed: scenario=%s exit=%s\n' "${CASE_ROOT:-uninitialized}" "$code" >&2
        for output in "${CASE_ROOT:-}/output" "${CASE_ROOT:-}/cancel-output" "${CASE_ROOT:-}/wrong-output" \
            "${CASE_ROOT:-}/progress" "${ST_LAUNCHER_HOME:-}/run/progress.env" "${ST_LAUNCHER_HOME:-}/run/last-result.env"; do
            if [[ -f "$output" ]]; then printf '\nDiagnostic: %s\n' "$output" >&2; tail -n 80 "$output" >&2; fi
        done
    fi
    if [[ -n "$RUNNING_PID" ]]; then
        # Let the real manager stop/reap its owned workers before removing the
        # fixture. Killing only its shell would leave grandchildren behind.
        kill -TERM "$RUNNING_PID" 2>/dev/null || true
        for attempt in $(seq 1 100); do kill -0 "$RUNNING_PID" 2>/dev/null || break; sleep .05; done
        kill -KILL "$RUNNING_PID" 2>/dev/null || true
        wait "$RUNNING_PID" 2>/dev/null || true
    fi
    [[ "$TEST_ROOT" == */st-operation-cancel.* && "$TEST_ROOT" != / ]] && rm -rf -- "$TEST_ROOT"
    exit "$code"
}
trap cleanup EXIT
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
wait_file() { local i; for i in $(seq 1 200); do [[ -f "$1" ]] && return 0; sleep .05; done; fail "waiting for $1"; }

# Signal/identity semantics are Linux-specific; MSYS does not expose Linux
# stop states or a faithful child tree. Never replace these tests with mocks.
SKIP_SIGNALS=0
if [[ "$OSTYPE" == msys* ]]; then
    bash -n "$MANAGER"
    grep -Fq '(trap - EXIT INT TERM; "$@") <&0 &' "$MANAGER" || fail 'worker stdin is not preserved'
    echo 'NOTE: MSYS skips STOP/child-tree simulations; Linux CI runs them without mocks.'
    SKIP_SIGNALS=1
fi

fixture() {
    CASE_ROOT="$TEST_ROOT/$1"; mkdir -p "$CASE_ROOT"
    export ST_HOME="$CASE_ROOT/install" ST_LAUNCHER_HOME="$CASE_ROOT/launcher" ST_DOWNLOAD_DIR="$CASE_ROOT/downloads"
    export ST_OPERATION_REQUEST_ID=11111111-2222-3333-4444-555555555555
}
launch() {
    fixture "$1"
    bash "${BASH_SOURCE[0]}" --worker "$MANAGER" "$1" "$CASE_ROOT" > "$CASE_ROOT/output" 2>&1 &
    RUNNING_PID=$!
    wait_file "$CASE_ROOT/ready"
    identity="$(cat "$ST_LAUNCHER_HOME/run/operation.lock/pid"):$(cat "$ST_LAUNCHER_HOME/run/operation.lock/start_ticks")"
}
cancel() {
    local code=0
    bash "$MANAGER" cancel "$identity" "$ST_OPERATION_REQUEST_ID" > "$CASE_ROOT/cancel-output" 2>&1 || code=$?
    (( code == 0 )) || fail "cancel request exited $code"
}
finish() {
    local expected="$1" code=0
    wait "$RUNNING_PID" || code=$?
    RUNNING_PID=""
    [[ "$code" == "$expected" ]] || { cat "$CASE_ROOT/output" >&2; fail "expected $expected, got $code"; }
    [[ ! -e "$ST_LAUNCHER_HOME/run/operation.lock" ]] || fail 'operation lock was retained after completion'
}

fixture missing-server-metadata
# A fresh Bash process is essential: an OR-list around a sourced function or
# subshell would suppress errexit and accidentally hide the original exit 2.
bash "${BASH_SOURCE[0]}" --worker "$MANAGER" missing-server-metadata "$CASE_ROOT" > "$CASE_ROOT/output" 2>&1 || fail 'absent server metadata rejected cancellation'
echo 'PASS: cancellation accepts a request before any server metadata exists'

fixture stdin
code=0
bash "${BASH_SOURCE[0]}" --worker "$MANAGER" stdin "$CASE_ROOT" > "$CASE_ROOT/output" 2>&1 || code=$?
[[ "$code" == 37 ]] && grep -Fxq stdin-ok "$CASE_ROOT/output" || fail 'heredoc input or worker exit code was lost'
echo 'PASS: real worker preserves heredoc stdin and failure exit code'

if (( ! SKIP_SIGNALS )); then
launch immediate
wrong="999999999:0"
bash "$MANAGER" cancel "$wrong" > "$CASE_ROOT/wrong-output"
grep -Fxq cancel_requested=0 "$CASE_ROOT/wrong-output" || fail 'stale identity was accepted'
kill -0 "$RUNNING_PID" || fail 'stale request killed current operation'
wait_file "$CASE_ROOT/grandchild"
grandchild="$(cat "$CASE_ROOT/grandchild")"
cancel
grep -Fxq cancel_requested=1 "$CASE_ROOT/cancel-output" || fail 'valid cancel was not accepted'
finish 130
[[ ! -e "$CASE_ROOT/term-handler" ]] || fail 'TERM handler could fork an untracked writer'
[[ "$(cat "$ST_HOME/data/keep")" == original ]] || fail 'backup cancellation changed source data'
[[ -z "$(find "$ST_DOWNLOAD_DIR" -mindepth 1 -print -quit)" ]] || fail 'incomplete ZIP was retained'
[[ -z "$(find "$ST_LAUNCHER_HOME/backups" -mindepth 1 -print -quit)" ]] || fail 'backup scratch files were retained'
[[ ! -f "/proc/$grandchild/stat" || "$(awk '{sub(/^.*\) /, ""); print $1}' "/proc/$grandchild/stat")" == Z ]] || fail 'child writer outlived lock removal'
grep -Fxq status=cancelled "$ST_LAUNCHER_HOME/run/progress.env" || fail 'cancelled terminal result not recorded'
echo 'PASS: identity-bound cancel stops actual parent/grandchild before deleting scratch, source preserved'
fi

for scenario in deferred failure committed restore restore-failed success; do
    launch "$scenario"
    cancel
    sleep .15
    kill -0 "$RUNNING_PID" || fail "$scenario did not wait for safe boundary"
    [[ -d "$ST_LAUNCHER_HOME/run/operation.lock" ]] || fail "$scenario released lock before safe boundary"
    bash "$MANAGER" progress > "$CASE_ROOT/progress"
    grep -Fxq cancellation_requested=1 "$CASE_ROOT/progress" || fail "$scenario pending cancellation missing"
    touch "$CASE_ROOT/release"
    case "$scenario" in
        deferred) finish 130; [[ -f "$CASE_ROOT/step-complete" ]] || fail 'package step interrupted' ;;
        failure) finish 58; grep -Fxq error_code=BACKUP_NO_SPACE "$ST_LAUNCHER_HOME/run/progress.env" || fail 'actual failure masked by cancel' ;;
        restore) finish 130; [[ "$(cat "$ST_HOME/data/keep")" == original ]] || fail 'restore was not rolled back' ;;
        restore-failed) finish 25; find "$ST_LAUNCHER_HOME/backups" -name recovery.env | grep -q . || fail 'failed rollback journal discarded' ;;
        *) finish 0; grep -Fxq status=success "$ST_LAUNCHER_HOME/run/progress.env" || fail 'committed result lost' ;;
    esac
    echo "PASS: $scenario cancellation boundary and final result"
done

if (( ! SKIP_SIGNALS )); then
for scenario in start-success start-pending start-existing; do
    launch "$scenario"
    cancel
    touch "$CASE_ROOT/release"
    if [[ "$scenario" == start-pending ]]; then finish 130; else finish 0; fi
    echo "PASS: $scenario request-bound server termination/completion fence"
done
fi

fixture queued
bash "$MANAGER" cancel '' "$ST_OPERATION_REQUEST_ID" > "$CASE_ROOT/cancel-output"
code=0
bash "$MANAGER" server-task > "$CASE_ROOT/server-output" 2>&1 || code=$?
[[ "$code" == 130 ]] || fail 'late queued server started after request cancellation'
[[ -f "$ST_LAUNCHER_HOME/run/cancel-request-$ST_OPERATION_REQUEST_ID" ]] || fail 'request tombstone removed before late dispatch'
echo 'PASS: queued server is blocked by durable request UUID cancellation'

echo 'All operation cancellation simulations passed.'
