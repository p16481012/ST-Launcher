#!/data/data/com.termux/files/usr/bin/bash
# Sourced by manager.sh. Durable Git/dependency transactions share its lock,
# recovery discovery and UI, but never reinterpret legacy restore journals.
GIT_TRANSACTION_DIR=""
GIT_TRANSACTION_ACTIVE=0
GIT_TRANSACTION_STATE="prepared"
GIT_TRANSACTION_MODE=""
GIT_TRANSACTION_OLD_BRANCH=""
GIT_TRANSACTION_OLD_COMMIT=""
GIT_TRANSACTION_TARGET_BRANCH=""
GIT_TRANSACTION_TARGET_COMMIT=""
GIT_TRANSACTION_STASH="none"
GIT_TRANSACTION_ROOT_ID="none"
GIT_TRANSACTION_GIT_ID="none"
GIT_TRANSACTION_MODULES_ID="none"
GIT_TRANSACTION_NEW_MODULES_ID="none"
GIT_TRANSACTION_HASH_SAVED=0
GIT_TRANSACTION_PUBLISHED=0

# Give every long writer an inherited, unguessable token before it can touch
# the installation. /proc environment checks also find reparented descendants
# when Android kills the manager or the intermediate waiting shell.
run_transaction_command() {
    local work="" token marker gate owner="$$" owner_start pid started code=0
    if (( GIT_TRANSACTION_ACTIVE )); then work="$GIT_TRANSACTION_DIR"
    elif (( RESTORE_TRANSACTION_ACTIVE )); then work="$CURRENT_WORK_DIR"; fi
    if [[ -z "$work" ]]; then "$@"; return $?; fi
    token="$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')" || return 1
    [[ "$token" =~ ^[0-9a-f]{32}$ ]] || return 1
    marker="$work/writer-$token.env"; gate="$work/writer-$token.go"
    owner_start="$(process_start_ticks "$owner")"
    [[ "$owner_start" =~ ^[1-9][0-9]{0,17}$ ]] || return 1
    printf 'token=%s\nowner_start=%s\n' "$token" "$owner_start" > "$marker" || return 1
    sync -f "$marker" 2>/dev/null || sync || return 1
    sync -f "$work" 2>/dev/null || sync || return 1
    env ST_LAUNCHER_WRITER_TOKEN="$token" bash -c '
        gate=$1; owner=$2; expected=$3; shift 3
        while [[ ! -f "$gate" ]]; do
            current=$(awk '\''{sub(/^.*\) /, ""); print $20}'\'' "/proc/$owner/stat" 2>/dev/null)
            [[ "$current" == "$expected" ]] || exit 130
            sleep 0.05
        done
        "$@"
    ' launcher-writer "$gate" "$owner" "$owner_start" "$@" &
    pid=$!; started="$(process_start_ticks "$pid")"
    [[ "$started" =~ ^[1-9][0-9]{0,17}$ ]] || return 1
    printf 'pid=%s\nstart=%s\n' "$pid" "$started" >> "$marker" || return 1
    sync -f "$marker" 2>/dev/null || sync || return 1
    : > "$gate" || return 1
    # The marker is deliberately retained after completion: the recovery scanner
    # verifies that no descendant with the token is still running before undo.
    while process_identity_live "$pid" "$started"; do
        if (( CANCELLATION_GUARD == 0 )) && cancellation_requested; then
            CANCELLABLE_CHILD="$pid"; CANCELLABLE_CHILD_START="$started"
            stop_cancellable_worker
            : > "$(cancellation_request_path).confirmed" || true
            return 130
        fi
        sleep 0.2
    done
    wait "$pid" || code=$?
    return "$code"
}

