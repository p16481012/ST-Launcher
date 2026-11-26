#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANAGER="$ROOT_DIR/app/src/main/assets/manager.sh"
fail() { echo "FAIL: ${CASE_NAME:-suite}: $*" >&2; exit 1; }

if [[ "${1:-}" != --case && "${1:-}" != --worker ]]; then
    bash "$ROOT_DIR/scripts/test-transaction-pid-race.sh"
    TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/st-transaction-tests.XXXXXX")"
    cleanup() { case "$TEST_ROOT" in "${TMPDIR:-/tmp}"/st-transaction-tests.*) rm -rf -- "$TEST_ROOT" ;; esac; }
    trap cleanup EXIT
    cases=(reset-before-move reset-after-move reset-after-replacement reset-clone-failed reset-commit
        update-prepared update-old-modules update-new-modules update-merged update-packages-failed update-server-failed update-commit
        branch-offline branch-switched branch-success update-incompatible reset-incompatible branch-incompatible
        tracked-staged-rollback untracked-collision
        journal-duplicate journal-symlink root-identity modules-identity git-lock rollback-interrupted legacy-orphan
        writer-live writer-orphan writer-descendant writer-pre-exec writer-bad-gate)
    (( $# == 0 )) || cases=("$@")
    for CASE_NAME in "${cases[@]}"; do
        if ! bash "$0" --case "$CASE_NAME" "$TEST_ROOT" > "$TEST_ROOT/$CASE_NAME.log" 2>&1; then
            cat "$TEST_ROOT/$CASE_NAME.log"; fail "$CASE_NAME"
        fi
        echo "PASS: transaction recovery $CASE_NAME"
    done
    echo "transaction recovery tests passed (${#cases[@]} isolated cases)"
    exit 0
fi

CASE_NAME="$2"; TEST_ROOT="$3"
[[ "$TEST_ROOT" == */st-transaction-tests.* && -d "$TEST_ROOT" ]] || fail 'unsafe fixture root'
export ST_HOME="$TEST_ROOT/$CASE_NAME/install" ST_LAUNCHER_HOME="$TEST_ROOT/$CASE_NAME/launcher" ST_DOWNLOAD_DIR="$TEST_ROOT/$CASE_NAME/downloads"
source "$MANAGER"
# Git Bash on this host cannot chmod newly created temporary directories;
# Linux CI exercises real private-directory modes.
if [[ "$OSTYPE" == msys* ]]; then
    mkdir() {
        local -a args=(); local skip=0 arg
        for arg in "$@"; do
            if (( skip )); then skip=0; continue; fi
            if [[ "$arg" == -m ]]; then skip=1; continue; fi
            args+=("$arg")
        done
        command mkdir "${args[@]}"
    }
    # MSYS exposes neither Linux process environments nor Android PID lifetime.
    # The dedicated orphan-writer cases below run on Linux CI.
    run_transaction_command() { "$@"; }
    transaction_writers_quiet() { return 0; }
fi
curl() { return 1; }
is_running() { return 1; }
installation_server_running() { return 1; }
terminate_server_process() { printf 'terminated\n' >> "$LAUNCHER_HOME/terminated"; }
ensure_remote_branch() { [[ "$CASE_NAME" != branch-offline ]]; }
begin_operation() { CURRENT_OPERATION="$1"; trap 'operation_cleanup $?' EXIT; }
run_cancellable() { "$@"; }
# No phone, process, network, or package manager is used. Files/Git and all
# recovery journal, rollback, import guard and snapshot code are real.
install_dependencies() {
    mkdir -p "$ST_HOME/node_modules"
    printf 'new-modules\n' > "$ST_HOME/node_modules/value"
    printf 'new-hash\n' > "$DEPENDENCY_HASH_FILE"
    [[ "$CASE_NAME" != update-packages-failed ]]
}
launch_server_process() { printf 'probe\n' > "$LAUNCHER_HOME/probe"; }
wait_for_server_strict() { [[ "$CASE_NAME" != update-server-failed ]]; }

if [[ "$1" == --worker ]]; then
    TRANSACTION_TEST_MANAGER_PID="$$"
    original_target="$(command git -C "$ST_HOME" rev-parse refs/remotes/origin/release)"
    eval "$(declare -f prepare_git_transaction | sed '1s/prepare_git_transaction/original_prepare_git_transaction/')"
    prepare_git_transaction() {
        original_prepare_git_transaction "$@" || return $?
        [[ "$CASE_NAME" != update-prepared ]] || kill -KILL "$$"
    }
    eval "$(declare -f finish_git_transaction | sed '1s/finish_git_transaction/original_finish_git_transaction/')"
    finish_git_transaction() {
        original_finish_git_transaction "$@" || return $?
        [[ "$CASE_NAME" != update-commit || "$1" != committed ]] || kill -KILL "$$"
    }
    eval "$(declare -f commit_restore_transaction | sed '1s/commit_restore_transaction/original_commit_restore_transaction/')"
    commit_restore_transaction() {
        original_commit_restore_transaction || return $?
        [[ "$CASE_NAME" != reset-commit ]] || kill -KILL "$$"
    }
    mv() {
        if [[ "$CASE_NAME" == reset-before-move && "${1:-}" == "$ST_HOME" ]]; then kill -KILL "$$"; fi
        command mv "$@" || return $?
        case "$CASE_NAME:${1:-}:${2:-}" in
            reset-after-move:"$ST_HOME":*) kill -KILL "$$" ;;
            reset-after-replacement:*/replacement-install:"$ST_HOME") kill -KILL "$$" ;;
            update-old-modules:"$ST_HOME/node_modules":*) kill -KILL "$$" ;;
            update-new-modules:*/new-node_modules:"$ST_HOME/node_modules") kill -KILL "$$" ;;
        esac
    }
    git() {
        if [[ "${1:-}" == clone ]]; then
            [[ "$CASE_NAME" != reset-clone-failed ]] || return 17
            command git clone -q --branch release "$TEST_ROOT/$CASE_NAME/origin" "${@: -1}"
            return $?
        fi
        command git "$@" || return $?
        if [[ "$CASE_NAME" == update-merged && " $* " == *' merge --ff-only '* ]]; then kill -KILL "$TRANSACTION_TEST_MANAGER_PID"; fi
        if [[ "$CASE_NAME" == branch-switched && " $* " == *' switch -C '* ]]; then kill -KILL "$TRANSACTION_TEST_MANAGER_PID"; fi
    }
    export CASE_NAME TEST_ROOT TRANSACTION_TEST_MANAGER_PID
    export -f git
    case "$CASE_NAME" in
        reset-*) reset_installation ;;
        branch-*) switch_branch staging 1 ;;
        *) update_st 0 1 ;;
    esac
    exit
