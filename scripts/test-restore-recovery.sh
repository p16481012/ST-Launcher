#!/usr/bin/env bash
# Recovery journals and real SIGKILL tests; no package installs, ZIP, or network.
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANAGER="$ROOT_DIR/app/src/main/assets/manager.sh"

fail() { echo "FAIL: ${CASE_NAME:-suite}: $*" >&2; exit 1; }

if [[ "${1:-}" != --case ]]; then
    TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/st-restore-recovery.XXXXXX")"
    cleanup() {
        [[ "$TEST_ROOT" == */st-restore-recovery.* && -d "$TEST_ROOT" && "$TEST_ROOT" != / ]] && rm -rf -- "$TEST_ROOT"
    }
    trap cleanup EXIT
    cases=(full-kill partial-kill full-prepared full-moved full-unrecorded-new first-install
        rollback-rekill-full rollback-rekill-partial rollback-journal-failure
        journal-initial-failure commit-publish-failure commit-flush-failure
        committed-residue rolled-back-residue live-writer running-server own-lock
        invalid-v1 invalid-payload invalid-duplicate invalid-installation invalid-path
        invalid-missing-backup invalid-backup-identity invalid-target-identity multiple-journals
        invalid-journal-symlink invalid-rollback-symlink invalid-nested-symlink
        dispatcher-block dispatcher-recover begin-operation-recover
        flow-journal flow-original-move flow-replacement-move flow-copy flow-committed flow-first-install)
    for CASE_NAME in "${cases[@]}"; do
        if ! bash "$0" --case "$CASE_NAME" "$TEST_ROOT" > "$TEST_ROOT/$CASE_NAME.log" 2>&1; then
            cat "$TEST_ROOT/$CASE_NAME.log"
            fail "$CASE_NAME"
        fi
        echo "PASS: restore recovery $CASE_NAME"
    done
    echo "restore recovery tests passed (${#cases[@]} isolated cases)"
    exit 0
fi

CASE_NAME="$2"
TEST_ROOT="$3"
[[ "$TEST_ROOT" == */st-restore-recovery.* && -d "$TEST_ROOT" ]] || fail "unsafe fixture root"
export ST_HOME="$TEST_ROOT/$CASE_NAME/install"
export ST_LAUNCHER_HOME="$TEST_ROOT/$CASE_NAME/launcher"
export ST_DOWNLOAD_DIR="$TEST_ROOT/$CASE_NAME/downloads"
export PREFIX="$TEST_ROOT/$CASE_NAME/prefix"
source "$MANAGER"
mkdir -p "$ST_HOME/data" "$DOWNLOAD_DIR" "$PREFIX"
printf 'old-data\n' > "$ST_HOME/data/value.txt"
printf 'old-config\n' > "$ST_HOME/config.yaml"
printf 'untouched\n' > "$ST_HOME/unselected.txt"
printf 'old-hash\n' > "$DEPENDENCY_HASH_FILE"
curl() { return 1; }
export -f curl

