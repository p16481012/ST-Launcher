#!/usr/bin/env bash
# Uses a disposable installation and real validated ZIPs. No network, user files or Termux needed.
set -Eeuo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
sandbox="$(mktemp -d)" ; test -d "$sandbox" && test "$sandbox" != /
trap 'rm -rf -- "$sandbox"' EXIT
export HOME="$sandbox/home" ST_HOME="$sandbox/install" ST_LAUNCHER_HOME="$sandbox/launcher" ST_DOWNLOAD_DIR="$sandbox/downloads" ST_PORT=55439
assets="$repo/app/src/main/assets"; name=SillyTavern-Import-test.zip
mkdir -p "$HOME" "$ST_DOWNLOAD_DIR" "$ST_HOME/data/default-user/characters" "$ST_HOME/data/work/worlds"
source "$assets/manager.sh"
pc() { bash "$assets/manager.sh" "$@"; }
value() { sed -n "s/^$1=//p" <<< "$2" | tail -1; }
mkzip() {
    python3 - "$ST_DOWNLOAD_DIR/$name" "$1" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1],'w',zipfile.ZIP_DEFLATED) as z:
    kind=sys.argv[2]
    if kind=='multi':
        for n,d in [('data/default-user/characters/A.png',b'CARD'),('data/default-user/chats/A/new.jsonl',b'wrong chat'),('data/default-user/characters/B.png',b'new'),('data/default-user/chats/B/new.jsonl',b'new chat'),('data/default-user/worlds/World.json',b'{}'),('data/default-user/OpenAI Settings/Preset.json',b'{}'),('data/default-user/settings.json',b'no'),('data/default-user/secrets.json',b'no'),('data/default-user/extensions/ext/index.js',b'no'),('data/_storage/db',b'no'),('data/pc2/worlds/PC.json',b'{}')]:
            z.writestr(n,d)
    elif kind=='native':
        z.writestr('.st-launcher-manifest','format=st-launcher-backup-v1\nitems=custom\n')
        z.writestr('data/default-user/characters/A.png',b'ignored outside native scope')
    elif kind=='ambiguous':
        z.writestr('data/default-user/characters/A.png',b'no')
        z.writestr('data/default-user/.st-launcher-manifest','format=st-launcher-backup-v1\nitems=custom\n')
    elif kind=='raw':
        z.writestr('characters/C.png',b'raw')
    elif kind=='wrapper':
        z.writestr('copied/PC/data/default-user/worlds/W.json',b'{}')
    elif kind=='avatars':
        z.writestr('User Avatars/Persona.png',b'avatar')
        z.writestr('worlds/Imported with no avatar.json',b'{}')
    elif kind=='legacy':
        z.writestr('PC/public/characters/C.png',b'raw')
    elif kind=='config':
        z.writestr('PC/config.yaml',b'port: 8000')
