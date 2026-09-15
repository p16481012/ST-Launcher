#!/usr/bin/env bash
# Sourced by the sandbox test harness when real ZIP tools are available.
# Every fixture lives below TEST_ROOT; HOME and the user's Git config are untouched.

for zip_test_tool in zip unzip zipinfo git node npm tar; do
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
    ensure_import_runtime() { command -v node >/dev/null && command -v git >/dev/null; }
    curl() { return 1; }
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
    # Instrument the actual streaming helper boundary. Counting only unzip calls
    # would miss an accidental second pass through the measured ZIP extractor.
    eval "$(declare -f measured_archive | sed '1s/measured_archive/zip_original_measured_archive/')"
    measured_archive() {
        printf '%s\n' "${3:-}" >> "$ZIP_CASE_ROOT/archive-helper-calls"
        zip_original_measured_archive "$@"
    }
    unzip() {
        printf '%s\n' "$*" >> "$ZIP_CASE_ROOT/unzip-calls"
        command unzip "$@"
    }
    zip() {
        printf '%s\n' "$*" >> "$ZIP_CASE_ROOT/zip-calls"
        command zip "$@"
    }
    import_backup SillyTavern-Import-Generic.zip
}

zip_regression_expect_exit 0 generic-import zip_regression_generic_import
[[ -z "$(find "$TEST_ROOT/zip-generic-import/downloads" -maxdepth 1 -type f -name '*.zip')" ]] ||
    fail "generic ZIP import left an intermediate or picker ZIP"
[[ "$(cat "$TEST_ROOT/zip-generic-import/install/data/default-user/state.txt")" == incoming ]] ||
    fail "generic ZIP import did not directly restore the data"
[[ "$(cat "$TEST_ROOT/zip-generic-import/install/config.yaml")" == incoming-config ]] ||
    fail "generic ZIP config was not restored"
[[ -f "$TEST_ROOT/zip-generic-import/install/public/scripts/extensions/third-party/example/index.js" ]] ||
    fail "generic ZIP extension was not restored"
[[ "$(cat "$TEST_ROOT/zip-generic-import/archive-helper-calls")" == extract ]] ||
    fail "generic import did not use exactly one measured extraction without recompression"
[[ ! -s "$TEST_ROOT/zip-generic-import/unzip-calls" ]] ||
    fail "generic import also used a legacy unzip content/integrity pass"
