#!/usr/bin/env bash
# Sourced by the sandbox test harness when real ZIP tools are available.
# Every fixture lives below TEST_ROOT; HOME and the user's Git config are untouched.

for zip_test_tool in zip unzip zipinfo git; do
    command -v "$zip_test_tool" >/dev/null 2>&1 || fail "ZIP regressions require $zip_test_tool"
done

zip_regression_setup() {
    ZIP_CASE_ROOT="$TEST_ROOT/zip-$1"
    export ST_HOME="$ZIP_CASE_ROOT/install"
    export ST_LAUNCHER_HOME="$ZIP_CASE_ROOT/launcher"
    export ST_DOWNLOAD_DIR="$ZIP_CASE_ROOT/downloads"
    export PREFIX="$ZIP_CASE_ROOT/prefix"
    export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
    mkdir -p "$ST_HOME" "$ST_DOWNLOAD_DIR" "$PREFIX/etc/apt"
    source "$ROOT_DIR/app/src/main/assets/manager.sh"
    # Never install packages or access the network in these fixture tests.
    ensure_archive_tools() { command -v zip >/dev/null && command -v unzip >/dev/null; }
    install_dependencies() { echo "Unexpected dependency installation in ZIP fixture" >&2; return 99; }
}

zip_regression_install() {
    local target="$1" marker="$2" with_git="${3:-0}"
    mkdir -p "$target/data/default-user"
    printf '{"version":"1.0.0"}\n' > "$target/package.json"
    printf '// fixture only\n' > "$target/server.js"
    printf '#!/bin/bash\n' > "$target/start.sh"
    printf '%s\n' "$marker" > "$target/data/default-user/state.txt"
    printf '%s-config\n' "$marker" > "$target/config.yaml"
    if [[ "$with_git" == 1 ]]; then
        git -c init.defaultBranch=release init -q "$target"
        git -C "$target" add --all
        git -C "$target" -c user.name='Launcher ZIP Test' -c user.email=test@example.invalid \
            -c commit.gpgsign=false -c core.hooksPath=/dev/null commit -qm fixture
    fi
}

zip_regression_manifest() {
    local target="$1" kinds="$2" expanded="${3:-0}"
    printf 'format=st-launcher-backup-v1\ncreated_at=2000-01-01 00:00:00\nitems=%s\ncustom=\nsecrets=0\nexpanded_bytes=%s\nentry_count=0\n' \
        "$kinds" "$expanded" > "$target/.st-launcher-manifest"
}

zip_regression_archive() {
    local directory="$1" archive="$2"
    (cd "$directory" && zip -qr "$archive" .)
}

zip_regression_metadata_complete() {
    local archive="$1" manifest expanded entries
    manifest="$(unzip -p "$archive" .st-launcher-manifest)"
    expanded="$(unsigned_decimal "$(sed -n 's/^expanded_bytes=//p' <<< "$manifest")")" || fail "ZIP expanded size metadata is invalid"
    entries="$(unsigned_decimal "$(sed -n 's/^entry_count=//p' <<< "$manifest")")" || fail "ZIP entry count metadata is invalid"
    (( expanded > 0 && entries > 0 )) || fail "ZIP metadata refresh was skipped for a fast archive operation"
}

zip_regression_expect_exit() {
    local expected="$1" name="$2" output="$TEST_ROOT/zip-$2.output" actual
    shift 2
    set +e
    (
        # Do not inherit the parent harness cleanup trap into fixture failures.
        trap - EXIT INT TERM
        set -Eeuo pipefail
        "$@"
    ) > "$output" 2>&1
    actual=$?
    set -e
    if [[ "$actual" != "$expected" ]]; then
        sed -n '1,100p' "$output" >&2
        tail -n 60 "$TEST_ROOT/zip-$name/launcher/logs/operation.log" >&2 2>/dev/null || true
        fail "ZIP $name returned $actual instead of $expected"
    fi
}

