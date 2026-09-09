#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/st-launcher-tests.XXXXXX")"
MANAGED_PID=""
UNRELATED_PID=""
REGRESSION_MANAGER_PID=""

cleanup() {
    [[ -n "$MANAGED_PID" ]] && kill "$MANAGED_PID" 2>/dev/null || true
    [[ -n "$UNRELATED_PID" ]] && kill "$UNRELATED_PID" 2>/dev/null || true
    [[ -n "$REGRESSION_MANAGER_PID" ]] && kill "$REGRESSION_MANAGER_PID" 2>/dev/null || true
    [[ "$TEST_ROOT" == */st-launcher-tests.* && "$TEST_ROOT" != / ]] && rm -rf -- "$TEST_ROOT"
}
trap cleanup EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

if [[ "${CI:-false}" == true ]]; then
    for archive_tool in zip unzip zipinfo; do
        command -v "$archive_tool" >/dev/null 2>&1 || fail "CI requires $archive_tool for ZIP integration tests"
    done
fi

export ST_HOME="$TEST_ROOT/SillyTavern"
export ST_LAUNCHER_HOME="$TEST_ROOT/launcher"
export ST_DOWNLOAD_DIR="$TEST_ROOT/downloads"
export PREFIX="$TEST_ROOT/prefix"
mkdir -p "$ST_HOME" "$PREFIX/etc/apt" "$ST_DOWNLOAD_DIR"

# Sourcing exposes safety helpers without dispatching a manager command.
source "$ROOT_DIR/app/src/main/assets/manager.sh"

probe_attempts=0
curl() {
    probe_attempts=$((probe_attempts + 1))
    (( probe_attempts >= 2 ))
}
probe_termux_repository "https://mirror.example/termux-main" ||
    fail "repository probe did not fall back from InRelease to Release"
[[ "$probe_attempts" == "2" ]] ||
    fail "repository probe did not try both metadata files"
unset -f curl

for line in $(seq 1 900); do
    printf 'server-session-line-%s\n' "$line" >> "$SERVER_LOG"
done
archive_server_log
grep -Fq 'server-session-line-900' "$PREVIOUS_SERVER_LOG" ||
    fail "previous server log did not retain the newest line"
grep -Fq 'server-session-line-101' "$PREVIOUS_SERVER_LOG" ||
    fail "previous server log did not retain the configured tail"
if grep -Fxq 'server-session-line-100' "$PREVIOUS_SERVER_LOG"; then
    fail "previous server log retained more than the configured tail"
fi
previous_output="$(previous_server_log 20)"
grep -Fq '이전 서버 세션' <<< "$previous_output" ||
    fail "previous server log output did not include the capture header"
grep -Fq 'server-session-line-900' <<< "$previous_output" ||
    fail "previous server log output did not include the latest line"

[[ "$(manager_error_code restore 29)" == "RESTORE_NO_SPACE" ]] ||
    fail "restore no-space error code is not operation-specific"
[[ "$(manager_error_code update 33)" == "UPDATE_SERVER_FAILED" ]] ||
    fail "update server error code is not operation-specific"
write_operation_result "restore" "error" 29 "RESTORE_NO_SPACE"
[[ "$(last_result_value operation)" == "restore" ]] ||
    fail "last operation was not persisted"
[[ "$(last_result_value error_code)" == "RESTORE_NO_SPACE" ]] ||
    fail "last error code was not persisted"
set +e
unknown_output="$(bash "$ROOT_DIR/app/src/main/assets/manager.sh" unknown-command 2>&1)"
unknown_status=$?
set -e
[[ "$unknown_status" == "64" ]] ||
    fail "unknown manager command did not preserve exit code 64"
grep -Fq 'error_code=INVALID_ARGUMENT' <<< "$unknown_output" ||
    fail "manager failure did not return a direct error_code"