[[ ! -s "$TEST_ROOT/zip-generic-import/zip-calls" ]] ||
    fail "generic import created a normalized intermediate ZIP"

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
    eval "$(declare -f measured_copy | sed '1s/measured_copy/zip_original_measured_copy/')"
    measured_copy() {
        if [[ "${1:-}" == "$CURRENT_WORK_DIR/extracted/data" ]]; then
            printf 'injected first-apply-copy failure\n' > "$ZIP_CASE_ROOT/copy-failed"
            return 1
        fi
        zip_original_measured_copy "$@"
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

zip_regression_data_root() {
    zip_regression_setup data-root
    zip_regression_install "$ST_HOME" original
    local payload="$ZIP_CASE_ROOT/payload"
    mkdir -p "$payload/data/default-user/characters" "$payload/data/second-user/chats"
    printf 'character\n' > "$payload/data/default-user/characters/card.json"
    printf 'chat\n' > "$payload/data/second-user/chats/chat.jsonl"
    zip_regression_archive "$payload" "$DOWNLOAD_DIR/SillyTavern-Import-Data.zip"
    import_backup SillyTavern-Import-Data.zip
}
zip_regression_expect_exit 0 data-root zip_regression_data_root
[[ -f "$TEST_ROOT/zip-data-root/install/data/second-user/chats/chat.jsonl" ]] || fail "data-only root lost additional users"

zip_regression_users_root() {
    zip_regression_setup users-root
    zip_regression_install "$ST_HOME" original
    local payload="$ZIP_CASE_ROOT/payload"
    mkdir -p "$payload/default-user/characters" "$payload/second-user/chats"
    printf 'character\n' > "$payload/default-user/characters/card.json"
    printf 'chat\n' > "$payload/second-user/chats/chat.jsonl"
    zip_regression_archive "$payload" "$DOWNLOAD_DIR/SillyTavern-Import-Users.zip"
    import_backup SillyTavern-Import-Users.zip
}
zip_regression_expect_exit 0 users-root zip_regression_users_root
[[ -f "$TEST_ROOT/zip-users-root/install/data/second-user/chats/chat.jsonl" ]] || fail "users-only root lost sibling users"

zip_regression_missing_install() {
    zip_regression_setup missing-install
    local payload="$ZIP_CASE_ROOT/payload"
    mkdir -p "$payload/data/default-user"
    printf 'incoming\n' > "$payload/data/default-user/state.txt"
    zip_regression_archive "$payload" "$DOWNLOAD_DIR/SillyTavern-Import-Missing.zip"
    import_backup SillyTavern-Import-Missing.zip
}
zip_regression_expect_exit 24 missing-install zip_regression_missing_install
[[ ! -e "$TEST_ROOT/zip-missing-install/install/data" ]] || fail "partial backup modified incomplete target"

zip_regression_folder_move() {
    zip_regression_setup folder-move
    zip_regression_install "$ST_HOME" original 1
    local source="$ZIP_CASE_ROOT/Documents/SillyTavern"
    zip_regression_install "$source" incoming 1
    mkdir -p "$source/node_modules" "$source/.git/hooks"
    printf 'old-device-module\n' > "$source/node_modules/old.txt"
    printf 'malicious hook fixture\n' > "$source/.git/hooks/post-checkout"
    git -C "$source" config core.sshCommand 'never-execute-fixture'
    install_dependencies() {
        [[ ! -e "$ST_HOME/node_modules/old.txt" ]] || fail "source dependencies were copied"
        mkdir -p "$ST_HOME/node_modules"
        printf 'current-device-module\n' > "$ST_HOME/node_modules/current.txt"
        dependency_hash > "$DEPENDENCY_HASH_FILE"
    }
    import_install "$(printf '%s' "$source" | base64 -w 0)"
}
zip_regression_expect_exit 0 folder-move zip_regression_folder_move
[[ "$(cat "$TEST_ROOT/zip-folder-move/install/data/default-user/state.txt")" == incoming ]] || fail "existing folder was not adopted"
[[ ! -e "$TEST_ROOT/zip-folder-move/Documents/SillyTavern" ]] || fail "successful move retained the source"
[[ ! -f "$TEST_ROOT/zip-folder-move/install/.git/hooks/post-checkout" ]] || fail "imported executable Git hooks were retained"
if grep -Fq never-execute-fixture "$TEST_ROOT/zip-folder-move/install/.git/config"; then fail "imported Git executable configuration was retained"; fi

zip_regression_folder_failure() {
    zip_regression_setup folder-failure
    zip_regression_install "$ST_HOME" original 1
    local source="$ZIP_CASE_ROOT/TermuxOld/SillyTavern"
    zip_regression_install "$source" incoming 1
    install_dependencies() { return 1; }
    import_install "$(printf '%s' "$source" | base64 -w 0)"
}
zip_regression_expect_exit 26 folder-failure zip_regression_folder_failure
[[ "$(cat "$TEST_ROOT/zip-folder-failure/install/data/default-user/state.txt")" == original ]] || fail "failed folder import did not roll back destination"
[[ "$(cat "$TEST_ROOT/zip-folder-failure/TermuxOld/SillyTavern/data/default-user/state.txt")" == incoming ]] || fail "failed folder import removed source"

zip_regression_folder_same() {
    zip_regression_setup folder-same
    zip_regression_install "$ST_HOME" original 1
    import_install "$(printf '%s' "$ST_HOME" | base64 -w 0)"
}
zip_regression_expect_exit 0 folder-same zip_regression_folder_same
[[ "$(cat "$TEST_ROOT/zip-folder-same/install/data/default-user/state.txt")" == original ]] || fail "same-path adoption deleted or changed live installation"

zip_regression_folder_overlap() {
    zip_regression_setup folder-overlap
    zip_regression_install "$ST_HOME" original 1
    import_install "$(printf '%s' "$ST_HOME/data" | base64 -w 0)"
}
zip_regression_expect_exit 51 folder-overlap zip_regression_folder_overlap

zip_regression_folder_changed() {
    zip_regression_setup folder-changed
    zip_regression_install "$ST_HOME" original 1
    local source="$ZIP_CASE_ROOT/old/SillyTavern"
    zip_regression_install "$source" incoming 1
    install_dependencies() { printf 'late-edit\n' > "$source/data/default-user/new.txt"; }
    import_install "$(printf '%s' "$source" | base64 -w 0)"
}
zip_regression_expect_exit 0 folder-changed zip_regression_folder_changed
[[ -f "$TEST_ROOT/zip-folder-changed/old/SillyTavern/data/default-user/new.txt" ]] || fail "late source edit was deleted"
grep -Fxq source_cleanup_failed=1 "$TEST_ROOT/zip-folder-changed.output" || fail "late source preservation was not reported"

zip_regression_folder_source_running() {
    zip_regression_setup folder-source-running
    zip_regression_install "$ST_HOME" original 1
    local source="$ZIP_CASE_ROOT/old/SillyTavern"
    zip_regression_install "$source" incoming 1
    installation_server_running() { [[ "$1" == "$source" ]]; }
    import_install "$(printf '%s' "$source" | base64 -w 0)"
}
zip_regression_expect_exit 10 folder-source-running zip_regression_folder_source_running
[[ "$(cat "$TEST_ROOT/zip-folder-source-running/install/data/default-user/state.txt")" == original ]] || fail "active source changed destination"
[[ "$(cat "$TEST_ROOT/zip-folder-source-running/old/SillyTavern/data/default-user/state.txt")" == incoming ]] || fail "active source was removed"

zip_regression_folder_source_started_late() {
    zip_regression_setup folder-source-started-late
    zip_regression_install "$ST_HOME" original 1
    local source="$ZIP_CASE_ROOT/old/SillyTavern" source_started=0
    zip_regression_install "$source" incoming 1
    installation_server_running() { [[ "$1" == "$source" && "$source_started" == 1 ]]; }
    install_dependencies() { source_started=1; }
    import_install "$(printf '%s' "$source" | base64 -w 0)"
}
zip_regression_expect_exit 0 folder-source-started-late zip_regression_folder_source_started_late
[[ "$(cat "$TEST_ROOT/zip-folder-source-started-late/install/data/default-user/state.txt")" == incoming ]] || fail "late source startup invalidated completed destination"
[[ "$(cat "$TEST_ROOT/zip-folder-source-started-late/old/SillyTavern/data/default-user/state.txt")" == incoming ]] || fail "late source startup was ignored before deletion"
grep -Fxq source_cleanup_failed=1 "$TEST_ROOT/zip-folder-source-started-late.output" || fail "late source startup preservation was not reported"

zip_regression_folder_target_running() {
    zip_regression_setup folder-target-running
    zip_regression_install "$ST_HOME" original 1
    local source="$ZIP_CASE_ROOT/old/SillyTavern"
    zip_regression_install "$source" incoming 1
    curl() { return 0; }
    import_install "$(printf '%s' "$source" | base64 -w 0)"
}
zip_regression_expect_exit 10 folder-target-running zip_regression_folder_target_running
[[ "$(cat "$TEST_ROOT/zip-folder-target-running/install/data/default-user/state.txt")" == original ]] || fail "HTTP-active target was changed"

if [[ "$OSTYPE" != msys* ]]; then
    zip_regression_source_process_probe() {
        zip_regression_setup source-process-probe
        local source="$ZIP_CASE_ROOT/old/SillyTavern" probe_pid found=0
        zip_regression_install "$source" incoming 1
        printf 'setTimeout(() => {}, 5000);\n' > "$source/server.js"
        node "$source/server.js" &
        probe_pid=$!
        for _ in $(seq 1 20); do
            if installation_server_running "$source"; then found=1; break; fi
            sleep 0.05
        done
        # This is our own fixture child, never a discovered user's process.
        kill -TERM "$probe_pid" 2>/dev/null || true
        wait "$probe_pid" 2>/dev/null || true
        [[ "$found" == 1 ]] || fail "source Node server outside launcher PID was not detected"
        (cd "$source" && sleep 5) &
        probe_pid=$!
        found=0
        installation_server_running "$source" && found=1
        kill -TERM "$probe_pid" 2>/dev/null || true
        wait "$probe_pid" 2>/dev/null || true
        [[ "$found" == 0 ]] || fail "ordinary shell/sleep cwd was mistaken for a source server"
    }
    zip_regression_expect_exit 0 source-process-probe zip_regression_source_process_probe

    zip_regression_folder_symlink() {
        zip_regression_setup folder-symlink
        zip_regression_install "$ST_HOME" original 1
        local source="$ZIP_CASE_ROOT/old/SillyTavern"
        zip_regression_install "$source" incoming 1
        ln -s "$ST_HOME/data" "$source/data/outside"
        import_install "$(printf '%s' "$source" | base64 -w 0)"
    }
    zip_regression_expect_exit 53 folder-symlink zip_regression_folder_symlink
    [[ "$(cat "$TEST_ROOT/zip-folder-symlink/install/data/default-user/state.txt")" == original ]] || fail "unsafe folder modified destination"
fi

echo "manager single-pass ZIP and installation-move regression tests passed"

zip_regression_unsafe_archive() {
    local kind="$1"
    zip_regression_setup "unsafe-$kind"
    zip_regression_install "$ST_HOME" original 1
    node "$ROOT_DIR/scripts/zip-safety-fixture.cjs" "$kind" "$DOWNLOAD_DIR/SillyTavern-Import-Unsafe.zip"
    import_backup SillyTavern-Import-Unsafe.zip
}
for zip_unsafe_case in traversal:21 duplicate:21 symlink:22 encrypted:23 size-mismatch:20 unicode:21 crc:20; do
    zip_unsafe_name="${zip_unsafe_case%:*}"
    zip_regression_expect_exit "${zip_unsafe_case#*:}" "unsafe-$zip_unsafe_name" zip_regression_unsafe_archive "$zip_unsafe_name"
    [[ "$(cat "$TEST_ROOT/zip-unsafe-$zip_unsafe_name/install/data/default-user/state.txt")" == original ]] ||
        fail "unsafe $zip_unsafe_name ZIP modified live data"
done
echo "manager malicious-ZIP regression tests passed"