# Git Bash without Windows symlink privileges needs the same atomic lock-marker
# emulation as test-manager-regressions.sh. Linux CI uses actual symlinks.
if [[ "$OSTYPE" == msys* ]]; then
    ln() {
        if [[ "${1:-}" == -s && "${3:-}" == */.reclaim ]]; then
            (set -C; printf '%s\n' "$2" > "$3")
        else command ln "$@"; fi
    }
    readlink() {
        if [[ $# == 1 && "$1" == */.reclaim && -f "$1" && ! -L "$1" ]]; then cat "$1";
        else command readlink "$@"; fi
    }
    export -f ln readlink
fi

WORK="$BACKUP_DIR/restore-work-$$"
fresh_manager() {
    source "$MANAGER"
    # source inside a function makes declare local; reset the persisted test
    # process's globals too, just as a genuinely new manager process would.
    declare -ga RESTORE_AFFECTED_PATHS=() RESTORE_ORIGINAL_PATHS=() RESTORE_RECOVERED_PATHS=()
    declare -ga RESTORE_PATH_IDENTITIES=() RESTORE_PENDING_WORKS=()
}
prepare_full() {
    mkdir -p "$WORK/rollback"
    track_current_work_dir "$WORK"
    prepare_restore_transaction full "$WORK/rollback"
    RESTORE_HAD_INSTALLATION=1
    RESTORE_DEPENDENCY_SAVED=1
    cp -a "$DEPENDENCY_HASH_FILE" "$RESTORE_ROLLBACK_DIR/dependency-lock.sha256"
    write_restore_journal
    RESTORE_TRANSACTION_ACTIVE=1
    [[ "${1:-}" == prepared ]] && return 0
    mv "$ST_HOME" "$RESTORE_ROLLBACK_DIR/full-install"
    RESTORE_FULL_ORIGINAL_MOVED=1
    [[ "${1:-}" == moved ]] && return 0
    mkdir -p "$ST_HOME/data"
    printf 'new-data\n' > "$ST_HOME/data/value.txt"
    printf 'new-hash\n' > "$DEPENDENCY_HASH_FILE"
    [[ "${1:-}" == unrecorded ]] && return 0
    RESTORE_INSTALLATION_ID="$(restore_file_identity "$ST_HOME")"
    write_restore_journal
}
prepare_partial() {
    mkdir -p "$WORK/rollback"
    track_current_work_dir "$WORK"
    prepare_restore_transaction partial "$WORK/rollback"
    normalize_restore_paths data config.yaml public/scripts/extensions/third-party
    cp -a "$ST_HOME/data" "$WORK/rollback/data"
    cp -a "$ST_HOME/config.yaml" "$WORK/rollback/config.yaml"
    RESTORE_ORIGINAL_PATHS=(data config.yaml)
    write_restore_journal
    RESTORE_TRANSACTION_ACTIVE=1
    rm -rf -- "$ST_HOME/data"
    mkdir -p "$ST_HOME/data" "$ST_HOME/public/scripts/extensions/third-party"
    printf 'new-data\n' > "$ST_HOME/data/value.txt"
    printf 'new-config\n' > "$ST_HOME/config.yaml"
    printf 'new-extension\n' > "$ST_HOME/public/scripts/extensions/third-party/new.txt"
}
expect_killed() {
    local result=0
    ( "$@"; kill -KILL "$BASHPID" ) || result=$?
    [[ "$result" == 137 ]] || fail "fixture producer was not killed ($result)"
    fresh_manager
    [[ "$RESTORE_TRANSACTION_ACTIVE" == 0 && -f "$WORK/recovery.env" ]] || fail "not a fresh process journal"
}
expect_old() {
    grep -Fxq old-data "$ST_HOME/data/value.txt" || fail "original data was not restored"
    grep -Fxq old-config "$ST_HOME/config.yaml" || fail "original config was not restored"
    grep -Fxq old-hash "$DEPENDENCY_HASH_FILE" || fail "original dependency hash was not restored"
    grep -Fxq untouched "$ST_HOME/unselected.txt" || fail "unselected data changed"
    [[ ! -e "$WORK" ]] || fail "completed rollback did not clean its work directory"
}
expect_blocked() {
    local result=0 before after
    before="$(find "$ST_HOME" -type f -exec sha256sum {} \; | sort)"
    recover_pending_restore || result=$?
    [[ "$result" == 25 ]] || fail "unsafe recovery did not block ($result)"
    after="$(find "$ST_HOME" -type f -exec sha256sum {} \; | sort)"
    [[ "$before" == "$after" && -f "$WORK/recovery.env" ]] || fail "blocked recovery changed data"
}
real_symlinks() {
    command ln -s "$ST_HOME" "$TEST_ROOT/$CASE_NAME/link-test" 2>/dev/null || true
    [[ -L "$TEST_ROOT/$CASE_NAME/link-test" ]] || { echo 'SKIP: real symlinks are verified on Linux CI'; return 1; }
}

restore_flow() {
    local point="$1" extracted="$WORK/extracted"
    mkdir -p "$extracted/data" "$WORK/rollback"
    printf 'format=st-launcher-backup-v1\nitems=full\nsecrets=1\n' > "$extracted/.st-launcher-manifest"
    printf 'new-data\n' > "$extracted/data/value.txt"
    printf '{}\n' > "$extracted/package.json"
    track_current_work_dir "$WORK"
    CURRENT_OPERATION=restore
    # Git/runtime compatibility has separate integration tests. Exercise the
    # real restore transaction below without npm/network in these kill tests.
    valid_full_installation() { return 0; }
    validate_install_runtime() { return 0; }
    sanitize_imported_git() { return 0; }
    ensure_restore_target_stopped() { return 0; }
    install_dependencies() { return 0; }
    measured_copy() {
        if [[ "$point" == copy ]]; then
            printf incomplete > "$ST_HOME/incomplete.txt"
            kill -KILL "$BASHPID"
        fi
        cp -a "$1/." "$2/"
    }
    mv() {
        command mv "$@" || return $?
        case "$point" in
            journal) [[ "${*: -1}" == "$WORK/recovery.env" ]] && kill -KILL "$BASHPID" ;;
            original-move) [[ "${1:-}" == "$ST_HOME" && "${2:-}" == "$WORK/rollback/full-install" ]] && kill -KILL "$BASHPID" ;;
            replacement-move|first-install) [[ "${1:-}" == "$WORK/replacement-install" && "${2:-}" == "$ST_HOME" ]] && kill -KILL "$BASHPID" ;;
            committed) [[ "${*: -1}" == "$WORK/recovery.env" && "$RESTORE_JOURNAL_STATE" == committed ]] && kill -KILL "$BASHPID" ;;
        esac
        return 0
    }
    restore_extracted_tree "$extracted" fixture.zip
    fail "requested restore kill point was not reached"
}