fi

mkdir -p "$ST_HOME/data" "$ST_HOME/node_modules" "$DOWNLOAD_DIR"
git -C "$ST_HOME" init -qb release
git -C "$ST_HOME" config user.name Fixture
git -C "$ST_HOME" config user.email fixture@example.invalid
git -C "$ST_HOME" config core.autocrlf false
printf '{"name":"fixture","version":"1.0.0","engines":{"node":">=18"}}\n' > "$ST_HOME/package.json"
printf 'old-code\n' > "$ST_HOME/server.js"
printf 'data/\nnode_modules/\nignored/\n' > "$ST_HOME/.gitignore"
git -C "$ST_HOME" add .
git -C "$ST_HOME" commit -qm old
OLD_COMMIT="$(git -C "$ST_HOME" rev-parse HEAD)"
printf 'new-code\n' > "$ST_HOME/server.js"
if [[ "$CASE_NAME" == *-incompatible ]]; then
    printf '{"name":"fixture","version":"2.0.0","engines":{"node":">=1 <2"}}\n' > "$ST_HOME/package.json"
fi
git -C "$ST_HOME" commit -qam new
NEW_COMMIT="$(git -C "$ST_HOME" rev-parse HEAD)"
git -C "$ST_HOME" update-ref refs/remotes/origin/release "$NEW_COMMIT"
git -C "$ST_HOME" update-ref refs/remotes/origin/staging "$NEW_COMMIT"
git -C "$ST_HOME" remote add origin "$TEST_ROOT/$CASE_NAME/origin"
git clone -q --bare "$ST_HOME" "$TEST_ROOT/$CASE_NAME/origin"
git -C "$ST_HOME" reset --hard -q "$OLD_COMMIT"
printf 'user-data\n' > "$ST_HOME/data/value"
printf 'old-modules\n' > "$ST_HOME/node_modules/value"
printf 'old-hash\n' > "$DEPENDENCY_HASH_FILE"
printf 'untracked-user-file\n' > "$ST_HOME/user-file.txt"
printf 'custom-code\n' > "$ST_HOME/server.js"