transaction_termux_ancestors() {
    local own_uid="$1" pid="$$" parent started name app_pid="${TERMUX_APP__PID:-${TERMUX_APP_PID:-}}" depth=0
    # A restarted non-dumpable Java process is newer than an old journal, but
    # cannot be an npm/Git descendant: it is an ancestor of this new manager.
    # Release Termux may also restrict cmdline; its official app-PID variable is
    # only a candidate, never sufficient without this actual ancestry check.
    while (( depth++ < 64 )); do
        read -r parent started < <(awk '{sub(/^.*\) /, ""); print $2, $20}' "/proc/$pid/stat" 2>/dev/null) || break
        [[ "$parent" =~ ^[0-9]+$ && "$started" =~ ^[1-9][0-9]{0,17}$ ]] || break
        [[ "$(stat -c %u "/proc/$pid" 2>/dev/null)" == "$own_uid" ]] || break
        if [[ "$pid" != "$$" ]]; then
            name=""
            if IFS= read -r -d '' name < "/proc/$pid/cmdline" 2>/dev/null; then
                [[ "$name" == com.termux ]] || name="rejected"
            elif [[ "$pid" == "$app_pid" && "$app_pid" =~ ^[1-9][0-9]{0,9}$ ]]; then
                name=com.termux
            fi
            if [[ "$name" == com.termux && "$(process_start_ticks "$pid")" == "$started" ]]; then
                printf '%s:%s\n' "$pid" "$started"
            fi
        fi
        (( parent > 1 && parent != pid )) || break
        [[ "$(process_start_ticks "$pid")" == "$started" ]] || break
        pid="$parent"
    done
}

transaction_writer_environment() {
    # Silence a disappearing /proc entry at redirection time, not just inside tr.
    tr '\0' '\n' 2>/dev/null < "$1/environ" | sed -n '/^ST_LAUNCHER_WRITER_TOKEN=/p'
}

