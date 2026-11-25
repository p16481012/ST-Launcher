#!/usr/bin/env bash
# Offline fixtures. Real repair, execution probes, package guards, error mapping
# and module cleanup run against disposable directories, never host packages.
set -Eeuo pipefail
project="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ ${1:-} == __case ]]; then
    shift; scenario="$1"; case_dir="$2"; shift 2
    export ST_HOME="$case_dir/install" ST_LAUNCHER_HOME="$case_dir/launcher" PREFIX="$case_dir/prefix"
    export ST_DOWNLOAD_DIR="$case_dir/downloads"
    source "$project/app/src/main/assets/manager.sh"
    mkdir -p "$ST_HOME/.git" "$ST_HOME/node_modules/old" "$ST_HOME/data" "$PREFIX/etc/apt"
    printf '{}' > "$ST_HOME/package.json"
    printf old-module > "$ST_HOME/node_modules/old/sentinel"
    printf user-chat > "$ST_HOME/data/sentinel"
    printf user-mirror > "$PREFIX/etc/apt/sources.list"
    trace="$case_dir/trace"; : > "$trace"
    begin_operation() { CURRENT_OPERATION="$1"; }
    cancellation_requested() { [[ -f "$case_dir/cancel" ]]; }
    is_running() { [[ "$scenario" == running ]]; }
    curl() { return 1; }
    pkg() { printf 'index\n' >> "$trace"; [[ "$*" == 'update -y' && "$scenario" != network ]]; }
    dpkg-query() {
        case "$scenario:${!#}" in
            regular:nodejs|legacy:nodejs-current) printf installed ;;
            regular:*|legacy:*|absent:*) printf 'not-installed' ;;
            *:nodejs-lts) printf installed ;;
            *) return 1 ;;
        esac
    }
    apt-get() {
        local args=" $* "
        if [[ "$args" == *' update '* ]]; then printf 'fallback\n' >> "$trace"; echo fake-index-error >&2; return 100; fi
        [[ "$DEBIAN_FRONTEND" == noninteractive && "$args" == *'Dpkg::Options::=--force-confold'* &&
           "$args" == *'Dpkg::Options::=--force-confdef'* && "$CANCELLATION_GUARD" -gt 0 ]] || return 99
        local expected_flavor=nodejs-lts
        if [[ "$scenario" == regular ]]; then expected_flavor=nodejs; elif [[ "$scenario" == legacy ]]; then expected_flavor=nodejs-current; fi
        if [[ "$args" == *" install -y git $expected_flavor nano "* && "$args" == *' --no-remove '* ]]; then
            printf 'tools\n' >> "$trace"; [[ "$scenario" != tools-error ]] || { echo fake-tool-error >&2; return 100; }
        elif [[ "$args" == *' full-upgrade '* && "$args" == *' --no-remove '* ]]; then
            printf 'upgrade\n' >> "$trace"
            [[ "$scenario" != upgrade-error ]] || { echo fake-upgrade-error >&2; return 100; }
            touch "$case_dir/upgraded"
            if [[ "$scenario" == cancel ]]; then touch "$case_dir/cancel"; fi
        else return 99; fi
    }
    linker_error='CANNOT LINK EXECUTABLE "node": library "libicui18n.so.79" not found: needed by main executable'
    node() {
        [[ "$*" == --version ]] || return 99
        printf 'node\n' >> "$trace"
        case "$scenario" in
            healthy|healthy-sync|npm-error|regular|legacy|absent) ;;
            generic) echo "syntax error" >&2; return 1 ;;
            fake-success) echo "$linker_error" >&2; return 0 ;; # Invalid version, no linker exit.
            unrelated) echo 'CANNOT LINK EXECUTABLE "other": library "x" not found' >&2; return 1 ;;
            symbol) echo 'CANNOT LINK EXECUTABLE "/prefix/bin/node": cannot locate symbol "bad" referenced by "node"' >&2; return 1 ;;
            persistent) echo "$linker_error" >&2; return 1 ;;
            *) [[ -f "$case_dir/upgraded" ]] || { echo "$linker_error" >&2; return 1; } ;;
        esac
        echo 'v24.22.1'
    }
    npm() {
        case "$1" in
            --version) printf 'npm\n' >> "$trace"; [[ "$scenario" != npm-error ]] || { echo "$linker_error" >&2; return 1; }; echo 11.0.0 ;;
            cache) printf 'cache\n' >> "$trace" ;;
            install) printf 'install\n' >> "$trace"; mkdir -p "$ST_HOME/node_modules/new" ;;
            *) return 99 ;;
        esac
    }
    trap 'rc=$?; printf "error_code=%s\n" "$(manager_error_code repair "$rc")" >&2' EXIT
    repair_st "$@"
    exit 0
