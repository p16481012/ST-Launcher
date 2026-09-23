#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/st-backup-create.XXXXXX")"
cleanup() { case "$TEST_ROOT" in "${TMPDIR:-/tmp}"/st-backup-create.*) rm -rf -- "$TEST_ROOT" ;; esac; }
trap cleanup EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
for tool in node zip unzip; do command -v "$tool" >/dev/null || fail "Missing dependency: $tool"; done
REAL_ZIP="$(command -v zip)"
put() { mkdir -p "$(dirname "$1")"; printf '%s\n' "$2" > "$1"; }
setup() {
    CASE_ROOT="$TEST_ROOT/$1"
    export ST_HOME="$CASE_ROOT/install" ST_LAUNCHER_HOME="$CASE_ROOT/launcher" ST_DOWNLOAD_DIR="$CASE_ROOT/downloads"
    export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
    mkdir -p "$ST_HOME" "$ST_DOWNLOAD_DIR"
    source "$ROOT_DIR/app/src/main/assets/manager.sh"
    ensure_archive_tools() { :; }; ensure_import_runtime() { :; }
    begin_operation() { CURRENT_OPERATION="$1"; OPERATION_STARTED_EPOCH="$(date +%s)"; trap 'operation_cleanup $?' EXIT; }
    # MSYS cannot apply Linux modes under some Windows filesystem policies.
    # Linux CI runs the unchanged mkdir with its real private permissions.
    if [[ "$OSTYPE" == msys* ]]; then mkdir() { if [[ "${1:-}" == -m ]]; then shift 2; fi; command mkdir "$@"; }; fi
    put "$ST_HOME/package.json" '{"version":"1.0.0"}'
    put "$ST_HOME/data/default-user/chats/selected.txt" 'preserve this original'
    put "$ST_HOME/data/default-user/secrets.json" 'excluded secret'
    put "$ST_HOME/data/default-user/extensions/example/index.js" 'extension'
    put "$ST_HOME/node_modules/ignored.js" ignored
    put "$ST_HOME/config.yaml" 'port: 8000'
    SOURCE_HASH="$(sha256sum "$ST_HOME/data/default-user/chats/selected.txt" | cut -d ' ' -f1)"
    eval "$(declare -f measured_archive | sed '1s/measured_archive/real_measured_archive/')"
}
run_case() {
    local expected="$1" name="$2" code=0; shift 2
    (trap - EXIT INT TERM; set -Eeuo pipefail; "$@") > "$TEST_ROOT/$name.output" 2>&1 || code=$?
    if [[ "$code" != "$expected" ]]; then
        cat "$TEST_ROOT/$name.output" >&2
        tail -n 40 "$TEST_ROOT/$name/launcher/logs/operation.log" >&2 2>/dev/null || true
        fail "$name: expected $expected, got $code"
    fi
    echo "PASS: $name"
}
assert_failure_cleanup() {
    local name="$1" error="$2"
    grep -Fq "error_code=$error" "$TEST_ROOT/$name.output" || fail "$name: wrong error code"
    [[ "$(cat "$TEST_ROOT/$name/install/data/default-user/chats/selected.txt")" == 'preserve this original' ]] || fail "$name: original modified"
    [[ -z "$(find "$TEST_ROOT/$name/downloads" -mindepth 1 -print -quit)" ]] || fail "$name: partial backup retained"
}
happy() {
    setup "$1"
    export ST_BACKUP_REQUEST_ID=12345678-1234-1234-1234-123456789abc
    backup_selected user_data,extensions,custom,config 0 chats
    local archive manifest
    archive="$(find "$DOWNLOAD_DIR" -maxdepth 1 -name '*.zip' -print -quit)"
    [[ -s "$archive" ]] || fail 'No completed ZIP'
    unzip -tq "$archive" >/dev/null
    manifest="$(unzip -p "$archive" .st-launcher-manifest)"
    grep -Fq 'items=user_data,extensions,custom,config' <<< "$manifest" || fail 'Selection scope changed'
    [[ "$(unzip -Z1 "$archive" | sort | uniq -d | wc -l)" == 0 ]] || fail 'Duplicate archive entries'
    ! unzip -Z1 "$archive" | grep -Eq 'secrets.json|node_modules' || fail 'Excluded content included'
    [[ "$(sha256sum "$ST_HOME/data/default-user/chats/selected.txt" | cut -d ' ' -f1)" == "$SOURCE_HASH" ]] || fail 'Original changed'
    grep -Fq 'backup_request_id=12345678-1234-1234-1234-123456789abc' "$PROGRESS_FILE" || fail 'Backup retry ID lost'
}
injected() {
    local name="$1" injected_action="$2" injected_code="$3"
    setup "$name"
    measured_archive() {
        if [[ "$3" == "$injected_action" ]]; then
            if [[ "$injected_code" == 58 ]]; then echo 'injected: No space left on device' >&2; else echo 'injected: I/O failure' >&2; fi
            return "$injected_code"
        fi
        real_measured_archive "$@"
    }
    backup_selected user_data 0 ''
}
changed() {
    setup changed
    measured_archive() {
        real_measured_archive "$@" || return $?
        if [[ "$3" == compress ]]; then put "$ST_HOME/data/default-user/chats/new.txt" new; fi
        return 0
    }
    backup_selected user_data 0 ''
}
fault_shim() {
    # Faults affect the actual Node worker; there are no production test hooks.
    local target="$1" mode="$2"
    node --input-type=commonjs - "$target" "$mode" <<'NODE'
const fs=require('fs');
const source=process.argv[3]==='space' ?
    `const fs=require('fs');fs.statfsSync=()=>({bavail:0n,bsize:4096n});` :
    process.argv[3]==='scratch' ?
    `const fs=require('fs'),stat=fs.statSync;fs.statSync=function(p,...args){const s=stat.call(this,p,...args);if(String(p).endsWith('downloads'))s.dev=987654321;return s};fs.statfsSync=p=>({bavail:String(p).endsWith('downloads')?1000000000n:0n,bsize:4096n});` :
    `const fs=require('fs'),open=fs.openSync;fs.openSync=function(p,...args){if(String(p).endsWith('selected.txt'))throw Object.assign(new Error('EACCES: fixture unreadable selected.txt'),{code:'EACCES'});return open.call(this,p,...args)};`;
fs.writeFileSync(process.argv[2],source);
NODE
}
log_failure() {
    setup log-failure
    begin_operation backup
    put "$CASE_ROOT/details.log" 'zip error: No space left on device'
    mkdir "$CASE_ROOT/log-is-a-directory"
    LOG_FILE="$CASE_ROOT/log-is-a-directory"
    backup_failure 58 '백업 생성 공간 부족' "$CASE_ROOT/details.log"
}
validation_space() {
    setup validation-space
    validate_restore_archive() { echo 'No space left on device' >&2; return 29; }
    backup_selected user_data 0 ''
}
unsafe_link() {
    setup unsafe-link
    ln -s "$ST_HOME/data/default-user/chats/selected.txt" "$ST_HOME/data/default-user/link.txt"
    backup_selected user_data 0 ''
}
preflight() {
    local name="$1" mode="$2"
    setup "$name"
    fault_shim "$CASE_ROOT/fault.cjs" "$mode"
    local shim="$CASE_ROOT/fault.cjs"
    if command -v cygpath >/dev/null 2>&1; then shim="$(cygpath -m "$shim")"; fi
    export NODE_OPTIONS="--require=\"$shim\""
    backup_selected user_data 0 ''
}
worker_zip_failure() {
    local name="$1" append="$2"
    setup "$name"
    # Inject a failed child into the real Node ZIP worker, also on Windows
    # where native spawn('zip') cannot execute an extensionless shell fixture.
    node --input-type=commonjs - "$CASE_ROOT/child-fault.cjs" "$append" <<'NODE'
const fs=require('fs');
fs.writeFileSync(process.argv[2],`const cp=require('child_process'),spawn=cp.spawn;cp.spawn=function(command,args,...rest){if(command==='zip' && (args[0]==='-g')===${process.argv[3]==='yes'}){const child=new(require('events').EventEmitter)();child.stdout=new(require('stream').PassThrough)();child.stderr=new(require('stream').PassThrough)();child.kill=()=>true;process.nextTick(()=>{child.stderr.end('zip error: No space left on device\\n');child.stdout.end();child.emit('close',14,null)});return child}return spawn.call(this,command,args,...rest)};`);
NODE
    local shim="$CASE_ROOT/child-fault.cjs"
    if command -v cygpath >/dev/null 2>&1; then shim="$(cygpath -m "$shim")"; fi
    export NODE_OPTIONS="--require=\"$shim\""
    backup_selected user_data 0 ''
}
append_probe() {
    setup "$1"
    local mode="$2" payload="$CASE_ROOT/payload" archive="$CASE_ROOT/append.zip"
    mkdir "$payload"
    node --input-type=commonjs - "$payload/blob.bin" <<'NODE'
const fs=require('fs'),crypto=require('crypto'),fd=fs.openSync(process.argv[2],'w');
try { for(let i=0;i<24;i++)fs.writeSync(fd,crypto.randomBytes(1024*1024)); } finally {fs.closeSync(fd)}
NODE
    (cd "$payload"; if [[ "$mode" == zip64 ]]; then zip -q -fz "$archive" blob.bin; else zip -q "$archive" blob.bin; fi)
    node --input-type=commonjs - "$archive" "$CASE_ROOT/probe.json" <<'NODE'
const fs=require('fs'),crypto=require('crypto');const data=fs.readFileSync(process.argv[2]),stat=fs.statSync(process.argv[2]);
let end=data.length-22;while(data.readUInt32LE(end)!==0x06054b50)end--;
let central=data.readUInt32LE(end+16);if(central===0xffffffff){const z=Number(data.readBigUInt64LE(end-12));central=Number(data.readBigUInt64LE(z+48))}
fs.writeFileSync(process.argv[3],JSON.stringify({ino:stat.ino,dev:stat.dev,central,hash:crypto.createHash('sha256').update(data.subarray(0,central)).digest('hex')}));
NODE
    put "$payload/.st-launcher-manifest" 'format=st-launcher-backup-v1'
    ST_PROGRESS_OPERATION=backup bash "$ARCHIVE_PROGRESS_HELPER" backup-manifest "$payload" "$archive" .st-launcher-manifest
    node --input-type=commonjs - "$archive" "$CASE_ROOT/probe.json" <<'NODE'
const fs=require('fs'),crypto=require('crypto'),before=JSON.parse(fs.readFileSync(process.argv[3]));
const stat=fs.statSync(process.argv[2]),fd=fs.openSync(process.argv[2],'r'),prefix=Buffer.alloc(before.central);
try{fs.readSync(fd,prefix,0,prefix.length,0)}finally{fs.closeSync(fd)}
if(stat.ino!==before.ino||stat.dev!==before.dev)throw Error('Archive was replaced, not appended');
if(crypto.createHash('sha256').update(prefix).digest('hex')!==before.hash)throw Error('Existing ZIP data rewritten');
NODE
    unzip -tq "$archive" >/dev/null
    validate_restore_archive "$archive" "$CASE_ROOT/append-index.json" >/dev/null
}
run_case 0 happy happy happy
run_case 58 no-space preflight no-space space
run_case 58 scratch-space preflight scratch-space scratch
run_case 59 unreadable preflight unreadable read
run_case 58 compression-space injected compression-space compress 58
run_case 58 metadata-space injected metadata-space backup-manifest 58
run_case 59 compression-io injected compression-io compress 59
run_case 58 verify-space injected verify-space verify 29
run_case 58 validation-space validation_space
run_case 58 log-failure log_failure
run_case 58 real-compression-space worker_zip_failure real-compression-space no
run_case 58 real-metadata-space worker_zip_failure real-metadata-space yes
run_case 60 changed changed
for name in no-space scratch-space compression-space metadata-space verify-space validation-space log-failure real-compression-space real-metadata-space; do assert_failure_cleanup "$name" BACKUP_NO_SPACE; done
for name in unreadable compression-io; do assert_failure_cleanup "$name" BACKUP_CREATE_FAILED; done
assert_failure_cleanup changed BACKUP_SOURCE_CHANGED
if [[ "$OSTYPE" != msys* ]]; then
    run_case 59 unsafe-link unsafe_link
    assert_failure_cleanup unsafe-link BACKUP_CREATE_FAILED
else echo 'SKIP: Unix symlink creation (covered on Linux)'; fi
grep -Fq 'No space left on device' "$TEST_ROOT/real-compression-space/launcher/logs/operation.log" || fail 'ZIP reason lost from persistent log'
grep -Fq 'No space left on device' "$TEST_ROOT/real-metadata-space.output" || fail 'Metadata reason lost from result'
run_case 0 append-classic append_probe append-classic classic
run_case 0 append-zip64 append_probe append-zip64 zip64
echo 'PASS: backup creation, free-space, source protection, ZIP64 and no-rewrite regressions'