PY
}
expect() { local expected="$1"; shift; local code=0; "$@" > "$sandbox/response" 2>&1 || code=$?; [[ "$code" == "$expected" ]] || { cat "$sandbox/response" >&2; echo "expected $expected, got $code" >&2; exit 1; }; }
enc() { printf '%s' "$1" | base64 -w0; }
mkzip avatars
out="$(pc import-preview "$name")"; ar="$(value archive_revision "$out")"; tr="$(value installation_revision "$out")"
out="$(pc import-preview "$name" '' merge-preview "$ar" skip-avatars)"
[[ "$(value candidate_avatars "$out")" == 1 && "$(value avatars_included "$out")" == 0 && "$(value excluded_count "$out")" == 1 ]]
expect 73 pc import-merge "$name" "$ar" "$tr" '' skip-avatars
out="$(pc import-merge "$name" "$ar" "$(value installation_revision "$out")" '' skip-avatars)"
[[ "$(value added "$out")" == 1 && ! -e "$ST_HOME/data/default-user/User Avatars/Persona.png" && -f "$ST_HOME/data/default-user/worlds/Imported with no avatar.json" ]]
mkzip multi
printf DIFFERENT > "$ST_HOME/data/default-user/characters/A.png"
printf LOCAL > "$ST_HOME/data/default-user/secrets.json"
out="$(pc import-preview "$name")"
[[ "$(value layout_kind "$out")" == user_data && "$(value add_count "$out")" == 4 && "$(value relationship_skipped "$out")" == 1 && "$(value keep_count "$out")" == 2 && "$(value excluded_count "$out")" == 4 ]]
[[ ! -d "$ST_HOME/data/default-user/chats" ]]
expect 151 pc import-preview "$name" "$(enc '{"default-user":"work","pc2":"work"}')"
mapping="$(enc '{"default-user":"default-user","pc2":"work"}')"
out="$(pc import-preview "$name" "$mapping")"; ar="$(value archive_revision "$out")"; tr="$(value installation_revision "$out")"
[[ "$(value add_count "$out")" == 5 ]]
printf RACE > "$ST_HOME/data/default-user/characters/B.png"
expect 73 pc import-merge "$name" "$ar" "$tr" "$mapping"
[[ "$(cat "$ST_HOME/data/default-user/characters/B.png")" == RACE ]]
rm -- "$ST_HOME/data/default-user/characters/B.png"
out="$(pc import-preview "$name" "$mapping")"; tr="$(value installation_revision "$out")"
out="$(pc import-merge "$name" "$ar" "$tr" "$mapping")"
[[ "$(value added "$out")" == 5 && "$(value kept "$out")" == 2 ]]
[[ "$(cat "$ST_HOME/data/default-user/characters/A.png")" == DIFFERENT && ! -e "$ST_HOME/data/default-user/chats/A/new.jsonl" ]]
[[ "$(cat "$ST_HOME/data/default-user/chats/B/new.jsonl")" == 'new chat' && -f "$ST_HOME/data/work/worlds/PC.json" ]]
[[ -f "$ST_HOME/data/default-user/OpenAI Settings/Preset.json" && -f "$ST_HOME/data/default-user/worlds/World.json" ]]
[[ "$(cat "$ST_HOME/data/default-user/secrets.json")" == LOCAL && ! -e "$ST_HOME/data/default-user/settings.json" && ! -e "$ST_HOME/data/default-user/extensions" && ! -e "$ST_HOME/data/_storage" ]]
[[ ! -e "$BACKUP_DIR/pc-merge-pending" ]]
for scenario in raw wrapper; do mkzip "$scenario"; out="$(pc import-preview "$name")"; [[ "$(value add_count "$out")" == 1 ]]; done
mkzip native; out="$(pc import-preview "$name")"; [[ "$(value layout_kind "$out")" == launcher_backup && "$(value mergeable "$out")" == 0 ]]
mkzip legacy; out="$(pc import-preview "$name")"; [[ "$(value layout_kind "$out")" == legacy_public ]]
mkzip config; out="$(pc import-preview "$name")"; [[ "$(value layout_kind "$out")" == config_extensions ]]
mkzip ambiguous; expect 23 pc import-preview "$name"

# Simulate an abrupt process death after the first no-replace publish but before
# the commit marker. Restart recovery may remove that unchanged inode only.
mkzip raw
archive="$ST_DOWNLOAD_DIR/$name"; work="$BACKUP_DIR/pc-merge-work-999922"; pending="$BACKUP_DIR/pc-merge-pending"
mkdir -p "$work/files" "$work/guard"
validate_restore_archive "$archive" "$work/index.json" >/dev/null
stamp="$(pc_merge_data stamp "$archive")"; out="$(pc_merge_data plan "$ST_HOME" "$archive" "$work/index.json" '' "$stamp")"
pc_merge_data plan "$ST_HOME" "$archive" "$work/index.json" '' "$stamp" "$(value installation_revision "$out")" "$work" 9999999999
measured_archive 'test' 'test' extract "$archive" "$work/files" "$work/selected.json"
pc_merge_data prepare "$ST_HOME" "$work"; mv "$work/guard" "$pending"
mv "$work/files/characters/C.png" "$ST_HOME/data/default-user/characters/C.png"
pc_merge_rollback_pending
[[ ! -e "$ST_HOME/data/default-user/characters/C.png" && ! -e "$pending" && ! -e "$work" ]]
[[ "$(cat "$ST_HOME/data/default-user/characters/A.png")" == DIFFERENT ]]

