#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKER="$ROOT_DIR/app/src/main/assets/safe-backup.sh"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/st-safety-storage.XXXXXX")"
cleanup() { case "$TEST_ROOT" in "${TMPDIR:-/tmp}"/st-safety-storage.*) rm -rf -- "$TEST_ROOT" ;; esac; }
trap cleanup EXIT
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
native_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

# Faults are confined to the worker Node process. No device, network, package
# manager, user files or production test hooks are involved.
mkdir -p "$TEST_ROOT/source/data" "$TEST_ROOT/source/.git" "$TEST_ROOT/source/node_modules"
node --input-type=commonjs - "$TEST_ROOT" <<'NODE'
const fs = require('fs'), path = require('path'), crypto = require('crypto');
const root = process.argv[2];
fs.writeFileSync(path.join(root, 'source/data/payload.bin'), crypto.randomBytes(1024 * 1024));
fs.writeFileSync(path.join(root, 'source/data/keep.txt'), 'original data must survive\n');
fs.writeFileSync(path.join(root, 'source/.git/ignored'), 'git');
fs.writeFileSync(path.join(root, 'source/node_modules/ignored'), 'modules');
fs.writeFileSync(path.join(root, 'storage-fault.cjs'), String.raw`
const fs = require('fs'), path = require('path'), cp = require('child_process');
const mode = process.env.SAFETY_STORAGE_MODE;
const archive = path.resolve(process.env.SAFETY_STORAGE_ARCHIVE);
const trace = process.env.SAFETY_STORAGE_TRACE;
const original = { openSync: fs.openSync, fsyncSync: fs.fsyncSync, writeSync: fs.writeSync,
    spawn: cp.spawn, linkSync: fs.linkSync, link: fs.link, createWriteStream: fs.createWriteStream };
let spawned = false, injected = false, archiveSyncs = 0;
function mark(value) { fs.appendFileSync(trace, value + '\n'); }
function denied(code, operation) { return Object.assign(new Error('fixture ' + operation + ': ' + code), { code }); }
function isArchivePath(value) { return typeof value === 'string' && path.resolve(value) === archive; }
function isArchiveFd(fd) {
    try {
        const a = fs.fstatSync(fd), b = fs.lstatSync(archive);
        return a.isFile() && b.isFile() && a.dev === b.dev && a.ino === b.ino;
    } catch (_) { return false; }
}
for (const method of ['linkSync', 'link']) {
    fs[method] = (...args) => {
        mark('hardlink-attempt');
        throw denied(mode === 'deny-link-eperm' ? 'EPERM' : 'EACCES', 'hardlink');
    };
}
fs.createWriteStream = function(target, options) {
    const stream = original.createWriteStream.call(this, target, options);
    if (isArchivePath(target)) stream.once('close', () => mark('archive-stream-closed'));
    return stream;
};
fs.openSync = function(target, flags, ...args) {
    if (isArchivePath(target)) {
        if (mode === 'open-denied') throw denied('EACCES', 'output open');
        const exclusive = typeof flags === 'number' ? (flags & fs.constants.O_EXCL) !== 0 : String(flags).includes('x');
        if (mode === 'reservation-race' && exclusive && !injected) {
            injected = true;
            fs.writeFileSync(archive, 'unrelated concurrent backup');
            mark('reservation-race');
        }
    }
    return original.openSync.call(this, target, flags, ...args);
};
fs.writeSync = function(fd, ...args) {
    if (mode === 'write-denied' && isArchiveFd(fd)) throw denied('EIO', 'preflight write');
    return original.writeSync.call(this, fd, ...args);
};
fs.fsyncSync = function(fd) {
    if (mode === 'directory-sync-denied' && fs.fstatSync(fd).isDirectory()) throw denied('EACCES', 'directory sync');
    if (isArchiveFd(fd)) {
        archiveSyncs++;
        if (mode === 'late-sync-denied' && spawned) throw denied('EIO', 'completed archive sync');
        if ((mode === 'replacement-race' || mode === 'replacement-without-error') && spawned && !injected) {
            injected = true;
            fs.renameSync(archive, archive + '.owned');
            fs.writeFileSync(archive, 'unrelated replacement backup');
            mark('replacement-race');
            if (mode === 'replacement-race') throw denied('EIO', 'completed archive replaced');
        }
    }
    return original.fsyncSync.call(this, fd);
};
cp.spawn = function(name, args, options) {
    if (name === 'tar') {
        spawned = true;
        mark('tar-started');
        mark('preflight-archive-syncs=' + archiveSyncs);
        if (mode === 'spawn-throws') throw denied('EACCES', 'tar spawn');
    }
    const child = original.spawn.call(this, name, args, options);
    if (name === 'tar' && mode === 'cancel-compression') setImmediate(() => process.emit('SIGTERM'));
    return child;
};
`);
NODE
SOURCE_HASH="$(sha256sum "$TEST_ROOT/source/data/payload.bin" | cut -d ' ' -f1)"