zip_regression_bad_numeric() {
    zip_regression_setup numeric
    local payload="$ZIP_CASE_ROOT/payload" bad_numeric
    zip_regression_install "$payload" incoming
    # This string is data. A vulnerable arithmetic parser would create only
    # this sandbox marker; the fixed parser must never evaluate it.
    printf -v bad_numeric 'probe[$(touch "%s")]' "$ZIP_CASE_ROOT/injected"
    zip_regression_manifest "$payload" user_data "$bad_numeric"
    zip_regression_archive "$payload" "$DOWNLOAD_DIR/SillyTavern-Launcher-20000101-000001.zip"
    command cp "$DOWNLOAD_DIR/SillyTavern-Launcher-20000101-000001.zip" "$DOWNLOAD_DIR/SillyTavern-Import-Numeric.zip"
    list_backups > "$ZIP_CASE_ROOT/list.txt"
    [[ ! -s "$ZIP_CASE_ROOT/list.txt" ]] || fail "malformed numeric metadata appeared in the backup list"
    [[ ! -e "$ZIP_CASE_ROOT/injected" ]] || fail "backup list evaluated numeric metadata as shell code"
    import_backup SillyTavern-Import-Numeric.zip
}

zip_regression_expect_exit 23 numeric zip_regression_bad_numeric
[[ ! -e "$TEST_ROOT/zip-numeric/injected" ]] || fail "backup import evaluated numeric metadata as shell code"
[[ ! -e "$TEST_ROOT/zip-numeric/downloads/SillyTavern-Import-Numeric.zip" ]] ||
    fail "invalid numeric import was not rejected"

zip_regression_invalid_full() {
    zip_regression_setup invalid-full
    zip_regression_install "$ST_HOME" original 1
    local payload="$ZIP_CASE_ROOT/payload"
    zip_regression_install "$payload" incoming
    zip_regression_manifest "$payload" full
    zip_regression_archive "$payload" "$DOWNLOAD_DIR/SillyTavern-Launcher-20000101-000002.zip"
    restore_backup SillyTavern-Launcher-20000101-000002.zip
}

zip_regression_expect_exit 24 invalid-full zip_regression_invalid_full
[[ "$(cat "$TEST_ROOT/zip-invalid-full/install/data/default-user/state.txt")" == original ]] ||
    fail "full archive missing Git modified the original user data"
[[ -d "$TEST_ROOT/zip-invalid-full/install/.git" ]] || fail "invalid full restore removed the original Git repository"

zip_regression_generic_import() {
    zip_regression_setup generic-import
    zip_regression_install "$ST_HOME" original
    local payload="$ZIP_CASE_ROOT/payload"
    zip_regression_install "$payload" incoming
    mkdir -p "$payload/public/scripts/extensions/third-party/example"
    printf 'extension fixture\n' > "$payload/public/scripts/extensions/third-party/example/index.js"
    zip_regression_archive "$payload" "$DOWNLOAD_DIR/SillyTavern-Import-Generic.zip"
    import_backup SillyTavern-Import-Generic.zip
}

zip_regression_expect_exit 0 generic-import zip_regression_generic_import
mapfile -t zip_imported_archives < <(find "$TEST_ROOT/zip-generic-import/downloads" -maxdepth 1 -type f -name 'SillyTavern-Launcher-*.zip')
[[ "${#zip_imported_archives[@]}" == 1 ]] || fail "generic ZIP did not create exactly one launcher backup"
zip_regression_metadata_complete "${zip_imported_archives[0]}"
unzip -p "${zip_imported_archives[0]}" .st-launcher-manifest > "$TEST_ROOT/zip-generic-import/manifest.txt"
grep -Fxq 'items=user_data,config,extensions' "$TEST_ROOT/zip-generic-import/manifest.txt" ||
    fail "no-Git ZIP was not normalized to partial data/config/extensions"
unzip -Z1 "${zip_imported_archives[0]}" > "$TEST_ROOT/zip-generic-import/entries.txt"
if grep -Fxq package.json "$TEST_ROOT/zip-generic-import/entries.txt"; then
    fail "partial generic import retained the unmanageable installation files"
