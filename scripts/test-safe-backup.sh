#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKER="$ROOT_DIR/app/src/main/assets/safe-backup.sh"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/st-safe-backup.XXXXXX")"
cleanup() { case "$TEST_ROOT" in "${TMPDIR:-/tmp}"/st-safe-backup.*) rm -rf -- "$TEST_ROOT" ;; esac; }
trap cleanup EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
native_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
value() { sed -n "s/^$1=//p" "$TEST_ROOT/progress.env"; }
expect_exit() {
    local expected="$1" actual=0; shift
    "$@" > "$TEST_ROOT/last-output" 2>&1 || actual=$?
    if [[ "$actual" != "$expected" ]]; then cat "$TEST_ROOT/last-output" >&2; fail "expected $expected, got $actual"; fi
}
assert_clean() {
    [[ -z "$(find "$TEST_ROOT/backups" -mindepth 1 -maxdepth 1 -name '.st-safety-*' -print)" ]] || fail 'partial safety archive directory remains'
}
mkdir -p "$TEST_ROOT/SillyTavern/data/empty-dir" "$TEST_ROOT/SillyTavern/.git" "$TEST_ROOT/SillyTavern/node_modules" "$TEST_ROOT/backups"
export ST_PROGRESS_FILE="$(native_path "$TEST_ROOT/progress.env")"
export ST_PROGRESS_LOG="$(native_path "$TEST_ROOT/activity.log")" ST_PROGRESS_HISTORY="$(native_path "$TEST_ROOT/history.log")"
export ST_PROGRESS_OPERATION=update ST_PROGRESS_PHASE='수정 파일 보호' ST_OPERATION_STARTED_EPOCH=12345
export ST_SAFETY_RESERVE_BYTES=33554432
node --input-type=commonjs - "$TEST_ROOT" <<'NODE'
const fs=require('fs'),path=require('path'),crypto=require('crypto'),root=process.argv[2],source=path.join(root,'SillyTavern');
fs.writeFileSync(path.join(source,'data','한글 공백.txt'),'private fixture content\n');
fs.writeFileSync(path.join(source,'data','empty.txt'),'');
fs.writeFileSync(path.join(source,'large.bin'),crypto.randomBytes(8*1024*1024));
fs.writeFileSync(path.join(source,'.git','excluded'),'excluded');
fs.writeFileSync(path.join(source,'node_modules','excluded'),'excluded');
fs.mkdirSync(path.join(source,'data','node_modules'));fs.writeFileSync(path.join(source,'data','node_modules','excluded'),'excluded');
for(let i=0;i<140;i++)fs.writeFileSync(path.join(source,'data',`${String(i).padStart(3,'0')}-${'long'.repeat(30)}.txt`),'fixture');
fs.writeFileSync(path.join(root,'expected-bytes'),String(8*1024*1024+Buffer.byteLength('private fixture content\n')+140*7));
fs.writeFileSync(path.join(root,'space-denied.cjs'),`const fs=require('fs');fs.statfsSync=()=>({bavail:0n,bsize:4096n});`);
fs.writeFileSync(path.join(root,'space-late.cjs'),`const fs=require('fs'),original=fs.statfsSync;let count=0;fs.statfsSync=(...args)=>++count===1?original(...args):({bavail:0n,bsize:4096n});`);
fs.writeFileSync(path.join(root,'tar-failed.cjs'),`const cp=require('child_process'),original=cp.spawn;cp.spawn=(name,args,options)=>name==='tar'?original(process.execPath,['-e','process.stderr.write("fixture tar fatal error\\n");process.exit(2)'],options):original(name,args,options);`);
fs.writeFileSync(path.join(root,'source-changed.cjs'),`const fs=require('fs'),path=require('path'),cp=require('child_process'),original=cp.spawn;cp.spawn=(name,args,options)=>{if(name==='tar')fs.appendFileSync(path.join(process.env.SAFETY_TEST_SOURCE,'large.bin'),'changed');return original(name,args,options);};`);
fs.mkdirSync(path.join(root,'cancel-source'));
for(let index=0;index<127;index++)fs.writeFileSync(path.join(root,'cancel-source',String(index)),'fixture');
fs.writeFileSync(path.join(root,'cancel-plan.cjs'),`const fs=require('fs'),cp=require('child_process'),spawn=cp.spawn,immediate=global.setImmediate;cp.spawn=(...args)=>{fs.writeFileSync(process.env.SAFETY_TEST_SPAWNED,'unexpected');return spawn(...args);};global.setImmediate=(callback,...args)=>immediate(()=>{process.emit('SIGTERM');callback(...args);});`);
NODE

