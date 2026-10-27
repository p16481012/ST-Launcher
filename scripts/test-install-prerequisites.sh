#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANAGER="$ROOT_DIR/app/src/main/assets/manager.sh"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/st-install-prerequisites.XXXXXX")"
cleanup() { case "$TEST_ROOT" in "${TMPDIR:-/tmp}"/st-install-prerequisites.*) rm -rf -- "$TEST_ROOT" ;; esac; }
trap cleanup EXIT
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

# No network or package-manager binary is executed: all platform programs are
# shell functions confined to this fixture. Real install ordering, runtime
# checks, error mapping, cancellation guards and dependency cleanup are used.
run_case() (
    local name="$1" case_root="$TEST_ROOT/$1" upgraded=0 cancel_flag=0
    export ST_HOME="$case_root/install" ST_LAUNCHER_HOME="$case_root/launcher"
    export ST_DOWNLOAD_DIR="$case_root/downloads" PREFIX="$case_root/prefix"
    source "$MANAGER"
    mkdir -p "$PREFIX/etc/apt"
    printf 'deb https://user-mirror.invalid stable main\n' > "$PREFIX/etc/apt/sources.list"
    printf 'user-custom-setting\n' > "$PREFIX/etc/custom.conf"
    local trace="$case_root/trace"
    : > "$trace"
    begin_operation() { CURRENT_OPERATION="$1"; OPERATION_STARTED_EPOCH="$(date +%s)"; }
    cancellation_requested() { (( cancel_flag )); }
    getprop() { printf 'arm64-v8a\n'; }
    pkg() {
        [[ "$*" == 'update -y' ]] || return 98
        printf 'indexes\n' >> "$trace"
        [[ "$name" != fallback && "$name" != indexes-fail ]]
    }
    apt-get() {
        local args=" $* "
        if [[ "$args" == *' update '* ]]; then
            printf 'temporary-indexes\n' >> "$trace"
            [[ "$args" == *"Dir::Etc::sourcelist=$TEMP_APT_SOURCES"* && -f "$TEMP_APT_SOURCES" ]] || return 98
            [[ "$name" != indexes-fail ]] || { echo 'E: fixture metadata fetch failed' >&2; return 100; }
            return 0
        fi
        [[ "${DEBIAN_FRONTEND:-}" == noninteractive ]] || return 98
        [[ "$args" == *'Dpkg::Options::=--force-confold'* && "$args" == *'Dpkg::Options::=--force-confdef'* ]] || return 98
        (( CANCELLATION_GUARD > 0 )) || return 98
        if [[ "$name" == fallback ]]; then
            [[ "$args" == *"Dir::Etc::sourcelist=$TEMP_APT_SOURCES"* ]] || return 98
        fi
        if [[ "$args" == *' full-upgrade '* ]]; then
            printf 'upgrade\n' >> "$trace"
            [[ "$name" != upgrade-fail ]] || { echo 'E: fixture package archive download failed' >&2; return 100; }
            upgraded=1
            [[ "$name" != cancel-upgrade ]] || cancel_flag=1
            return 0
        fi
        [[ "$args" == *' install -y git nodejs-lts nano '* ]] || return 98
        printf 'tools\n' >> "$trace"
        [[ "$name" != tools-fail ]] || { echo 'E: fixture tool dependency conflict' >&2; return 100; }
    }
    node() {
        [[ "$*" == --version ]] || return 98
        printf 'node-check\n' >> "$trace"
        if (( ! upgraded )) || [[ "$name" == node-fail || "$name" == preserve-node ]]; then
            echo 'CANNOT LINK EXECUTABLE "node": cannot locate symbol "_ZNSt6__ndk113__hash_memoryEPKvm" referenced by "/fixture/bin/node"' >&2
            return 1
        fi
        printf 'v24.22.1\r\n'
    }
    npm() {
        if [[ "$*" == --version ]]; then
            printf 'npm-check\n' >> "$trace"
            if [[ "$name" == npm-fail || "$name" == preserve-npm ]]; then
                echo "Error: Cannot find module '/fixture/npm-cli.js'" >&2
                return 1
            fi
            printf '11.0.0\r\n'
            return 0
        fi
        [[ " $* " == *' install '* ]] || return 98
        printf 'npm-install\n' >> "$trace"
        [[ "$name" != dependency-fail ]] || { echo 'npm error code EAI_AGAIN' >&2; return 1; }
        mkdir -p "$ST_HOME/node_modules/fixture"
    }
    git() {
        [[ "$1" == clone ]] || return 98
        printf 'clone\n' >> "$trace"
        grep -Fxq npm-check "$trace" || return 98
        [[ "$name" != clone-fail ]] || { echo 'fatal: fixture connection reset' >&2; return 128; }
        mkdir -p "$ST_HOME"
        printf '{"name":"offline-fixture","version":"1.0.0"}\n' > "$ST_HOME/package.json"
    }
    if [[ "$name" == preserve-* ]]; then
        upgraded=1
        mkdir -p "$ST_HOME/node_modules/original" "$ST_HOME/data"
        printf original > "$ST_HOME/node_modules/original/keep"
        printf user-data > "$ST_HOME/data/keep"
        install_dependencies
    else
        install_st release
    fi
)

