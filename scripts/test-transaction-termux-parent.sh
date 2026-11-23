#!/usr/bin/env bash
# Model release Termux's non-dumpable Java ancestor, including app restart.
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ "$OSTYPE" != linux* ]]; then echo 'SKIP: non-dumpable Termux ancestor requires Linux'; exit 0; fi
fail() { echo "FAIL: Termux ancestor: $*" >&2; exit 1; }
if [[ "${1:-}" != --child ]]; then
    TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/st-termux-parent.XXXXXX")"
    trap 'case "$TEST_ROOT" in "${TMPDIR:-/tmp}"/st-termux-parent.*) rm -rf -- "$TEST_ROOT" ;; esac' EXIT
    cc -x c -o "$TEST_ROOT/parent" - <<'C'
#include <sys/prctl.h>
#include <sys/wait.h>
#include <unistd.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
int main(int argc, char **argv) {
    if (argc < 2 || prctl(PR_SET_DUMPABLE, 0) != 0) return 90;
    if (!strcmp(argv[1], "--hold")) {
        FILE *f = fopen(argv[2], "w"); if (!f) return 91;
        fputs("ready", f); fclose(f); for (;;) pause();
    }
    char pid[32]; snprintf(pid, sizeof(pid), "%ld", (long)getpid());
    setenv("TERMUX_APP__PID", pid, 1);
    pid_t child = fork();
    if (child < 0) return 92;
    if (!child) { execvp(argv[1], argv + 1); _exit(93); }
    int status; if (waitpid(child, &status, 0) < 0) return 94;
    return WIFEXITED(status) ? WEXITSTATUS(status) : 95;
}
C
    bash -c 'exec -a com.termux "$@"' fixture "$TEST_ROOT/parent" bash "$0" --child "$TEST_ROOT"
    echo 'PASS: restarted non-dumpable Termux ancestor allowed, unrelated namesake and known writer blocked'
    exit 0
fi
TEST_ROOT="$2"
[[ "$TEST_ROOT" == */st-termux-parent.* && -d "$TEST_ROOT" ]] || fail 'unsafe fixture'
export ST_HOME="$TEST_ROOT/install" ST_LAUNCHER_HOME="$TEST_ROOT/launcher" ST_DOWNLOAD_DIR="$TEST_ROOT/downloads"
source "$ROOT_DIR/app/src/main/assets/manager.sh"
WORK="$TEST_ROOT/work"; mkdir "$WORK"
app_start="$(process_start_ticks "$TERMUX_APP__PID")"
cutoff=$((app_start - 1))
(( cutoff > 0 )) || fail 'invalid cutoff'
token=0123456789abcdef0123456789abcdef
marker="$WORK/writer-$token.env"
printf 'token=%s\nowner_start=%s\n' "$token" "$cutoff" > "$marker"
transaction_writers_quiet "$WORK" || fail 'restarted app ancestor blocked recovery'
[[ -f "$WORK/writer-$token.done" ]] || fail 'missing completion proof'
rm "$WORK/writer-$token.done"
# Even a verified Java ancestor must not override an explicit active writer.
printf 'pid=%s\nstart=%s\n' "$TERMUX_APP__PID" "$app_start" >> "$marker"
if transaction_writers_quiet "$WORK"; then fail 'known writer ancestor bypassed identity guard'; fi
printf 'token=%s\nowner_start=%s\n' "$token" "$cutoff" > "$marker"
bash -c 'exec -a com.termux "$@"' fixture "$TEST_ROOT/parent" --hold "$TEST_ROOT/ready" &
unrelated=$!
trap 'kill "$unrelated" 2>/dev/null || true; wait "$unrelated" 2>/dev/null || true' EXIT
for _ in $(seq 1 100); do [[ -f "$TEST_ROOT/ready" ]] && break; sleep 0.02; done
[[ -f "$TEST_ROOT/ready" ]] || fail 'unrelated process not ready'
if transaction_writers_quiet "$WORK"; then fail 'unrelated namesake bypassed unreadable environment guard'; fi
kill "$unrelated"; wait "$unrelated" 2>/dev/null || true
trap - EXIT
transaction_writers_quiet "$WORK" || fail 'terminated unrelated process stayed blocked'