bash "$WORKER" "$TEST_ROOT/SillyTavern" "$TEST_ROOT/backups/contents.tar.gz" > "$TEST_ROOT/result"
[[ "$(value completed_bytes)" == "$(cat "$TEST_ROOT/expected-bytes")" ]] || fail 'payload byte count mismatch'
[[ "$(value completed_bytes)" == "$(value total_bytes)" ]] || fail 'incomplete byte progress'
[[ "$(value completed_files)" == 143 && "$(value total_files)" == 143 ]] || fail 'file count mismatch'
[[ "$(value status)" == running && "$(value operation_started_at)" == 12345 ]] || fail 'operation identity/result changed'
grep -Fq 'safety_backup_b64=' "$TEST_ROOT/result" || fail 'missing result protocol'
grep -Fq '파일 143/143개' "$TEST_ROOT/activity.log" || fail 'measured activity log missing'
grep -Fq '압축 파일의 마무리' "$TEST_ROOT/activity.log" || fail 'gzip completion wait was not logged'
grep -Fq '원본 변경 여부와 저장 결과를 검증' "$TEST_ROOT/activity.log" || fail 'same-mode verification transition was not logged'
! grep -Fq 'private fixture content' "$TEST_ROOT/activity.log" || fail 'file contents leaked into log'
mkdir "$TEST_ROOT/extracted"
tar -xzf "$TEST_ROOT/backups/contents.tar.gz" -C "$TEST_ROOT/extracted"
cmp "$TEST_ROOT/SillyTavern/large.bin" "$TEST_ROOT/extracted/large.bin"
[[ -d "$TEST_ROOT/extracted/data/empty-dir" && -f "$TEST_ROOT/extracted/data/empty.txt" ]] || fail 'empty entries lost'
[[ ! -e "$TEST_ROOT/extracted/.git" && ! -e "$TEST_ROOT/extracted/node_modules" && ! -e "$TEST_ROOT/extracted/data/node_modules" ]] || fail 'excluded trees included'
assert_clean
echo 'PASS: measured tar.gz preserves data/empty entries/long names, excludes dependencies/Git, and cleans staging'

bash "$WORKER" "$TEST_ROOT/SillyTavern" "$TEST_ROOT/backups/directory.tar.gz" directory > /dev/null
tar -tzf "$TEST_ROOT/backups/directory.tar.gz" | grep -Fxq 'SillyTavern/large.bin' || fail 'branch snapshot layout changed'
before="$(sha256sum "$TEST_ROOT/backups/contents.tar.gz")"
expect_exit 36 bash "$WORKER" "$TEST_ROOT/SillyTavern" "$TEST_ROOT/backups/contents.tar.gz"
[[ "$(sha256sum "$TEST_ROOT/backups/contents.tar.gz")" == "$before" ]] || fail 'existing snapshot overwritten'
expect_exit 36 bash "$WORKER" "$TEST_ROOT/SillyTavern" "$TEST_ROOT/SillyTavern/recursive.tar.gz"
assert_clean
echo 'PASS: both snapshot layouts supported; overwrite and source/output overlap rejected'