# Called with the active tokens and verified ancestor identities in dynamic scope.
transaction_process_quiet() {
    local proc="$1" own_uid="$2" earliest="$3" pid="${1##*/}" owner started snapshot state now environment token attempt
    for attempt in 1 2 3; do
        [[ -d "$proc" && "$pid" != "$$" ]] || return 0
        owner="$(stat -c %u "$proc" 2>/dev/null)" || { [[ ! -d "$proc" ]] && return 0; continue; }
        [[ "$owner" == "$own_uid" ]] || return 0
        started="$(process_start_ticks "$pid")"
        if [[ ! "$started" =~ ^[0-9]{1,18}$ ]]; then [[ ! -d "$proc" ]] && return 0; continue; fi
        (( 10#$started < 10#$earliest )) && return 0
        if environment="$(transaction_writer_environment "$proc")"; then
            while IFS= read -r token; do
                token="${token#ST_LAUNCHER_WRITER_TOKEN=}"
                if [[ "$token" =~ ^[0-9a-f]{32}$ && -n "${tokens[$token]:-}" ]]; then
                    echo "이전 작업의 하위 프로세스가 아직 실행 중입니다. 종료 후 점검·복구를 다시 실행해 주세요." >&2
                    return 1
                fi
            done <<< "$environment"
            return 0
        fi
        # Losing an exited PID is benign. A replacement with the same PID may be a writer itself.
        snapshot="$(awk '{sub(/^.*\) /, ""); print $1, $20}' "$proc/stat" 2>/dev/null)" || { [[ ! -d "$proc" ]] && return 0; continue; }
        read -r state now <<< "$snapshot"
        [[ "$now" =~ ^[0-9]{1,18}$ ]] || continue
        [[ "$state" != Z ]] || return 0
        [[ "$now" == "$started" ]] || continue
        [[ -z "${termux_ancestors[$pid:$started]:-}" ]] || return 0
        break
    done
    echo "이전 작업의 하위 프로세스 종료를 확인하지 못했습니다. Termux 작업이 끝난 뒤 다시 시도해 주세요." >&2
    return 1
}

transaction_writers_quiet() {
    local work="$1" marker token cutoff earliest="" started proc owner environment own_uid done pid gate
    local -A tokens=() termux_ancestors=()
    for marker in "$work"/writer-*.env; do
        [[ -e "$marker" || -L "$marker" ]] || continue
        [[ -f "$marker" && ! -L "$marker" && "$(stat -c %s "$marker")" -le 256 ]] || return 1
        token="$(sed -n 's/^token=//p' "$marker")"
        [[ "$token" =~ ^[0-9a-f]{32}$ && "$marker" == "$work/writer-$token.env" ]] || return 1
        cutoff="$(sed -n 's/^owner_start=//p' "$marker")"
        [[ "$cutoff" =~ ^[1-9][0-9]{0,17}$ ]] || return 1
        done="$work/writer-$token.done"
        if [[ -e "$done" || -L "$done" ]]; then
            [[ -f "$done" && ! -L "$done" && "$(stat -c %s "$done")" == 33 && "$(cat "$done")" == "$token" ]] || return 1
            continue
        fi
        pid="$(sed -n 's/^pid=//p' "$marker")"
        started="$(sed -n 's/^start=//p' "$marker")"
        gate="$work/writer-$token.go"
        if [[ -n "$pid" || -n "$started" ]]; then
            [[ "$pid" =~ ^[1-9][0-9]{0,9}$ && "$started" =~ ^[1-9][0-9]{0,17}$ ]] || return 1
            # Between fork and exec the child need not expose its token yet.
            # Its recorded PID/lifetime still proves that it can start writing.
            if process_identity_live "$pid" "$started"; then
                echo "이전 작업 프로세스가 아직 실행 중입니다. 종료 후 점검·복구를 다시 실행해 주세요." >&2
                return 1
            fi
        elif [[ -e "$gate" || -L "$gate" ]]; then
            return 1
        fi
        [[ ! -L "$gate" && ( ! -e "$gate" || -f "$gate" ) ]] || return 1
        tokens["$token"]=1
        if [[ -z "$earliest" ]] || (( 10#$cutoff < 10#$earliest )); then earliest="$cutoff"; fi
    done
    (( ${#tokens[@]} )) || return 0
    own_uid="$(id -u)" || return 1
    while IFS= read -r proc; do
        [[ "$proc" =~ ^[1-9][0-9]*:[1-9][0-9]*$ ]] && termux_ancestors["$proc"]=1
    done < <(transaction_termux_ancestors "$own_uid")
    for proc in /proc/[0-9]*; do
        transaction_process_quiet "$proc" "$own_uid" "$earliest" || return 1
    done
    # Only after proving no tagged descendant survives may subsequent checks
    # take the cheap completion path. A mere parent wait()/exit is not proof.
    for token in "${!tokens[@]}"; do
        done="$work/writer-$token.done"
        (set -C; printf '%s\n' "$token" > "$done") || return 1
        sync -f "$done" 2>/dev/null || sync || return 1
    done
    sync -f "$work" 2>/dev/null || sync || return 1
    return 0
}

git_transaction_work_safe() {
    local work="$1" name="${1##*/}" base installation
    [[ "$name" =~ ^(update|branch)-work-[0-9]+$ ]] || return 1
    [[ -d "$work" && ! -L "$work" && ! -L "$BACKUP_DIR" && ! -L "$work/git-recovery.env" ]] || return 1
    base="$(realpath -m "$BACKUP_DIR")" || return 1
    installation="$(realpath -m "$ST_HOME")" || return 1
    [[ "$installation" != / && "$installation" != "$(realpath -m "$HOME")" &&
        "$installation" != "$base/$name" && "$installation" != "$base/$name/"* && "$base/$name" != "$installation/"* ]] || return 1
    [[ "$(realpath -m "$work")" == "$base/$name" && "$(realpath -m "$(dirname "$work")")" == "$base" ]]
}

write_git_transaction_journal() {
    GIT_TRANSACTION_PUBLISHED=0
    git_transaction_work_safe "$GIT_TRANSACTION_DIR" || return 1
    local temp
    temp="$(mktemp "$GIT_TRANSACTION_DIR/git-recovery.tmp.XXXXXXXX")" || return 1
    {
        printf 'format=st-launcher-git-recovery-v1\nmode=%s\nstate=%s\ninstallation_b64=%s\n' \
            "$GIT_TRANSACTION_MODE" "$GIT_TRANSACTION_STATE" "$(printf '%s' "$ST_HOME" | base64 -w 0)"
        printf 'root_identity=%s\ngit_identity=%s\nold_branch=%s\nold_commit=%s\ntarget_branch=%s\ntarget_commit=%s\n' \
            "$GIT_TRANSACTION_ROOT_ID" "$GIT_TRANSACTION_GIT_ID" "$GIT_TRANSACTION_OLD_BRANCH" "$GIT_TRANSACTION_OLD_COMMIT" \
            "$GIT_TRANSACTION_TARGET_BRANCH" "$GIT_TRANSACTION_TARGET_COMMIT"
        printf 'stash_commit=%s\nmodules_identity=%s\nnew_modules_identity=%s\nhash_saved=%s\n' \
            "$GIT_TRANSACTION_STASH" "$GIT_TRANSACTION_MODULES_ID" "$GIT_TRANSACTION_NEW_MODULES_ID" "$GIT_TRANSACTION_HASH_SAVED"
    } > "$temp" || return 1
    sync -f "$temp" 2>/dev/null || sync || return 1
    mv -f -- "$temp" "$GIT_TRANSACTION_DIR/git-recovery.env" || return 1
    GIT_TRANSACTION_PUBLISHED=1
    sync -f "$GIT_TRANSACTION_DIR" 2>/dev/null || sync || return 1
}

load_git_transaction_journal() {
    local work="$1" line key value seen='|' installation=""
    git_transaction_work_safe "$work" || return 1
    [[ -f "$work/git-recovery.env" && "$(stat -c %s "$work/git-recovery.env")" -le 16384 ]] || return 1
    GIT_TRANSACTION_DIR="$work"
    while IFS= read -r line || [[ -n "$line" ]]; do
        [[ "$line" == *=* ]] || return 1
        key="${line%%=*}"; value="${line#*=}"
        [[ "$seen" != *"|$key|"* ]] || return 1
        seen="$seen$key|"
        case "$key" in
            format) [[ "$value" == st-launcher-git-recovery-v1 ]] || return 1 ;;
            mode) [[ "$value" == update || "$value" == branch ]] || return 1; GIT_TRANSACTION_MODE="$value" ;;
            state) [[ "$value" == prepared || "$value" == applying || "$value" == committed || "$value" == rolled-back ]] || return 1; GIT_TRANSACTION_STATE="$value" ;;
            installation_b64) installation="$(decode_restore_journal_path "$value")" || return 1 ;;
            root_identity|git_identity|modules_identity|new_modules_identity)
                [[ "$value" =~ ^(none|[0-9]+:[0-9]+)$ ]] || return 1
                case "$key" in root_identity) GIT_TRANSACTION_ROOT_ID="$value" ;; git_identity) GIT_TRANSACTION_GIT_ID="$value" ;;
                    modules_identity) GIT_TRANSACTION_MODULES_ID="$value" ;; new_modules_identity) GIT_TRANSACTION_NEW_MODULES_ID="$value" ;; esac ;;
            old_branch|target_branch)
                [[ "$value" == release || "$value" == staging ]] || return 1
                if [[ "$key" == old_branch ]]; then GIT_TRANSACTION_OLD_BRANCH="$value"; else GIT_TRANSACTION_TARGET_BRANCH="$value"; fi ;;
            old_commit|target_commit|stash_commit)
                [[ "$value" =~ ^[0-9a-f]{40}$ || ( "$key" == stash_commit && "$value" == none ) ]] || return 1
                case "$key" in old_commit) GIT_TRANSACTION_OLD_COMMIT="$value" ;; target_commit) GIT_TRANSACTION_TARGET_COMMIT="$value" ;; stash_commit) GIT_TRANSACTION_STASH="$value" ;; esac ;;
            hash_saved) [[ "$value" == 0 || "$value" == 1 ]] || return 1; GIT_TRANSACTION_HASH_SAVED="$value" ;;
            *) return 1 ;;
        esac
    done < "$work/git-recovery.env"
    for key in format mode state installation_b64 root_identity git_identity old_branch old_commit target_branch target_commit stash_commit modules_identity new_modules_identity hash_saved; do
        [[ "$seen" == *"|$key|"* ]] || return 1
    done
    [[ "$installation" == "$ST_HOME" && "$GIT_TRANSACTION_ROOT_ID" != none && "$GIT_TRANSACTION_GIT_ID" != none &&
        ! -L "$ST_HOME" && ! -L "$ST_HOME/.git" && -d "$ST_HOME/.git" ]] || return 1
    [[ "$GIT_TRANSACTION_MODE" != branch || ( "$GIT_TRANSACTION_MODULES_ID" == none && "$GIT_TRANSACTION_NEW_MODULES_ID" == none ) ]] || return 1
    [[ "$GIT_TRANSACTION_MODE" != update || "$GIT_TRANSACTION_NEW_MODULES_ID" != none ]] || return 1
    return 0
}

