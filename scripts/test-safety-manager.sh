#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/st-safety-manager.XXXXXX")"
cleanup() { case "$TEST_ROOT" in "${TMPDIR:-/tmp}"/st-safety-manager.*) rm -rf -- "$TEST_ROOT" ;; esac; }
trap cleanup EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
export ST_HOME="$TEST_ROOT/source" ST_LAUNCHER_HOME="$TEST_ROOT/launcher" ST_DOWNLOAD_DIR="$TEST_ROOT/downloads"
source "$ROOT_DIR/app/src/main/assets/manager.sh"
mkdir -p "$ST_HOME/data"
printf 'original user data\n' > "$ST_HOME/data/keep.txt"
CURRENT_OPERATION=update
# The process/cancellation machinery has its own suite. Here exercise the real
# manager/worker storage interface and the cleanup boundary after each result.
run_cancellable() { "$@"; }
original_helper="$SAFETY_BACKUP_HELPER"
requested="$BACKUP_DIR/before-forced-update-fixture.tar.gz"
printf 'older backup\n' > "$requested"
safety_backup "$requested"
first="$SAFETY_BACKUP_PATH"
[[ -f "$first" && -z "$CURRENT_SAFETY_WORK_DIR" && "$first" != "$requested" ]] || fail 'success did not retain/report its private snapshot'
cleanup_current_work_dir
[[ -f "$first" ]] || fail 'later operation cleanup removed a completed snapshot'
safety_backup "$requested"
second="$SAFETY_BACKUP_PATH"
[[ -f "$first" && -f "$second" && "$first" != "$second" ]] || fail 'repeated snapshot name reused/overwrote a destination'
[[ "$(cat "$requested")" == 'older backup' ]] || fail 'old flat backup was changed'
mkdir "$TEST_ROOT/extract"
tar -xzf "$second" -C "$TEST_ROOT/extract"
cmp "$ST_HOME/data/keep.txt" "$TEST_ROOT/extract/data/keep.txt"
echo 'PASS: manager retains readable unique snapshots, protects older files and survives later cleanup'

node --input-type=commonjs - "$TEST_ROOT" <<'NODE'
const fs=require('fs'),path=require('path'),root=process.argv[2];
fs.writeFileSync(path.join(root,'failure.sh'),'printf partial > "$2"\necho "fixture storage write failed" >&2\nexit 36\n');
fs.writeFileSync(path.join(root,'cancel.sh'),'printf partial > "$2"\nexit 130\n');
fs.writeFileSync(path.join(root,'missing.sh'),'exit 0\n');
NODE
for entry in failure:36 cancel:130 missing:36; do
    name="${entry%:*}"; expected="${entry#*:}"; code=0
    SAFETY_BACKUP_HELPER="$TEST_ROOT/$name.sh"
    safety_backup "$requested" > "$TEST_ROOT/$name-output" 2>&1 || code=$?
    [[ "$code" == "$expected" && -z "$SAFETY_BACKUP_PATH" && -z "$CURRENT_SAFETY_WORK_DIR" ]] || fail "$name reported success or retained partial tracking"
    [[ "$(find "$BACKUP_DIR" -maxdepth 1 -type d -name 'safety-snapshot-*' | wc -l)" == 2 ]] || fail "$name leaked a partial snapshot directory"
    [[ -f "$first" && -f "$second" ]] || fail "$name removed another completed snapshot"
done
grep -Fq 'fixture storage write failed' "$TEST_ROOT/failure-output" || fail 'worker cause missing from callback stderr'
CURRENT_SAFETY_WORK_DIR="$(mktemp -d "$BACKUP_DIR/safety-snapshot-$$.XXXXXXXX")"
partial="$CURRENT_SAFETY_WORK_DIR"
printf partial > "$partial/interrupted.tar.gz"
cleanup_current_work_dir
[[ ! -e "$partial" && -f "$first" && -f "$second" ]] || fail 'operation cancellation cleanup removed the wrong snapshot'
[[ "$(cat "$ST_HOME/data/keep.txt")" == 'original user data' ]] || fail 'user data changed'
echo 'PASS: failure, cancelled worker, missing success output and operation cleanup remove only pending snapshots'
