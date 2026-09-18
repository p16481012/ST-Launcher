#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/st-import-ambiguous.XXXXXX")"
cleanup() {
    case "$TEST_ROOT" in "${TMPDIR:-/tmp}"/st-import-ambiguous.*) rm -rf -- "$TEST_ROOT" ;; esac
}
trap cleanup EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
put() { mkdir -p "$(dirname "$1")"; printf '%s\n' "$2" > "$1"; }

for scenario in data default-user sibling; do
    case_root="$TEST_ROOT/$scenario"
    set +e
    (
        trap - EXIT INT TERM
        set -Eeuo pipefail
        export ST_HOME="$case_root/install" ST_LAUNCHER_HOME="$case_root/launcher" ST_DOWNLOAD_DIR="$case_root/downloads"
        export PREFIX="$case_root/prefix"
        source "$ROOT_DIR/app/src/main/assets/manager.sh"
        ensure_import_runtime() { :; }
        ensure_archive_tools() { :; }
        is_running() { return 1; }
        begin_operation() {
            CURRENT_OPERATION="$1"; OPERATION_STARTED_EPOCH="$(date +%s)"
            trap 'operation_cleanup $?' EXIT
        }
        # Git Bash cannot apply RLIMIT_FSIZE to native Windows Node.
        if command -v cygpath >/dev/null 2>&1; then ulimit() { :; }; fi
        put "$ST_HOME/package.json" '{"version":"1.0.0"}'
        put "$ST_HOME/server.js" '// fixture only'
        put "$ST_HOME/data/default-user/characters/keep.png" keep-character
        put "$ST_HOME/data/other-user/chats/keep.jsonl" keep-other-user
        source_root="$case_root/payload/$scenario"
        if [[ "$scenario" == sibling ]]; then
            source_root="$case_root/payload/outer/inner"
            put "$case_root/payload/README.txt" 'Sibling makes the archive scope ambiguous'
        fi
        put "$source_root/.st-launcher-manifest" "$(printf 'format=st-launcher-backup-v1\nitems=custom\ncustom=chats\nsecrets=0\n')"
        put "$source_root/data/default-user/chats/incoming.jsonl" incoming
        node "$ROOT_DIR/scripts/import-normalization-fixture.cjs" "$case_root/original.zip" "$case_root/payload" .
        mkdir -p "$DOWNLOAD_DIR"
        cp "$case_root/original.zip" "$DOWNLOAD_DIR/SillyTavern-Import-Fixture.zip"
        sha256sum "$case_root/original.zip" > "$case_root/original.sha256"
        import_backup SillyTavern-Import-Fixture.zip
    ) > "$case_root.output" 2>&1
    code=$?
    set -e
    if [[ "$code" != 23 ]]; then cat "$case_root.output" >&2; fail "$scenario expected format rejection, got $code"; fi
    grep -Fq 'error_code=IMPORT_FORMAT_UNSUPPORTED' "$case_root.output" || fail "$scenario error code missing"
    grep -Fq '모호한 위치' "$case_root.output" || fail "$scenario guidance missing"
    [[ "$(cat "$case_root/install/data/default-user/characters/keep.png")" == keep-character ]] || fail "$scenario removed characters"
    [[ "$(cat "$case_root/install/data/other-user/chats/keep.jsonl")" == keep-other-user ]] || fail "$scenario removed another user"
    sha256sum -c "$case_root/original.sha256" >/dev/null || fail "$scenario changed original ZIP"
    [[ ! -e "$case_root/downloads/SillyTavern-Import-Fixture.zip" ]] || fail "$scenario left disposable ZIP"
    [[ -z "$(find "$case_root/launcher/backups" -mindepth 1 -print -quit)" ]] || fail "$scenario left extraction staging"
    echo "PASS: ambiguous native manifest ($scenario) rejected before data mutation"
done
