#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HELPER="$ROOT_DIR/app/src/main/assets/progress.sh"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/st-progress-tests.XXXXXX")"
COPY_PID=""
cleanup() {
    [[ -n "$COPY_PID" ]] && kill "$COPY_PID" 2>/dev/null || true
    [[ "$TEST_ROOT" == */st-progress-tests.* && "$TEST_ROOT" != / ]] && rm -rf -- "$TEST_ROOT"
}
trap cleanup EXIT
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
pass() { printf 'PASS: %s\n' "$*"; }
native_path() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s\n' "$1"; fi
}
export ST_PROGRESS_FILE="$(native_path "$TEST_ROOT/progress.env")"
export ST_PROGRESS_LOG="$(native_path "$TEST_ROOT/operation.log")"
export ST_PROGRESS_HISTORY="$(native_path "$TEST_ROOT/history.log")"
export ST_PROGRESS_PHASE="Measured transfer"
export ST_PROGRESS_DETAIL="Copying fixture files"
export ST_PROGRESS_OPERATION="test-copy"

node - "$TEST_ROOT" <<'NODE'
const fs = require('fs');
const path = require('path');
const root = process.argv[2];
fs.mkdirSync(path.join(root, 'source/nested'), { recursive: true });
fs.writeFileSync(path.join(root, 'source/large.bin'), Buffer.alloc(5 * 1024 * 1024, 0x42));
fs.writeFileSync(path.join(root, 'source/nested/small.txt'), 'small');
fs.writeFileSync(path.join(root, 'source/empty.txt'), '');
fs.chmodSync(path.join(root, 'source/nested/small.txt'), 0o640);
fs.chmodSync(path.join(root, 'source/nested'), 0o750);
fs.utimesSync(path.join(root, 'source/nested/small.txt'), new Date('2020-01-01'), new Date('2021-02-03'));
    fs.utimesSync(path.join(root, 'source/nested'), new Date('2020-01-01'), new Date('2022-02-03'));
fs.writeFileSync(path.join(root, 'history.log'), 'old history line\n'.repeat(65000));
NODE

# Deliberate test-only pacing makes it possible to verify a fractional update on
# fast desktop disks; values must still be measured from bytes already written.
ST_PROGRESS_TEST_MODE=1 ST_PROGRESS_TEST_CHUNK_DELAY_MS=50 ST_PROGRESS_ITEM=ignored-directory-override \
    bash "$HELPER" copy "$TEST_ROOT/source" "$TEST_ROOT/copied" "" &
COPY_PID=$!
observed_partial=false
for ((attempt = 0; attempt < 100; attempt++)); do
    if [[ -f "$TEST_ROOT/progress.env" ]]; then
        copied_bytes="$(sed -n 's/^completed_bytes=//p' "$TEST_ROOT/progress.env")"
        total_bytes="$(sed -n 's/^total_bytes=//p' "$TEST_ROOT/progress.env")"
        if [[ "${copied_bytes:-0}" -gt 0 && "${total_bytes:-0}" -gt "${copied_bytes:-0}" ]]; then
            observed_partial=true
            node - "$TEST_ROOT/progress.env" <<'NODE'
const fs = require('fs');
const assert = require('assert');
const state = Object.fromEntries(fs.readFileSync(process.argv[2], 'utf8').trim().split('\n').map(line => {
    const equals = line.indexOf('='); return [line.slice(0, equals), line.slice(equals + 1)];
}));
assert.equal(state.progress_mode, 'bytes');
assert.equal(Number(state.percent), Math.floor(Number(state.completed_bytes) * 100 / Number(state.total_bytes)));
assert.ok(Number(state.heartbeat_at) >= Number(state.activity_at));
assert.ok(Number(state.activity_at) >= Number(state.phase_started_at));
assert.equal(state.status, 'running');
assert.ok(state.current_item_b64.length > 0);
NODE
            break
        fi
    fi
    kill -0 "$COPY_PID" 2>/dev/null || break
    sleep 0.1