git_transaction_ref() { printf 'refs/st-launcher-recovery/%s\n' "${GIT_TRANSACTION_DIR##*/}"; }

git_transaction_locks_clear() {
    local file
    for file in index.lock HEAD.lock config.lock packed-refs.lock "refs/heads/$GIT_TRANSACTION_OLD_BRANCH.lock" "refs/heads/$GIT_TRANSACTION_TARGET_BRANCH.lock"; do
        if [[ -e "$ST_HOME/.git/$file" || -L "$ST_HOME/.git/$file" ]]; then
            echo "Git 잠금 파일이 남아 있어 자동 복구를 중단했습니다: $file. 다른 Git 작업의 종료 여부를 확인해 주세요. 보호 사본과 잠금은 삭제하지 않았습니다." >&2
            return 1
        fi
    done
}

# Never let reset/switch remove an untracked (including ignored) path that
# obstructs a tracked path. No git clean is used by these transactions.
git_transaction_target_safe() {
    local commit="$1" paths others path ancestor file
    git_transaction_work_safe "$GIT_TRANSACTION_DIR" || return 1
    paths="$(mktemp "$GIT_TRANSACTION_DIR/paths.XXXXXXXX")" || return 1
    others="$(mktemp "$GIT_TRANSACTION_DIR/others.XXXXXXXX")" || { rm -f -- "$paths"; return 1; }
    if ! git -C "$ST_HOME" ls-tree -r --name-only -z "$commit" > "$paths" ||
        ! git -C "$ST_HOME" ls-files --others --directory --no-empty-directory -z > "$others"; then
        rm -f -- "$paths" "$others"; return 1
    fi
    local -A tracked=() untracked=()
    while IFS= read -r -d '' path; do tracked["$path"]=1; done < "$paths"
    while IFS= read -r -d '' path; do untracked["${path%/}"]=1; done < "$others"
    rm -f -- "$paths" "$others"
    for file in "${!tracked[@]}"; do
        ancestor="$file"
        while [[ -n "$ancestor" ]]; do
            [[ -z "${untracked[$ancestor]:-}" ]] || return 1
            [[ "$ancestor" == */* ]] || break
            ancestor="${ancestor%/*}"
        done
    done
    for file in "${!untracked[@]}"; do
        ancestor="$file"
        while [[ -n "$ancestor" ]]; do
            [[ -z "${tracked[$ancestor]:-}" ]] || return 1
            [[ "$ancestor" == */* ]] || break
            ancestor="${ancestor%/*}"
        done
    done
}

