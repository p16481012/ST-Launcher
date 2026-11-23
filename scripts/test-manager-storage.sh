#!/usr/bin/env bash
# Isolated permission/nonce/path/port tests. No Android or user files are touched.
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/st-launcher-storage.XXXXXX")"
trap '[[ "$TEST_ROOT" == */st-launcher-storage.* && "$TEST_ROOT" != / ]] && rm -rf -- "$TEST_ROOT"' EXIT
export ST_HOME="$TEST_ROOT/SillyTavern" ST_LAUNCHER_HOME="$TEST_ROOT/launcher" ST_DOWNLOAD_DIR="$TEST_ROOT/downloads"
mkdir -p "$ST_HOME" "$ST_DOWNLOAD_DIR"
source "$ROOT_DIR/app/src/main/assets/manager.sh"
fail() { echo "FAIL: $*" >&2; exit 1; }
name=SillyTavern-Probe-12345678-1234-1234-1234-123456789abc.txt
token=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
check_reason() {
    local expected="$1" output
    output="$(verify_shared_read "$name" "$token")"
    [[ "$output" == *"shared_read_reason=$expected"* ]] || fail "expected $expected, got $output"
    if [[ "$expected" == ok ]]; then [[ "$output" == *shared_read_ready=1* ]] || fail 'success not ready'
    else [[ "$output" == *shared_read_ready=0* ]] || fail 'failure marked ready'; fi
    [[ "$output" != *"$token"* ]] || fail 'probe leaked token'
}
check_reason missing
printf '%s' "$token" > "$DOWNLOAD_DIR/$name"
check_reason ok
[[ -f "$DOWNLOAD_DIR/$name" ]] || fail 'manager deleted probe'
printf '%064d' 0 > "$DOWNLOAD_DIR/$name"
check_reason content_mismatch
printf '%s' "$token" > "$DOWNLOAD_DIR/$name"
printf x >> "$DOWNLOAD_DIR/$name"
check_reason content_mismatch
printf '%s' "$token" > "$DOWNLOAD_DIR/$name"
( head() { return 1; }; check_reason read_denied )
( DOWNLOAD_DIR="$TEST_ROOT/not-connected"; check_reason storage_unavailable )
for invalid in '../outside' '/tmp/probe' "${name}/x"; do
    if verify_shared_read "$invalid" "$token" >/dev/null 2>&1; then fail 'unsafe name accepted'; fi
done
if verify_shared_read "$name" '../bad-token' >/dev/null 2>&1; then fail 'invalid token accepted'; fi
if [[ "$(uname -s)" != MINGW* && "$(uname -s)" != MSYS* ]]; then
    mv "$DOWNLOAD_DIR/$name" "$TEST_ROOT/probe-original"
    ln -s "$TEST_ROOT/probe-original" "$DOWNLOAD_DIR/$name"
    check_reason read_denied
fi

# Resolve roots without creating anything in /storage; realpath is controlled only
# in this subshell, and every candidate still passes the production root validator.
(
    realpath() { printf '%s' /storage/emulated/10; }
    [[ "$(shared_storage_root)" == /storage/emulated/10 ]] || fail 'secondary profile root not resolved'
)
(
    realpath() { printf '%s' /storage/emulated/10/../0; }
    EXTERNAL_STORAGE=/storage/emulated/10
    [[ -z "$(shared_storage_root)" ]] || fail 'traversal root accepted'
)
(
    realpath() { printf '%s' /; }
    EXTERNAL_STORAGE=/
    [[ -z "$(shared_storage_root)" ]] || fail 'whole device root accepted'
)

# Stub only YAML.parse for deterministic fixtures; production parser still uses
# the installed yaml package. JSON is a YAML subset; failures emulate malformed YAML.
mkdir -p "$ST_HOME/node_modules/yaml"
printf '%s\n' 'exports.parse = JSON.parse;' > "$ST_HOME/node_modules/yaml/index.js"
printf '%s\n' '{"port":12556,"listen":true}' > "$ST_HOME/config.yaml"
before="$(sha256sum "$ST_HOME/config.yaml")"
settings="$(server_connection_settings)"
[[ "$settings" == *config_port=12556* && "$settings" == *external_access=1* ]] || fail 'config port not read'
[[ "$(sha256sum "$ST_HOME/config.yaml")" == "$before" ]] || fail 'port inspection wrote config'
for bad in '0' '65536' '8000.5' '"8000"' 'null'; do
    printf '{"port":%s}\n' "$bad" > "$ST_HOME/config.yaml"
    settings="$(server_connection_settings)"
    [[ "$(printf '%s\n' "$settings" | sed -n 's/^config_port=//p')" == '' ]] || fail 'invalid port accepted'
done
printf '%s' 'invalid YAML [' > "$ST_HOME/config.yaml"
settings="$(server_connection_settings)"
[[ "$settings" == *config_port=* && "$settings" == *external_access=0* ]] || fail 'invalid config broke inspection'
mv "$ST_HOME/node_modules/yaml" "$TEST_ROOT/yaml-disabled"
settings="$(server_connection_settings)"
[[ "$settings" == *config_port=* ]] || fail 'fresh install requires yaml'
echo 'PASS: real shared-file read, nonce mismatch, denied read, roots and read-only config port'
