#!/usr/bin/env bash
# Exercises the manager dispatch / lock / server guards, in a self-contained installation.
set -Eeuo pipefail
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
box="$(mktemp -d "${TMPDIR:-/tmp}/st-chat-manager.XXXXXXXX")"
trap '[[ "$box" == */st-chat-manager.* && "$box" != / ]] && rm -rf -- "$box"' EXIT
export ST_HOME="$box/ST" ST_LAUNCHER_HOME="$box/launcher" ST_DOWNLOAD_DIR="$box/downloads" ST_PORT=49826
mkdir -p "$ST_HOME/data/user/chats/one" "$ST_DOWNLOAD_DIR"
rel="data/user/chats/one/test.jsonl"
printf '%s\n%s\n%s' '{"chat_metadata":{}}' '{"mes":"good","is_user":true}' '{"mes":"truncated' > "$ST_HOME/$rel"
cp "$ST_HOME/$rel" "$box/original"
manager="$repo/app/src/main/assets/manager.sh"
run_manager(){ bash "$manager" "$@"; }
rows="$(run_manager chat-scan '')"
id="$(printf '%s\n' "$rows" | awk -F '\t' '$1=="chat" {print $2}')"
rev="$(printf '%s\n' "$rows" | awk -F '\t' '$1=="chat" {print $7}')"
[[ -n "$id" && ${#rev} == 64 ]]
# Server gate must be after acquiring the operation lock, before any data write.
if ( source "$manager"; is_running(){ return 0; }; chat_operation repair "$id" "$rev" trim-tail '' '' ); then
    exit 1
else
    [[ $? == 10 ]]
fi
cmp "$ST_HOME/$rel" "$box/original"
out="$(run_manager chat-repair "$id" "$rev" trim-tail '' '')"
receipt="$(printf '%s\n' "$out" | sed -n 's/^trash_id=//p')"
[[ -n "$receipt" && -f "$ST_LAUNCHER_HOME/file-trash/$receipt/item" ]]
run_manager chat-undo "$receipt" | grep -Fx 'undone=1'
cmp "$ST_HOME/$rel" "$box/original"
echo 'manager chat server guard, repair and undo passed'
mkdir -p "$box/pending/backups/restore-work-fixture"
printf 'pending fixture\n' > "$box/pending/backups/restore-work-fixture/recovery.env"
for act in scan repair undo; do
    if (
        export ST_LAUNCHER_HOME="$box/pending"
        source "$manager"
        # If any dispatcher chooses the mutating recovery path, leave a detectable marker.
        recover_pending_restore(){ touch "$box/UNEXPECTED-RECOVERY"; return 1; }
        CURRENT_OPERATION="chat-$act"
        recover_restore_before_dispatch
        chat_operation "$act"
    ); then exit 1; else [[ $? == 25 ]]; fi
    [[ ! -e "$box/UNEXPECTED-RECOVERY" ]]
    grep -Fxq 'pending fixture' "$box/pending/backups/restore-work-fixture/recovery.env"
    cmp "$ST_HOME/$rel" "$box/original"
done
echo 'pending transaction is never applied by chat inspection, repair or undo'