prepare_git_transaction() {
    local mode="$1" target_branch="$2" target_commit="$3" stash
    [[ "$mode" == update || "$mode" == branch ]] || return 1
    [[ ! -L "$ST_HOME" && -d "$ST_HOME/.git" && ! -L "$ST_HOME/.git" && ! -L "$DEPENDENCY_HASH_FILE" ]] || return 1
    GIT_TRANSACTION_DIR="$BACKUP_DIR/$mode-work-$$"
    mkdir -m 700 -- "$GIT_TRANSACTION_DIR" || return 1
    GIT_TRANSACTION_MODE="$mode"; GIT_TRANSACTION_STATE=prepared; GIT_TRANSACTION_ACTIVE=0
    GIT_TRANSACTION_OLD_BRANCH="$(current_branch)" || return 1
    GIT_TRANSACTION_OLD_COMMIT="$(git -C "$ST_HOME" rev-parse HEAD)" || return 1
    GIT_TRANSACTION_TARGET_BRANCH="$target_branch"; GIT_TRANSACTION_TARGET_COMMIT="$target_commit"
    GIT_TRANSACTION_ROOT_ID="$(restore_file_identity "$ST_HOME")" || return 1
    GIT_TRANSACTION_GIT_ID="$(restore_file_identity "$ST_HOME/.git")" || return 1
    GIT_TRANSACTION_MODULES_ID=none; GIT_TRANSACTION_NEW_MODULES_ID=none; GIT_TRANSACTION_HASH_SAVED=0
    if [[ -f "$DEPENDENCY_HASH_FILE" ]]; then
        cp -a -- "$DEPENDENCY_HASH_FILE" "$GIT_TRANSACTION_DIR/dependency-lock.sha256" || return 1
        GIT_TRANSACTION_HASH_SAVED=1
    fi
    if [[ "$mode" == update ]]; then
        [[ ! -L "$ST_HOME/node_modules" && ( ! -e "$ST_HOME/node_modules" || -d "$ST_HOME/node_modules" ) ]] || return 1
        GIT_TRANSACTION_MODULES_ID="$(restore_file_identity "$ST_HOME/node_modules")" || return 1
        mkdir "$GIT_TRANSACTION_DIR/new-node_modules" || return 1
        GIT_TRANSACTION_NEW_MODULES_ID="$(restore_file_identity "$GIT_TRANSACTION_DIR/new-node_modules")" || return 1
    fi
    # Stash create is read-only for the working tree and index, and excludes the
    # ignored multi-gigabyte data tree. A private ref, not the user's stash stack,
    # keeps tracked/staged changes reachable until the transaction terminates.
    stash="$(git -C "$ST_HOME" -c user.name=ST-Launcher -c user.email=launcher@localhost stash create 'ST-Launcher recovery')" || return 1
    GIT_TRANSACTION_STASH="${stash:-none}"
    write_git_transaction_journal || return 1
    GIT_TRANSACTION_ACTIVE=1
    if [[ "$GIT_TRANSACTION_STASH" != none ]]; then
        git -C "$ST_HOME" update-ref "$(git_transaction_ref)" "$GIT_TRANSACTION_STASH" || return 1
    fi
    git_transaction_target_safe "$GIT_TRANSACTION_OLD_COMMIT" && git_transaction_target_safe "$target_commit" || {
        echo "추적하지 않는 파일이 변경할 코드 경로와 겹칩니다. 원본을 보존하고 작업을 중단합니다." >&2; return 1;
    }
    GIT_TRANSACTION_STATE=applying
    write_git_transaction_journal || return 1
    GIT_TRANSACTION_ACTIVE=1
}

