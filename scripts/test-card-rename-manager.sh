#!/usr/bin/env bash
# Exercise the real manager dispatch on both sides of a process death.
# The fixture deliberately has NO server startup script or network dependency.
set -Eeuo pipefail
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
box="$(mktemp -d "${TMPDIR:-/tmp}/st-card-start.XXXXXXXX")"
trap '[[ -n "$box" && "$box" == */st-card-start.* && "$box" != / ]] && rm -rf -- "$box"' EXIT
export ST_HOME="$box/ST" ST_LAUNCHER_HOME="$box/launcher" ST_DOWNLOAD_DIR="$box/downloads" ST_PORT=49677
profile="$ST_HOME/data/card-user"
mkdir -p "$profile/characters" "$profile/chats/Old" "$profile/groups" "$ST_DOWNLOAD_DIR"
printf '{}\n' > "$profile/settings.json"
printf '%s\n' '{"chat_metadata":{}}' '{"name":"Old","mes":"unchanged chat"}' > "$profile/chats/Old/one.jsonl"
cp "$profile/chats/Old/one.jsonl" "$box/original.jsonl"
node - "$profile/characters/Old.png" <<'JS'
const fs=require('fs'),crc=b=>{let a=~0;for(const n of b){a^=n;for(let i=0;i<8;i++)a=a&1?0xedb88320^(a>>>1):a>>>1;}return (~a)>>>0;};
function chunk(s,b){const d=Buffer.concat([Buffer.from(s),Buffer.from(b)]),out=Buffer.alloc(d.length+8);out.writeUInt32BE(d.length-4);d.copy(out,4);out.writeUInt32BE(crc(d),d.length+4);return out;}
const card=Buffer.from(JSON.stringify({spec:'chara_card_v3',spec_version:'3.0',data:{name:'Old'}})).toString('base64');
fs.writeFileSync(process.argv[2],Buffer.concat([Buffer.from('89504e470d0a1a0a','hex'),
    chunk('IHDR',Buffer.from('00000001000000010802000000','hex')),
    chunk('IDAT',require('zlib').deflateSync(Buffer.from([0,10,50,130]))),chunk('tEXt','ccv3\0'+card),chunk('IEND','')]));
JS
cp "$profile/characters/Old.png" "$box/original.png"
cat > "$box/kill-worker.cjs" <<'JS'
if(process.argv[2]==='library-card-change'){
    const cp=require('child_process'),move=cp.execFileSync;let steps=0;
    cp.execFileSync=function(exe,...rest){const r=move.call(this,exe,...rest);if(exe==='mv'&&++steps===2)process.exit(85);return r;};
}
JS
manager="$repo/app/src/main/assets/manager.sh"
b64(){ printf %s "$1" | base64 -w 0; }
user="$(b64 card-user)"; card="$(b64 characters/Old.png)"; target="$(b64 New)"
revision(){ bash "$manager" library-list "$user" cards '' | awk -F '\t' -v card="$card" '$1=="item" && $2==card {print $7}'; }
crash(){
    local rev rc
    rev="$(revision)"; [[ "${#rev}" == 64 ]]
    NODE_OPTIONS="--require=$box/kill-worker.cjs" bash "$manager" library-card-change "$user" "$card" "$rev" rename "$target" > "$box/fault.log" 2>&1 && return 1
    rc=$?; [[ "$rc" == 85 ]] || { cat "$box/fault.log"; return 1; }
    [[ -f "$ST_LAUNCHER_HOME/card-rename-pending.json" && -f "$profile/chats/New/one.jsonl" ]]
}
expect_code(){
    local expected="$1" rc=0; shift
    bash "$manager" "$@" > "$box/command.log" 2>&1 || rc=$?
    if [[ "$rc" != "$expected" ]]; then cat "$box/command.log"; echo "$1: wanted $expected, got $rc" >&2; return 1; fi
}
assert_recovered(){
    [[ ! -e "$ST_LAUNCHER_HOME/card-rename-pending.json" && ! -e "$profile/characters/New.png" && ! -e "$profile/chats/New" ]]
    cmp "$profile/characters/Old.png" "$box/original.png"
    cmp "$profile/chats/Old/one.jsonl" "$box/original.jsonl"
}
crash
bash "$manager" doctor > "$box/doctor.log"
grep -Fxq 'recovery_pending=1' "$box/doctor.log"
[[ -f "$profile/chats/New/one.jsonl" ]] # Read-only inspection did NOT replay.
mkdir "$profile/chats/Old"; printf 'new user data\n' > "$profile/chats/Old/one.jsonl"
expect_code 25 prepare-persistent-start # Never start a server with a blocked transaction.
expect_code 25 library-undo "$user" '12345678-1234-1234-1234-123456789012'
expect_code 25 library-trash "$user" "$(b64 '[]')" 0 # No other library write may run ahead of recovery.
[[ -f "$ST_LAUNCHER_HOME/card-rename-pending.json" ]]
grep -Fxq 'new user data' "$profile/chats/Old/one.jsonl"; cmp "$profile/chats/New/one.jsonl" "$box/original.jsonl"
mv -- "$profile/chats/Old" "$box/collision-saved"
expect_code 4 prepare-persistent-start # Normal "not installed" check, after safe recovery.
assert_recovered
crash
expect_code 4 start
assert_recovered
echo 'manager card transaction: diagnostic is read-only; start/prepare refuse conflict and recover before normal startup'
