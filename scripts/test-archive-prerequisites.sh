#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANAGER="$ROOT_DIR/app/src/main/assets/manager.sh"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/st-archive-prerequisites.XXXXXX")"
cleanup() { case "$TEST_ROOT" in "${TMPDIR:-/tmp}"/st-archive-prerequisites.*) rm -rf -- "$TEST_ROOT" ;; esac; }
trap cleanup EXIT
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

# Only package discovery and package-manager programs are mocked. Never contact
# a real mirror or install anything on the test host. Exercise production refresh,
# temporary fallback, cancellation boundaries, error mapping and result reporting.
run_case() (
    local name="$1" case_root="$TEST_ROOT/$1" indexes_ready=0 tools_ready=0 cancel_flag=0
    export ST_HOME="$case_root/install" ST_LAUNCHER_HOME="$case_root/launcher"
    export ST_DOWNLOAD_DIR="$case_root/downloads" PREFIX="$case_root/prefix"
    source "$MANAGER"
    mkdir -p "$PREFIX/etc/apt" "$ST_HOME/data"
    printf 'deb https://user-mirror.invalid stable main\n' > "$PREFIX/etc/apt/sources.list"
    printf 'preserve user data\n' > "$ST_HOME/data/keep"
    local trace="$case_root/trace"
    : > "$trace"
    CURRENT_OPERATION=backup
    trap 'command_cleanup $?' EXIT
    cancellation_requested() { (( cancel_flag )); }
    command() {
        if [[ "${1:-}" == -v && ( "${2:-}" == zip || "${2:-}" == unzip ) ]]; then
            [[ "$name" == installed || "$tools_ready" == 1 ]] && return 0
            [[ "$name" == missing-zip && "$2" == unzip ]] && return 0
            [[ "$name" == missing-unzip && "$2" == zip ]] && return 0
            return 1
        fi
        builtin command "$@"
    }
    pkg() {
        [[ "$*" == 'update -y' ]] || return 98
        printf 'indexes\n' >> "$trace"
        if [[ "$name" == fallback || "$name" == indexes-fail ]]; then
            echo 'E: fixture selected mirror metadata unavailable' >&2
            return 100
        fi
        indexes_ready=1
        [[ "$name" != cancel-indexes ]] || cancel_flag=1
    }
    apt-get() {
        local args=" $* "
        (( CANCELLATION_GUARD > 0 )) || return 98
        if [[ "$args" == *' update '* ]]; then
            printf 'temporary-indexes\n' >> "$trace"
            [[ "$args" == *"Dir::Etc::sourcelist=$TEMP_APT_SOURCES"* && -f "$TEMP_APT_SOURCES" ]] || return 98
            if [[ "$name" == indexes-fail ]]; then
                echo 'E: fixture temporary mirror metadata unavailable' >&2
                return 100
            fi
            indexes_ready=1
            return 0
        fi
        [[ "$args" == *' install -y zip unzip '* ]] || return 98
        printf 'install\n' >> "$trace"
        # A stale-index regression would fail before this package can be installed.
        (( indexes_ready )) || { echo 'E: fixture 404 stale package index' >&2; return 100; }
        [[ "${DEBIAN_FRONTEND:-}" == noninteractive ]] || return 98
        [[ "$args" == *'Dpkg::Options::=--force-confold'* && "$args" == *'Dpkg::Options::=--force-confdef'* ]] || return 98
        if [[ "$name" == fallback ]]; then
            [[ "$args" == *"Dir::Etc::sourcelist=$TEMP_APT_SOURCES"* ]] || return 98
        fi
        if [[ "$name" == install-fail ]]; then
            echo 'E: fixture zip package download failed (404)' >&2
            return 100
        fi
        [[ "$name" == still-missing ]] || tools_ready=1
        [[ "$name" != cancel-install ]] || cancel_flag=1
        return 0
    }
    ensure_archive_tools
    printf 'continue-backup\n' >> "$trace"
    cleanup_temporary_package_source
)

expect_case() {
    local name="$1" expected="$2" code=0
    run_case "$name" > "$TEST_ROOT/$name-output" 2>&1 || code=$?
    if [[ "$code" != "$expected" ]]; then
        cat "$TEST_ROOT/$name-output" >&2
        fail "$name: expected exit $expected, got $code"
    fi
    grep -Fxq 'deb https://user-mirror.invalid stable main' "$TEST_ROOT/$name/prefix/etc/apt/sources.list" || fail "$name changed global mirror"
    grep -Fxq 'preserve user data' "$TEST_ROOT/$name/install/data/keep" || fail "$name changed user data"
    if (( expected != 0 )); then
        ! grep -Fxq continue-backup "$TEST_ROOT/$name/trace" || fail "$name continued backup after failure"
    fi
    printf 'PASS: archive prerequisites %s\n' "$name"
}

expect_case installed 0
[[ "$(cat "$TEST_ROOT/installed/trace")" == continue-backup ]] || fail 'installed tools caused package/network requests'
for name in missing-both missing-zip missing-unzip fallback; do
    expect_case "$name" 0
    trace="$TEST_ROOT/$name/trace"
    [[ "$(head -n 1 "$trace")" == indexes ]] || fail 'package installation preceded index refresh'
    [[ "$(tail -n 2 "$trace")" == $'install\ncontinue-backup' ]] || fail 'wrong prerequisite order'
    [[ -z "$(find "$TEST_ROOT/$name/launcher/run" -name 'termux-main-*.list' -print -quit)" ]] || fail 'temporary mirror file leaked'
done
expect_case indexes-fail 14
grep -Fq 'fixture temporary mirror metadata unavailable' "$TEST_ROOT/indexes-fail-output" || fail 'package index error hidden'
grep -Fq 'error_code=PACKAGE_INDEX_FAILED' "$TEST_ROOT/indexes-fail-output" || fail 'wrong index error code'
! grep -Fxq install "$TEST_ROOT/indexes-fail/trace" || fail 'attempted install with failed indexes'
expect_case install-fail 65
grep -Fq 'fixture zip package download failed (404)' "$TEST_ROOT/install-fail-output" || fail 'package installation error hidden'
grep -Fq 'error_code=ARCHIVE_TOOL_INSTALL_FAILED' "$TEST_ROOT/install-fail-output" || fail 'wrong archive error code'
expect_case still-missing 65
grep -Fq 'error_code=ARCHIVE_TOOL_INSTALL_FAILED' "$TEST_ROOT/still-missing-output" || fail 'missing tools accepted as success'
for name in cancel-indexes cancel-install; do
    expect_case "$name" 130
    # Cancellation can exit while package output is redirected to operation.log;
    # the persisted result and shell status must still identify cancellation.
    grep -Fxq 'error_code=OPERATION_CANCELLED' "$TEST_ROOT/$name/launcher/run/last-result.env" || fail 'cancellation became a package error'
done
! grep -Fxq install "$TEST_ROOT/cancel-indexes/trace" || fail 'cancelled refresh continued to install'
echo 'PASS: refresh-before-install, offline reuse, original errors, cancellation and global/user data preservation'
