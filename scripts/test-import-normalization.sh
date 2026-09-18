#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/st-import-normalization.XXXXXX")"
cleanup() {
    case "$TEST_ROOT" in "${TMPDIR:-/tmp}"/st-import-normalization.*) rm -rf -- "$TEST_ROOT" ;; esac
}
trap cleanup EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
for tool in node unzip git; do command -v "$tool" >/dev/null || fail "Missing test dependency: $tool"; done
fixture_zip() { node "$ROOT_DIR/scripts/import-normalization-fixture.cjs" "$@"; }
put() { mkdir -p "$(dirname "$1")"; printf '%s\n' "$2" > "$1"; }
expect() { [[ "$1" == "$2" ]] || fail "$3 (actual: $1; expected: $2)"; }
run_case() {
    local expected="$1" name="$2" code; shift 2
    set +e
    (trap - EXIT INT TERM; set -Eeuo pipefail; "$@") > "$TEST_ROOT/$name.output" 2>&1
    code=$?
    set -e
    if [[ "$code" != "$expected" ]]; then
        cat "$TEST_ROOT/$name.output" >&2
        tail -n 60 "$TEST_ROOT/$name/launcher/logs/operation.log" >&2 2>/dev/null || true
        fail "$name returned $code, expected $expected"
    fi
    echo "PASS: $name"
}
setup_case() {
    CASE_ROOT="$TEST_ROOT/$1"
    export ST_HOME="$CASE_ROOT/install" ST_LAUNCHER_HOME="$CASE_ROOT/launcher" ST_DOWNLOAD_DIR="$CASE_ROOT/downloads"
    export PREFIX="$CASE_ROOT/prefix" GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
    mkdir -p "$ST_HOME" "$ST_DOWNLOAD_DIR" "$CASE_ROOT/payload"
    source "$ROOT_DIR/app/src/main/assets/manager.sh"
    # Only orchestration prerequisites are replaced; actual ZIP metadata,
    # extraction, measured copy, restore transaction and cleanup remain active.
    ensure_import_runtime() { :; }
    ensure_archive_tools() { :; }
    is_running() { return 1; }
    ensure_restore_target_stopped() { :; }
    # Git Bash cannot set RLIMIT_FSIZE for native Windows Node. The production
    # helper's per-entry streamed size checks still run; Linux keeps ulimit.
    if command -v cygpath >/dev/null 2>&1; then ulimit() { :; }; fi
    begin_operation() {
        CURRENT_OPERATION="$1"; OPERATION_STARTED_EPOCH="$(date +%s)"
        trap 'operation_cleanup $?' EXIT
    }
    put "$ST_HOME/package.json" '{"version":"1.0.0"}'
    put "$ST_HOME/server.js" '// fixture only'
    put "$ST_HOME/data/default-user/characters/current.png" character
    put "$ST_HOME/data/default-user/chats/current.jsonl" chat
    put "$ST_HOME/data/default-user/settings.json" settings
    put "$ST_HOME/data/other-user/chats/other.jsonl" other-user
    put "$ST_HOME/config.yaml" 'port: 9000'
}
make_import() {
    fixture_zip "$CASE_ROOT/original.zip" "$CASE_ROOT/payload" .
    ORIGINAL_SHA="$(sha256sum "$CASE_ROOT/original.zip" | cut -d ' ' -f 1)"
    put "$CASE_ROOT/original.sha256" "$ORIGINAL_SHA"
    cp "$CASE_ROOT/original.zip" "$DOWNLOAD_DIR/SillyTavern-Import-Fixture.zip"
}
check_original() {
    expect "$(sha256sum "$CASE_ROOT/original.zip" | cut -d ' ' -f 1)" "$ORIGINAL_SHA" 'Original ZIP changed'
}
native_scope() {
    local scenario="$1" wrapper="$2" kind="$3" source
    setup_case "$scenario"
    source="$CASE_ROOT/payload${wrapper:+/$wrapper}"
    put "$source/.st-launcher-manifest" "$(printf 'format=st-launcher-backup-v1\nitems=%s\ncustom=chats\nsecrets=0\n' "$kind")"
    if [[ "$kind" == custom ]]; then
        put "$source/data/default-user/chats/incoming.jsonl" incoming-chat
    else
        put "$source/data/default-user/extensions/example/index.js" incoming-extension
    fi
    make_import
    import_backup SillyTavern-Import-Fixture.zip
    check_original
    expect "$(cat "$ST_HOME/data/default-user/characters/current.png")" character 'Unselected characters were replaced'
    expect "$(cat "$ST_HOME/data/default-user/settings.json")" settings 'Unselected settings were replaced'
    expect "$(cat "$ST_HOME/data/other-user/chats/other.jsonl")" other-user 'Another account was replaced'
    if [[ "$kind" == custom ]]; then
        expect "$(cat "$ST_HOME/data/default-user/chats/incoming.jsonl")" incoming-chat 'Custom data missing'
        [[ ! -e "$ST_HOME/data/default-user/chats/current.jsonl" ]] || fail 'Selected chats were not replaced'
    else
        expect "$(cat "$ST_HOME/data/default-user/extensions/example/index.js")" incoming-extension 'Extension missing'
        expect "$(cat "$ST_HOME/data/default-user/chats/current.jsonl")" chat 'Unselected chats were replaced'
    fi
}
legacy_rejected() {
    local scenario="$1" wrapper="$2" config="$3"
    setup_case "$scenario"
    local source="$CASE_ROOT/payload${wrapper:+/$wrapper}"
    put "$source/public/characters/incoming.png" legacy-character
    put "$source/public/chats/chat.jsonl" legacy-chat
    [[ "$config" == no ]] || put "$source/config.yaml" 'port: 8000'
    make_import
    import_backup SillyTavern-Import-Fixture.zip
}
check_legacy_rejection() {
    local name="$1"
    grep -Fq 'error_code=IMPORT_FORMAT_UNSUPPORTED' "$TEST_ROOT/$name.output" || fail 'Missing unsupported-format error code'
    grep -Fq '구형 SillyTavern 백업은 직접 복원할 수 없습니다' "$TEST_ROOT/$name.output" || fail 'Missing legacy guidance'
    expect "$(cat "$TEST_ROOT/$name/install/config.yaml")" 'port: 9000' 'Legacy rejection modified config'
    expect "$(cat "$TEST_ROOT/$name/install/data/default-user/characters/current.png")" character 'Legacy rejection modified data'
    [[ -s "$TEST_ROOT/$name/original.zip" ]] || fail 'Original ZIP removed after rejected import'
    expect "$(sha256sum "$TEST_ROOT/$name/original.zip" | cut -d ' ' -f 1)" "$(cat "$TEST_ROOT/$name/original.sha256")" 'Rejected original ZIP changed'
    [[ ! -e "$TEST_ROOT/$name/downloads/SillyTavern-Import-Fixture.zip" ]] || fail 'Disposable ZIP was not cleaned'
}
generic_data() {
    local scenario="$1" wrapper="$2" data_root="$3"
    setup_case "$scenario"
    local source="$CASE_ROOT/payload${wrapper:+/$wrapper}${data_root:+/$data_root}"
    put "$source/default-user/characters/incoming.png" new-character
    put "$source/second-user/chats/incoming.jsonl" second-user
    make_import
    import_backup SillyTavern-Import-Fixture.zip
    check_original
    expect "$(cat "$ST_HOME/data/default-user/characters/incoming.png")" new-character 'Default account was remapped'
    expect "$(cat "$ST_HOME/data/second-user/chats/incoming.jsonl")" second-user 'Second account was dropped'
}
invalid_native() {
    setup_case invalid-native
    put "$CASE_ROOT/payload/one/two/.st-launcher-manifest" 'format=invalid'
    put "$CASE_ROOT/payload/one/two/data/default-user/chats/incoming.jsonl" incoming
    make_import
    import_backup SillyTavern-Import-Fixture.zip
}
backup_items() {
    local name="$1" selected="$2" expected="$3" custom="${4:-}" extensions="${5:-no}"
    setup_case "$name"
    rm "$ST_HOME/config.yaml"
    put "$ST_HOME/data/default-user/file-only" 'Not a custom folder'
    if [[ "$extensions" == empty ]]; then mkdir -p "$ST_HOME/public/scripts/extensions/third-party"; fi
    if [[ "$extensions" == per-user ]]; then mkdir -p "$ST_HOME/data/default-user/extensions"; fi
    # Real Info-ZIP is used when available. The dependency-free fallback only
    # creates Store ZIP bytes; all production archive/CRC validation still runs.
    if ! command -v zip >/dev/null 2>&1; then
        zip() { [[ "$1" == -q && "$3" == .st-launcher-manifest ]] || return 99; fixture_zip "$2" "$PWD" "$3"; }
        eval "$(declare -f measured_archive | sed '1s/measured_archive/original_measured_archive/')"
        measured_archive() {
            if [[ "$3" == compress ]]; then fixture_zip "$5" "$4" "${@:6}";
            else original_measured_archive "$@"; fi
        }
    fi
    backup_selected "$selected" 0 "$custom"
    local archive manifest
    archive="$(find "$DOWNLOAD_DIR" -maxdepth 1 -name 'SillyTavern-Launcher-*.zip' -type f)"
    [[ -n "$archive" ]] || fail 'Backup ZIP missing'
    manifest="$(unzip -p "$archive" .st-launcher-manifest)"
    expect "$(sed -n 's/^items=//p' <<< "$manifest")" "$expected" 'Manifest contains absent selected item'
    if [[ "$selected" == *custom* ]]; then expect "$(sed -n 's/^custom=//p' <<< "$manifest")" chats 'Absent custom folder retained'; fi
    cp "$archive" "$DOWNLOAD_DIR/SillyTavern-Import-Fixture.zip"
    put "$ST_HOME/config.yaml" 'port: 9000'
    import_backup SillyTavern-Import-Fixture.zip
    expect "$(cat "$ST_HOME/config.yaml")" 'port: 9000' 'Absent config unexpectedly restored'
}
empty_selection() {
    setup_case empty-selection
    rm "$ST_HOME/config.yaml"
    backup_selected config,extensions 0 ''
}

