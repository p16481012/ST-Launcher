#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ "${1:-}" != --case ]]; then
    TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/st-version-flow.XXXXXX")"
    trap '[[ "$TEST_ROOT" == */st-version-flow.* && -d "$TEST_ROOT" ]] && rm -rf -- "$TEST_ROOT"' EXIT
    for name in keep-current snapshot dependencies server receipt-mismatch changed-data changed-config; do
        bash "$0" --case "$name" "$TEST_ROOT"
        echo "PASS version flow $name"
    done
    exit
fi
CASE="$2" TEST_ROOT="$3"
[[ "$TEST_ROOT" == */st-version-flow.* && -d "$TEST_ROOT" ]] || exit 1
export ST_HOME="$TEST_ROOT/$CASE/install" ST_LAUNCHER_HOME="$TEST_ROOT/$CASE/launcher" ST_DOWNLOAD_DIR="$TEST_ROOT/$CASE/Downloads"
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
source "$ROOT_DIR/app/src/main/assets/manager.sh"
mkdir -p "$ST_HOME/data/default-user" "$DOWNLOAD_DIR"
git -C "$ST_HOME" init -q -b release
git -C "$ST_HOME" config user.name Fixture
git -C "$ST_HOME" config user.email fixture@localhost
printf 'data/\nconfig.yaml\nnode_modules/\n' > "$ST_HOME/.gitignore"
printf '{"name":"sillytavern","version":"1.0.0","engines":{"node":">=18"}}\n' > "$ST_HOME/package.json"
printf old-code > "$ST_HOME/server.js"
printf shell > "$ST_HOME/start.sh"
printf old-data > "$ST_HOME/data/default-user/data.txt"
printf old-secret > "$ST_HOME/data/default-user/secrets.json"
printf old-config > "$ST_HOME/config.yaml"
# A historical tracked file must not replace the verified backup manifest when
# checking out old code in staging (or turn a full transaction into partial).
printf 'format=st-launcher-backup-v1\nitems=user_data\nsecrets=0\n' > "$ST_HOME/.st-launcher-manifest"
git -C "$ST_HOME" add .
git -C "$ST_HOME" commit -qm old
OLD="$(git -C "$ST_HOME" rev-parse HEAD)"
SNAPSHOT=SillyTavern-Launcher-20261124-000001.zip
node "$ROOT_DIR/scripts/version-full-zip-fixture.cjs" "$ST_HOME" "$DOWNLOAD_DIR/$SNAPSHOT"
SNAPSHOT_REV="$(bash "$VERSION_STATE_HELPER" record "$ST_HOME" "$LAUNCHER_HOME" "$DOWNLOAD_DIR" "$SNAPSHOT" "$OLD" | sed -n 's/^snapshot_revision=//p')"
printf new-code > "$ST_HOME/server.js"
rm -- "$ST_HOME/.st-launcher-manifest"
git -C "$ST_HOME" commit -qam new
CURRENT="$(git -C "$ST_HOME" rev-parse HEAD)"
printf current-data > "$ST_HOME/data/default-user/data.txt"
printf current-secret > "$ST_HOME/data/default-user/secrets.json"
printf current-config > "$ST_HOME/config.yaml"
INSTALL_REV="$(previous_versions | sed -n 's/^installation_revision=//p')"
is_running() { return 1; }
ensure_restore_target_stopped() { :; }
start_progress_monitor() { :; }
stop_progress_monitor() { :; }
validate_install_runtime() { :; }
launch_server_process() { :; }
terminate_server_process() { :; }
wait_for_server_strict() { if [[ "$CASE" == server ]]; then printf migrated > "$ST_HOME/data/default-user/data.txt"; return 1; fi; return 0; }
install_dependencies() { [[ "$CASE" != dependencies ]]; }
begin_operation() { CURRENT_OPERATION="$1"; OPERATION_STARTED_EPOCH="$(date +%s)"; trap 'operation_cleanup $?' EXIT; }
if [[ "$OSTYPE" == msys* ]]; then mkdir() { if [[ "${1:-}" == -m ]]; then shift 2; fi; command mkdir "$@"; }; fi
# Archive tooling is independently tested with native ZIP on Linux. This local
# fixture still runs real metadata validation, CRC extraction and full journal.
backup_selected() {
    [[ "$BACKUP_PARENT_OPERATION" == change-version && "$CURRENT_OPERATION" == change-version && "$1" == full && "$2" == 1 ]]
    BACKUP_CREATED_PATH="$DOWNLOAD_DIR/SillyTavern-Launcher-20261124-000002.zip"
    local work="$BACKUP_DIR/backup-work-$$"
    mkdir -p "$work"
    track_current_work_dir "$work"
    measured_archive "Fixture plan" "Fixture" backup-plan "$ST_HOME" "$DOWNLOAD_DIR" "$work/source-plan.json" . -x 'node_modules/*' '*/node_modules/*' .st-launcher-manifest > /dev/null
    node "$ROOT_DIR/scripts/version-full-zip-fixture.cjs" "$ST_HOME" "$BACKUP_CREATED_PATH"
    bash "$VERSION_STATE_HELPER" record "$ST_HOME" "$LAUNCHER_HOME" "$DOWNLOAD_DIR" "${BACKUP_CREATED_PATH##*/}" "$CURRENT" >/dev/null
    if [[ "$CASE" == changed-data ]]; then printf external-edit > "$ST_HOME/data/default-user/data.txt"; fi
    if [[ "$CASE" == changed-config ]]; then printf external-config > "$ST_HOME/config.yaml"; fi
}
if [[ "$CASE" == receipt-mismatch ]]; then
    rm -- "$DOWNLOAD_DIR/$SNAPSHOT"
    node "$ROOT_DIR/scripts/version-full-zip-fixture.cjs" "$ST_HOME" "$DOWNLOAD_DIR/$SNAPSHOT"
    # Simulate forged/corrupt local receipt. The extracted .git HEAD must still
    # be checked; the receipt is not sufficient proof of archived code.
    node - "$LAUNCHER_HOME" "$DOWNLOAD_DIR/$SNAPSHOT" "$OLD" <<'NODE'