printf 'deb https://user-selected.example/termux stable main\n' > "$PREFIX/etc/apt/sources.list"
configure_temporary_main_repository "https://packages-cf.termux.dev/apt/termux-main/"
grep -Fq 'https://packages-cf.termux.dev/apt/termux-main/' "$TEMP_APT_SOURCES" ||
    fail "temporary official package source was not created"
grep -Fq 'https://user-selected.example/termux' "$PREFIX/etc/apt/sources.list" ||
    fail "user-selected Termux package source was modified"
temporary_source="$TEMP_APT_SOURCES"
cleanup_temporary_package_source
[[ ! -e "$temporary_source" ]] ||
    fail "temporary package source was not removed"

if command -v zip >/dev/null 2>&1; then
    sample_dir="$TEST_ROOT/sample"
    sample_zip="$TEST_ROOT/sample.zip"
    mkdir -p "$sample_dir"
    printf '1234567890' > "$sample_dir/file.txt"
    (cd "$sample_dir" && zip -q "$sample_zip" file.txt)
    [[ "$(archive_expanded_size "$sample_zip")" == "10" ]] ||
        fail "archive_expanded_size must not count the ZIP summary twice"
else
    echo "SKIP: ZIP integration tests require zip (non-ZIP safety tests still run)."
fi

tracked_work="$BACKUP_DIR/restore-work-$$"
mkdir -p "$tracked_work"
track_current_work_dir "$tracked_work"
cleanup_current_work_dir
[[ ! -e "$tracked_work" ]] || fail "tracked restore work directory was not removed"

(
    cd "$ST_HOME"
    exec -a "node server.js --port 8000" sleep 30
) &
MANAGED_PID=$!
write_server_pid "$MANAGED_PID"
for _ in $(seq 1 40); do
    is_running && break
    sleep 0.05
done
is_running || fail "managed node server was not recognized"

printf 'start_ticks=0\n' > "$PID_META_FILE"
if is_running; then
    fail "PID start-time mismatch was accepted"
fi
write_server_pid "$MANAGED_PID"
is_running || fail "managed node server was not restored after metadata rewrite"

kill "$MANAGED_PID"
wait "$MANAGED_PID" 2>/dev/null || true
MANAGED_PID=""

(
    cd "$ST_HOME"
    sleep 300
) &
UNRELATED_PID=$!
write_server_pid "$UNRELATED_PID"
if is_running; then
    fail "unrelated process was accepted as the managed server"
fi
terminate_server_process
kill -0 "$UNRELATED_PID" 2>/dev/null ||
    fail "terminate_server_process killed an unrelated reused PID"

# A stale UI request must not reinstall packages when the local and remote commits match.
git -C "$ST_HOME" init -q
git -C "$ST_HOME" config user.email test@example.invalid
git -C "$ST_HOME" config user.name "Launcher Test"
printf '{"version":"1.0.0"}\n' > "$ST_HOME/package.json"
printf '# test\n' > "$ST_HOME/server.js"
printf '# test\n' > "$ST_HOME/start.sh"
git -C "$ST_HOME" add package.json server.js start.sh
git -C "$ST_HOME" commit -qm initial
git -C "$ST_HOME" branch -M release
git -C "$ST_HOME" update-ref refs/remotes/origin/release HEAD
ensure_remote_branch() { return 0; }
is_running() { return 1; }
stopped_result="$({ stop_st; echo "restart_can_continue=1"; })"
grep -Fq 'restart_can_continue=1' <<< "$stopped_result" ||
    fail "stopping an already stopped server terminated the restart command"
noop_result="$(update_st 0 0)"
grep -Fq 'updated=0' <<< "$noop_result" || fail "current commit triggered a redundant update"
grep -Fq 'already_current=1' <<< "$noop_result" || fail "no-op update did not report current state"

source "$ROOT_DIR/scripts/test-manager-regressions.sh"
echo "manager safety helper and regression tests passed"