git_transaction_modules_prepared() {
    [[ "$GIT_TRANSACTION_ACTIVE" == 1 && "$GIT_TRANSACTION_MODE" == update &&
        ! -L "$ST_HOME/node_modules" && "$(restore_file_identity "$ST_HOME/node_modules")" == "$GIT_TRANSACTION_NEW_MODULES_ID" ]]
}

finish_git_transaction() {
    transaction_writers_quiet "$GIT_TRANSACTION_DIR" || return 1
    GIT_TRANSACTION_STATE="$1"
    if ! write_git_transaction_journal; then
        # Once published, even a failed directory fsync must not cause a success
        # record to describe a subsequent rollback. Leave both copies to inspect.
        (( GIT_TRANSACTION_PUBLISHED )) && GIT_TRANSACTION_ACTIVE=0
        return 1
    fi
    GIT_TRANSACTION_ACTIVE=0
    if [[ "$GIT_TRANSACTION_STASH" != none ]]; then
        git -C "$ST_HOME" update-ref -d "$(git_transaction_ref)" "$GIT_TRANSACTION_STASH" || return 1
    fi
    git_transaction_work_safe "$GIT_TRANSACTION_DIR" || return 1
    rm -rf -- "$GIT_TRANSACTION_DIR" || return 1
    GIT_TRANSACTION_DIR=""
}

