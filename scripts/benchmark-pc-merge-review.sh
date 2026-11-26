#!/usr/bin/env bash
# Isolated Linux measurement, not a phone/Termux or a user data benchmark.
# Archive creation, optional pre-backup and server stop/start are not timed.
# No network; ~256 MiB incompressible image-like data plus small chat files.
set -Eeuo pipefail
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
parent="$(cd "${TMPDIR:-/tmp}" && pwd -P)"
box="$(mktemp -d "$parent/st-pc-merge-bench.XXXXXXXX")"
trap '[[ -n "$box" && "$box" != / && "${box%/*}" == "$parent" && "${box##*/}" == st-pc-merge-bench.* ]] && rm -rf -- "$box"' EXIT
export HOME="$box/home" ST_HOME="$box/ST" ST_LAUNCHER_HOME="$box/launcher" ST_DOWNLOAD_DIR="$box/downloads" ST_PORT=49673
profile="$ST_HOME/data/default-user"
mkdir -p "$HOME" "$profile/characters" "$ST_DOWNLOAD_DIR"
archive=SillyTavern-Import-review-benchmark.zip
python3 - "$ST_DOWNLOAD_DIR/$archive" <<'PY'
import json,random,sys,zipfile
chunk=random.Random(13017).randbytes(1024*1024)
with zipfile.ZipFile(sys.argv[1],'w',compression=zipfile.ZIP_DEFLATED,compresslevel=1) as z:
    for i in range(64):
        with z.open('backgrounds/image-%02d.png'%i,'w') as item:
            for _ in range(4):
                item.write(chunk)
    z.writestr('characters/PC.png',b'synthetic card identity')
    for i in range(256):
        z.writestr('chats/PC/%04d.jsonl'%i,'{"chat_metadata":{}}\n{"mes":"small fixture"}\n')
    z.writestr('groups/One.json',json.dumps(dict(id='One',members=['PC.png'],chats=['session'],chat_id='session')))
    z.writestr('group chats/session.jsonl','{"mes":"group fixture"}\n')
    print('expected_files='+str(len(z.infolist())))
    print('uncompressed_bytes='+str(sum(e.file_size for e in z.infolist())))
PY
mgr="$repo/app/src/main/assets/manager.sh"
now_ms(){ python3 -c 'import time;print(time.monotonic_ns()//1000000)'; }
integer(){ sed -n "s/^$1=//p" "$2" | tail -n 1; }
zip_bytes="$(stat -c %s -- "$ST_DOWNLOAD_DIR/$archive")"
t0="$(now_ms)"
bash "$mgr" import-preview "$archive" > "$box/preview.log"
t1="$(now_ms)"
archive_rev="$(integer archive_revision "$box/preview.log")"
install_rev="$(integer installation_revision "$box/preview.log")"
[[ "${#archive_rev}" == 64 && "${#install_rev}" == 64 && "$(integer add_count "$box/preview.log")" == 323 ]]
# The manager verifies server-off itself. Clock the complete import-merge call,
# including plan, extraction+CRC, second check, journaling, fsync and cleanup.
t2="$(now_ms)"
bash "$mgr" import-merge "$archive" "$archive_rev" "$install_rev" > "$box/merge.log"
t3="$(now_ms)"
[[ "$(integer added "$box/merge.log")" == 323 ]]
[[ -s "$profile/chats/PC/0255.jsonl" && -s "$profile/group chats/session.jsonl" ]]
[[ "$(stat -c %s -- "$profile/backgrounds/image-63.png")" == 4194304 ]]
printf 'zip_bytes=%s\npreview_ms=%s\nmerge_while_stopped_ms=%s\n' "$zip_bytes" "$((t1-t0))" "$((t3-t2))"
