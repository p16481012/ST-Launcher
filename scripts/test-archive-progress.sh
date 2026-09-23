#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKER="$ROOT_DIR/app/src/main/assets/archive-progress.sh"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/st-archive-progress.XXXXXX")"
cleanup() {
    case "$TEST_ROOT" in "${TMPDIR:-/tmp}"/st-archive-progress.*) rm -rf -- "$TEST_ROOT" ;; esac
}
trap cleanup EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
export ST_PROGRESS_FILE="$TEST_ROOT/progress.env"
export ST_PROGRESS_LOG="$TEST_ROOT/activity.log" ST_PROGRESS_HISTORY="$TEST_ROOT/history.log"
export ST_PROGRESS_OPERATION=restore ST_PROGRESS_PHASE='ZIP 해제' ST_PROGRESS_DETAIL='실제 처리량 시험'
# Git Bash invokes a native Windows Node binary; convert only this worker-owned
# temporary path, never user HOME or any shared installation path.
if command -v cygpath >/dev/null 2>&1; then
    export ST_PROGRESS_FILE="$(cygpath -w "$ST_PROGRESS_FILE")"
fi
read_progress() { sed -n "s/^$1=//p" "$TEST_ROOT/progress.env"; }
expect_exit() {
    local expected="$1" actual=0; shift
    "$@" > "$TEST_ROOT/last-output" 2>&1 || actual=$?
    if [[ "$actual" != "$expected" ]]; then
        cat "$TEST_ROOT/last-output" >&2
        fail "expected exit $expected, got $actual"
    fi
}