done
wait "$COPY_PID" || fail "measured directory copy failed"
COPY_PID=""
[[ "$observed_partial" == true ]] || fail "no actual partial-byte update was observed"
node - "$TEST_ROOT" <<'NODE'
const fs = require('fs');
const path = require('path');
const assert = require('assert');
const root = process.argv[2];
for (const name of ['large.bin', 'nested/small.txt', 'empty.txt']) {
    const source = path.join(root, 'source', name), target = path.join(root, 'copied', name);
    assert.deepEqual(fs.readFileSync(source), fs.readFileSync(target));
    if (process.platform !== 'win32') assert.equal(fs.statSync(source).mode & 0o777, fs.statSync(target).mode & 0o777);
    assert.ok(Math.abs(fs.statSync(source).mtimeMs - fs.statSync(target).mtimeMs) < 0.01);
}
if (process.platform !== 'win32') assert.equal(fs.statSync(path.join(root, 'copied/nested')).mode & 0o777, 0o750);
assert.equal(fs.statSync(path.join(root, 'source/nested')).mtimeMs, fs.statSync(path.join(root, 'copied/nested')).mtimeMs);
const state = Object.fromEntries(fs.readFileSync(path.join(root, 'progress.env'), 'utf8').trim().split('\n').map(line => {
    const equals = line.indexOf('='); return [line.slice(0, equals), line.slice(equals + 1)];
}));
assert.equal(Number(state.completed_bytes), 5 * 1024 * 1024 + 5);
assert.equal(state.completed_bytes, state.total_bytes);
assert.equal(state.completed_files, '3');
assert.equal(state.completed_files, state.total_files);
assert.equal(state.percent, '100');
assert.equal(state.status, 'running');
assert.equal(state.phase, 'Measured transfer');
assert.equal(state.operation, 'test-copy');
assert.equal(state.current_item_b64, '');
const log = fs.readFileSync(path.join(root, 'operation.log'), 'utf8');
const history = fs.readFileSync(path.join(root, 'history.log'), 'utf8');
assert.ok(log.includes('[Measured transfer]'));
assert.ok(log.includes('large.bin'));
assert.ok(!log.includes('ignored-directory-override'), 'directory copies must show actual relative entries');
assert.ok(log.includes('5.00 MiB / 5.00 MiB'));
assert.ok(log.includes('파일 3 / 3개'));
assert.ok(log.includes('복사 완료'));
assert.ok(!log.includes('BBBBBBBBBBBBBBBB'), 'file payload must never appear in logs');
assert.ok(history.includes('복사 완료'));
assert.ok(fs.statSync(path.join(root, 'history.log')).size <= 1000000);
assert.ok(!history.startsWith('ld history'), 'history must start at a full line');
assert.ok(log.trim().split('\n').length <= 6, 'activity log must be throttled, not one line per chunk');
NODE
pass "exact byte/file progress, current filename, metadata, source retained, bounded activity history"

mkdir -p "$TEST_ROOT/excluded/node_modules" "$TEST_ROOT/excluded/nested/node_modules"
printf keep > "$TEST_ROOT/excluded/keep.txt"
printf skip > "$TEST_ROOT/excluded/node_modules/dependency.js"
printf skip > "$TEST_ROOT/excluded/nested/node_modules/dependency.js"
bash "$HELPER" copy "$TEST_ROOT/excluded" "$TEST_ROOT/exclusion-result" node_modules
[[ -f "$TEST_ROOT/exclusion-result/keep.txt" && ! -e "$TEST_ROOT/exclusion-result/node_modules" &&
   ! -e "$TEST_ROOT/exclusion-result/nested/node_modules" ]] || fail "excluded component was copied"
grep -Fxq 'total_bytes=4' "$TEST_ROOT/progress.env" || fail "excluded bytes were counted"
grep -Fxq 'total_files=1' "$TEST_ROOT/progress.env" || fail "excluded files were counted"
pass "excluded path components are skipped and excluded from totals"

mkdir -p "$TEST_ROOT/zero-source/empty-directory"
: > "$TEST_ROOT/zero-source/zero.txt"
bash "$HELPER" copy "$TEST_ROOT/zero-source" "$TEST_ROOT/zero-result" ""
grep -Fxq 'progress_mode=files' "$TEST_ROOT/progress.env" || fail "zero-byte files need file-count progress"
grep -Fxq 'completed_files=1' "$TEST_ROOT/progress.env" || fail "zero-byte file not counted"
[[ -d "$TEST_ROOT/zero-result/empty-directory" ]] || fail "empty directory was lost"
mkdir -p "$TEST_ROOT/empty-source"
bash "$HELPER" copy "$TEST_ROOT/empty-source" "$TEST_ROOT/empty-result" ""
grep -Fxq 'percent=100' "$TEST_ROOT/progress.env" || fail "empty directory copy did not finish"
pass "empty files and directories complete with honest file-count progress"

printf before > "$TEST_ROOT/single-target.txt"
ST_PROGRESS_ITEM='data/friendly-name.json' ST_PROGRESS_LOG="$(native_path "$TEST_ROOT/labelled.log")" \
    bash "$HELPER" copy "$TEST_ROOT/source/nested/small.txt" "$TEST_ROOT/single-target.txt" ""