run_case() {
    local name="$1" expected="$2" code=0
    CASE_ROOT="$TEST_ROOT/$name"
    mkdir -p "$CASE_ROOT"
    CASE_ARCHIVE="$CASE_ROOT/backup.tar.gz"
    env NODE_OPTIONS="--require=\"$(native_path "$TEST_ROOT/storage-fault.cjs")\"" \
        SAFETY_STORAGE_MODE="$name" SAFETY_STORAGE_ARCHIVE="$(native_path "$CASE_ARCHIVE")" \
        SAFETY_STORAGE_TRACE="$(native_path "$CASE_ROOT/trace")" \
        ST_PROGRESS_FILE="$(native_path "$CASE_ROOT/progress.env")" \
        ST_PROGRESS_LOG="$(native_path "$CASE_ROOT/operation.log")" \
        ST_PROGRESS_HISTORY="$(native_path "$CASE_ROOT/history.log")" \
        ST_PROGRESS_OPERATION=update ST_SAFETY_RESERVE_BYTES=33554432 \
        bash "$WORKER" "$TEST_ROOT/source" "$CASE_ARCHIVE" > "$CASE_ROOT/output" 2>&1 || code=$?
    if [[ "$code" != "$expected" ]]; then cat "$CASE_ROOT/output" >&2; fail "$name: expected exit $expected, got $code"; fi
    [[ "$(sha256sum "$TEST_ROOT/source/data/payload.bin" | cut -d ' ' -f1)" == "$SOURCE_HASH" ]] || fail "$name changed source data"
    [[ "$(cat "$TEST_ROOT/source/data/keep.txt")" == 'original data must survive' ]] || fail "$name removed original data"
    [[ -z "$(find "$CASE_ROOT" -maxdepth 1 -type d -name '.st-safety-*' -print -quit)" ]] || fail "$name left worker scratch"
    if (( expected != 0 )); then
        ! grep -q '^safety_backup_b64=' "$CASE_ROOT/output" || fail "$name reported success"
    fi
}
assert_no_tar() { [[ ! -f "$CASE_ROOT/trace" ]] || ! grep -Fxq tar-started "$CASE_ROOT/trace" || fail 'compression ran before storage checks passed'; }
assert_clean_archive() { [[ ! -e "$CASE_ARCHIVE" && ! -L "$CASE_ARCHIVE" ]] || fail 'failed/cancelled output was retained'; }

for name in deny-link-eacces deny-link-eperm; do
    run_case "$name" 0
    ! grep -Fxq hardlink-attempt "$CASE_ROOT/trace" || fail 'backup attempted a forbidden hardlink'
    grep -Eq '^preflight-archive-syncs=[1-9][0-9]*$' "$CASE_ROOT/trace" || fail 'destination was not synced before tar'
    grep -q '^safety_backup_b64=' "$CASE_ROOT/output" || fail 'completed backup result missing'
    mkdir "$CASE_ROOT/extracted"
    tar -xzf "$CASE_ARCHIVE" -C "$CASE_ROOT/extracted"
    cmp "$TEST_ROOT/source/data/payload.bin" "$CASE_ROOT/extracted/data/payload.bin"
    [[ ! -e "$CASE_ROOT/extracted/.git" && ! -e "$CASE_ROOT/extracted/node_modules" ]] || fail 'excluded source trees copied'
    echo "PASS: completed safety backup remains readable when hardlinks are denied ($name)"
