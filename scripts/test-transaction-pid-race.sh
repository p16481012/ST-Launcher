#!/usr/bin/env bash
# Exercise the real same-UID environment retry gate with synthetic /proc lifetime transitions.
set -Eeuo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")"/.. && pwd)"
source "$here/app/src/main/assets/transaction-recovery.sh"
box="$(mktemp -d "${TMPDIR:-/tmp}/st-proc-race.XXXXXXXX")"
trap 'case "$box" in */st-proc-race.*) rm -rf -- "$box";; esac' EXIT
proc="$box/927341879"; own="$(id -u)"; t=1123456789abcdef0123456789abcdef
declare -A tokens=(["$t"]=1) termux_ancestors=()
process_start_ticks() { awk '{print $22}' "$proc/stat" 2>/dev/null || true; }
born() {
    mkdir -p "$proc"
    # /proc/<pid>/stat: pid, comm, state, fields 4..21, start_time.
    { printf '927341879 (worker) %s' "$1"; for _ in {4..21}; do printf ' 0'; done; printf ' %s\n' "$2"; } > "$proc/stat"
}
transaction_writer_environment() {
    local p="$1"
    if [[ -e "$box/next" ]]; then
        rm -- "$box/next"
        case "$transition" in
            exit) rm -- "$p/stat"; rmdir -- "$p";;
            zombie) born Z 100;;
            replace) born S 110;;
        esac
        return 1
    fi
    case "$subsequent" in
        blocked) return 1;;
        writer) printf 'ST_LAUNCHER_WRITER_TOKEN=%s\n' "$t";;
        clear) printf '';;
    esac
}
check() {
    local transition="$1" subsequent="$2" expected="$3" rc=0
    born S 100; : > "$box/next"
    transaction_process_quiet "$proc" "$own" 90 || rc=$?
    [[ "$rc" == "$expected" ]] || { echo "$transition/$subsequent: expected $expected, got $rc" >&2; exit 1; }
}
check exit blocked 0
check zombie blocked 0
check stable blocked 1
check replace clear 0
check replace writer 1
check replace blocked 1
echo 'PASS: disappearing, zombie, reused and unreadable writer identities'