node --input-type=commonjs - "$TEST_ROOT" "$ROOT_DIR" <<'NODE'
const fs = require('fs'), path = require('path'), zlib = require('zlib');
const root = process.argv[2];
const table = Array.from({length:256},(_,n)=>{for(let i=0;i<8;i++)n=(n>>>1)^((n&1)?0xedb88320:0);return n>>>0;});
const crc = b => {let n=0xffffffff;for(const v of b)n=(n>>>8)^table[(n^v)&255];return(n^0xffffffff)>>>0;};
const input = [
    ['data/', Buffer.alloc(0), 0],
    ['data/빈 파일.txt', Buffer.alloc(0), 0],
    ['data/한글 공백.txt', Buffer.from('실제 파일 내용\n'.repeat(20000)), 8],
    ['large.bin', Buffer.alloc(16 * 1024 * 1024, 0xa5), 8],
    ['raw.bin', Buffer.from([0,255,4,8,12]), 0],
];
let cursor = 0; const blocks = [], central = [], entries = [];
for(const [name, content, method] of input) {
    const filename = Buffer.from(name), packed = method === 8 ? zlib.deflateRawSync(content) : content;
    const checksum = crc(content), local = Buffer.alloc(30), directory = name.endsWith('/');
    local.writeUInt32LE(0x04034b50); local.writeUInt16LE(20,4); local.writeUInt16LE(0x800,6);
    local.writeUInt16LE(method,8); local.writeUInt32LE(checksum,14); local.writeUInt32LE(packed.length,18);
    local.writeUInt32LE(content.length,22); local.writeUInt16LE(filename.length,26);
    const h = Buffer.alloc(46); h.writeUInt32LE(0x02014b50); h.writeUInt16LE(0x314,4);
    h.writeUInt16LE(20,6); h.writeUInt16LE(0x800,8); h.writeUInt16LE(method,10); h.writeUInt32LE(checksum,16);
    h.writeUInt32LE(packed.length,20); h.writeUInt32LE(content.length,24); h.writeUInt16LE(filename.length,28);
    h.writeUInt32LE(((directory?0x41ed:0x81a4)*65536)>>>0,38); h.writeUInt32LE(cursor,42);
    entries.push({path:name.replace(/\/$/,''),dataOffset:cursor+30+filename.length,compressedBytes:packed.length,
        bytes:content.length,method,crc32:checksum,mode:directory?0o755:0o644,mtime:1600000000000,isDirectory:directory});
    blocks.push(local,filename,packed); central.push(h,filename); cursor += 30+filename.length+packed.length;
}
const centralBuffer=Buffer.concat(central), end=Buffer.alloc(22);
end.writeUInt32LE(0x06054b50);end.writeUInt16LE(input.length,8);end.writeUInt16LE(input.length,10);
end.writeUInt32LE(centralBuffer.length,12);end.writeUInt32LE(cursor,16);
const archive=path.join(root,'input.zip');fs.writeFileSync(archive,Buffer.concat([...blocks,centralBuffer,end]));
const stat=fs.statSync(archive), identity={size:stat.size,mtimeMs:stat.mtimeMs,dev:stat.dev,ino:stat.ino};
const index={archive:identity,entries};fs.writeFileSync(path.join(root,'index.json'),JSON.stringify(index));
fs.writeFileSync(path.join(root,'bad-crc.json'),JSON.stringify({...index,entries:entries.map(e=>e.path==='large.bin'?{...e,crc32:123}:e)}));
fs.writeFileSync(path.join(root,'bad-path.json'),JSON.stringify({...index,entries:[{...entries[4],path:'../escape'}]}));
fs.writeFileSync(path.join(root,'bad-size.json'),JSON.stringify({...index,entries:entries.map(e=>e.path==='large.bin'?{...e,bytes:10}:e)}));
fs.writeFileSync(path.join(root,'stale.json'),JSON.stringify({...index,archive:{...identity,mtimeMs:0}}));
fs.writeFileSync(path.join(root,'expected-bytes'),String(entries.reduce((n,e)=>n+e.bytes,0)));
fs.writeFileSync(path.join(root,'history.log'),Buffer.alloc(1050000,10));
// Run the actual embedded validator without sourcing manager.sh (which also
// initializes runtime directories). Its stdout must stay machine-readable.
const manager=fs.readFileSync(path.join(process.argv[3],'app/src/main/assets/manager.sh'),'utf8');
const validation=/validate_restore_archive\(\) \{[\s\S]*?<<'NODE'\r?\n([\s\S]*?)\r?\nNODE/.exec(manager);
if(!validation)throw Error('ZIP metadata validator not found');
const result=require('child_process').spawnSync(process.execPath,['--input-type=commonjs','-',archive,path.join(root,'scan-index.json')],{
    input:validation[1],encoding:'utf8',env:{...process.env,ST_PROGRESS_FILE:path.join(root,'scan.env'),
        ST_PROGRESS_LOG:path.join(root,'scan.log'),ST_PROGRESS_HISTORY:path.join(root,'scan-history.log'),ST_PROGRESS_OPERATION:'restore'},
});
if(result.status!==0)throw Error(`Metadata validator failed: ${result.stderr}`);
if(result.stdout.trim()!==String(entries.reduce((n,e)=>n+e.bytes,0)))throw Error('Metadata validator stdout was polluted');
const scan=fs.readFileSync(path.join(root,'scan.env'),'utf8');
if(!scan.includes('completed_files=5\n')||!scan.includes('total_files=5\n')||!scan.includes('progress_mode=files\n'))throw Error('Metadata count progress incorrect');
if(!fs.readFileSync(path.join(root,'scan-history.log'),'utf8').includes('항목 5/5개'))throw Error('Metadata history not recorded');
NODE
echo 'PASS: real ZIP metadata validator reports exact entry counts without polluting stdout'

bash "$WORKER" crc-self-test
mkdir "$TEST_ROOT/extracted"
bash "$WORKER" extract "$TEST_ROOT/input.zip" "$TEST_ROOT/extracted" "$TEST_ROOT/index.json"
[[ "$(read_progress progress_mode)" == bytes ]] || fail 'extraction did not report byte progress'
[[ "$(read_progress completed_bytes)" == "$(cat "$TEST_ROOT/expected-bytes")" ]] || fail 'written-byte count does not match extracted content'
[[ "$(read_progress total_bytes)" == "$(read_progress completed_bytes)" ]] || fail 'extraction byte total differs'
[[ "$(read_progress completed_files)" == 4 ]] || fail 'directory was counted as extracted file'
[[ "$(read_progress total_files)" == 4 ]] || fail 'wrong file total'
[[ "$(read_progress percent)" == 100 ]] || fail 'completed phase should reach 100 percent'
[[ "$(read_progress status)" == running ]] || fail 'worker prematurely completed manager operation'
node --input-type=commonjs - "$TEST_ROOT/extracted" <<'NODE'
const fs=require('fs'),path=require('path'),root=process.argv[2];
if(fs.readFileSync(path.join(root,'data/한글 공백.txt'),'utf8')!=='실제 파일 내용\n'.repeat(20000))throw Error('Unicode file content mismatch');
if(fs.statSync(path.join(root,'data/빈 파일.txt')).size!==0)throw Error('Empty file mismatch');
if(fs.statSync(path.join(root,'large.bin')).size!==16*1024*1024)throw Error('Large file mismatch');
NODE
echo 'PASS: single-pass extraction reports actual bytes, Unicode, empty files and CRC'
grep -Fq 'MiB' "$TEST_ROOT/activity.log" || fail 'actual byte history is not human readable'
grep -Fq '파일 4/4개' "$TEST_ROOT/history.log" || fail 'diagnostic history did not receive actual counters'
[[ "$(wc -c < "$TEST_ROOT/history.log")" -lt 1000000 ]] || fail 'diagnostic history was not bounded'
echo 'PASS: actual processing details mirrored into bounded diagnostic history'

bash "$WORKER" verify "$TEST_ROOT/input.zip" "$TEST_ROOT/index.json"
[[ "$(read_progress completed_bytes)" == "$(cat "$TEST_ROOT/expected-bytes")" ]] || fail 'CRC verification did not measure checked bytes'
[[ "$(read_progress completed_files)" == 4 ]] || fail 'CRC verification file count differs'
expect_exit 20 bash "$WORKER" verify "$TEST_ROOT/input.zip" "$TEST_ROOT/bad-crc.json"
echo 'PASS: CRC verification measures streamed bytes without a second extraction tree'

for kind in bad-crc bad-size stale bad-path; do mkdir "$TEST_ROOT/$kind"; done
expect_exit 20 bash "$WORKER" extract "$TEST_ROOT/input.zip" "$TEST_ROOT/bad-crc" "$TEST_ROOT/bad-crc.json"
[[ ! -e "$TEST_ROOT/bad-crc/large.bin" ]] || fail 'CRC-failed partial file was retained'
expect_exit 20 bash "$WORKER" extract "$TEST_ROOT/input.zip" "$TEST_ROOT/bad-size" "$TEST_ROOT/bad-size.json"
[[ ! -e "$TEST_ROOT/bad-size/large.bin" ]] || fail 'oversized partial file was retained'
expect_exit 20 bash "$WORKER" extract "$TEST_ROOT/input.zip" "$TEST_ROOT/stale" "$TEST_ROOT/stale.json"
expect_exit 21 bash "$WORKER" extract "$TEST_ROOT/input.zip" "$TEST_ROOT/bad-path" "$TEST_ROOT/bad-path.json"
[[ ! -e "$TEST_ROOT/escape" ]] || fail 'entry escaped extraction directory'
expect_exit 21 bash "$WORKER" extract "$TEST_ROOT/input.zip" "$TEST_ROOT/extracted" "$TEST_ROOT/index.json"
echo 'PASS: corrupt content, size overflow, stale index, traversal and nonempty destination rejected'

if command -v zip >/dev/null 2>&1 && command -v unzip >/dev/null 2>&1; then
    bash "$WORKER" compress "$TEST_ROOT/extracted" "$TEST_ROOT/output.zip" . -x 'raw.bin' > "$TEST_ROOT/zip-output"
    [[ "$(read_progress progress_mode)" == files ]] || fail 'native ZIP did not provide measured entry counts'
    [[ "$(read_progress completed_files)" == "$(read_progress total_files)" ]] || fail 'native ZIP count is incomplete'
    [[ "$(read_progress total_files)" -gt 0 ]] || fail 'native ZIP entry count is zero'
    [[ "$(read_progress completed_bytes)" == 0 ]] || fail 'compression invented byte progress'
    unzip -tq "$TEST_ROOT/output.zip"
    ! unzip -Z1 "$TEST_ROOT/output.zip" | grep -Fxq raw.bin || fail 'ZIP exclude argument was lost'
    expect_exit 59 bash "$WORKER" compress "$TEST_ROOT/extracted" "$TEST_ROOT/missing.zip" 'not-present'
    echo 'PASS: native compression measured counts, exclusions and failure propagation'
else
    echo 'SKIP: native compression test needs zip and unzip (required in CI)'
fi