done

for name in open-denied write-denied; do
    run_case "$name" 36
    assert_no_tar; assert_clean_archive
    grep -Fq 'fixture ' "$CASE_ROOT/output" || fail 'original storage failure hidden'
    echo "PASS: $name stops before compression and preserves source"
done
if [[ "$(uname -s)" != MINGW* && "$(uname -s)" != MSYS* ]]; then
    run_case directory-sync-denied 36
    assert_no_tar; assert_clean_archive
    grep -Fq 'fixture directory sync: EACCES' "$CASE_ROOT/output" || fail 'directory sync failure hidden'
    echo 'PASS: directory sync failure detected before compression'
else
    echo 'SKIP: directory fsync requires Linux CI'
fi

run_case late-sync-denied 36
grep -Fxq tar-started "$CASE_ROOT/trace" || fail 'late sync fault did not follow compression'
grep -Fq 'fixture completed archive sync: EIO' "$CASE_ROOT/output" || fail 'late sync failure hidden'
assert_clean_archive
echo 'PASS: post-compression durability failure cannot publish success or delete source'

run_case spawn-throws 36
grep -Fq 'fixture tar spawn: EACCES' "$CASE_ROOT/output" || fail 'synchronous spawn error hidden'
grep -Fxq archive-stream-closed "$CASE_ROOT/trace" || fail 'spawn error leaked archive stream'
assert_clean_archive
echo 'PASS: synchronous tar startup failure closes the output stream before cleanup'

mkdir -p "$TEST_ROOT/existing"
printf 'unrelated previous backup' > "$TEST_ROOT/existing/backup.tar.gz"
run_case existing 36
assert_no_tar
[[ "$(cat "$CASE_ARCHIVE")" == 'unrelated previous backup' ]] || fail 'existing archive overwritten/deleted'
run_case reservation-race 36
assert_no_tar
grep -Fxq reservation-race "$CASE_ROOT/trace" || fail 'exclusive reservation race not exercised'
[[ "$(cat "$CASE_ARCHIVE")" == 'unrelated concurrent backup' ]] || fail 'concurrent archive overwritten/deleted'
echo 'PASS: pre-existing and concurrently reserved archive names are preserved'

if [[ "$(uname -s)" != MINGW* && "$(uname -s)" != MSYS* ]]; then
    mkdir -p "$TEST_ROOT/dangling-link"
    ln -s "$TEST_ROOT/nonexistent-target" "$TEST_ROOT/dangling-link/backup.tar.gz"
    run_case dangling-link 36
    assert_no_tar
    [[ -L "$CASE_ARCHIVE" && ! -e "$TEST_ROOT/nonexistent-target" ]] || fail 'dangling symlink replaced/followed/deleted'
    echo 'PASS: dangling symlink destination is rejected without mutation'
else
    echo 'SKIP: Unix symlink creation requires Linux CI'
fi

for name in replacement-race replacement-without-error; do
    run_case "$name" 36
    grep -Fxq replacement-race "$CASE_ROOT/trace" || fail 'post-compression replacement race not exercised'
    [[ "$(cat "$CASE_ARCHIVE")" == 'unrelated replacement backup' ]] || fail 'failure cleanup deleted another file'
    echo "PASS: $name cannot report success or delete another file"
done

run_case cancel-compression 130
grep -Fxq tar-started "$CASE_ROOT/trace" || fail 'cancellation did not occur during compression'
assert_clean_archive
echo 'PASS: interrupted compression removes its own partial archive and preserves originals'