fi

t="$(mktemp -d "${TMPDIR:-/tmp}/st-repair-runtime.XXXXXXXX")"
trap 'case "$t" in "${TMPDIR:-/tmp}"/st-repair-runtime.*) rm -rf -- "$t";; esac' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
check() {
    local name="$1" expected="$2" expected_upgrades="$3" rc=0; shift 3
    local dir="$t/$name" flags=( "$@" )
    mkdir -p "$dir"
    bash "$0" __case "$name" "$dir" "${flags[@]}" > "$dir/output" 2>&1 || rc=$?
    [[ "$rc" == "$expected" ]] || { cat "$dir/output" >&2; fail "$name expected $expected, got $rc"; }
    [[ "$(grep -cx upgrade "$dir/trace" || true)" == "$expected_upgrades" ]] || fail "$name wrong upgrade count"
    [[ "$(cat "$dir/install/data/sentinel")" == user-chat && "$(cat "$dir/prefix/etc/apt/sources.list")" == user-mirror ]] || fail "$name changed user data or mirror"
    if (( expected == 0 )); then
        [[ -d "$dir/install/node_modules/new" ]] || fail "$name didn't repair modules"
        awk '$0=="npm"{verified=1} $0=="cache"{if(!verified)exit 1; cache=1} $0=="install"{if(!cache)exit 1; done=1} END{if(!done)exit 1}' "$dir/trace" || fail "$name touched modules before verifying tools"
    else
        [[ "$(cat "$dir/install/node_modules/old/sentinel")" == old-module ]] || fail "$name changed modules after a failed check"
        ! grep -Eq '^(cache|install)$' "$dir/trace" || fail "$name touched npm cache/modules"
    fi
    echo "PASS $name (exit $rc; full-upgrades=$expected_upgrades)"
}
flag=--sync-runtime-libraries
check linker 71 0; check symbol 71 0
grep -Fq 'libicui18n.so.79' "$t/linker/output" && grep -Fxq 'error_code=NODE_LINKER_FAILED' "$t/linker/output" || fail 'missing original linker cause or offer code'
check selected 0 1 "$flag"
diff -u <(printf '%s\n' index tools node upgrade node npm cache node npm install) "$t/selected/trace"
check persistent 62 1 "$flag"
grep -Fxq 'error_code=NODE_RUNTIME_FAILED' "$t/persistent/output" && grep -Fq 'libicui18n.so.79' "$t/persistent/output" || fail 'repeated link failure must be terminal with original cause'
check generic 62 0 "$flag"; check unrelated 62 0 "$flag"; check fake-success 62 0 "$flag"
check npm-error 63 0 "$flag"; check healthy 0 0; check healthy-sync 0 0 "$flag"
check regular 0 0 "$flag"; check legacy 0 0; check absent 0 0
check network 14 0 "$flag"; check tools-error 15 0 "$flag"; check upgrade-error 61 1 "$flag"
grep -Fq fake-index-error "$t/network/output" && grep -Fq fake-tool-error "$t/tools-error/output" && grep -Fq fake-upgrade-error "$t/upgrade-error/output" || fail 'package errors lost'
check cancel 130 1 "$flag"; check running 10 0 "$flag"
check bad-flag 64 0 --yes; check extra-flag 64 0 "$flag" "$flag"