const fs=require('fs'),p=require('path'),c=require('crypto'),[root,file,commit]=process.argv.slice(2),sha=v=>c.createHash('sha256').update(v).digest('hex');
const s=fs.lstatSync(file,{bigint:true}),name=p.basename(file),f=p.join(root,'update-history/version-snapshots',sha(name)+'.json'),r=JSON.parse(fs.readFileSync(f));
r.archiveRevision=sha([s.dev,s.ino,s.mode,s.size,s.mtimeNs,s.ctimeNs].join(':'));r.bytes=Number(s.size);r.commit=commit;fs.writeFileSync(f,JSON.stringify(r));
NODE
    SNAPSHOT_REV="$(bash "$VERSION_STATE_HELPER" snapshot-info "$ST_HOME" "$LAUNCHER_HOME" "$DOWNLOAD_DIR" "$SNAPSHOT" | sed -n 's/^snapshot_revision=//p')"
fi
mode=keep-current
[[ "$CASE" != snapshot && "$CASE" != receipt-mismatch ]] || mode=snapshot
result=0
( change_version "$OLD" "$CURRENT" "$INSTALL_REV" "$mode" "$SNAPSHOT" "$SNAPSHOT_REV" ) > "$TEST_ROOT/$CASE.output" 2>&1 || result=$?
expected=0
case "$CASE" in dependencies) expected=26 ;; server) expected=33 ;; receipt-mismatch) expected=83 ;; changed-data|changed-config) expected=81 ;; esac
if [[ "$result" != "$expected" ]]; then cat "$TEST_ROOT/$CASE.output"; cat "$LOG_FILE"; echo "FAIL expected $expected got $result" >&2; exit 1; fi
[[ -s "$DOWNLOAD_DIR/SillyTavern-Launcher-20261124-000002.zip" ]] # mandatory current backup survives every later failure
if (( expected )); then
    data=current-data config=current-config
    [[ "$CASE" != changed-data ]] || data=external-edit
    [[ "$CASE" != changed-config ]] || config=external-config
    [[ "$(git -C "$ST_HOME" rev-parse HEAD)" == "$CURRENT" && "$(cat "$ST_HOME/data/default-user/data.txt")" == "$data" && "$(cat "$ST_HOME/config.yaml")" == "$config" ]]
    grep -Fxq status=error "$LAST_RESULT_FILE"
else
    [[ "$(git -C "$ST_HOME" rev-parse HEAD)" == "$OLD" && "$(cat "$ST_HOME/server.js")" == old-code ]]
    if [[ "$mode" == snapshot ]]; then prefix=old; else prefix=current; fi
    [[ "$(cat "$ST_HOME/data/default-user/data.txt")" == "$prefix-data" && "$(cat "$ST_HOME/config.yaml")" == "$prefix-config" && "$(cat "$ST_HOME/data/default-user/secrets.json")" == "$prefix-secret" ]]
    grep -Fxq status=success "$LAST_RESULT_FILE"
fi