check_original() {
    [[ "$(git -C "$ST_HOME" rev-parse HEAD)" == "$OLD_COMMIT" ]] || fail 'old commit not restored'
    [[ "$(git -C "$ST_HOME" symbolic-ref --short HEAD)" == release ]] || fail 'old branch not restored'
    grep -Fxq custom-code "$ST_HOME/server.js" || fail 'tracked edits not restored'
    grep -Fxq user-data "$ST_HOME/data/value" || fail 'user data changed'
    grep -Fxq untracked-user-file "$ST_HOME/user-file.txt" || fail 'untracked file removed'
    grep -Fxq old-modules "$ST_HOME/node_modules/value" || fail 'old modules not restored'
    grep -Fxq old-hash "$DEPENDENCY_HASH_FILE" || fail 'old dependency marker not restored'
}
fresh_recovery() {
    GIT_TRANSACTION_ACTIVE=0; GIT_TRANSACTION_DIR=""
    RESTORE_TRANSACTION_ACTIVE=0; RESTORE_RECOVERY_RETAINED=0; CURRENT_WORK_DIR=""
    inspect_restore_recovery
    recover_pending_restore
    inspect_restore_recovery
    [[ ${#RESTORE_PENDING_WORKS[@]} == 0 ]] || fail 'completed recovery still pending'
}

case "$CASE_NAME" in
    reset-*|update-*|branch-*)
        code=0
        bash "$0" --worker "$CASE_NAME" "$TEST_ROOT" > "$TEST_ROOT/$CASE_NAME/worker.log" 2>&1 || code=$?
        if [[ "$CASE_NAME" == *-incompatible ]]; then
            [[ "$code" == 56 ]] || { cat "$TEST_ROOT/$CASE_NAME/worker.log"; fail "expected NODE_VERSION_UNSUPPORTED, exit=$code"; }
            grep -Fq '>=1 <2' "$TEST_ROOT/$CASE_NAME/worker.log" || fail 'target requirement not shown'
            grep -Fq "$(node -p 'process.versions.node')" "$TEST_ROOT/$CASE_NAME/worker.log" || fail 'actual Node version not shown'
            check_original
            [[ -z "$(find "$BACKUP_DIR" \( -name '*.tar.gz' -o -name '*recovery.env' \) -print -quit)" ]] || fail 'failure started a destructive transaction or large backup'
        elif [[ "$CASE_NAME" == branch-offline ]]; then
            [[ "$code" == 18 ]] || { cat "$TEST_ROOT/$CASE_NAME/worker.log"; fail "offline exit=$code"; }
            check_original
            [[ -z "$(find "$BACKUP_DIR" -name '*.tar.gz' -print -quit)" ]] || fail 'offline case needlessly compressed safety copy'
        elif [[ "$CASE_NAME" == branch-success ]]; then
            [[ "$code" == 0 ]] || { cat "$TEST_ROOT/$CASE_NAME/worker.log"; fail "branch success exit=$code"; }
            [[ "$(git -C "$ST_HOME" symbolic-ref --short HEAD)" == staging ]] || fail 'target branch not selected'
            grep -Fxq untracked-user-file "$ST_HOME/user-file.txt" || fail 'successful branch deleted untracked data'
            inspect_restore_recovery; [[ ${#RESTORE_PENDING_WORKS[@]} == 0 ]] || fail 'success left recovery pending'
        elif [[ "$CASE_NAME" == reset-commit || "$CASE_NAME" == update-commit ]]; then
            [[ "$code" == 137 ]] || { cat "$TEST_ROOT/$CASE_NAME/worker.log"; fail "commit kill exit=$code"; }
            inspect_restore_recovery; [[ ${#RESTORE_PENDING_WORKS[@]} == 0 ]] || fail 'committed operation rolled back'
            grep -Fxq new-code "$ST_HOME/server.js" || fail 'committed replacement lost'
        elif [[ "$CASE_NAME" == *failed ]]; then
            [[ "$code" != 0 ]] || fail 'failure was ignored'
            check_original
            fresh_recovery
        else
            [[ "$code" == 137 ]] || { cat "$TEST_ROOT/$CASE_NAME/worker.log"; fail "kill exit=$code"; }
            inspect_restore_recovery; [[ ${#RESTORE_PENDING_WORKS[@]} == 1 ]] || fail 'crash not discovered'
            fresh_recovery; check_original
            fresh_recovery; check_original
        fi ;;
    tracked-staged-rollback)
        git -C "$ST_HOME" add server.js
        printf 'unstaged-custom\n' > "$ST_HOME/server.js"
        prepare_git_transaction update release "$NEW_COMMIT"
        git -C "$ST_HOME" reset --hard -q HEAD
        mv "$ST_HOME/node_modules" "$GIT_TRANSACTION_DIR/node_modules"
        mv "$GIT_TRANSACTION_DIR/new-node_modules" "$ST_HOME/node_modules"
        git -C "$ST_HOME" merge --ff-only "$NEW_COMMIT" >/dev/null
        rollback_git_transaction
        [[ "$(git -C "$ST_HOME" show :server.js)" == custom-code ]] || fail 'staged state lost'
        grep -Fxq unstaged-custom "$ST_HOME/server.js" || fail 'unstaged state lost' ;;
    untracked-collision)
        # Existing ignored files must not be overwritten by a new tracked path.
        git -C "$ST_HOME" reset --hard -q "$NEW_COMMIT"
        mkdir "$ST_HOME/ignored"; printf 'new tracked\n' > "$ST_HOME/ignored/secret"
        git -C "$ST_HOME" add -f ignored/secret; git -C "$ST_HOME" commit -qm collision
        TARGET="$(git -C "$ST_HOME" rev-parse HEAD)"
        git -C "$ST_HOME" reset --hard -q "$OLD_COMMIT"
        mkdir -p "$ST_HOME/ignored"; printf 'private original\n' > "$ST_HOME/ignored/secret"
        code=0; prepare_git_transaction branch staging "$TARGET" || code=$?
        [[ "$code" != 0 ]] || fail 'ignored collision accepted'
        rollback_git_transaction
        grep -Fxq 'private original' "$ST_HOME/ignored/secret" || fail 'ignored file destroyed' ;;
    legacy-orphan)
        mkdir -p "$BACKUP_DIR/reset-rollback-123/full-install/data"
        printf original > "$BACKUP_DIR/reset-rollback-123/full-install/data/value"
        inspect_restore_recovery
        [[ ${#RESTORE_PENDING_WORKS[@]} == 1 && "$RESTORE_PENDING_BLOCKED" == 1 ]] || fail 'legacy orphan not exposed'
        if recover_pending_restore; then fail 'unverified legacy recovery accepted'; fi ;;
    writer-*)
        if [[ "$OSTYPE" == msys* ]]; then echo 'SKIP: process-environment proof requires Linux /proc'; exit 0; fi
        prepare_git_transaction update release "$NEW_COMMIT"
        WORK="$GIT_TRANSACTION_DIR"
        if [[ "$CASE_NAME" == writer-pre-exec || "$CASE_NAME" == writer-bad-gate ]]; then
            token=0123456789abcdef0123456789abcdef
            marker="$WORK/writer-$token.env"
            printf 'token=%s\nowner_start=%s\n' "$token" "$(process_start_ticks "$$")" > "$marker"
            : > "$WORK/writer-$token.go"
            if [[ "$CASE_NAME" == writer-bad-gate ]]; then
                if transaction_writers_quiet "$WORK"; then fail 'gate without child identity accepted'; fi
                [[ ! -e "$WORK/writer-$token.done" ]] || fail 'invalid gate marked complete'
                exit 0
            fi
            # The recorded child exists but has not execed env/token yet.
            sleep 30 & runner=$!
            printf 'pid=%s\nstart=%s\n' "$runner" "$(process_start_ticks "$runner")" >> "$marker"
            code=0; transaction_writers_quiet "$WORK" || code=$?
            kill "$runner"; wait "$runner" 2>/dev/null || true
            [[ "$code" != 0 ]] || fail 'pre-exec child without token accepted'
            transaction_writers_quiet "$WORK" || fail 'ended pre-exec child stayed blocked'
            rollback_git_transaction; check_original
            exit 0
        fi
        ready="$TEST_ROOT/$CASE_NAME/ready"; release="$TEST_ROOT/$CASE_NAME/release"
        if [[ "$CASE_NAME" == writer-descendant ]]; then
            run_transaction_command bash -c '
                echo "$$" > "$1.root"
                bash -c '\''echo ready > "$1"; while [[ ! -f "$2" ]]; do sleep 0.1; done'\'' child "$1" "$2" &
                wait
            ' writer "$ready" "$release" &
        else
            run_transaction_command bash -c 'echo ready > "$1"; while [[ ! -f "$2" ]]; do sleep 0.1; done' writer "$ready" "$release" &
        fi
        runner=$!
        for _ in $(seq 1 100); do [[ -f "$ready" ]] && break; sleep 0.05; done
        [[ -f "$ready" ]] || fail 'writer never passed persisted gate'
        if [[ "$CASE_NAME" == writer-orphan ]]; then kill -KILL "$runner"; wait "$runner" 2>/dev/null || true; fi
        if [[ "$CASE_NAME" == writer-descendant ]]; then kill -KILL "$(cat "$ready.root")"; fi
        if transaction_writers_quiet "$WORK"; then fail 'live/orphan writer was considered quiescent'; fi
        if rollback_git_transaction; then fail 'live writer was allowed to race rollback'; fi
        printf done > "$release"
        wait "$runner" 2>/dev/null || true
        quiet=0
        for _ in $(seq 1 100); do
            if transaction_writers_quiet "$WORK"; then quiet=1; break; fi
            sleep 0.05
        done
        [[ "$quiet" == 1 ]] || fail 'completed writers stayed blocked'
        rollback_git_transaction
        check_original
        ;;
    *)
        prepare_git_transaction update release "$NEW_COMMIT"
        WORK="$GIT_TRANSACTION_DIR"
        mv "$ST_HOME/node_modules" "$WORK/node_modules"
        mv "$WORK/new-node_modules" "$ST_HOME/node_modules"
        git -C "$ST_HOME" reset --hard -q "$NEW_COMMIT"
        case "$CASE_NAME" in
            journal-duplicate) printf 'mode=branch\n' >> "$WORK/git-recovery.env" ;;
            journal-symlink)
                if [[ "$OSTYPE" == msys* ]]; then echo 'SKIP: actual symlink validation runs on Linux'; exit 0; fi
                mv "$WORK/git-recovery.env" "$WORK/other"; ln -s other "$WORK/git-recovery.env" ;;
            root-identity) mv "$ST_HOME" "$TEST_ROOT/$CASE_NAME/replaced"; mkdir "$ST_HOME"; cp -a "$TEST_ROOT/$CASE_NAME/replaced/.git" "$ST_HOME/.git" ;;
            modules-identity) mv "$ST_HOME/node_modules" "$TEST_ROOT/$CASE_NAME/new-modules"; mkdir "$ST_HOME/node_modules"; printf foreign > "$ST_HOME/node_modules/foreign" ;;
            git-lock) printf 'unknown-lock-owner\n' > "$ST_HOME/.git/index.lock" ;;
            rollback-interrupted)
                code=0
                (mv() { command mv "$@" || return $?; [[ " $* " != *" $WORK/node_modules "* ]] || kill -KILL "$BASHPID"; }; rollback_git_transaction) || code=$?
                [[ "$code" == 137 ]] || fail "recovery interruption=$code"
                fresh_recovery; check_original; exit 0 ;;
        esac
        if rollback_git_transaction; then fail 'unsafe journal/identity was accepted'; fi
        [[ -d "$WORK/node_modules" ]] || fail 'protected old modules removed'
        [[ -f "$WORK/git-recovery.env" || -L "$WORK/git-recovery.env" ]] || fail 'failed recovery discarded journal'
        [[ "$CASE_NAME" != git-lock || -f "$ST_HOME/.git/index.lock" ]] || fail 'unknown Git lock deleted'
        ;;
esac
