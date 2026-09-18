#!/usr/bin/env bash
# A reused PID must not reuse, merge, or clean up an earlier restore work tree.
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANAGER="$ROOT_DIR/app/src/main/assets/manager.sh"
fail() { echo "FAIL: ${ROUTE:-suite}/${COLLISION:-}: $*" >&2; exit 1; }

if [[ "${1:-}" != --case ]]; then
    TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/st-restore-work-collision.XXXXXX")"
    cleanup() {
        [[ "$TEST_ROOT" == */st-restore-work-collision.* && -d "$TEST_ROOT" && "$TEST_ROOT" != / ]] && rm -rf -- "$TEST_ROOT"
    }
    trap cleanup EXIT
    for ROUTE in import-backup import-install restore; do
        for COLLISION in directory file symlink; do
            output="$TEST_ROOT/$ROUTE-$COLLISION.log"
            if ! bash "$0" --case "$ROUTE" "$COLLISION" "$TEST_ROOT" > "$output" 2>&1; then
                cat "$output"
                fail "work collision changed protected data"
            fi
            if grep -q '^SKIP:' "$output"; then cat "$output";
            else echo "PASS: restore work collision $ROUTE/$COLLISION"; fi
        done
    done
    echo 'restore work collision tests passed (3 entry points)'
    exit 0
fi

ROUTE="$2"
COLLISION="$3"
TEST_ROOT="$4"
[[ "$TEST_ROOT" == */st-restore-work-collision.* && -d "$TEST_ROOT" ]] || fail 'unsafe fixture root'
FIXTURE="$TEST_ROOT/$ROUTE-$COLLISION"
export ST_HOME="$FIXTURE/install"
export ST_LAUNCHER_HOME="$FIXTURE/launcher"
export ST_DOWNLOAD_DIR="$FIXTURE/downloads"
export PREFIX="$FIXTURE/prefix"
source "$MANAGER"
SOURCE="$FIXTURE/source"
LINK_TARGET="$FIXTURE/protected-link-target"
mkdir -p "$ST_HOME/data" "$SOURCE/data" "$DOWNLOAD_DIR" "$LINK_TARGET"
printf 'existing-installation\n' > "$ST_HOME/data/original.txt"
printf 'original-source\n' > "$SOURCE/data/source.txt"
printf 'original-shared-target\n' > "$LINK_TARGET/marker.txt"
case "$ROUTE" in
    import-backup) WORK="$BACKUP_DIR/import-work-$$"; FILE_NAME=SillyTavern-Import-fixture.zip ;;
    import-install) WORK="$BACKUP_DIR/install-import-work-$$"; FILE_NAME=source-unused.zip ;;
    restore) WORK="$BACKUP_DIR/restore-work-$$"; FILE_NAME=SillyTavern-Launcher-20260918-000000.zip ;;
esac
ARCHIVE="$DOWNLOAD_DIR/$FILE_NAME"
printf 'original-picked-archive\n' > "$ARCHIVE"

case "$COLLISION" in
    directory)
        mkdir -p "$WORK/extracted" "$WORK/rollback/full-install/data"
        printf 'unrelated-stale-data\n' > "$WORK/extracted/stale.txt"
        printf 'protected-prior-installation\n' > "$WORK/rollback/full-install/data/old.txt"
        # This is a valid completed record, so the real begin_operation recovery
        # scan intentionally leaves it alone before atomic mkdir rejects reuse.
        track_current_work_dir "$WORK"
        prepare_restore_transaction full "$WORK/rollback"
        RESTORE_HAD_INSTALLATION=1
        RESTORE_ORIGINAL_ID="$(restore_file_identity "$WORK/rollback/full-install")"
        RESTORE_INSTALLATION_ID="$(restore_file_identity "$ST_HOME")"
        RESTORE_JOURNAL_STATE=committed
        write_restore_journal
        CURRENT_WORK_DIR=""
        ;;
    file) printf 'protected-regular-file\n' > "$WORK" ;;
    symlink)
        command ln -s "$LINK_TARGET" "$WORK" 2>/dev/null || true
        [[ -L "$WORK" ]] || { echo "SKIP: $ROUTE/symlink requires real symlink support (Linux CI)"; exit 0; }
        ;;
esac

fingerprint() {
    find "$ST_HOME" "$SOURCE" "$LINK_TARGET" "$WORK" "$ARCHIVE" -type f -exec sha256sum {} \; | sort
}
before="$(fingerprint)"
work_identity="$(restore_file_identity "$WORK")"
result=0
(
    # Only independent runtime/source preflights are stubbed. Entry points,
    # begin_operation, mkdir guards, error recording, and EXIT cleanup are real.
    ensure_import_runtime() { :; }
    ensure_archive_tools() { :; }
    ensure_restore_target_stopped() { :; }
    is_running() { return 1; }
    installation_server_running() { return 1; }
    curl() { return 1; }
    start_progress_monitor() { :; }
    resolve_install_source() { printf '%s\n' "$SOURCE"; }
    validate_install_tree() { :; }
    validate_install_runtime() { :; }
    install_source_snapshot() { printf fixture-snapshot; }
    extract_restore_archive() { touch "$FIXTURE/UNSAFE_ACTION"; return 99; }
    measured_copy() { touch "$FIXTURE/UNSAFE_ACTION"; return 99; }
    restore_extracted_tree() { touch "$FIXTURE/UNSAFE_ACTION"; return 99; }
    remove_verified_install_source() { touch "$FIXTURE/UNSAFE_ACTION"; return 99; }
    case "$ROUTE" in
        import-backup) import_backup "$FILE_NAME" ;;
        import-install) import_install ignored ;;
        restore) restore_backup "$FILE_NAME" ;;
    esac
) > "$FIXTURE/request.log" 2>&1 || result=$?

[[ "$result" == 25 ]] || { cat "$FIXTURE/request.log"; fail "collision was not refused with recovery error ($result)"; }
[[ ! -e "$FIXTURE/UNSAFE_ACTION" ]] || fail 'copy/extraction/restore/source cleanup reached a conflicting work path'
[[ "$before" == "$(fingerprint)" ]] || fail 'source, installation, archive, or pre-existing work changed'
[[ "$work_identity" == "$(restore_file_identity "$WORK")" ]] || fail 'pre-existing work inode was replaced'
[[ ! -d "$LOCK_DIR" ]] || fail 'refused request left its operation lock'
grep -Fxq error_code=RESTORE_ROLLBACK_REQUIRED "$LAST_RESULT_FILE" || fail 'collision result lost recovery error code'
grep -Fq "$WORK" "$FIXTURE/request.log" || fail 'collision error omitted protected location'