# Hold only the post-commit cleanup, then exercise the real manager recovery guard.
# The fake writer receipt points at THIS still-live test process; no sleeps or PID races.
out="$(pc import-preview "$name")"
out="$(bash -c '
    source "$1"; holder=$2; holder_start=$3; shift 3
    transaction_writers_quiet() {
        if [[ "$1" == "$BACKUP_DIR/pc-merge-pending" && -f "$1/committed.json" ]]; then
            local t=abcdef0123456789abcdef0123456789
            printf "token=%s\nowner_start=%s\npid=%s\nstart=%s\n" "$t" "$holder_start" "$holder" "$holder_start" > "$1/writer-$t.env"
            return 1
        fi
        return 0
    }
    pc_merge_command "$@"
' fixture "$assets/manager.sh" "$$" "$(process_start_ticks "$$")" import-merge "$name" "$(value archive_revision "$out")" "$(value installation_revision "$out")")"
[[ "$(value cleanup_pending "$out")" == 1 && "$(value added "$out")" == 1 && -f "$pending/committed.json" ]]
[[ "$(cat "$ST_HOME/data/default-user/characters/C.png")" == raw ]]
grep -Fx 'status=success' "$RUN_DIR/last-result.env" >/dev/null
expect 25 pc import-preview "$name"
[[ -f "$pending/committed.json" && "$(cat "$ST_HOME/data/default-user/characters/C.png")" == raw ]]
tx_work="$BACKUP_DIR/$(node -p 'require(process.argv[1]).work' "$pending/journal.json")"
req="$(node -p 'require(process.argv[1]).requestId' "$pending/journal.json")"
[[ "$req" =~ ^[0-9a-f]{32}$ ]]
expect 153 pc_merge_data verify-current-commit "$ST_HOME" "$pending" "$tx_work" invalid
expect 153 pc_merge_data verify-current-commit "$ST_HOME" "$pending" "$tx_work" ffffffffffffffffffffffffffffffff
expect 0 pc_merge_data verify-current-commit "$ST_HOME" "$pending" "$tx_work" "$req"
rm -- "$pending/writer-abcdef0123456789abcdef0123456789.env"
out="$(pc import-preview "$name")"
[[ ! -e "$pending" && ! -e "$tx_work" && "$(value add_count "$out")" == 0 && "$(cat "$ST_HOME/data/default-user/characters/C.png")" == raw ]]
echo 'pc merge standalone and manager scenarios passed'

# A replacement inventory is opt-in and uses original ZIP users, never the
# destination chosen for merging. Refuse to invent counts for native scopes.
mkdir -p "$ST_HOME/data/localonly/characters" "$ST_HOME/data/localonly/chats/old"
printf 'local' > "$ST_HOME/data/localonly/characters/Only.png"
printf 'local' > "$ST_HOME/data/localonly/chats/old/x.jsonl"
mkzip multi
out="$(pc import-preview "$name" "$mapping")"; [[ "$out" != *replace_impact* ]]
out="$(pc import-preview "$name" "$mapping" replace-impact)"
# C.png from the committed transaction and localonly/Only.png are absent,
# as are the work and localonly users by their original (not merge-mapped) names.
grep -Fxq $'replace_impact\tknown\t2\t1\t2' <<< "$out"
approved_zip="$(value archive_revision "$out")"
pc import-preview "$name" "$mapping" merge-preview "$approved_zip" >/dev/null
mkzip native; out="$(pc import-preview "$name" '' replace-impact)"
grep -Fxq $'replace_impact\tunknown' <<< "$out"
# A replaced staging ZIP is rejected before a stale account mapping can mask it.
expect 72 pc import-preview "$name" "$mapping" merge-preview "$approved_zip"
expect 72 pc import-preview "$name" "$mapping" replace-impact "$approved_zip"
