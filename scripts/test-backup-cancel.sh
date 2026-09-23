#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANAGER="$ROOT_DIR/app/src/main/assets/manager.sh"

if [[ "${1:-}" == --worker ]]; then
    source "$MANAGER"
    ensure_archive_tools() { :; }
    ensure_import_runtime() { :; }
    backup_selected user_data 0 ''
    exit 0
fi

if [[ "$OSTYPE" == msys* ]]; then
    echo 'SKIP: full backup cancellation requires Linux process identities (covered in CI)'
    exit 0
fi
for tool in node zip unzip timeout; do command -v "$tool" >/dev/null || { echo "Missing $tool" >&2; exit 1; }; done
export ST_TEST_REAL_ZIP="$(command -v zip)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/st-backup-cancel.XXXXXX")"
RUNNING_PID=""
cleanup() {
    local exit_code=$? diagnostic
    if (( exit_code != 0 )) && [[ -n "${ST_TEST_CASE:-}" ]]; then
        for diagnostic in output cancel-output launcher/run/progress.env; do
            if [[ -f "$ST_TEST_CASE/$diagnostic" ]]; then
                printf '\nFailure diagnostic (%s):\n' "$diagnostic" >&2
                cat "$ST_TEST_CASE/$diagnostic" >&2
            fi
        done
    fi
    if [[ -n "$RUNNING_PID" ]]; then
        kill -TERM "$RUNNING_PID" 2>/dev/null || true
        wait "$RUNNING_PID" 2>/dev/null || true
    fi
    case "$TEST_ROOT" in "${TMPDIR:-/tmp}"/st-backup-cancel.*) rm -rf -- "$TEST_ROOT" ;; esac
}
trap cleanup EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
for phase in compress manifest; do
    export ST_TEST_CASE="$TEST_ROOT/$phase" ST_TEST_ZIP_PHASE="$phase"
    export ST_HOME="$ST_TEST_CASE/install" ST_LAUNCHER_HOME="$ST_TEST_CASE/launcher" ST_DOWNLOAD_DIR="$ST_TEST_CASE/downloads"
    export ST_OPERATION_REQUEST_ID=3e9ec7dd-3242-476b-8847-aed78da162fc
    mkdir -p "$ST_HOME/data/default-user/chats" "$ST_DOWNLOAD_DIR" "$ST_TEST_CASE/bin"
    printf 'original user data\n' > "$ST_HOME/data/default-user/chats/keep.txt"
    printf '{"version":"1.0.0"}\n' > "$ST_HOME/package.json"
    printf 'existing backup must remain\n' > "$ST_DOWNLOAD_DIR/existing.zip"
    # Only replace the external ZIP command's timing. Production planning,
    # Node worker, backup_selected, lock/progress and cleanup all remain real.
    printf '%s\n' '#!/usr/bin/env bash' 'set -e' \
        'if [[ ( "$ST_TEST_ZIP_PHASE" == compress && "${1:-}" != -g ) || ( "$ST_TEST_ZIP_PHASE" == manifest && "${1:-}" == -g ) ]]; then' \
        '  printf ready > "$ST_TEST_CASE/zip-ready"' \
        '  sleep 120' \
        'fi' \
        'exec "$ST_TEST_REAL_ZIP" "$@"' > "$ST_TEST_CASE/bin/zip"
    chmod 700 "$ST_TEST_CASE/bin/zip"
    PATH="$ST_TEST_CASE/bin:$PATH" timeout --kill-after=3s 30s bash "${BASH_SOURCE[0]}" --worker "$MANAGER" > "$ST_TEST_CASE/output" 2>&1 &
    RUNNING_PID=$!
    ready=0
    for _ in $(seq 1 200); do
        if [[ -f "$ST_TEST_CASE/zip-ready" ]]; then ready=1; break; fi
        kill -0 "$RUNNING_PID" 2>/dev/null || break
        sleep .05
    done
    if (( ! ready )); then cat "$ST_TEST_CASE/output" >&2; fail "$phase: real backup did not reach ZIP worker"; fi
    identity="$(cat "$ST_LAUNCHER_HOME/run/operation.lock/pid"):$(cat "$ST_LAUNCHER_HOME/run/operation.lock/start_ticks")"
    bash "$MANAGER" cancel "$identity" "$ST_OPERATION_REQUEST_ID" > "$ST_TEST_CASE/cancel-output" 2>&1
    grep -Fxq cancel_requested=1 "$ST_TEST_CASE/cancel-output" || fail "$phase: cancel request rejected"
    code=0
    wait "$RUNNING_PID" || code=$?
    RUNNING_PID=""
    if [[ "$code" != 130 ]]; then cat "$ST_TEST_CASE/output" >&2; fail "$phase: expected cancellation 130, got $code"; fi
    grep -Fxq status=cancelled "$ST_LAUNCHER_HOME/run/progress.env" || fail "$phase: final result was not cancellation"
    grep -Fxq error_code=OPERATION_CANCELLED "$ST_LAUNCHER_HOME/run/progress.env" || fail "$phase: cancellation became a ZIP/network error"
    [[ ! -d "$ST_LAUNCHER_HOME/run/operation.lock" ]] || fail "$phase: operation lock was retained"
    [[ "$(cat "$ST_HOME/data/default-user/chats/keep.txt")" == 'original user data' ]] || fail "$phase: source changed"
    [[ "$(cat "$ST_DOWNLOAD_DIR/existing.zip")" == 'existing backup must remain' ]] || fail "$phase: existing backup changed"
    [[ "$(find "$ST_DOWNLOAD_DIR" -mindepth 1 -maxdepth 1 -printf '%f\n')" == existing.zip ]] || fail "$phase: incomplete backup remains"
    [[ -z "$(find "$ST_LAUNCHER_HOME/backups" -mindepth 1 -print -quit)" ]] || fail "$phase: scratch was not removed"
    echo "PASS: production backup cancellation during $phase preserves originals and completed backups"
done
