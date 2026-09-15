#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/st-progress-checks.XXXXXX")"
cleanup() { case "$TEST_ROOT" in "${TMPDIR:-/tmp}"/st-progress-checks.*) rm -rf -- "$TEST_ROOT" ;; esac; }
trap cleanup EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
export ST_HOME="$TEST_ROOT/install" ST_LAUNCHER_HOME="$TEST_ROOT/launcher"
export ST_DOWNLOAD_DIR="$TEST_ROOT/downloads" PREFIX="$TEST_ROOT/prefix"
mkdir -p "$ST_HOME/.git" "$ST_HOME/node_modules" "$ST_DOWNLOAD_DIR" "$PREFIX/etc/apt"
printf 'deb https://fixture.invalid/termux-main stable main\n' > "$PREFIX/etc/apt/sources.list"
printf '{"version":"1.2.3","engines":{"node":">=18"}}\n' > "$ST_HOME/package.json"
source "$ROOT_DIR/app/src/main/assets/manager.sh"
# No device, network, Git repository or dependency installation is used here.
begin_operation() { CURRENT_OPERATION="$1"; reset_current_log; }
ensure_remote_branch() { return 0; }
dependencies_ready() { return 0; }
git() {
    if [[ "$1" == -C ]]; then shift 2; fi
    case "$1" in
        --version) echo 'git version 2.50.0' ;;
        status)
            [[ "$CURRENT_OPERATION" == diagnose || "$CURRENT_OPERATION" == update-preflight ]] || fail 'dirty scan ran before operation began'
            echo ' M fixture-private-filename.txt' ;;
        branch) echo release ;;
        rev-parse) [[ "$2" == HEAD ]] && echo aaaaa || echo bbbbb ;;
        rev-list) echo 2 ;;
        show) echo '{"version":"1.2.3","engines":{"node":">=18"}}' ;;
        *) fail "unexpected Git command: $1" ;;
    esac
}
node() {
    case "$1" in
        --version) echo v22.0.0 ;;
        -p) [[ "$2" == *process.versions* ]] && echo 22 || echo '>=18' ;;
        -e) cat >/dev/null; [[ "$2" == *engines* ]] && echo '>=18' || echo 1.2.3 ;;
        *) fail 'unexpected Node invocation' ;;
    esac
}
npm() { echo 10.0.0; }
curl() { return 0; }
ss() { echo 'LISTEN 0 511 127.0.0.1:8000 0.0.0.0:*'; }
pgrep() { echo '123 node server.js'; }
df() { printf 'Filesystem 1024-blocks Used Available Capacity Mounted\nfixture 2097152 1024 1048576 1%% /\n'; }
du() { printf '1048576\t%s\n' "$ST_HOME/node_modules"; }
MANAGER_VERSION=hidden-manager-fixture
LAUNCHER_VERSION=hidden-build-fixture
write_operation_result restore error 29 RESTORE_NO_SPACE
printf 'Error: PRIVATE_ERROR_BODY_DO_NOT_LOG\n' > "$SERVER_LOG"

update_preflight > "$TEST_ROOT/preflight.env"
grep -Fxq 'commits_behind=2' "$TEST_ROOT/preflight.env" || fail 'preflight commit result changed'
grep -Fxq 'dirty_count=1' "$TEST_ROOT/preflight.env" || fail 'preflight dirty count changed'
grep -Fxq 'node_compatible=1' "$TEST_ROOT/preflight.env" || fail 'preflight Node result changed'
grep -Fq '수정 파일 검사 완료' "$LOG_FILE" || fail 'preflight local check result is missing'
grep -Fq '받을 커밋 2개' "$HISTORY_LOG" || fail 'preflight actual commit result missing from history'
grep -Fq 'GiB' "$HISTORY_LOG" || fail 'preflight space summary is not readable'

diagnose > "$TEST_ROOT/diagnose.env"
for pair in github_reachable=1 termux_repo_reachable=1 dependencies_ready=1 server_reachable=1 node_process_count=1 dirty_count=1; do
    grep -Fxq "$pair" "$TEST_ROOT/diagnose.env" || fail "diagnose protocol changed: $pair"
done
for phase in 'GitHub 연결 검사 완료' 'Termux 저장소 검사 완료' 'Git 실행 검사 완료' 'Node.js 검사 완료' 'npm 실행 검사 완료' '실행 패키지 검사 완료' '서버 포트 검사 완료' '서버 HTTP 검사 완료' '서버 프로세스 검사 완료' '최근 오류 검사 완료'; do
    grep -Fq "$phase" "$LOG_FILE" || fail "missing detailed check: $phase"
    grep -Fq "$phase" "$HISTORY_LOG" || fail "missing diagnostic history: $phase"
done
for hidden in hidden-manager-fixture hidden-build-fixture PRIVATE_ERROR_BODY_DO_NOT_LOG fixture-private-filename; do
    ! grep -Fq "$hidden" "$LOG_FILE" || fail 'private diagnostic detail leaked into progress log'
    ! grep -Fq "$hidden" "$HISTORY_LOG" || fail 'private diagnostic detail leaked into history'
done
grep -Fxq 'manager_version=hidden-manager-fixture' "$TEST_ROOT/diagnose.env" || fail 'copy-only internal diagnostic field changed'
echo 'PASS: preflight and diagnosis report real check results without changing protocol or exposing private details'