for pair in space-denied:35 space-late:35 tar-failed:36 source-changed:37; do
    fixture="${pair%:*}" expected="${pair#*:}"
    export NODE_OPTIONS="--require=$(native_path "$TEST_ROOT/$fixture.cjs")"
    export SAFETY_TEST_SOURCE="$(native_path "$TEST_ROOT/SillyTavern")"
    expect_exit "$expected" bash "$WORKER" "$TEST_ROOT/SillyTavern" "$TEST_ROOT/backups/$fixture.tar.gz"
    unset NODE_OPTIONS SAFETY_TEST_SOURCE
    [[ ! -e "$TEST_ROOT/backups/$fixture.tar.gz" ]] || fail 'failed snapshot was published'
    [[ -f "$TEST_ROOT/SillyTavern/large.bin" ]] || fail 'source deleted after failure'
    assert_clean
done
echo 'PASS: preflight/runtime space failure, tar fatal error and changed source preserve source and remove partial output'

export NODE_OPTIONS="--require=$(native_path "$TEST_ROOT/cancel-plan.cjs")" SAFETY_TEST_SPAWNED="$(native_path "$TEST_ROOT/spawned")"
expect_exit 130 bash "$WORKER" "$TEST_ROOT/cancel-source" "$TEST_ROOT/backups/cancelled.tar.gz"
unset NODE_OPTIONS SAFETY_TEST_SPAWNED
[[ ! -e "$TEST_ROOT/spawned" && ! -e "$TEST_ROOT/backups/cancelled.tar.gz" ]] || fail 'cancelled planning started compression'
assert_clean
echo 'PASS: cancellation during final planning yield does not start compression or publish output'

if [[ "$(uname -s)" != MINGW* && "$(uname -s)" != MSYS* ]]; then
    ln "$TEST_ROOT/SillyTavern/large.bin" "$TEST_ROOT/SillyTavern/hard-link.bin"
    ln -s 'data/한글 공백.txt' "$TEST_ROOT/SillyTavern/link.txt"
    bash "$WORKER" "$TEST_ROOT/SillyTavern" "$TEST_ROOT/backups/links.tar.gz" > /dev/null
    mkdir "$TEST_ROOT/links"
    tar -xzf "$TEST_ROOT/backups/links.tar.gz" -C "$TEST_ROOT/links"
    [[ "$TEST_ROOT/links/large.bin" -ef "$TEST_ROOT/links/hard-link.bin" ]] || fail 'hard link not preserved'
    [[ "$(readlink "$TEST_ROOT/links/link.txt")" == 'data/한글 공백.txt' ]] || fail 'symbolic link target changed'
    echo 'PASS: Unix hard links and symbolic links preserve archive semantics'
else
    echo 'SKIP: Unix link semantics require Linux CI'
fi

# Source-only manager checks do not invoke Git, package tools, or a real install.
export ST_HOME="$TEST_ROOT/SillyTavern" ST_LAUNCHER_HOME="$TEST_ROOT/launcher" ST_DOWNLOAD_DIR="$TEST_ROOT/downloads"
source "$ROOT_DIR/app/src/main/assets/manager.sh"
[[ "$(manager_error_code update 35)" == SAFETY_BACKUP_NO_SPACE ]] || fail 'space error mapped incorrectly'
[[ "$(manager_error_code switch-branch 36)" == SAFETY_BACKUP_FAILED ]] || fail 'tar fatal error mapped to branch error'
[[ "$(manager_error_code update 37)" == SAFETY_BACKUP_SOURCE_CHANGED ]] || fail 'source change error mapped incorrectly'
node --input-type=commonjs - "$ROOT_DIR/app/src/main/assets/manager.sh" <<'NODE'
const fs=require('fs'),assert=require('assert'),s=fs.readFileSync(process.argv[2],'utf8');
for(const name of ['update_st','switch_branch']) {
 const start=s.indexOf(`${name}() {`),end=s.indexOf('\n}\n',start),body=s.slice(start,end);
 assert(body.indexOf('safety_backup "')>=0,`${name} does not use shared safety helper`);
 assert(body.indexOf('safety_backup "')<body.indexOf('reset --hard HEAD'),`${name} cleans Git before backup`);
 assert(/safety_backup [^\n]+\|\| exit \$\?/.test(body),`${name} ignores backup failure`);
}
NODE
echo 'PASS: both destructive Git paths require completed safety backup and use dedicated error codes'