fi
[[ "$(cat "$TEST_ROOT/zip-generic-import/install/data/default-user/state.txt")" == original ]] ||
    fail "ZIP import changed the current installation"

zip_regression_tiny_backup() {
    zip_regression_setup tiny-backup
    zip_regression_install "$ST_HOME" original 1
    backup_selected user_data 0 ""
}

zip_regression_expect_exit 0 tiny-backup zip_regression_tiny_backup
mapfile -t zip_tiny_archives < <(find "$TEST_ROOT/zip-tiny-backup/downloads" -maxdepth 1 -type f -name 'SillyTavern-Launcher-*.zip')
[[ "${#zip_tiny_archives[@]}" == 1 ]] || fail "tiny backup did not create exactly one archive"
zip_regression_metadata_complete "${zip_tiny_archives[0]}"

zip_regression_full_dependency_failure() {
    zip_regression_setup dependency-failure
    zip_regression_install "$ST_HOME" original 1
    git -C "$ST_HOME" rev-parse HEAD > "$ZIP_CASE_ROOT/original-commit"
    mkdir -p "$ST_HOME/node_modules"
    printf 'original-module\n' > "$ST_HOME/node_modules/fixture.txt"
    printf 'original-hash\n' > "$DEPENDENCY_HASH_FILE"
    local payload="$ZIP_CASE_ROOT/payload"
    zip_regression_install "$payload" incoming 1
    zip_regression_manifest "$payload" full
    zip_regression_archive "$payload" "$DOWNLOAD_DIR/SillyTavern-Launcher-20000101-000003.zip"
    install_dependencies() {
        printf 'changed-hash\n' > "$DEPENDENCY_HASH_FILE"
        return 1
    }
    restore_backup SillyTavern-Launcher-20000101-000003.zip
}

zip_regression_expect_exit 26 dependency-failure zip_regression_full_dependency_failure
[[ "$(cat "$TEST_ROOT/zip-dependency-failure/install/data/default-user/state.txt")" == original ]] ||
    fail "dependency failure did not restore original user data"
[[ "$(cat "$TEST_ROOT/zip-dependency-failure/install/node_modules/fixture.txt")" == original-module ]] ||
    fail "dependency failure did not restore original node_modules"
[[ "$(GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1 git -C "$TEST_ROOT/zip-dependency-failure/install" rev-parse HEAD)" == "$(cat "$TEST_ROOT/zip-dependency-failure/original-commit")" ]] ||
    fail "dependency failure did not restore the original Git commit"
[[ "$(cat "$TEST_ROOT/zip-dependency-failure/launcher/dependency-lock.sha256")" == original-hash ]] ||
    fail "dependency failure did not restore the dependency hash"

zip_regression_partial_copy_failure() {
    zip_regression_setup partial-copy
    zip_regression_install "$ST_HOME" original
    local payload="$ZIP_CASE_ROOT/payload"
    zip_regression_install "$payload" incoming
    zip_regression_manifest "$payload" user_data,config
    zip_regression_archive "$payload" "$DOWNLOAD_DIR/SillyTavern-Launcher-20000101-000004.zip"
    cp() {
        if [[ "${1:-}" == -a && "${2:-}" == "$CURRENT_WORK_DIR/extracted/data" ]]; then
            printf 'injected first-apply-copy failure\n' > "$ZIP_CASE_ROOT/copy-failed"
            return 1
        fi
        command cp "$@"
    }
    restore_backup SillyTavern-Launcher-20000101-000004.zip
}

zip_regression_expect_exit 25 partial-copy zip_regression_partial_copy_failure
[[ -f "$TEST_ROOT/zip-partial-copy/copy-failed" ]] || fail "partial first-copy failure was not exercised"
[[ "$(cat "$TEST_ROOT/zip-partial-copy/install/data/default-user/state.txt")" == original ]] ||
    fail "partial first-copy failure did not restore the original data directory"
[[ "$(cat "$TEST_ROOT/zip-partial-copy/install/config.yaml")" == original-config ]] ||
    fail "partial first-copy failure did not roll back the later config replacement"

echo "manager real-ZIP regression tests passed"
