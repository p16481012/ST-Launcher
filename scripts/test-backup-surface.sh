#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/st-backup-surface.XXXXXX")"
cleanup() { case "$TEST_ROOT" in "${TMPDIR:-/tmp}"/st-backup-surface.*) rm -rf -- "$TEST_ROOT" ;; esac; }
trap cleanup EXIT
export ST_HOME="$TEST_ROOT/install" ST_LAUNCHER_HOME="$TEST_ROOT/launcher" ST_DOWNLOAD_DIR="$TEST_ROOT/downloads"
mkdir -p "$ST_HOME" "$ST_DOWNLOAD_DIR"
source "$ROOT_DIR/app/src/main/assets/manager.sh"
fail() { echo "FAIL: $*" >&2; exit 1; }
expect_exit() {
    local expected="$1" code=0; shift
    (set -e; "$@") > "$TEST_ROOT/output" 2>&1 || code=$?
    [[ "$code" == "$expected" ]] || { cat "$TEST_ROOT/output" >&2; fail "expected $expected, got $code"; }
}
ensure_archive_tools() { command -v unzip >/dev/null; }
is_running() { return 1; }
# A validated name reaches dispatch. No real installation or deletion is done.
begin_operation() { exit 77; }
names=('SillyTavern-Launcher-20261123-120000.zip' 'SillyTavern-Launcher-20261123-120000 (1).zip' 'SillyTavern-Launcher-20261123-120000-1234.zip')
for name in "${names[@]}"; do
    node "$ROOT_DIR/scripts/backup-surface-fixture.cjs" "$DOWNLOAD_DIR/$name" valid
    expect_exit 77 restore_backup "$name"
    expect_exit 77 delete_backup "$name"
done
listed="$(list_backups)"
[[ "$(grep -c '^backup' <<< "$listed")" == 3 ]] || fail 'name rules differ between list and actions'
# Keep all three backups visible if the read-only preview cannot measure space.
# A real measured zero is different; neither case authorizes an extraction.
for space in unknown 0 123456789; do
    listed="$(df() { [[ "$space" != unknown ]] || return 1; printf 'header\nfs 0 0 %s 0%% /data\n' "$space"; }; list_backups)"
    expected=""; [[ "$space" == unknown ]] || expected=$((space * 1024))
    awk -F '\t' -v want="$expected" '$1=="backup"{if (("x" $15)!=("x" want)) exit 1; n++} END {if (n!=3) exit 1}' <<< "$listed" || fail "wrong restore space preview: $space"
done
expect_exit 77 delete_backups "$(printf '%s\n' "${names[@]}" | base64 -w 0)"
for name in '../SillyTavern-Launcher-20261123-120000.zip' 'SillyTavern-Launcher-20261123-120000/evil.zip' 'SillyTavern-Launcher-20261123-120000\evil.zip' $'SillyTavern-Launcher-20261123-120000\n.zip'; do
    expect_exit 64 restore_backup "$name"
    expect_exit 64 delete_backup "$name"
done
echo 'PASS: normal, collision and file-manager suffix names work consistently; paths/control characters fail closed'
for kind in limit oversized duplicate nul corrupt encrypted; do
    file="$TEST_ROOT/$kind.zip"
    node "$ROOT_DIR/scripts/backup-surface-fixture.cjs" "$file" "$kind"
    if [[ "$kind" == limit ]]; then
        read_backup_manifest "$file" > "$TEST_ROOT/manifest"
        [[ "$(wc -c < "$TEST_ROOT/manifest" | tr -d ' ')" == 65536 ]] || fail 'valid 64 KiB UTF-8 metadata was changed'
    else
        if read_backup_manifest "$file" > "$TEST_ROOT/manifest" 2>/dev/null; then fail "$kind metadata accepted"; fi
        invalid="SillyTavern-Launcher-20261123-120001.zip"
        cp "$file" "$DOWNLOAD_DIR/$invalid"
        expect_exit 23 delete_backup "$invalid"
        [[ -f "$DOWNLOAD_DIR/$invalid" ]] || fail 'invalid backup was deleted'
        [[ "$(list_backups | grep -c '^backup')" == 3 ]] || fail "$kind metadata was displayed"
    fi
done
if [[ "$OSTYPE" != msys* ]]; then
    linked='SillyTavern-Launcher-20261123-120002.zip'
    ln -s "$DOWNLOAD_DIR/${names[0]}" "$DOWNLOAD_DIR/$linked"
    expect_exit 6 restore_backup "$linked"
    expect_exit 6 delete_backup "$linked"
    [[ "$(list_backups | grep -c '^backup')" == 3 ]] || fail 'symlink backup was displayed'
else
    echo 'SKIP: symlink backup fixture requires Linux filesystem semantics'
fi
echo 'PASS: bounded metadata preserves 64 KiB and rejects oversized/duplicate/NUL/corrupt/encrypted metadata'
