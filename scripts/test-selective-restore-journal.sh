#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANAGER="$ROOT_DIR/app/src/main/assets/manager.sh"
if [[ "${1:-}" != --case ]]; then
    TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/st-selective-journal.XXXXXX")"
    trap '[[ "$TEST_ROOT" == */st-selective-journal.* && -d "$TEST_ROOT" ]] && rm -rf -- "$TEST_ROOT"' EXIT
    cases=(success journal first-file last-file committed journal-failure changed-target ancestor-link new-file-conflict)
    for profile in default-user other-user; do
        for point in "${cases[@]}"; do
            bash "$0" --case "$point" "$TEST_ROOT" "$profile"
            echo "PASS selective journal $profile $point"
        done
    done
    exit
fi
POINT="$2" TEST_ROOT="$3" PROFILE="$4"
[[ "$TEST_ROOT" == */st-selective-journal.* && -d "$TEST_ROOT" ]] || exit 1
export ST_HOME="$TEST_ROOT/$PROFILE-$POINT/install" ST_LAUNCHER_HOME="$TEST_ROOT/$PROFILE-$POINT/launcher"
source "$MANAGER"
WORK="$BACKUP_DIR/restore-work-$$"
mkdir -p "$ST_HOME/data/$PROFILE/characters" "$ST_HOME/data/$PROFILE/chats/Alice" "$WORK/extracted/data/$PROFILE/characters" "$WORK/extracted/data/$PROFILE/chats/Alice" "$WORK/rollback"
CARD=data/$PROFILE/characters/Alice.png CHAT=data/$PROFILE/chats/Alice/one.jsonl
printf old > "$ST_HOME/$CARD"
printf secrets > "$ST_HOME/data/$PROFILE/secrets.json"
printf untouched > "$ST_HOME/data/$PROFILE/chats/Alice/unselected.jsonl"
printf new > "$WORK/extracted/$CARD"
printf chat > "$WORK/extracted/$CHAT"
printf fixture > "$WORK/backup.zip"
node -e 'const fs=require("fs"),p=process.argv[1],a=fs.statSync(p+"/backup.zip");fs.writeFileSync(p+"/index.json",JSON.stringify({archive:{size:a.size,mtimeMs:a.mtimeMs,dev:a.dev,ino:a.ino},entries:process.argv.slice(2).map(path=>({path,bytes:fs.statSync(p+"/extracted/"+path).size,isDirectory:false}))}))' "$WORK" "$CARD" "$CHAT"
bash "$SELECTIVE_RESTORE_HELPER" list "$WORK/index.json" "$WORK/backup.zip" "$ST_HOME" > "$WORK/list.txt"
ARCHIVE_REV="$(sed -n 's/^archive_revision=//p' "$WORK/list.txt")"
INSTALL_REV="$(sed -n 's/^installation_revision=//p' "$WORK/list.txt")"
REQUEST="$(node -e 'const fs=require("fs");console.log(Buffer.from(JSON.stringify(fs.readFileSync(process.argv[1],"utf8").split("\n").filter(x=>x.startsWith("restore_item\t")).map(x=>{const a=x.split("\t");return {path:Buffer.from(a[2],"base64").toString(),policy:"replace",targetRevision:a[5]}}))).toString("base64"))' "$WORK/list.txt")"
bash "$SELECTIVE_RESTORE_HELPER" plan "$WORK/index.json" "$WORK/backup.zip" "$ST_HOME" "$REQUEST" "$WORK/plan.json" "$ARCHIVE_REV" "$INSTALL_REV" >/dev/null
ensure_restore_target_stopped() { :; }
is_running() { return 1; }
write_progress() { :; }
measured_copy() { cp -a -- "$1" "$2"; }
run_transaction_command() { "$@"; }
flow() {
    CURRENT_OPERATION=restore-selected
    track_current_work_dir "$WORK"
    bash() {
        command bash "$@" || return $?
        if [[ "$1" == "$SELECTIVE_RESTORE_HELPER" && "$2" == apply-item ]]; then
            case "$POINT" in
                first-file) [[ "${6:-}" == "$CARD" ]] && kill -KILL "$BASHPID" ;;
                last-file) [[ "${6:-}" == "$CHAT" ]] && kill -KILL "$BASHPID" ;;
            esac
        fi
        return 0
    }
    mv() {
        if [[ "$POINT" == journal-failure && "${*: -1}" == "$WORK/recovery.env" ]]; then return 1; fi
        command mv "$@" || return $?
        case "$POINT" in
            journal) [[ "${*: -1}" == "$WORK/recovery.env" ]] && kill -KILL "$BASHPID" ;;
            committed) [[ "${*: -1}" == "$WORK/recovery.env" && "$RESTORE_JOURNAL_STATE" == committed ]] && kill -KILL "$BASHPID" ;;
            ancestor-link)
                if [[ "${*: -1}" == "$WORK/recovery.env" ]]; then
                    node -e 'const fs=require("fs"),p=process.argv[1];fs.mkdirSync(p+"/Other");fs.writeFileSync(p+"/Other/one.jsonl","unselected-other");fs.renameSync(p+"/Alice",p+"/Alice-saved");fs.symlinkSync(p+"/Other",p+"/Alice",process.platform==="win32"?"junction":"dir")' "$ST_HOME/data/$PROFILE/chats"
                fi ;;
            new-file-conflict) [[ "${*: -1}" != "$WORK/recovery.env" ]] || printf external-new > "$ST_HOME/$CHAT" ;;
        esac
        return 0
    }
    apply_selected_restore "$WORK/plan.json" "$WORK/extracted"
}
[[ "$POINT" != changed-target ]] || printf edited > "$ST_HOME/$CARD"
result=0
(flow) || result=$?
case "$POINT" in
    success) [[ "$result" == 0 && "$(cat "$ST_HOME/$CARD")" == new && "$(cat "$ST_HOME/$CHAT")" == chat ]] ;;
    committed) [[ "$result" == 137 ]]; inspect_restore_recovery; [[ ${#RESTORE_PENDING_WORKS[@]} == 0 && "$(cat "$ST_HOME/$CARD")" == new ]] ;;
    journal-failure) [[ "$result" == 25 && "$(cat "$ST_HOME/$CARD")" == old && ! -e "$ST_HOME/$CHAT" ]] ;;
    changed-target) [[ "$result" == 73 && "$(cat "$ST_HOME/$CARD")" == edited && ! -e "$ST_HOME/$CHAT" ]] ;;
    ancestor-link)
        [[ "$result" == 25 && "$(cat "$ST_HOME/data/$PROFILE/chats/Other/one.jsonl")" == unselected-other ]]
        recovery=0; recover_pending_restore || recovery=$?
        [[ "$recovery" == 25 && "$(cat "$ST_HOME/data/$PROFILE/chats/Other/one.jsonl")" == unselected-other ]]
        node -e 'const fs=require("fs"),p=process.argv[1];fs.unlinkSync(p+"/Alice");fs.renameSync(p+"/Alice-saved",p+"/Alice")' "$ST_HOME/data/$PROFILE/chats"
        recover_pending_restore
        [[ "$(cat "$ST_HOME/$CARD")" == old && ! -e "$ST_HOME/$CHAT" ]]
        ;;
    new-file-conflict)
        [[ "$result" == 73 && "$(cat "$ST_HOME/$CHAT")" == external-new ]]
        recovery=0; recover_pending_restore || recovery=$?
        [[ "$recovery" == 25 && "$(cat "$ST_HOME/$CHAT")" == external-new ]]
        rm -- "$ST_HOME/$CHAT"
        recover_pending_restore
        [[ "$(cat "$ST_HOME/$CARD")" == old && ! -e "$ST_HOME/$CHAT" ]]
        ;;
    *) [[ "$result" == 137 ]]; recover_pending_restore; [[ "$(cat "$ST_HOME/$CARD")" == old && ! -e "$ST_HOME/$CHAT" && ! -e "$WORK" ]] ;;
esac
[[ "$(cat "$ST_HOME/data/$PROFILE/secrets.json")" == secrets && "$(cat "$ST_HOME/data/$PROFILE/chats/Alice/unselected.jsonl")" == untouched ]]