run_case 0 native-custom native_scope native-custom '' custom
run_case 0 wrapped-custom native_scope wrapped-custom Backup custom
run_case 0 nested-custom native_scope nested-custom outer/inner/Backup custom
run_case 0 wrapped-extensions native_scope wrapped-extensions outer/inner extensions
run_case 0 data-root generic_data data-root '' data
run_case 0 users-root generic_data users-root '' ''
run_case 0 wrapped-data generic_data wrapped-data outer/inner data
run_case 23 invalid-native invalid_native
grep -Fq 'error_code=IMPORT_FORMAT_UNSUPPORTED' "$TEST_ROOT/invalid-native.output" || fail 'Invalid manifest fell back to generic data'
expect "$(cat "$TEST_ROOT/invalid-native/install/data/default-user/chats/current.jsonl")" chat 'Invalid manifest modified existing data'
[[ -s "$TEST_ROOT/invalid-native/original.zip" ]] || fail 'Invalid manifest removed original ZIP'
for scenario in legacy-config legacy-no-config legacy-wrapped; do
    wrapper=''; config=yes
    [[ "$scenario" != legacy-wrapped ]] || wrapper=outer/inner
    [[ "$scenario" != legacy-no-config ]] || config=no
    run_case 23 "$scenario" legacy_rejected "$scenario" "$wrapper" "$config"
    check_legacy_rejection "$scenario"
done
run_case 0 missing-config backup_items missing-config user_data,config user_data
run_case 0 missing-extensions backup_items missing-extensions user_data,extensions,config user_data
run_case 0 empty-global-extensions backup_items empty-global-extensions extensions,config extensions '' empty
run_case 0 empty-user-extensions backup_items empty-user-extensions extensions,config extensions '' per-user
run_case 0 selected-custom backup_items selected-custom custom,config custom chats,missing,file-only
run_case 0 data-with-custom backup_items data-with-custom user_data,custom,config user_data,custom chats,missing
run_case 8 empty-selection empty_selection
[[ -z "$(find "$TEST_ROOT/empty-selection/downloads" -name '*.zip' -type f)" ]] || fail 'Empty selection published a ZIP'
echo 'PASS: import normalization and selected-backup manifest regressions'