rollback_git_transaction() {
    local work="$GIT_TRANSACTION_DIR" head branch current saved ref
    [[ -n "$work" ]] || return 0
    load_git_transaction_journal "$work" || return 1
    [[ "$GIT_TRANSACTION_STATE" == applying || "$GIT_TRANSACTION_STATE" == prepared ]] || { GIT_TRANSACTION_ACTIVE=0; return 0; }
    [[ "$(restore_file_identity "$ST_HOME")" == "$GIT_TRANSACTION_ROOT_ID" &&
        "$(restore_file_identity "$ST_HOME/.git")" == "$GIT_TRANSACTION_GIT_ID" && ! -L "$DEPENDENCY_HASH_FILE" ]] || return 1
    transaction_writers_quiet "$work" || return 1
    git_transaction_locks_clear || return 1
    GIT_TRANSACTION_ACTIVE=1
    if [[ "$GIT_TRANSACTION_STATE" == prepared ]]; then finish_git_transaction rolled-back; return $?; fi
    head="$(git -C "$ST_HOME" rev-parse HEAD)" || return 1
    branch="$(current_branch)" || return 1
    [[ "$head" == "$GIT_TRANSACTION_OLD_COMMIT" || "$head" == "$GIT_TRANSACTION_TARGET_COMMIT" ]] || return 1
    [[ "$branch" == "$GIT_TRANSACTION_OLD_BRANCH" || "$branch" == "$GIT_TRANSACTION_TARGET_BRANCH" ]] || return 1
    [[ "$GIT_TRANSACTION_HASH_SAVED" != 1 || ( -f "$work/dependency-lock.sha256" && ! -L "$work/dependency-lock.sha256" ) ]] || return 1
    if [[ "$GIT_TRANSACTION_MODE" == update ]]; then
        [[ ! -L "$ST_HOME/node_modules" && ! -L "$work/node_modules" ]] || return 1
        current="$(restore_file_identity "$ST_HOME/node_modules")" || return 1
        saved="$(restore_file_identity "$work/node_modules")" || return 1
        [[ "$current" == none || "$current" == "$GIT_TRANSACTION_MODULES_ID" || "$current" == "$GIT_TRANSACTION_NEW_MODULES_ID" ]] || return 1
        [[ "$saved" == "$GIT_TRANSACTION_MODULES_ID" || ( "$saved" == none && "$current" == "$GIT_TRANSACTION_MODULES_ID" ) ]] || return 1
    fi
    if [[ "$GIT_TRANSACTION_STASH" != none ]]; then
        git -C "$ST_HOME" cat-file -e "$GIT_TRANSACTION_STASH^{commit}" || return 1
        [[ "$(git -C "$ST_HOME" rev-parse "$GIT_TRANSACTION_STASH^1")" == "$GIT_TRANSACTION_OLD_COMMIT" ]] || return 1
    fi
    git_transaction_target_safe "$GIT_TRANSACTION_OLD_COMMIT" || return 1
    terminate_server_process
    if [[ "$branch" != "$GIT_TRANSACTION_OLD_BRANCH" ]]; then
        [[ "$(git -C "$ST_HOME" rev-parse "refs/heads/$GIT_TRANSACTION_OLD_BRANCH")" == "$GIT_TRANSACTION_OLD_COMMIT" ]] || return 1
        run_transaction_command git -C "$ST_HOME" switch --discard-changes "$GIT_TRANSACTION_OLD_BRANCH" >> "$LOG_FILE" 2>&1 || return 1
    fi
    run_transaction_command git -C "$ST_HOME" reset --hard "$GIT_TRANSACTION_OLD_COMMIT" >> "$LOG_FILE" 2>&1 || return 1
    if [[ "$GIT_TRANSACTION_STASH" != none ]]; then
        run_transaction_command git -C "$ST_HOME" stash apply --index "$GIT_TRANSACTION_STASH" >> "$LOG_FILE" 2>&1 || return 1
    fi
    if [[ "$GIT_TRANSACTION_MODE" == update && "$current" != "$GIT_TRANSACTION_MODULES_ID" ]]; then
        [[ "$current" == none ]] || rm -rf -- "$ST_HOME/node_modules" || return 1
        if [[ "$GIT_TRANSACTION_MODULES_ID" != none ]]; then mv -- "$work/node_modules" "$ST_HOME/node_modules" || return 1; fi
    fi
    if [[ "$GIT_TRANSACTION_HASH_SAVED" == 1 ]]; then cp -a -- "$work/dependency-lock.sha256" "$DEPENDENCY_HASH_FILE" || return 1
    else rm -f -- "$DEPENDENCY_HASH_FILE" || return 1; fi
    finish_git_transaction rolled-back || return 1
    echo "이전 브랜치·코드·수정 파일과 실행 패키지를 복구했습니다. 사용자 데이터는 별도 백업으로 보호됩니다." >&2
}

inspect_git_transaction_recovery() {
    local work state
    for work in "$BACKUP_DIR"/update-work-* "$BACKUP_DIR"/branch-work-*; do
        [[ -e "$work/git-recovery.env" || -L "$work/git-recovery.env" ]] || continue
        if state="$(load_git_transaction_journal "$work" && printf '%s' "$GIT_TRANSACTION_STATE")"; then
            [[ "$state" == committed || "$state" == rolled-back ]] && continue
        else RESTORE_PENDING_BLOCKED=1; fi
        RESTORE_PENDING_WORKS+=("$work")
    done
    # Pre-journal releases cannot prove which replacement directory they own.
    # Expose surviving originals, but never guess and delete a live installation.
    for work in "$BACKUP_DIR"/reset-rollback-* "$BACKUP_DIR"/update-rollback-*; do
        [[ -d "$work/full-install" || -d "$work/node_modules" ]] || continue
        RESTORE_PENDING_WORKS+=("$work"); RESTORE_PENDING_BLOCKED=1
    done
}