case "$CASE_NAME" in
    full-kill|partial-kill)
        if [[ "$CASE_NAME" == full-kill ]]; then expect_killed prepare_full; else expect_killed prepare_partial; fi
        report="$(doctor)"
        grep -Fxq recovery_pending=1 <<< "$report" || fail "doctor did not detect interrupted restore"
        grep -Fxq new-data "$ST_HOME/data/value.txt" || fail "doctor mutated the installation"
        recover_pending_restore
        expect_old
        [[ ! -e "$ST_HOME/public/scripts/extensions/third-party" ]] || fail "originally absent extension survived rollback"
        ;;
    full-prepared|full-moved)
        expect_killed prepare_full "${CASE_NAME#full-}"
        recover_pending_restore
        expect_old
        ;;
    full-unrecorded-new)
        expect_killed prepare_full unrecorded
        expect_blocked
        grep -Fxq old-data "$WORK/rollback/full-install/data/value.txt" || fail "unknown root lost protected original"
        ;;
    first-install)
        rm -rf -- "$ST_HOME"
        rm -f -- "$DEPENDENCY_HASH_FILE"
        mkdir -p "$WORK/rollback"
        track_current_work_dir "$WORK"
        prepare_restore_transaction full "$WORK/rollback"
        write_restore_journal
        mkdir -p "$ST_HOME/data"
        RESTORE_INSTALLATION_ID="$(restore_file_identity "$ST_HOME")"
        write_restore_journal
        printf new > "$ST_HOME/data/value.txt"
        fresh_manager
        recover_pending_restore
        [[ ! -e "$ST_HOME" && ! -e "$WORK" ]] || fail "first-install rollback retained replacement"
        ;;
    rollback-rekill-full|rollback-rekill-partial)
        if [[ "$CASE_NAME" == rollback-rekill-full ]]; then expect_killed prepare_full; else expect_killed prepare_partial; fi
        result=0
        (
            mv() {
                command mv "$@" || return $?
                if [[ "${1:-}" == "$WORK/rollback/full-install" || "${1:-}" == "$WORK/rollback/data" ]]; then kill -KILL "$BASHPID"; fi
            }
            recover_pending_restore
        ) || result=$?
        [[ "$result" == 137 ]] || fail "rollback was not killed after original move"
        fresh_manager
        recover_pending_restore
        expect_old
        ;;
    rollback-journal-failure)
        expect_killed prepare_partial
        sync() { return 1; }
        result=0; recover_pending_restore || result=$?
        [[ "$result" == 25 && -f "$WORK/recovery.env" ]] || fail "journal flush failure discarded recovery"
        unset -f sync
        fresh_manager
        recover_pending_restore
        expect_old
        ;;
    journal-initial-failure)
        mkdir -p "$WORK/rollback"
        track_current_work_dir "$WORK"
        prepare_restore_transaction full "$WORK/rollback"
        RESTORE_HAD_INSTALLATION=1
        sync() { return 1; }
        if write_restore_journal; then fail "failed initial flush reported success"; fi
        [[ ! -e "$WORK/recovery.env" ]] || fail "unflushed initial journal was published"
        grep -Fxq old-data "$ST_HOME/data/value.txt" || fail "initial journal failure altered original"
        ;;
    commit-publish-failure)
        prepare_full
        mv() { [[ "${*: -1}" == "$WORK/recovery.env" ]] && return 1; command mv "$@"; }
        if commit_restore_transaction; then fail "failed commit publication reported success"; fi
        [[ "$RESTORE_TRANSACTION_ACTIVE" == 1 && "$RESTORE_JOURNAL_STATE" == applying ]] || fail "unpublished commit suppressed rollback"
        unset -f mv
        rollback_restore_transaction
        cleanup_current_work_dir
        expect_old
        ;;
    commit-flush-failure)
        prepare_full
        sync() { [[ "${2:-}" == "$WORK/"recovery.env.tmp.* ]]; }
        if commit_restore_transaction; then fail "failed commit flush reported success"; fi
        [[ "$RESTORE_TRANSACTION_ACTIVE" == 0 && "$RESTORE_RECOVERY_RETAINED" == 1 ]] || fail "published commit triggered destructive rollback"
        unset -f sync
        fresh_manager
        inspect_restore_recovery
        [[ ${#RESTORE_PENDING_WORKS[@]} == 0 ]] || fail "published commit was treated as pending"
        grep -Fxq new-data "$ST_HOME/data/value.txt" || fail "committed data was rolled back"
        grep -Fxq old-data "$WORK/rollback/full-install/data/value.txt" || fail "failed flush lost protected copy"
        ;;
    committed-residue|rolled-back-residue)
        prepare_full
        if [[ "$CASE_NAME" == committed-residue ]]; then commit_restore_transaction; else rollback_restore_transaction; fi
        rm -rf -- "$WORK/rollback"
        fresh_manager
        inspect_restore_recovery
        [[ ${#RESTORE_PENDING_WORKS[@]} == 0 ]] || fail "completed marker residue was pending"
        recover_pending_restore
        [[ -f "$ST_HOME/data/value.txt" ]] || fail "completed installation was removed"
        ;;
    live-writer|own-lock)
        expect_killed prepare_full
        operation_active() { return 0; }
        mkdir -p "$LOCK_DIR"
        if [[ "$CASE_NAME" == live-writer ]]; then printf 99999999 > "$LOCK_DIR/pid"; else printf '%s' "$$" > "$LOCK_DIR/pid"; fi
        inspect_restore_recovery
        if [[ "$CASE_NAME" == live-writer ]]; then
            [[ ${#RESTORE_PENDING_WORKS[@]} == 0 ]] || fail "live writer was called interrupted"
        else [[ ${#RESTORE_PENDING_WORKS[@]} == 1 ]] || fail "own new lock hid prior journal"; fi
        grep -Fxq new-data "$ST_HOME/data/value.txt" || fail "inspection changed live data"
        ;;
    running-server)
        expect_killed prepare_full
        is_running() { return 0; }
        expect_blocked
        CURRENT_OPERATION=stop
        recover_restore_before_dispatch
        [[ -f "$WORK/recovery.env" ]] || fail "stop command unexpectedly recovered live data"
        ;;
    invalid-v1|invalid-payload|invalid-duplicate|invalid-installation|invalid-path)
        expect_killed prepare_partial
        case "$CASE_NAME" in
            invalid-v1) sed -i 's/st-launcher-restore-recovery-v2/st-launcher-restore-recovery-v1/' "$WORK/recovery.env" ;;
            invalid-payload) printf 'payload=$(touch "%s")\n' "$TEST_ROOT/PAYLOAD_EXECUTED" >> "$WORK/recovery.env" ;;
            invalid-duplicate) printf 'state=committed\n' >> "$WORK/recovery.env" ;;
            invalid-installation) sed -i "s/^installation_b64=.*/installation_b64=$(printf '%s' "$TEST_ROOT" | base64 -w 0)/" "$WORK/recovery.env" ;;
            invalid-path) printf 'path=%s|none\n' "$(printf '../outside' | base64 -w 0)" >> "$WORK/recovery.env" ;;
        esac
        expect_blocked
        [[ ! -e "$TEST_ROOT/PAYLOAD_EXECUTED" ]] || fail "journal was executed as shell"
        [[ "$RESTORE_PENDING_DETAIL" == *"$WORK"* ]] || fail "blocked journal hid protected location"
        ;;
    invalid-missing-backup|invalid-backup-identity|invalid-target-identity)
        expect_killed prepare_full
        case "$CASE_NAME" in
            invalid-missing-backup) mv "$WORK/rollback/full-install" "$WORK/protected-elsewhere" ;;
            invalid-backup-identity) mv "$WORK/rollback/full-install" "$WORK/protected-elsewhere"; mkdir -p "$WORK/rollback/full-install" ;;
            invalid-target-identity) mv "$ST_HOME" "$TEST_ROOT/$CASE_NAME/replacement-elsewhere"; mkdir -p "$ST_HOME/data"; printf unrelated > "$ST_HOME/data/value.txt" ;;
        esac
        expect_blocked
        ;;
    multiple-journals)
        expect_killed prepare_full
        cp -a "$WORK" "$BACKUP_DIR/import-work-12345678"
        expect_blocked
        [[ "$RESTORE_PENDING_BLOCKED" == 1 ]] || fail "multiple journals were not flagged"
        ;;
    invalid-journal-symlink|invalid-rollback-symlink|invalid-nested-symlink)
        real_symlinks || exit 0
        if [[ "$CASE_NAME" == invalid-nested-symlink ]]; then
            mkdir -p "$WORK/rollback/data/default-user/chats" "$ST_HOME/data/default-user/chats"
            printf original > "$WORK/rollback/data/default-user/chats/value.txt"
            printf replacement > "$ST_HOME/data/default-user/chats/value.txt"
            track_current_work_dir "$WORK"
            prepare_restore_transaction partial "$WORK/rollback"
            RESTORE_AFFECTED_PATHS=(data/default-user/chats)
            RESTORE_ORIGINAL_PATHS=(data/default-user/chats)
            write_restore_journal
            mkdir -p "$WORK/rollback-evil"
            mv "$WORK/rollback/data" "$WORK/rollback-evil/data"
            command ln -s "$WORK/rollback-evil/data" "$WORK/rollback/data"
        else
            prepare_full
            if [[ "$CASE_NAME" == invalid-journal-symlink ]]; then
                mv "$WORK/recovery.env" "$WORK/journal-real"
                command ln -s "$WORK/journal-real" "$WORK/recovery.env"
            else
                mv "$WORK/rollback" "$WORK/rollback-real"
                command ln -s "$WORK/rollback-real" "$WORK/rollback"
            fi
        fi
        fresh_manager
        expect_blocked
        ;;
    dispatcher-block)
        expect_killed prepare_full
        printf 'unknown=field\n' >> "$WORK/recovery.env"
        printf 'status=success\n' > "$PROGRESS_FILE"
        result=0; bash "$MANAGER" start > "$TEST_ROOT/$CASE_NAME/start.log" 2>&1 || result=$?
        [[ "$result" == 25 ]] || fail "dispatcher ignored pending restore or prior success reset code ($result)"
        grep -Fxq error_code=RESTORE_ROLLBACK_REQUIRED "$LAST_RESULT_FILE" || fail "blocked mutation error code missing"
        grep -Fxq new-data "$ST_HOME/data/value.txt" || fail "blocked dispatcher changed installation"
        ;;
    dispatcher-recover)
        expect_killed prepare_full
        result=0; bash "$MANAGER" start > "$TEST_ROOT/$CASE_NAME/start.log" 2>&1 || result=$?
        [[ "$result" == 4 ]] || fail "start's ordinary preflight did not run after recovery ($result)"
        expect_old
        ;;
    flow-*)
        if [[ "$CASE_NAME" == flow-first-install ]]; then rm -rf -- "$ST_HOME"; rm -f -- "$DEPENDENCY_HASH_FILE"; fi
        expect_killed restore_flow "${CASE_NAME#flow-}"
        if [[ "$CASE_NAME" == flow-committed ]]; then
            inspect_restore_recovery
            [[ ${#RESTORE_PENDING_WORKS[@]} == 0 ]] || fail "committed real restore was pending"
            grep -Fxq new-data "$ST_HOME/data/value.txt" || fail "committed real restore lost new data"
        else
            recover_pending_restore
            if [[ "$CASE_NAME" == flow-first-install ]]; then
                [[ ! -e "$ST_HOME" && ! -e "$WORK" ]] || fail "real first-install rollback retained new root"
            else expect_old; fi
        fi
        ;;
    begin-operation-recover)
        expect_killed prepare_partial
        (
            start_progress_monitor() { :; }
            begin_operation repair
            grep -Fxq old-data "$ST_HOME/data/value.txt" || fail "begin_operation did not recover"
        )
        expect_old
        ;;
    *) fail "unknown case" ;;
esac