[[ "$(< "$TEST_ROOT/single-target.txt")" == small ]] || fail "single file did not use exact target path"
grep -Fq 'data/friendly-name.json' "$TEST_ROOT/labelled.log" || fail "single-file destination label was not shown"
if grep -Fq 'small.txt' "$TEST_ROOT/labelled.log"; then fail "transport source name leaked instead of display label"; fi
if bash "$HELPER" copy "$TEST_ROOT/source" "$TEST_ROOT/source/inside" "" 2>/dev/null; then
    fail "overlapping source/destination accepted"
fi
[[ ! -e "$TEST_ROOT/source/inside" ]] || fail "overlap failure mutated source"
if bash "$HELPER" copy "$TEST_ROOT/source" "$TEST_ROOT" "" 2>/dev/null; then
    fail "destination containing source accepted"
fi
pass "single-file replacement and bidirectional overlap rejection"

# The source changes during the controlled copy. Its replacement is deliberately
# retained, and an existing destination file must not become a partial file.
printf original-destination > "$TEST_ROOT/changed-target.bin"
ST_PROGRESS_TEST_MODE=1 ST_PROGRESS_TEST_CHUNK_DELAY_MS=50 \
    bash "$HELPER" copy "$TEST_ROOT/source/large.bin" "$TEST_ROOT/changed-target.bin" "" 2> "$TEST_ROOT/change-error.log" &
COPY_PID=$!
sleep 0.5
printf changed >> "$TEST_ROOT/source/large.bin"
if wait "$COPY_PID"; then fail "changing source was accepted"; fi
COPY_PID=""
[[ "$(< "$TEST_ROOT/changed-target.bin")" == original-destination ]] || fail "failed copy replaced existing destination"
grep -Fq COPY_SOURCE_CHANGED "$TEST_ROOT/change-error.log" || fail "source-change failure was not identified"
[[ -f "$TEST_ROOT/source/large.bin" ]] || fail "source was removed on failure"
[[ -z "$(find "$TEST_ROOT" -name '.st-copy-*' -print -quit)" ]] || fail "failed copy left temporary payload"
pass "source changes abort without replacing destination or deleting source"

if [[ "$(node -p 'process.platform')" != win32 ]]; then
    mkdir -p "$TEST_ROOT/links-source" "$TEST_ROOT/outside"
    printf outside > "$TEST_ROOT/outside/secret.txt"
    printf source > "$TEST_ROOT/links-source/data.txt"
    ln -s "$TEST_ROOT/outside/secret.txt" "$TEST_ROOT/links-source/link.txt"
    if bash "$HELPER" copy "$TEST_ROOT/links-source" "$TEST_ROOT/rejected-links" "" 2>/dev/null; then
        fail "symlink was followed or allowed by default"
    fi
    [[ ! -e "$TEST_ROOT/rejected-links" ]] || fail "unsafe source was partially copied"
    bash "$HELPER" copy "$TEST_ROOT/links-source" "$TEST_ROOT/preserved-links" "" preserve-links
    [[ -L "$TEST_ROOT/preserved-links/link.txt" ]] || fail "explicit symlink preservation lost link node"
    [[ "$(readlink "$TEST_ROOT/preserved-links/link.txt")" == "$TEST_ROOT/outside/secret.txt" ]] || fail "symlink target changed"
    grep -Fxq 'total_bytes=6' "$TEST_ROOT/progress.env" || fail "link target bytes were followed and counted"
    mkdir -p "$TEST_ROOT/unsafe-destination"
    ln -s "$TEST_ROOT/outside" "$TEST_ROOT/unsafe-destination/nested"
    if bash "$HELPER" copy "$TEST_ROOT/source" "$TEST_ROOT/unsafe-destination" "" 2>/dev/null; then
        fail "destination symlink was followed"
    fi
    [[ ! -e "$TEST_ROOT/outside/small.txt" ]] || fail "copy escaped through destination symlink"
    ln "$TEST_ROOT/links-source/data.txt" "$TEST_ROOT/links-source/hard.txt"
    bash "$HELPER" copy "$TEST_ROOT/links-source" "$TEST_ROOT/hard-links" "" preserve-links
    [[ "$(stat -c %i "$TEST_ROOT/hard-links/data.txt")" == "$(stat -c %i "$TEST_ROOT/hard-links/hard.txt")" ]] || fail "hard links were duplicated"
    mkdir -p "$TEST_ROOT/special-source"
    mkfifo "$TEST_ROOT/special-source/pipe"
    if bash "$HELPER" copy "$TEST_ROOT/special-source" "$TEST_ROOT/special-result" "" 2>/dev/null; then
        fail "special device accepted"
    fi
    pass "symlink policy, destination containment, hard links, special-file rejection"
else
    printf 'SKIP: Linux symlink, hard-link and special-file semantics (Windows host)\n'
fi

printf 'Measured transfer regression tests passed.\n'