expect_case() {
    local name="$1" expected="$2" code=0
    run_case "$name" > "$TEST_ROOT/$name-output" 2>&1 || code=$?
    if [[ "$code" != "$expected" ]]; then
        cat "$TEST_ROOT/$name-output" >&2
        fail "$name: expected exit $expected, got $code"
    fi
}
for name in normal fallback; do
    expect_case "$name" 0
    trace="$TEST_ROOT/$name/trace"
    [[ "$(sed -n '1p' "$trace")" == indexes ]] || fail 'indexes must be checked before upgrading'
    upgrade_line="$(grep -n -m 1 '^upgrade$' "$trace" | cut -d: -f1)"
    tools_line="$(grep -n -m 1 '^tools$' "$trace" | cut -d: -f1)"
    node_line="$(grep -n -m 1 '^node-check$' "$trace" | cut -d: -f1)"
    clone_line="$(grep -n -m 1 '^clone$' "$trace" | cut -d: -f1)"
    (( upgrade_line < tools_line && tools_line < node_line && node_line < clone_line )) || fail 'fresh install prerequisites ran out of order'
    grep -Fq 'user-mirror.invalid' "$TEST_ROOT/$name/prefix/etc/apt/sources.list" || fail 'mirror setting changed'
    [[ "$(cat "$TEST_ROOT/$name/prefix/etc/custom.conf")" == user-custom-setting ]] || fail 'custom config changed'
    [[ -d "$TEST_ROOT/$name/install/node_modules/fixture" ]] || fail 'dependency installation did not finish'
done
echo 'PASS: whole-package upgrade precedes tools, CRLF node/npm execution checks and clone; temporary fallback and user settings preserved'

for entry in indexes-fail:14 upgrade-fail:61 cancel-upgrade:130 tools-fail:15 node-fail:62 npm-fail:63; do
    name="${entry%:*}"; expect_case "$name" "${entry#*:}"
    if grep -Fxq clone "$TEST_ROOT/$name/trace"; then fail "$name cloned before prerequisites passed"; fi
    [[ ! -e "$TEST_ROOT/$name/install" ]] || fail "$name created an installation"
done
grep -Fq 'fixture metadata fetch failed' "$TEST_ROOT/indexes-fail-output" || fail 'index error cause hidden'
grep -Fq 'fixture package archive download failed' "$TEST_ROOT/upgrade-fail-output" || fail 'upgrade error cause hidden'
grep -Fq 'fixture tool dependency conflict' "$TEST_ROOT/tools-fail-output" || fail 'tool error cause hidden'
grep -Fq '__hash_memory' "$TEST_ROOT/node-fail-output" || fail 'Node linker cause hidden'
grep -Fq 'npm-cli.js' "$TEST_ROOT/npm-fail-output" || fail 'npm cause hidden'
if grep -Fxq tools "$TEST_ROOT/cancel-upgrade/trace"; then fail 'cancelled upgrade proceeded to tool installation'; fi
echo 'PASS: prerequisite failures retain real causes; upgrade cancellation waits for safe package boundary and does not clone'

expect_case clone-fail 17
grep -Fq 'fixture connection reset' "$TEST_ROOT/clone-fail-output" || fail 'clone cause hidden'
expect_case dependency-fail 12
grep -Fq EAI_AGAIN "$TEST_ROOT/dependency-fail-output" || fail 'npm install cause hidden'
[[ ! -e "$TEST_ROOT/dependency-fail/install" ]] || fail 'failed new installation not cleaned'
echo 'PASS: download and module-install errors retain actual causes and safely clean partial new installs'

for entry in preserve-node:62 preserve-npm:63; do
    name="${entry%:*}"; expect_case "$name" "${entry#*:}"
    [[ "$(cat "$TEST_ROOT/$name/install/node_modules/original/keep")" == original ]] || fail 'runtime failure deleted existing modules'
    [[ "$(cat "$TEST_ROOT/$name/install/data/keep")" == user-data ]] || fail 'runtime failure changed user data'
    if grep -Eq '^(upgrade|npm-install)$' "$TEST_ROOT/$name/trace"; then fail 'dependency recheck unexpectedly upgraded packages or ran npm install'; fi
done
echo 'PASS: runtime failure preserves existing modules/data and does not trigger a full upgrade outside fresh installation'
