#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

MANAGER_VERSION="6"
LAUNCHER_VERSION="${ST_LAUNCHER_VERSION:-unknown}"
ST_HOME="${ST_HOME:-$HOME/SillyTavern}"
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
LAUNCHER_HOME="${ST_LAUNCHER_HOME:-$HOME/.st-launcher}"
DOWNLOAD_DIR="${ST_DOWNLOAD_DIR:-$HOME/storage/downloads}"
MANAGER_SCRIPT_PATH="$(realpath -m "${BASH_SOURCE[0]}")"
RUN_DIR="$LAUNCHER_HOME/run"
LOG_DIR="$LAUNCHER_HOME/logs"
BACKUP_DIR="$LAUNCHER_HOME/backups"
UPDATE_DIR="$LAUNCHER_HOME/update-history"
PID_FILE="$RUN_DIR/sillytavern.pid"
PID_META_FILE="$RUN_DIR/sillytavern.pid.meta"
LOG_FILE="$LOG_DIR/operation.log"
SERVER_LOG="$LOG_DIR/server.log"
PREVIOUS_SERVER_LOG="$LOG_DIR/server-previous.log"
LEGACY_SERVER_LOG="$LOG_DIR/sillytavern.log"
HISTORY_LOG="$LOG_DIR/launcher-history.log"
PROGRESS_FILE="$RUN_DIR/progress.env"
LAST_RESULT_FILE="$RUN_DIR/last-result.env"
SESSION_FILE="$RUN_DIR/server-session.env"
LOCK_DIR="$RUN_DIR/operation.lock"
DEPENDENCY_HASH_FILE="$LAUNCHER_HOME/dependency-lock.sha256"
INSTALL_MARKER="$RUN_DIR/install-owned"
PORT="${ST_PORT:-8000}"
CURRENT_OPERATION="idle"
LAST_LOGGED_PROGRESS=""
OPERATION_STARTED_EPOCH=0
CURRENT_WORK_DIR=""
TEMP_APT_SOURCES=""
OPERATION_LOCK_START=""
RESTORE_TRANSACTION_ACTIVE=0
RESTORE_TRANSACTION_KIND=""
RESTORE_ROLLBACK_DIR=""
RESTORE_HAD_INSTALLATION=0
RESTORE_DEPENDENCY_SAVED=0
RESTORE_RECOVERY_RETAINED=0
RESTORE_FULL_ORIGINAL_MOVED=0
RESTORE_FULL_DATA_RECOVERED=0
declare -a RESTORE_AFFECTED_PATHS=()
declare -a RESTORE_ORIGINAL_PATHS=()
declare -a RESTORE_RECOVERED_PATHS=()

mkdir -p "$RUN_DIR" "$LOG_DIR" "$BACKUP_DIR" "$UPDATE_DIR"

write_progress() {
    local percent="$1"
    local phase="$2"
    local detail="$3"
    local status="${4:-running}"
    local operation="${5:-$CURRENT_OPERATION}"
    local error_code="${6:-}"
    local temp="$PROGRESS_FILE.tmp.$$"
    printf 'percent=%s\nphase=%s\ndetail=%s\nstatus=%s\noperation=%s\nerror_code=%s\n' \
        "$percent" "$phase" "$detail" "$status" "$operation" "$error_code" > "$temp"
    mv -f "$temp" "$PROGRESS_FILE"

    printf '[%s] %s - %s (%s%%)\n' \
        "$(date '+%H:%M:%S')" "$phase" "$detail" "$percent" >> "$LOG_FILE"

    local progress_key="$operation|$phase|$status"
    if [[ "$progress_key" != "$LAST_LOGGED_PROGRESS" ]]; then
        printf '[%s] [%s] %s - %s (%s%%, %s)\n' \
            "$(date '+%Y-%m-%d %H:%M:%S')" "$operation" "$phase" "$detail" "$percent" "$status" >> "$HISTORY_LOG"
        LAST_LOGGED_PROGRESS="$progress_key"
        trim_history_log
    fi
}

manager_error_code() {
    local operation="$1"
    local exit_code="$2"
    case "$operation:$exit_code" in
        restore:20) echo "RESTORE_ZIP_DAMAGED" ;;
        restore:21) echo "RESTORE_ZIP_UNSAFE_PATH" ;;
        restore:22) echo "RESTORE_ZIP_SYMLINK_REJECTED" ;;
        restore:23) echo "RESTORE_FORMAT_UNSUPPORTED" ;;
        restore:24) echo "RESTORE_CONTENT_INVALID" ;;
        restore:25) echo "RESTORE_ROLLBACK_REQUIRED" ;;
        restore:26) echo "RESTORE_DEPENDENCY_FAILED" ;;
        restore:27) echo "RESTORE_VERIFICATION_FAILED" ;;
        restore:29) echo "RESTORE_NO_SPACE" ;;
        import-backup:20) echo "IMPORT_ZIP_DAMAGED" ;;
        import-backup:21) echo "IMPORT_ZIP_UNSAFE_PATH" ;;
        import-backup:22) echo "IMPORT_ZIP_SYMLINK_REJECTED" ;;
        import-backup:23) echo "IMPORT_FORMAT_UNSUPPORTED" ;;
        import-backup:29) echo "IMPORT_NO_SPACE" ;;
        update:29) echo "UPDATE_NODE_REQUIREMENT" ;;
        update:30) echo "UPDATE_NO_SPACE" ;;
        update:31) echo "UPDATE_GIT_FAILED" ;;
        update:32) echo "UPDATE_PACKAGES_FAILED" ;;
        update:33) echo "UPDATE_SERVER_FAILED" ;;
        update:34) echo "UPDATE_HISTORY_DIVERGED" ;;
        reset-installation:30) echo "RESET_NO_SPACE" ;;
        *:2) echo "UNSUPPORTED_BRANCH" ;;
        *:3) echo "INSTALL_PATH_EXISTS" ;;
        *:4) echo "INSTALLATION_NOT_FOUND" ;;
        *:5) echo "SERVER_START_FAILED" ;;
        *:6) echo "FILE_NOT_FOUND" ;;
        *:7) echo "BACKUP_STORAGE_UNAVAILABLE" ;;
        *:8) echo "INSTALLATION_INCOMPLETE" ;;
        *:9) echo "LOCAL_CHANGES_PRESENT" ;;
        *:10) echo "SERVER_MUST_STOP" ;;
        *:11) echo "OPERATION_ALREADY_RUNNING" ;;
        *:12) echo "DEPENDENCY_SETUP_FAILED" ;;
        *:14) echo "PACKAGE_INDEX_FAILED" ;;
        *:15) echo "TERMUX_TOOL_INSTALL_FAILED" ;;
        *:16) echo "ESBUILD_INSTALL_FAILED" ;;
        *:17) echo "GIT_DOWNLOAD_FAILED" ;;
        *:18) echo "NETWORK_FETCH_FAILED" ;;
        *:19) echo "BACKUP_INTEGRITY_FAILED" ;;
        *:20) echo "ZIP_DAMAGED" ;;
        *:21) echo "ZIP_UNSAFE_PATH" ;;
        *:22) echo "ZIP_SYMLINK_REJECTED" ;;
        *:23) echo "BACKUP_FORMAT_UNSUPPORTED" ;;
        *:24) echo "BACKUP_CONTENT_INVALID" ;;
        *:25) echo "RESTORE_ROLLBACK_REQUIRED" ;;
        *:26) echo "RESTORE_DEPENDENCY_FAILED" ;;
        *:27) echo "RESTORE_VERIFICATION_FAILED" ;;
        *:28) echo "BACKUP_DELETE_FAILED" ;;
        *:29) echo "REQUIREMENT_NOT_MET" ;;
        *:30) echo "INSUFFICIENT_STORAGE" ;;
        *:31) echo "UPDATE_GIT_FAILED" ;;
        *:32) echo "UPDATE_PACKAGES_FAILED" ;;
        *:33) echo "UPDATE_SERVER_FAILED" ;;
        *:34) echo "UPDATE_HISTORY_DIVERGED" ;;
        *:64) echo "INVALID_ARGUMENT" ;;
        *:130) echo "OPERATION_CANCELLED" ;;
        *) echo "UNKNOWN_COMMAND_FAILURE" ;;
    esac
}

write_operation_result() {
    local operation="$1"
    local status="$2"
    local exit_code="$3"
    local error_code="${4:-}"
    local temp="$LAST_RESULT_FILE.tmp.$$"
    printf 'operation=%s\nstatus=%s\nexit_code=%s\nerror_code=%s\nfinished_at=%s\n' \
        "$operation" "$status" "$exit_code" "$error_code" "$(date +%s)" > "$temp"
    mv -f "$temp" "$LAST_RESULT_FILE"
}

last_result_value() {
    local key="$1"
    sed -n "s/^${key}=//p" "$LAST_RESULT_FILE" 2>/dev/null | head -n 1
}

attach_progress_result() {
    local error_code="${1:-}"
    [[ -f "$PROGRESS_FILE" ]] || return 0
    local temp="$PROGRESS_FILE.tmp.$$"
    grep -vE '^(error_code|finished_at)=' "$PROGRESS_FILE" > "$temp" || true
    printf 'error_code=%s\nfinished_at=%s\n' "$error_code" "$(date +%s)" >> "$temp"
    mv -f "$temp" "$PROGRESS_FILE"
}

should_record_operation() {
    case "$1" in
        install|start|stop|restart|backup|import-backup|delete-backup|restore|repair|reset-installation|update|update-preflight|switch-branch|diagnose|save-server-connection)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

command_cleanup() {
    local code="${1:-0}"
    trap - EXIT
    local status="success"
    local error_code=""
    local record_result=0
    should_record_operation "$CURRENT_OPERATION" && record_result=1
    # A rejected concurrent request does not own the shared progress/result.
    # Leave the actual running operation's state untouched.
    if (( code == 11 )) || operation_active; then record_result=0; fi
    if (( code != 0 )); then
        status="error"
        error_code="$(manager_error_code "$CURRENT_OPERATION" "$code")"
        printf 'error_code=%s\n' "$error_code" >&2
        if (( record_result )); then
            write_progress 0 "작업 실패" "명령 실행에 실패했습니다. 오류 코드를 확인해 주세요." error "$CURRENT_OPERATION" "$error_code"
        fi
    fi
    if (( record_result )); then
        attach_progress_result "$error_code"
        write_operation_result "$CURRENT_OPERATION" "$status" "$code" "$error_code"
    fi
    exit "$code"
}

trim_history_log() {
    [[ -f "$HISTORY_LOG" ]] || return 0
    local size
    size="$(wc -c < "$HISTORY_LOG" 2>/dev/null || echo 0)"
    if (( size > 1000000 )); then
        tail -c 750000 "$HISTORY_LOG" > "$HISTORY_LOG.tmp.$$"
        mv -f "$HISTORY_LOG.tmp.$$" "$HISTORY_LOG"
    fi
}

reset_current_log() {
    : > "$LOG_FILE"
}

archive_server_log() {
    [[ -s "$SERVER_LOG" ]] || return 0
    local temp="$PREVIOUS_SERVER_LOG.tmp.$$"
    {
        printf '===== 이전 서버 세션 · %s =====\n' "$(date '+%Y-%m-%d %H:%M:%S')"
        tail -n 800 "$SERVER_LOG" 2>/dev/null || true
    } > "$temp"
    mv -f "$temp" "$PREVIOUS_SERVER_LOG"
}

record_activity() {
    printf '[%s] [%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$CURRENT_OPERATION" "$1" >> "$HISTORY_LOG"
    trim_history_log
}

track_current_work_dir() {
    local path="$1"
    case "$path" in
        "$BACKUP_DIR/"*-work-"$$") CURRENT_WORK_DIR="$path" ;;
        *) echo "안전하지 않은 임시 작업 경로입니다: $path" >&2; return 1 ;;
    esac
}

cleanup_current_work_dir() {
    if (( RESTORE_TRANSACTION_ACTIVE || RESTORE_RECOVERY_RETAINED )); then
        printf '복원 복구 사본을 보존했습니다: %s\n' "$CURRENT_WORK_DIR" >&2
        return 0
    fi
    if [[ -n "$CURRENT_WORK_DIR" ]]; then
        case "$CURRENT_WORK_DIR" in
            "$BACKUP_DIR/"*-work-"$$") rm -rf -- "$CURRENT_WORK_DIR" ;;
        esac
    fi
    CURRENT_WORK_DIR=""
}

cleanup_temporary_package_source() {
    if [[ -n "$TEMP_APT_SOURCES" ]]; then
        rm -f -- "$TEMP_APT_SOURCES"
    fi
    TEMP_APT_SOURCES=""
}

show_progress() {
    if [[ -f "$PROGRESS_FILE" ]]; then
        cat "$PROGRESS_FILE"
    else
        printf 'percent=0\nphase=준비 중\ndetail=작업을 준비하고 있습니다.\nstatus=idle\noperation=idle\nerror_code=\nfinished_at=\n'
    fi
}

acquire_operation() {
    if mkdir "$LOCK_DIR" 2>/dev/null; then
        write_operation_lock
        trap 'operation_cleanup $?' EXIT
        trap 'exit 130' INT TERM
        return 0
    fi

    # Another process may have just created the directory and still be writing
    # its identity. Never remove a freshly acquired, not-yet-initialized lock.
    local attempt
    for attempt in $(seq 1 20); do
        [[ -s "$LOCK_DIR/pid" ]] && break
        [[ -d "$LOCK_DIR" ]] || break
        sleep 0.1
    done
    if operation_active; then
        echo "다른 설치 또는 실행 작업이 진행 중입니다. 완료될 때까지 기다려 주세요." >&2
        exit 11
    fi

    local stale_owner stale_start reclaim_token existing_claim claim_pid claim_start quarantine
    stale_owner="$(cat "$LOCK_DIR/pid" 2>/dev/null || true)"
    stale_start="$(cat "$LOCK_DIR/start_ticks" 2>/dev/null || true)"
    reclaim_token="$$:$(process_start_ticks "$$")"
    if ! ln -s "$reclaim_token" "$LOCK_DIR/.reclaim" 2>/dev/null; then
        existing_claim="$(readlink "$LOCK_DIR/.reclaim" 2>/dev/null || true)"
        claim_pid="${existing_claim%%:*}"
        claim_start="${existing_claim#*:}"
        if [[ "$claim_pid" =~ ^[1-9][0-9]{0,9}$ && "$claim_start" =~ ^[0-9]+$ ]] &&
            [[ "$(process_start_ticks "$claim_pid")" == "$claim_start" ]]; then
            echo "다른 작업이 이전 작업 정보를 정리하고 있습니다. 잠시 후 다시 시도해 주세요." >&2
            exit 11
        fi
        # Remove only a dead reclaimer's marker, never the operation directory.
        rm -f "$LOCK_DIR/.reclaim"
        ln -s "$reclaim_token" "$LOCK_DIR/.reclaim" 2>/dev/null || exit 11
    fi
    if [[ "$(cat "$LOCK_DIR/pid" 2>/dev/null || true)" != "$stale_owner" ||
          "$(cat "$LOCK_DIR/start_ticks" 2>/dev/null || true)" != "$stale_start" ]] || operation_active; then
        [[ "$(readlink "$LOCK_DIR/.reclaim" 2>/dev/null || true)" == "$reclaim_token" ]] && rm -f "$LOCK_DIR/.reclaim"
        echo "작업 소유자가 변경되어 이전 잠금 정보를 삭제하지 않았습니다." >&2
        exit 11
    fi
    quarantine="$(mktemp -d "$RUN_DIR/lock-reclaim-XXXXXX")"
    if [[ "$(readlink "$LOCK_DIR/.reclaim" 2>/dev/null || true)" != "$reclaim_token" ]] ||
        ! mv -T "$LOCK_DIR" "$quarantine/operation.lock" 2>/dev/null; then
        rmdir "$quarantine" 2>/dev/null || true
        exit 11
    fi
    if [[ "$(readlink "$quarantine/operation.lock/.reclaim" 2>/dev/null || true)" != "$reclaim_token" ||
          "$(cat "$quarantine/operation.lock/pid" 2>/dev/null || true)" != "$stale_owner" ||
          "$(cat "$quarantine/operation.lock/start_ticks" 2>/dev/null || true)" != "$stale_start" ]]; then
        mv -T -n "$quarantine/operation.lock" "$LOCK_DIR" 2>/dev/null || true
        rmdir "$quarantine" 2>/dev/null || true
        echo "잠금 정보가 바뀌어 삭제하지 않았습니다. 진행 중인 작업을 확인해 주세요." >&2
        exit 11
    fi
    rm -rf "$quarantine"
    if ! mkdir "$LOCK_DIR" 2>/dev/null; then
        echo "다른 작업이 먼저 시작되었습니다. 잠시 후 다시 시도해 주세요." >&2
        exit 11
    fi
    write_operation_lock
    trap 'operation_cleanup $?' EXIT
    trap 'exit 130' INT TERM
}

write_operation_lock() {
    OPERATION_LOCK_START="$(process_start_ticks "$$")"
    [[ "$OPERATION_LOCK_START" =~ ^[0-9]+$ ]] || {
        rmdir "$LOCK_DIR" 2>/dev/null || true
        echo "현재 작업 프로세스의 시작 정보를 확인하지 못했습니다." >&2
        exit 11
    }
    printf '%s\n' "$CURRENT_OPERATION" > "$LOCK_DIR/operation"
    printf '%s\n' "$OPERATION_LOCK_START" > "$LOCK_DIR/start_ticks"
    printf '%s\n' "$$" > "$LOCK_DIR/pid"
}

is_manager_process() {
    local pid="$1" argument candidate process_cwd
    [[ "$pid" =~ ^[1-9][0-9]{0,9}$ ]] || return 1
    kill -0 "$pid" 2>/dev/null || return 1
    process_cwd="$(readlink -f "/proc/$pid/cwd" 2>/dev/null || true)"
    while IFS= read -r -d '' argument; do
        [[ "$argument" == "$MANAGER_SCRIPT_PATH" ]] && return 0
        if [[ "$argument" == */manager.sh || "$argument" == manager.sh ]]; then
            candidate="$argument"
            [[ "$candidate" != /* ]] && candidate="$process_cwd/$candidate"
            # Android may expose the same app-private path through /data/data
            # and /data/user/0. Inode equality handles those aliases as well.
            [[ "$candidate" -ef "$MANAGER_SCRIPT_PATH" ]] && return 0
        fi
    done < "/proc/$pid/cmdline" 2>/dev/null
    return 1
}

is_operation_owner() {
    local pid="$1" recorded_start current_start
    is_manager_process "$pid" || return 1
    current_start="$(process_start_ticks "$pid")"
    [[ "$current_start" =~ ^[0-9]+$ ]] || return 1
    recorded_start="$(cat "$LOCK_DIR/start_ticks" 2>/dev/null || true)"
    [[ "$recorded_start" =~ ^[0-9]+$ ]] || return 1
    [[ "$recorded_start" == "$current_start" ]]
}

release_operation_lock() {
    local owner recorded_start
    owner="$(cat "$LOCK_DIR/pid" 2>/dev/null || true)"
    recorded_start="$(cat "$LOCK_DIR/start_ticks" 2>/dev/null || true)"
    if [[ "$owner" == "$$" && -n "$OPERATION_LOCK_START" && "$recorded_start" == "$OPERATION_LOCK_START" ]]; then
        rm -rf "$LOCK_DIR"
    fi
}

operation_cleanup() {
    local code="${1:-0}"
    trap - EXIT INT TERM
    # Recovery and result recording must not prevent each other if a disk write
    # fails. Recovery helpers explicitly propagate every destructive I/O error.
    set +e
    local progress_status=""
    progress_status="$(sed -n 's/^status=//p' "$PROGRESS_FILE" 2>/dev/null | head -n 1)"
    local restore_interrupted=0
    if (( RESTORE_TRANSACTION_ACTIVE )); then
        restore_interrupted=1
        (( code == 0 )) && code=25
        if ! rollback_restore_transaction; then
            RESTORE_RECOVERY_RETAINED=1
            code=25
        fi
    fi
    if (( code != 0 )) && [[ "$CURRENT_OPERATION" == "reset-installation" ]]; then
        rollback_reset_installation || true
    fi
    if [[ "$progress_status" == "success" ]] && (( ! restore_interrupted )); then
        # A completed operation remains successful even if its result stream closes late.
        code=0
    elif [[ -f "$LOCK_DIR/cancel-requested" ]]; then
        local child
        while IFS= read -r child; do
            [[ -n "$child" ]] && kill -TERM "$child" 2>/dev/null || true
        done < <(jobs -pr 2>/dev/null || true)
        if [[ "$CURRENT_OPERATION" == "install" && -f "$INSTALL_MARKER" ]]; then
            rm -rf "$ST_HOME"
            rm -f "$INSTALL_MARKER" "$DEPENDENCY_HASH_FILE"
        elif [[ "$CURRENT_OPERATION" == "start" ]]; then
            rm -f "$DEPENDENCY_HASH_FILE"
            terminate_server_process
        fi
        write_progress 0 "작업 중단됨" "요청에 따라 작업을 안전하게 중단했습니다." cancelled
        echo "cancelled=1"
        code=130
    elif (( code != 0 )) && [[ "$progress_status" != "error" ]]; then
        write_progress 0 "작업 실패" "작업이 중단되었습니다. 로그를 확인해 주세요." error
    fi
    local final_status="success"
    local final_error_code=""
    if (( code != 0 )); then
        final_status="error"
        final_error_code="$(manager_error_code "$CURRENT_OPERATION" "$code")"
        printf 'error_code=%s\n' "$final_error_code" >&2
    fi
    attach_progress_result "$final_error_code"
    write_operation_result "$CURRENT_OPERATION" "$final_status" "$code" "$final_error_code"
    if (( OPERATION_STARTED_EPOCH > 0 )); then
        local duration=$(( $(date +%s) - OPERATION_STARTED_EPOCH ))
        record_activity "작업 종료 · 상태=$([[ $code -eq 0 ]] && echo 성공 || echo 실패) · 소요 ${duration}초"
    fi
    cleanup_current_work_dir
    cleanup_temporary_package_source
    release_operation_lock
    exit "$code"
}

signal_descendants() {
    local parent="$1"
    local parent_start="$2"
    [[ "$(process_start_ticks "$parent")" == "$parent_start" ]] || return 0
    local child child_start
    while IFS= read -r child; do
        [[ "$child" =~ ^[0-9]+$ ]] || continue
        child_start="$(process_start_ticks "$child")"
        [[ -n "$child_start" ]] || continue
        signal_descendants "$child" "$child_start"
        if [[ "$(process_start_ticks "$child")" == "$child_start" ]]; then
            kill -TERM "$child" 2>/dev/null || true
        fi
    done < <(pgrep -P "$parent" 2>/dev/null || true)
}

cancel_operation() {
    operation_active || { echo "현재 진행 중인 작업이 없습니다." >&2; exit 6; }
    local owner operation owner_start
    owner="$(cat "$LOCK_DIR/pid" 2>/dev/null || true)"
    operation="$(cat "$LOCK_DIR/operation" 2>/dev/null || true)"
    [[ "$owner" =~ ^[0-9]+$ ]] || { echo "중단할 작업 정보를 확인할 수 없습니다." >&2; exit 6; }
    [[ "$operation" == "install" || "$operation" == "start" ]] || {
        echo "현재 단계는 데이터 보호를 위해 중간에 중단할 수 없습니다." >&2
        exit 12
    }
    is_operation_owner "$owner" || { echo "작업 프로세스가 변경되어 중단하지 않았습니다." >&2; exit 6; }
    owner_start="$(cat "$LOCK_DIR/start_ticks")"
    touch "$LOCK_DIR/cancel-requested"
    signal_descendants "$owner" "$owner_start"
    if [[ "$(process_start_ticks "$owner")" == "$owner_start" ]] && is_operation_owner "$owner"; then
        kill -TERM "$owner" 2>/dev/null || true
    fi
    local attempt
    for attempt in $(seq 1 100); do
        [[ ! -d "$LOCK_DIR" ]] && break
        sleep 0.1
    done
    echo "cancel_requested=1"
}

begin_operation() {
    CURRENT_OPERATION="$1"
    OPERATION_STARTED_EPOCH="$(date +%s)"
    acquire_operation
    reset_current_log
    write_progress 0 "작업 준비" "요청한 작업을 안전하게 준비하고 있습니다."
    record_activity "작업 시작"
}

operation_active() {
    [[ -d "$LOCK_DIR" ]] || return 1
    local owner=""
    owner="$(cat "$LOCK_DIR/pid" 2>/dev/null || true)"
    if [[ ! -s "$LOCK_DIR/start_ticks" ]]; then
        # A genuine live legacy manager conservatively blocks a second writer,
        # but cannot be cancelled without a recorded start identity.
        is_manager_process "$owner"
        return
    fi
    is_operation_owner "$owner"
}

configure_temporary_main_repository() {
    local url="$1"
    TEMP_APT_SOURCES="$RUN_DIR/termux-main-$$.list"
    printf 'deb %s stable main\n' "$url" > "$TEMP_APT_SOURCES"
}

apt_with_selected_sources() {
    if [[ -n "$TEMP_APT_SOURCES" && -f "$TEMP_APT_SOURCES" ]]; then
        local -a source_options=(
            -o "Dir::Etc::sourcelist=$TEMP_APT_SOURCES"
            -o "Dir::Etc::sourceparts=-"
            -o "APT::Get::List-Cleanup=0"
        )
        apt-get "${source_options[@]}" "$@"
    else
        apt-get "$@"
    fi
}

refresh_package_indexes() {
    write_progress 5 "패키지 목록 확인" "현재 Termux 저장소를 확인하고 있습니다."
    if pkg update -y >> "$LOG_FILE" 2>&1; then
        return 0
    fi

    echo "[launcher] Selected mirror failed; retrying temporarily with the official Cloudflare repository." >> "$LOG_FILE"
    write_progress 9 "공식 미러로 재시도" "사용자 미러 설정은 유지하고 이번 작업만 공식 저장소로 재시도합니다."
    configure_temporary_main_repository "https://packages-cf.termux.dev/apt/termux-main/"
    if apt_with_selected_sources update -o Acquire::Retries=3 >> "$LOG_FILE" 2>&1; then
        return 0
    fi

    echo "[launcher] Cloudflare repository failed; retrying temporarily with the official direct repository." >> "$LOG_FILE"
    write_progress 12 "보조 공식 미러로 재시도" "사용자 설정을 바꾸지 않고 Termux 공식 직접 저장소로 다시 연결합니다."
    configure_temporary_main_repository "https://packages.termux.dev/apt/termux-main/"
    apt_with_selected_sources update -o Acquire::Retries=3 >> "$LOG_FILE" 2>&1
}

dependency_hash() {
    if [[ -f "$ST_HOME/package-lock.json" ]]; then
        sha256sum "$ST_HOME/package-lock.json" | awk '{print $1}'
    else
        sha256sum "$ST_HOME/package.json" | awk '{print $1}'
    fi
}

dependencies_ready() {
    [[ -d "$ST_HOME/node_modules" ]] || return 1
    [[ -f "$DEPENDENCY_HASH_FILE" ]] || return 1
    [[ "$(cat "$DEPENDENCY_HASH_FILE" 2>/dev/null || true)" == "$(dependency_hash)" ]] || return 1
    (cd "$ST_HOME" && node -e "import('yargs')" >/dev/null 2>&1)
}

install_dependencies() {
    if [[ ! -d "$ST_HOME" ]]; then
        write_progress 0 "설치 실패" "SillyTavern 폴더를 찾을 수 없습니다." error
        echo "SillyTavern 폴더를 찾을 수 없습니다: $ST_HOME" >&2
        return 1
    fi
    if ! command -v npm >/dev/null 2>&1; then
        write_progress 0 "설치 실패" "npm이 설치되지 않았습니다." error
        echo "npm이 설치되지 않았습니다. Termux 패키지 설치 로그를 확인해 주세요." >&2
        return 1
    fi
    write_progress 48 "Node 모듈 준비" "손상되거나 오래된 의존성을 정리하고 있습니다."
    echo "[launcher] Rebuilding Node modules..." >> "$LOG_FILE"
    record_activity "기존 Node 모듈 정리 시작"
    rm -rf "$ST_HOME/node_modules"
    record_activity "기존 Node 모듈 정리 완료"

    write_progress 55 "Node 모듈 설치" "필수 패키지를 내려받고 있습니다. 몇 분 걸릴 수 있어요."
    (
        cd "$ST_HOME"
        export NODE_ENV=production
        npm install --no-save --no-audit --no-fund --loglevel=notice --no-progress --omit=dev --ignore-scripts
    ) >> "$LOG_FILE" 2>&1 &
    local npm_pid=$!
    local elapsed=0
    local percent=55
    while kill -0 "$npm_pid" 2>/dev/null; do
        elapsed=$((elapsed + 2))
        percent=$((55 + elapsed / 15))
        (( percent > 76 )) && percent=76
        local package_count=0
        package_count="$(find "$ST_HOME/node_modules" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | wc -l || true)"
        write_progress "$percent" "Node 모듈 설치" "패키지 ${package_count}개 구성 중 · ${elapsed}초 경과"
        if (( elapsed % 10 == 0 )); then
            printf '[%s] [live] Node 패키지 %s개 구성 중 · %s초 경과\n' "$(date '+%H:%M:%S')" "$package_count" "$elapsed" >> "$LOG_FILE"
        fi
        sleep 2
    done
    if ! wait "$npm_pid"; then
        write_progress 0 "설치 실패" "Node 모듈 설치에 실패했습니다. 로그를 확인해 주세요." error
        echo "Node 모듈 설치에 실패했습니다. 로그를 확인해 주세요." >&2
        tail -n 40 "$LOG_FILE" >&2 || true
        return 1
    fi

    dependency_hash > "$DEPENDENCY_HASH_FILE"
    local installed_count
    installed_count="$(find "$ST_HOME/node_modules" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | wc -l || true)"
    record_activity "Node 모듈 설치 완료 · 상위 패키지 ${installed_count}개"
    write_progress 78 "Node 모듈 확인" "필수 패키지 설치를 확인했습니다."
}

wait_for_server() {
    local attempt percent
    for attempt in $(seq 1 150); do
        if ! is_running; then
            write_progress 0 "시작 실패" "서버 프로세스가 종료됐습니다. 로그를 확인해 주세요." error
            echo "SillyTavern 시작에 실패했습니다. 로그를 확인하세요." >&2
            tail -n 80 "$LOG_FILE" >&2 || true
            return 5
        fi

        # Any HTTP response means that the local server socket is ready.
        # Do not use --fail: authentication or whitelist responses may be 4xx.
        if curl -sS --max-time 2 -o /dev/null "http://127.0.0.1:$PORT/" 2>/dev/null; then
            write_progress 100 "서버 준비 완료" "브라우저에서 열 수 있습니다." success
            echo "started=1"
            echo "pid=$(cat "$PID_FILE")"
            return 0
        fi

        percent=$((82 + attempt * 16 / 150))
        (( percent > 98 )) && percent=98
        write_progress "$percent" "서버 초기화" "기본 콘텐츠를 준비하고 있습니다."
        sleep 2
    done

    # Keep the live process instead of reporting a false crash. The Android app
    # continues checking localhost in the background.
    write_progress 99 "초기화 계속 진행 중" "서버 프로세스는 실행 중이며 앱이 응답을 계속 확인합니다." success
    echo "started=1"
    echo "pending=1"
    echo "pid=$(cat "$PID_FILE")"
}

process_start_ticks() {
    awk '{print $22}' "/proc/$1/stat" 2>/dev/null || true
}

write_server_pid() {
    local pid="$1"
    local start_ticks
    start_ticks="$(process_start_ticks "$pid")"
    [[ "$pid" =~ ^[0-9]+$ && -n "$start_ticks" ]] || return 1
    printf 'start_ticks=%s\n' "$start_ticks" > "$PID_META_FILE.tmp.$$"
    printf '%s\n' "$pid" > "$PID_FILE.tmp.$$"
    mv -f "$PID_META_FILE.tmp.$$" "$PID_META_FILE"
    mv -f "$PID_FILE.tmp.$$" "$PID_FILE"
}

clear_server_pid() {
    rm -f "$PID_FILE" "$PID_META_FILE"
}

is_managed_server_process() {
    local pid="$1"
    [[ "$pid" =~ ^[0-9]+$ ]] || return 1
    kill -0 "$pid" 2>/dev/null || return 1

    local command_line process_cwd expected_cwd current_start recorded_start
    command_line="$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null || true)"
    [[ "$command_line" == *node* && "$command_line" == *server.js* ]] || return 1
    process_cwd="$(readlink -f "/proc/$pid/cwd" 2>/dev/null || true)"
    expected_cwd="$(realpath -m "$ST_HOME" 2>/dev/null || true)"
    [[ -n "$process_cwd" && "$process_cwd" == "$expected_cwd" ]] || return 1

    current_start="$(process_start_ticks "$pid")"
    [[ -n "$current_start" ]] || return 1
    recorded_start="$(sed -n 's/^start_ticks=//p' "$PID_META_FILE" 2>/dev/null | head -n 1)"
    if [[ -n "$recorded_start" ]]; then
        [[ "$recorded_start" == "$current_start" ]] || return 1
    else
        # Migrate a server that was started by an older launcher version.
        printf 'start_ticks=%s\n' "$current_start" > "$PID_META_FILE.tmp.$$"
        mv -f "$PID_META_FILE.tmp.$$" "$PID_META_FILE"
    fi
}

launch_server_process() {
    (
        cd "$ST_HOME"
        archive_server_log
        : > "$SERVER_LOG"
        nohup node server.js --port "$PORT" >> "$SERVER_LOG" 2>&1 &
        write_server_pid "$!"
    )
}

prepare_persistent_start() {
    if [[ ! -f "$ST_HOME/start.sh" ]]; then
        write_progress 0 "시작 실패" "SillyTavern이 설치되어 있지 않습니다." error start
        echo "SillyTavern이 설치되어 있지 않습니다." >&2
        exit 4
    fi
    begin_operation "start"
    if is_running; then
        write_progress 100 "이미 실행 중" "기존 SillyTavern 서버 작업을 유지합니다." success
        if [[ ! -s "$SESSION_FILE" ]]; then
            record_server_session "detected_running" 1
        fi
        echo "already_running=1"
        return 0
    fi
    clear_server_pid
    write_progress 8 "설치 상태 확인" "Node 모듈이 정상인지 확인하고 있습니다."
    if ! dependencies_ready; then
        install_dependencies || exit 12
    else
        write_progress 78 "Node 모듈 확인" "필수 패키지가 정상입니다."
    fi
    write_progress 82 "서버 작업 준비" "Termux에서 지속 실행할 서버 작업을 준비했습니다."
    echo "ready_to_launch=1"
}

run_persistent_server_task() {
    if is_running; then
        echo "already_running=1"
        return 0
    fi
    cd "$ST_HOME"
    archive_server_log
    : > "$SERVER_LOG"
    write_server_pid "$$"
    exec node server.js --port "$PORT" >> "$SERVER_LOG" 2>&1
}

finish_persistent_start() {
    CURRENT_OPERATION="start"
    OPERATION_STARTED_EPOCH="$(date +%s)"
    acquire_operation
    record_activity "Termux 지속 실행 작업 확인 시작"

    local attempt
    for attempt in $(seq 1 100); do
        is_running && break
        sleep 0.1
    done
    if ! is_running; then
        write_progress 0 "시작 실패" "Termux 서버 작업이 시작되지 않았습니다." error
        echo "Termux 서버 작업이 시작되지 않았습니다." >&2
        record_server_session "start_failed" 0
        return 5
    fi
    if wait_for_server; then
        record_server_session "${SESSION_ACTION:-start}" 1
    else
        record_server_session "start_failed" 0
        return 5
    fi
}

wait_for_server_strict() {
    local attempt percent
    for attempt in $(seq 1 150); do
        is_running || return 1
        if curl -sS --max-time 2 -o /dev/null "http://127.0.0.1:$PORT/" 2>/dev/null; then
            sleep 1
            is_running && return 0
        fi
        percent=$((82 + attempt * 16 / 150))
        (( percent > 98 )) && percent=98
        write_progress "$percent" "업데이트 서버 검사" "새 버전의 로컬 서버 응답을 기다리고 있습니다."
        sleep 2
    done
    return 1
}

terminate_server_process() {
    if ! is_running; then
        clear_server_pid
        return 0
    fi
    local pid
    pid="$(cat "$PID_FILE")"
    pkill -TERM -P "$pid" 2>/dev/null || true
    kill -TERM "$pid" 2>/dev/null || true
    for _ in 1 2 3 4 5; do
        kill -0 "$pid" 2>/dev/null || break
        sleep 1
    done
    pkill -KILL -P "$pid" 2>/dev/null || true
    kill -KILL "$pid" 2>/dev/null || true
    clear_server_pid
}

is_running() {
    if [[ -f "$PID_FILE" ]]; then
        local pid
        pid="$(cat "$PID_FILE" 2>/dev/null || true)"
        is_managed_server_process "$pid"
        return
    fi
    return 1
}

record_server_session() {
    local action="$1"
    local running="$2"
    local now_epoch now_text started_epoch started_at
    now_epoch="$(date +%s)"
    now_text="$(date '+%Y-%m-%d %H:%M:%S')"
    if [[ "$running" == "1" ]]; then
        started_epoch="$now_epoch"
        started_at="$now_text"
    else
        started_epoch=0
        started_at=""
    fi
    printf 'started_epoch=%s\nstarted_at=%s\nlast_action=%s\nlast_action_at=%s\n' \
        "$started_epoch" "$started_at" "$action" "$now_text" > "$SESSION_FILE"
}

session_value() {
    local key="$1"
    sed -n "s/^${key}=//p" "$SESSION_FILE" 2>/dev/null | head -n 1
}

set_termux_wake_lock() {
    local enabled="${1:-0}"
    if [[ "$enabled" == "1" ]]; then
        command -v termux-wake-lock >/dev/null 2>&1 || {
            echo "현재 Termux에서 termux-wake-lock 명령을 찾을 수 없습니다." >&2
            return 1
        }
        termux-wake-lock >> "$LOG_FILE" 2>&1
        echo "wake_lock=1"
    else
        if command -v termux-wake-unlock >/dev/null 2>&1; then
            termux-wake-unlock >> "$LOG_FILE" 2>&1 || true
        fi
        echo "wake_lock=0"
    fi
}

current_branch() {
    git -C "$ST_HOME" branch --show-current 2>/dev/null || true
}

ensure_remote_branch() {
    local branch="$1"
    local refspec="+refs/heads/$branch:refs/remotes/origin/$branch"
    if ! git -C "$ST_HOME" config --get-all remote.origin.fetch | grep -Fxq "$refspec"; then
        git -C "$ST_HOME" config --add remote.origin.fetch "$refspec"
    fi
    git -C "$ST_HOME" fetch origin "$refspec"
}

required_node_major() {
    printf '%s\n' "$1" | grep -oE '[0-9]+' | head -n 1 || true
}

update_preflight() {
    [[ -d "$ST_HOME/.git" ]] || { echo "SillyTavern Git 설치를 찾을 수 없습니다." >&2; exit 8; }
    local branch dirty_files current_commit remote_commit package_json target_version node_required commits_behind
    local node_major required_major free_bytes modules_bytes required_bytes node_compatible space_ready
    branch="$(current_branch)"
    [[ "$branch" == "release" || "$branch" == "staging" ]] || { echo "지원하지 않는 현재 브랜치입니다: $branch" >&2; exit 2; }
    dirty_files="$(git -C "$ST_HOME" status --porcelain=v1 --untracked-files=all | sed 's/^...//' || true)"

    begin_operation "update-preflight"
    write_progress 18 "수정 파일 확인" "업데이트를 방해할 수 있는 로컬 변경을 확인하고 있습니다."
    write_progress 38 "업데이트 정보 확인" "$branch 브랜치의 최신 커밋과 요구 사항을 가져오고 있습니다."
    if ! ensure_remote_branch "$branch" >> "$LOG_FILE" 2>&1; then
        echo "GitHub에서 최신 브랜치 정보를 가져오지 못했습니다." >&2
        exit 18
    fi
    current_commit="$(git -C "$ST_HOME" rev-parse HEAD)"
    remote_commit="$(git -C "$ST_HOME" rev-parse "origin/$branch")"
    commits_behind="$(git -C "$ST_HOME" rev-list --count "$current_commit..$remote_commit" 2>/dev/null || echo 0)"
    package_json="$(git -C "$ST_HOME" show "origin/$branch:package.json" 2>/dev/null || true)"
    target_version="$(printf '%s' "$package_json" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>console.log(JSON.parse(s).version||""))' 2>/dev/null || true)"
    node_required="$(printf '%s' "$package_json" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>console.log(JSON.parse(s).engines?.node||""))' 2>/dev/null || true)"
    node_major="$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null || echo 0)"
    required_major="$(required_node_major "$node_required")"
    required_major="${required_major:-0}"
    node_compatible=0
    (( node_major >= required_major && required_major > 0 )) && node_compatible=1

    write_progress 68 "저장 공간 확인" "기존 패키지를 보존한 채 새 패키지를 설치할 공간을 계산하고 있습니다."
    free_bytes="$(df -Pk "$HOME" 2>/dev/null | awk 'NR==2 {print $4 * 1024}' | cut -d. -f1)"
    free_bytes="${free_bytes:-0}"
    modules_bytes="$(du -sk "$ST_HOME/node_modules" 2>/dev/null | awk '{print $1 * 1024}' | cut -d. -f1)"
    modules_bytes="${modules_bytes:-0}"
    required_bytes=$((modules_bytes + 536870912))
    space_ready=0
    (( free_bytes >= required_bytes )) && space_ready=1

    write_progress 100 "업데이트 검사 완료" "커밋·Node.js·저장 공간·수정 파일 검사를 완료했습니다." success
    echo "branch=$branch"
    echo "current_commit=$current_commit"
    echo "remote_commit=$remote_commit"
    echo "current_version=$(version_from_file "$ST_HOME/package.json")"
    echo "target_version=$target_version"
    echo "update_available=$([[ "$current_commit" == "$remote_commit" ]] && echo 0 || echo 1)"
    echo "commits_behind=${commits_behind:-0}"
    echo "node_version=$(node --version 2>/dev/null || true)"
    echo "node_required=$node_required"
    echo "node_compatible=$node_compatible"
    echo "free_bytes=$free_bytes"
    echo "required_bytes=$required_bytes"
    echo "space_ready=$space_ready"
    echo "dirty_count=$(printf '%s\n' "$dirty_files" | sed '/^$/d' | wc -l)"
    echo "dirty_files_b64=$(printf '%s' "$dirty_files" | base64 -w 0)"
    echo "backup_storage_ready=$([[ -d "$DOWNLOAD_DIR" && -w "$DOWNLOAD_DIR" ]] && echo 1 || echo 0)"
}

version_from_file() {
    sed -n 's/^[[:space:]]*"version":[[:space:]]*"\([^"]*\)".*/\1/p' "$1" 2>/dev/null | head -n 1
}

server_connection_settings() {
    local config="$ST_HOME/config.yaml"
    if [[ ! -f "$config" || ! -d "$ST_HOME/node_modules/yaml" ]]; then
        echo "external_access=0"
        echo "whitelist_b64=$(printf '%s' $'::1\n127.0.0.1' | base64 -w 0)"
        return 0
    fi
    (cd "$ST_HOME" && node --input-type=commonjs - "$config" <<'NODE'
const fs = require('fs');
const YAML = require('yaml');
const config = YAML.parse(fs.readFileSync(process.argv[2], 'utf8')) || {};
const whitelist = Array.isArray(config.whitelist) ? config.whitelist.map(String) : ['::1', '127.0.0.1'];
console.log(`external_access=${config.listen === true ? 1 : 0}`);
console.log(`whitelist_b64=${Buffer.from(whitelist.join('\n')).toString('base64')}`);
NODE
    )
}

save_server_connection_settings() {
    local enabled="${1:-0}"
    local whitelist_b64="${2:-}"
    [[ "$enabled" == "0" || "$enabled" == "1" ]] || { echo "외부 접속 설정이 올바르지 않습니다." >&2; exit 64; }
    [[ -d "$ST_HOME/.git" && -f "$ST_HOME/package.json" ]] || { echo "SillyTavern 설치를 찾을 수 없습니다." >&2; exit 8; }
    is_running && { echo "서버 연결 설정을 바꾸려면 먼저 SillyTavern 서버를 종료해 주세요." >&2; exit 10; }
    [[ -d "$ST_HOME/node_modules/yaml" ]] || { echo "설정 편집 도구를 찾을 수 없습니다. 관리 화면에서 설치 점검·복구를 먼저 실행해 주세요." >&2; exit 12; }
    local config="$ST_HOME/config.yaml"
    if [[ ! -f "$config" ]]; then
        [[ -f "$ST_HOME/default/config.yaml" ]] || { echo "기본 config.yaml을 찾을 수 없습니다." >&2; exit 8; }
        cp "$ST_HOME/default/config.yaml" "$config"
    fi
    mkdir -p "$BACKUP_DIR/network-settings"
    cp -a "$config" "$BACKUP_DIR/network-settings/config-$(date '+%Y%m%d-%H%M%S').yaml"
    (cd "$ST_HOME" && node --input-type=commonjs - "$config" "$enabled" "$whitelist_b64" <<'NODE'
const fs = require('fs');
const YAML = require('yaml');
const [configPath, enabled, encoded] = process.argv.slice(2);
const entries = Buffer.from(encoded, 'base64').toString('utf8').split(/\r?\n/).map(v => v.trim()).filter(Boolean);
for (const local of ['::1', '127.0.0.1']) if (!entries.includes(local)) entries.unshift(local);
const document = YAML.parseDocument(fs.readFileSync(configPath, 'utf8'));
if (document.errors.length) throw new Error('config.yaml 형식을 읽을 수 없습니다.');
document.set('listen', enabled === '1');
document.set('whitelistMode', true);
document.set('whitelist', [...new Set(entries)]);
const temporary = `${configPath}.launcher-tmp-${process.pid}`;
fs.writeFileSync(temporary, String(document), { mode: 0o600 });
fs.renameSync(temporary, configPath);
NODE
    )
    echo "saved=1"
    server_connection_settings
}

doctor() {
    echo "protocol=1"
    echo "manager_version=$MANAGER_VERSION"
    echo "termux_ready=1"
    if operation_active; then
        echo "operation_active=1"
    else
        echo "operation_active=0"
    fi
    if command -v git >/dev/null 2>&1; then
        echo "git_installed=1"
        echo "git_version=$(git --version | awk '{print $3}')"
    else
        echo "git_installed=0"
        echo "git_version="
    fi
    if command -v node >/dev/null 2>&1; then
        echo "node_installed=1"
        echo "node_version=$(node --version)"
    else
        echo "node_installed=0"
        echo "node_version="
    fi
    if [[ -d "$ST_HOME/.git" && -f "$ST_HOME/server.js" ]]; then
        local modified_files
        modified_files="$(git -C "$ST_HOME" status --porcelain=v1 --untracked-files=all 2>/dev/null | sed 's/^...//' || true)"
        echo "st_installed=1"
        echo "branch=$(current_branch)"
        echo "commit=$(git -C "$ST_HOME" rev-parse --short HEAD 2>/dev/null || true)"
        echo "st_version=$(version_from_file "$ST_HOME/package.json")"
        if [[ -n "$modified_files" ]]; then
            echo "working_tree_clean=0"
        else
            echo "working_tree_clean=1"
        fi
        echo "modified_files_b64=$(printf '%s' "$modified_files" | base64 -w 0 2>/dev/null || true)"
        server_connection_settings
    else
        echo "st_installed=0"
        echo "branch="
        echo "commit="
        echo "st_version="
        echo "working_tree_clean=1"
        echo "modified_files_b64="
        echo "external_access=0"
        echo "whitelist_b64=$(printf '%s' $'::1\n127.0.0.1' | base64 -w 0)"
    fi
    if is_running; then
        if [[ ! -s "$SESSION_FILE" ]]; then
            record_server_session "detected_running" 1
        fi
        echo "running=1"
    else
        clear_server_pid
        local previous_started_epoch
        previous_started_epoch="$(session_value started_epoch || true)"
        if [[ "${previous_started_epoch:-0}" =~ ^[0-9]+$ ]] && (( previous_started_epoch > 0 )); then
            record_server_session "unexpected_stop" 0
        fi
        echo "running=0"
    fi
    echo "session_started_epoch=$(session_value started_epoch || echo 0)"
    echo "session_started_at=$(session_value started_at || true)"
    echo "last_server_action=$(session_value last_action || true)"
    echo "last_server_action_at=$(session_value last_action_at || true)"
    if command -v ss >/dev/null 2>&1 && ss -ltn 2>/dev/null | awk '{print $4}' | grep -Eq "(^|:)$PORT$"; then
        echo "port_listening=1"
    else
        echo "port_listening=0"
    fi
    echo "port=$PORT"
    echo "log_file=$LOG_FILE"
}

install_st() {
    local branch="${1:-release}"
    if [[ "$branch" != "release" && "$branch" != "staging" ]]; then
        write_progress 0 "설치 실패" "지원하지 않는 브랜치입니다: $branch" error install
        echo "지원하지 않는 브랜치입니다: $branch" >&2
        exit 2
    fi
    if [[ -f "$INSTALL_MARKER" ]] && ! operation_active; then
        rm -rf "$ST_HOME"
        rm -f "$INSTALL_MARKER" "$DEPENDENCY_HASH_FILE"
    fi
    if [[ -e "$ST_HOME" ]]; then
        write_progress 0 "설치 중단" "기존 SillyTavern 폴더가 있어 덮어쓰지 않았습니다." error install
        echo "설치 경로가 이미 존재합니다: $ST_HOME" >&2
        exit 3
    fi
    begin_operation "install"
    if ! refresh_package_indexes; then
        write_progress 0 "패키지 확인 실패" "Termux 패키지 목록을 갱신하지 못했습니다. 로그를 확인해 주세요." error
        echo "Termux 공식 저장소에서도 패키지 목록 갱신에 실패했습니다. 네트워크를 확인하거나 termux-change-repo를 실행해 주세요." >&2
        exit 14
    fi
    write_progress 20 "필수 도구 설치" "Git과 Node.js를 준비하고 있습니다."
    if ! apt_with_selected_sources install -y git nodejs-lts nano >> "$LOG_FILE" 2>&1; then
        write_progress 0 "도구 설치 실패" "Git 또는 Node.js를 설치하지 못했습니다. 로그를 확인해 주세요." error
        echo "Git 또는 Node.js 설치에 실패했습니다." >&2
        exit 15
    fi
    if [[ "$(getprop ro.product.cpu.abi 2>/dev/null || true)" == *"armeabi-v7a"* ]]; then
        if ! apt_with_selected_sources install -y esbuild >> "$LOG_FILE" 2>&1; then
            write_progress 0 "도구 설치 실패" "esbuild를 설치하지 못했습니다." error
            echo "esbuild 설치에 실패했습니다." >&2
            exit 16
        fi
    fi
    write_progress 35 "SillyTavern 다운로드" "$branch 브랜치를 내려받고 있습니다."
    : > "$INSTALL_MARKER"
    if ! git clone --progress --branch "$branch" --single-branch https://github.com/SillyTavern/SillyTavern.git "$ST_HOME" >> "$LOG_FILE" 2>&1; then
        write_progress 0 "다운로드 실패" "SillyTavern을 내려받지 못했습니다. 부분 설치를 정리했습니다." error
        rm -rf "$ST_HOME"
        rm -f "$INSTALL_MARKER"
        echo "SillyTavern 다운로드에 실패했습니다." >&2
        exit 17
    fi
    if ! install_dependencies; then
        rm -rf "$ST_HOME"
        rm -f "$DEPENDENCY_HASH_FILE" "$INSTALL_MARKER"
        exit 12
    fi
    rm -f "$INSTALL_MARKER"
    write_progress 100 "설치 완료" "SillyTavern을 시작할 준비가 됐습니다." success
    echo "installed=1"
    echo "branch=$branch"
}

start_st() {
    if [[ ! -f "$ST_HOME/start.sh" ]]; then
        write_progress 0 "시작 실패" "SillyTavern이 설치되어 있지 않습니다." error start
        echo "SillyTavern이 설치되어 있지 않습니다." >&2
        exit 4
    fi
    begin_operation "start"
    if is_running; then
        write_progress 84 "실행 상태 확인" "이미 실행 중인 서버의 응답을 기다리고 있습니다."
        if wait_for_server; then
            if [[ ! -s "$SESSION_FILE" ]]; then
                record_server_session "start" 1
            fi
            exit 0
        fi
        record_server_session "start_failed" 0
        exit 5
    fi
    write_progress 8 "설치 상태 확인" "Node 모듈이 정상인지 확인하고 있습니다."
    if ! dependencies_ready; then
        install_dependencies || exit 12
    else
        write_progress 78 "Node 모듈 확인" "필수 패키지가 정상입니다."
    fi

    write_progress 82 "서버 실행" "SillyTavern 프로세스를 시작하고 있습니다."
    launch_server_process
    if wait_for_server; then
        record_server_session "${SESSION_ACTION:-start}" 1
    else
        record_server_session "start_failed" 0
        return 5
    fi
}

stop_st() {
    if ! is_running; then
        clear_server_pid
        echo "already_stopped=1"
        return 0
    fi
    write_progress 20 "서버 종료" "SillyTavern 프로세스를 종료하고 있습니다."
    terminate_server_process
    record_server_session "${SESSION_ACTION:-stop}" 0
    write_progress 100 "종료 완료" "SillyTavern 서버를 종료했습니다." success
    echo "stopped=1"
}

backup_st() {
    backup_selected "full" "0" ""
}

ensure_archive_tools() {
    if command -v zip >/dev/null 2>&1 && command -v unzip >/dev/null 2>&1; then
        return 0
    fi
    write_progress 8 "압축 도구 준비" "ZIP 백업 도구를 설치하고 있습니다."
    apt_with_selected_sources install -y zip unzip >> "$LOG_FILE" 2>&1
}

archive_expanded_size() {
    unzip -l "$1" 2>/dev/null |
        awk '/^---------/ {separators++; next} separators == 1 && $1 ~ /^[0-9]+$/ {sum += $1} END {printf "%.0f", sum + 0}'
}

unsigned_decimal() {
    local value="$1"
    # Bash arithmetic recursively evaluates strings, including array subscripts.
    # External metadata must pass this whitelist before entering any arithmetic.
    [[ "$value" =~ ^[0-9]+$ && ${#value} -le 18 ]] || return 1
    printf '%s\n' "$((10#$value))"
}

backup_metadata_valid() {
    local manifest="$1" field value
    [[ "$(printf '%s\n' "$manifest" | sed -n 's/^format=//p' | head -n 1)" == "st-launcher-backup-v1" ]] || return 1
    value="$(printf '%s\n' "$manifest" | sed -n 's/^items=//p' | head -n 1)"
    [[ "$value" =~ ^(user_data|extensions|config|full|custom)(,(user_data|extensions|config|full|custom))*$ ]] || return 1
    value="$(printf '%s\n' "$manifest" | sed -n 's/^secrets=//p' | head -n 1)"
    [[ "$value" == "0" || "$value" == "1" ]] || return 1
    for field in expanded_bytes entry_count; do
        value="$(printf '%s\n' "$manifest" | sed -n "s/^${field}=//p" | head -n 1)"
        # These fields were absent from early v1 backups.
        unsigned_decimal "${value:-0}" >/dev/null || return 1
    done
}

ensure_restore_space() {
    local archive="$1"
    local expanded_size free_bytes required_bytes
    if ! expanded_size="$(unsigned_decimal "$(archive_expanded_size "$archive")")" ||
        ! free_bytes="$(unsigned_decimal "$(df -Pk "$BACKUP_DIR" 2>/dev/null | awk 'NR==2 {print $4 * 1024}' | cut -d. -f1)")"; then
        echo "복원에 필요한 저장 공간을 계산하지 못했습니다." >&2
        return 29
    fi
    required_bytes=$((expanded_size * 2 + 268435456))
    if (( free_bytes < required_bytes )); then
        echo "백업을 임시 해제하고 현재 상태를 보호할 저장 공간이 부족합니다. 필요: ${required_bytes}바이트, 여유: ${free_bytes}바이트" >&2
        return 29
    fi
    echo "restore_expanded_bytes=$expanded_size"
    echo "restore_required_bytes=$required_bytes"
    echo "restore_free_bytes=$free_bytes"
}

backup_selected() {
    local kinds="${1:-}"
    local include_secrets="${2:-0}"
    local custom_folders="${3:-}"
    if [[ ! -d "$ST_HOME" ]]; then
        echo "백업할 SillyTavern 설치가 없습니다." >&2
        exit 6
    fi
    if [[ ! -d "$DOWNLOAD_DIR" ]]; then
        echo "휴대폰 Download 폴더가 연결되지 않았습니다. Termux에서 termux-setup-storage를 한 번 실행해 주세요." >&2
        exit 7
    fi
    if [[ "$include_secrets" != "0" && "$include_secrets" != "1" ]]; then
        echo "비밀 설정 포함 값이 올바르지 않습니다." >&2
        exit 64
    fi
    if [[ ! "$kinds" =~ ^(user_data|extensions|config|full|custom)(,(user_data|extensions|config|full|custom))*$ ]]; then
        echo "지원하지 않는 백업 종류입니다." >&2
        exit 64
    fi

    begin_operation "backup"
    ensure_archive_tools
    write_progress 12 "백업 항목 확인" "선택한 파일과 폴더를 확인하고 있습니다."

    local work="$BACKUP_DIR/backup-work-$$"
    track_current_work_dir "$work"
    local output="$DOWNLOAD_DIR/SillyTavern-Launcher-$(date +%Y%m%d-%H%M%S).zip"
    local manifest="$work/.st-launcher-manifest"
    local created_at
    created_at="$(date '+%Y-%m-%d %H:%M:%S')"
    mkdir -p "$work"
    while [[ -e "$output" ]]; do
        sleep 1
        output="$DOWNLOAD_DIR/SillyTavern-Launcher-$(date +%Y%m%d-%H%M%S).zip"
        created_at="$(date '+%Y-%m-%d %H:%M:%S')"
    done

    local -a paths=()
    local -a custom_paths=()
    local item
    IFS=',' read -ra selected_kinds <<< "$kinds"
    if [[ ",$kinds," == *,full,* ]]; then
        paths=(".")
        kinds="full"
        custom_folders=""
    else
        for item in "${selected_kinds[@]}"; do
            case "$item" in
                user_data) [[ -d "$ST_HOME/data" ]] && paths+=("data") ;;
                extensions)
                    [[ -d "$ST_HOME/public/scripts/extensions/third-party" ]] && paths+=("public/scripts/extensions/third-party")
                    while IFS= read -r extension_path; do
                        [[ -n "$extension_path" ]] && paths+=("${extension_path#"$ST_HOME/"}")
                    done < <(find "$ST_HOME/data" -mindepth 2 -maxdepth 2 -type d -name extensions 2>/dev/null || true)
                    ;;
                config) [[ -f "$ST_HOME/config.yaml" ]] && paths+=("config.yaml") ;;
                custom)
                    IFS=',' read -ra requested_custom <<< "$custom_folders"
                    for custom in "${requested_custom[@]}"; do
                        [[ "$custom" =~ ^[A-Za-z0-9_[:space:]-]+$ ]] || { echo "허용되지 않는 사용자 폴더 이름입니다: $custom" >&2; exit 64; }
                        local relative="data/default-user/$custom"
                        if [[ -e "$ST_HOME/$relative" ]]; then
                            paths+=("$relative")
                            custom_paths+=("$custom")
                        fi
                    done
                    ;;
            esac
        done
    fi
    if (( ${#paths[@]} == 0 )); then
        echo "선택한 백업 항목에서 저장할 파일을 찾지 못했습니다." >&2
        rm -rf "$work"
        exit 8
    fi

    printf 'format=st-launcher-backup-v1\ncreated_at=%s\nitems=%s\ncustom=%s\nsecrets=%s\nst_version=%s\nbranch=%s\nlauncher_version=%s\nintegrity=zip-crc32\n' \
        "$created_at" "$kinds" "$(IFS=','; echo "${custom_paths[*]}")" "$include_secrets" \
        "$(version_from_file "$ST_HOME/package.json")" "$(current_branch)" "$LAUNCHER_VERSION" > "$manifest"

    write_progress 28 "ZIP 생성" "선택한 항목을 휴대폰 Download 폴더에 압축하고 있습니다."
    (cd "$work" && zip -q "$output" .st-launcher-manifest) || {
        rm -rf "$work"; rm -f "$output"
        echo "백업 manifest 압축에 실패했습니다." >&2
        exit 18
    }
    local -a excludes=("node_modules/*" "*/node_modules/*" ".st-launcher-manifest")
    if [[ "$include_secrets" != "1" ]]; then
        excludes+=("data/*/secrets.json")
    fi
    (cd "$ST_HOME" && zip -rq "$output" "${paths[@]}" -x "${excludes[@]}") >> "$LOG_FILE" 2>&1 || {
        rm -rf "$work"; rm -f "$output"
        echo "선택한 파일을 ZIP으로 만드는 데 실패했습니다." >&2
        exit 18
    }
    local expanded_bytes entry_count
    expanded_bytes="$(archive_expanded_size "$output")"
    entry_count="$(unzip -Z1 "$output" 2>/dev/null | grep -Fvx '.st-launcher-manifest' | wc -l | tr -d ' ')"
    printf 'expanded_bytes=%s\nentry_count=%s\n' "${expanded_bytes:-0}" "${entry_count:-0}" >> "$manifest"
    # Replace this entry explicitly: -u can skip a just-edited manifest when
    # both writes fall within the ZIP timestamp's two-second resolution.
    (cd "$work" && zip -q "$output" .st-launcher-manifest) >> "$LOG_FILE" 2>&1 || {
        rm -rf "$work"; rm -f "$output"
        echo "백업 메타데이터를 기록하지 못했습니다." >&2
        exit 18
    }

    write_progress 88 "ZIP 검사" "ZIP CRC와 확장 메타데이터를 확인하고 있습니다."
    unzip -tq "$output" >> "$LOG_FILE" 2>&1 || {
        rm -rf "$work"; rm -f "$output"
        echo "생성된 ZIP 검증에 실패하여 불완전한 파일을 삭제했습니다." >&2
        exit 19
    }
    local size
    size="$(stat -c %s "$output" 2>/dev/null || wc -c < "$output")"
    rm -rf "$work"
    write_progress 100 "백업 완료" "검증된 ZIP을 휴대폰 Download 폴더에 저장했습니다." success
    echo "backup=$output"
    echo "size=$size"
}

list_backups() {
    compgen -G "$DOWNLOAD_DIR/SillyTavern-Launcher-*.zip" >/dev/null || return 0
    ensure_archive_tools
    local file manifest format created items custom secrets version branch launcher_version expanded_bytes entry_count integrity
    local required_restore_bytes available_restore_bytes
    available_restore_bytes="$(df -Pk "$BACKUP_DIR" 2>/dev/null | awk 'NR==2 {print $4 * 1024}' | cut -d. -f1)"
    available_restore_bytes="$(unsigned_decimal "${available_restore_bytes:-0}" || echo 0)"
    shopt -s nullglob
    for file in "$DOWNLOAD_DIR"/SillyTavern-Launcher-*.zip; do
        # Creation, import, and restore perform full integrity checks. Reading the embedded
        # manifest is enough for the list screen and avoids rescanning multi-gigabyte ZIPs.
        manifest="$(unzip -p "$file" .st-launcher-manifest 2>/dev/null || true)"
        backup_metadata_valid "$manifest" || continue
        format="$(printf '%s\n' "$manifest" | sed -n 's/^format=//p' | head -n 1)"
        [[ "$format" == "st-launcher-backup-v1" ]] || continue
        created="$(printf '%s\n' "$manifest" | sed -n 's/^created_at=//p' | head -n 1)"
        items="$(printf '%s\n' "$manifest" | sed -n 's/^items=//p' | head -n 1)"
        custom="$(printf '%s\n' "$manifest" | sed -n 's/^custom=//p' | head -n 1)"
        secrets="$(printf '%s\n' "$manifest" | sed -n 's/^secrets=//p' | head -n 1)"
        version="$(printf '%s\n' "$manifest" | sed -n 's/^st_version=//p' | head -n 1)"
        branch="$(printf '%s\n' "$manifest" | sed -n 's/^branch=//p' | head -n 1)"
        launcher_version="$(printf '%s\n' "$manifest" | sed -n 's/^launcher_version=//p' | head -n 1)"
        expanded_bytes="$(printf '%s\n' "$manifest" | sed -n 's/^expanded_bytes=//p' | head -n 1)"
        entry_count="$(printf '%s\n' "$manifest" | sed -n 's/^entry_count=//p' | head -n 1)"
        integrity="$(printf '%s\n' "$manifest" | sed -n 's/^integrity=//p' | head -n 1)"
        expanded_bytes="$(unsigned_decimal "${expanded_bytes:-0}")" || continue
        entry_count="$(unsigned_decimal "${entry_count:-0}")" || continue
        required_restore_bytes=$((expanded_bytes * 2 + 268435456))
        printf 'backup\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
            "$(printf '%s' "$(basename "$file")" | base64 -w 0)" "$(stat -c %s "$file" 2>/dev/null || wc -c < "$file")" \
            "$(printf '%s' "$created" | base64 -w 0)" "$(printf '%s' "$items" | base64 -w 0)" \
            "$(printf '%s' "$custom" | base64 -w 0)" "${secrets:-0}" "$(printf '%s' "$version" | base64 -w 0)" \
            "$(printf '%s' "$branch" | base64 -w 0)" "$(printf '%s' "$launcher_version" | base64 -w 0)" \
            "$expanded_bytes" "${entry_count:-0}" "$(printf '%s' "$integrity" | base64 -w 0)" \
            "$required_restore_bytes" "$available_restore_bytes"
    done
}

import_backup() {
    local file_name="${1:-}"
    [[ "$file_name" =~ ^SillyTavern-Import-[A-Za-z0-9-]+\.zip$ ]] || {
        echo "가져올 백업 파일 이름이 올바르지 않습니다." >&2
        exit 64
    }
    local archive="$DOWNLOAD_DIR/$file_name"
    [[ -f "$archive" ]] || { echo "선택한 ZIP 파일을 Download 폴더에서 찾을 수 없습니다." >&2; exit 6; }
    begin_operation "import-backup"
    ensure_archive_tools
    write_progress 8 "가져온 ZIP 검사" "손상 여부와 내부 경로를 확인하고 있습니다."
    if ! unzip -tq "$archive" >> "$LOG_FILE" 2>&1; then
        rm -f "$archive"
        echo "선택한 ZIP 파일이 손상되어 가져올 수 없습니다." >&2
        exit 20
    fi
    if unzip -Z1 "$archive" | grep -Eq '(^/|(^|/)\.\.(/|$)|\\)'; then
        rm -f "$archive"
        echo "ZIP 안에 안전하지 않은 경로가 있습니다." >&2
        exit 21
    fi
    if zipinfo -l "$archive" 2>/dev/null | awk '$1 ~ /^l/ {found=1} END {exit !found}'; then
        rm -f "$archive"
        echo "심볼릭 링크가 포함된 ZIP은 가져올 수 없습니다." >&2
        exit 22
    fi

    local expanded_size free_bytes
    if ! expanded_size="$(unsigned_decimal "$(archive_expanded_size "$archive")")" ||
        ! free_bytes="$(unsigned_decimal "$(df -Pk "$BACKUP_DIR" 2>/dev/null | awk 'NR==2 {print $4 * 1024}' | cut -d. -f1)")" ||
        (( expanded_size > free_bytes / 2 )); then
        rm -f "$archive"
        echo "ZIP을 안전하게 검사하고 변환할 저장 공간이 부족합니다." >&2
        exit 29
    fi

    local output="$DOWNLOAD_DIR/SillyTavern-Launcher-$(date +%Y%m%d-%H%M%S).zip"
    while [[ -e "$output" ]]; do
        sleep 1
        output="$DOWNLOAD_DIR/SillyTavern-Launcher-$(date +%Y%m%d-%H%M%S).zip"
    done
    local existing_manifest
    existing_manifest="$(unzip -p "$archive" .st-launcher-manifest 2>/dev/null || true)"
    if [[ "$(printf '%s\n' "$existing_manifest" | sed -n 's/^format=//p' | head -n 1)" == "st-launcher-backup-v1" ]]; then
        if ! backup_metadata_valid "$existing_manifest"; then
            rm -f "$archive"
            echo "백업 manifest의 항목 또는 숫자 정보가 올바르지 않습니다." >&2
            exit 23
        fi
        mv "$archive" "$output"
        write_progress 100 "백업 가져오기 완료" "런처 백업을 Download 폴더에 등록했습니다." success
        echo "imported=$(basename "$output")"
        return 0
    fi

    write_progress 26 "백업 구조 분석" "일반 SillyTavern ZIP에서 복원 가능한 항목을 찾고 있습니다."
    local work="$BACKUP_DIR/import-work-$$"
    track_current_work_dir "$work"
    local extracted="$work/extracted"
    local normalized="$work/normalized"
    mkdir -p "$extracted" "$normalized"
    if ! unzip -q "$archive" -x 'node_modules/*' '*/node_modules/*' -d "$extracted"; then
        rm -rf "$work"; rm -f "$archive"
        echo "ZIP을 임시 검사 폴더에 풀지 못했습니다." >&2
        exit 20
    fi

    local root="$extracted"
    local -a top_entries=()
    while IFS= read -r -d '' entry; do top_entries+=("$entry"); done < <(find "$extracted" -mindepth 1 -maxdepth 1 -print0)
    if (( ${#top_entries[@]} == 1 )) && [[ -d "${top_entries[0]}" ]]; then
        root="${top_entries[0]}"
    fi

    local kinds=""
    if valid_full_installation "$root"; then
        cp -a "$root"/. "$normalized"/
        kinds="full"
    else
        if [[ -f "$root/package.json" && -f "$root/server.js" && -d "$root/data" ]]; then
            record_activity "Git 설치 정보가 없는 ZIP에서 사용자 데이터·설정·확장 프로그램만 가져옵니다."
        fi
        if [[ -d "$root/data" ]]; then
            cp -a "$root/data" "$normalized/data"
            kinds="user_data"
        elif [[ -d "$root/default-user" ]]; then
            mkdir -p "$normalized/data"
            cp -a "$root/default-user" "$normalized/data/default-user"
            kinds="user_data"
        elif [[ -d "$root/characters" || -d "$root/chats" || -f "$root/settings.json" ]]; then
            mkdir -p "$normalized/data/default-user"
            cp -a "$root"/. "$normalized/data/default-user"/
            kinds="user_data"
        fi
        if [[ -f "$root/config.yaml" ]]; then
            cp -a "$root/config.yaml" "$normalized/config.yaml"
            kinds="${kinds:+$kinds,}config"
        fi
        if [[ -d "$root/public/scripts/extensions/third-party" ]]; then
            mkdir -p "$normalized/public/scripts/extensions"
            cp -a "$root/public/scripts/extensions/third-party" "$normalized/public/scripts/extensions/third-party"
            kinds="${kinds:+$kinds,}extensions"
        elif [[ -d "$root/third-party" ]]; then
            mkdir -p "$normalized/public/scripts/extensions"
            cp -a "$root/third-party" "$normalized/public/scripts/extensions/third-party"
            kinds="${kinds:+$kinds,}extensions"
        fi
    fi

    if [[ -z "$kinds" ]]; then
        rm -rf "$work"; rm -f "$archive"
        echo "SillyTavern 사용자 데이터, config.yaml, 확장 프로그램 또는 전체 설치 구조를 찾지 못했습니다. 다른 런처의 독자 형식은 해당 형식의 설명이 필요합니다." >&2
        exit 23
    fi

    local include_secrets=0
    if find "$normalized/data" -mindepth 2 -maxdepth 2 -type f -name secrets.json -print -quit 2>/dev/null | grep -q .; then
        include_secrets=1
    fi
    local manifest="$normalized/.st-launcher-manifest"
    printf 'format=st-launcher-backup-v1\ncreated_at=%s\nitems=%s\ncustom=\nsecrets=%s\nst_version=%s\nbranch=imported\nlauncher_version=%s\nintegrity=zip-crc32\n' \
        "$(date '+%Y-%m-%d %H:%M:%S')" "$kinds" "$include_secrets" \
        "$(version_from_file "$normalized/package.json")" "$LAUNCHER_VERSION" > "$manifest"

    write_progress 68 "안전한 백업으로 변환" "검증된 구조를 런처 복원 형식으로 변환하고 있습니다."
    if ! (cd "$normalized" && zip -rq "$output" .) >> "$LOG_FILE" 2>&1; then
        rm -rf "$work"; rm -f "$archive" "$output"
        echo "가져온 ZIP을 안전한 백업 형식으로 변환하지 못했습니다." >&2
        exit 18
    fi
    local imported_expanded imported_entries
    imported_expanded="$(archive_expanded_size "$output")"
    imported_entries="$(unzip -Z1 "$output" 2>/dev/null | grep -Fvx '.st-launcher-manifest' | wc -l | tr -d ' ')"
    printf 'expanded_bytes=%s\nentry_count=%s\n' "${imported_expanded:-0}" "${imported_entries:-0}" >> "$manifest"
    # Metadata appended in the same ZIP timestamp interval still must replace
    # the embedded manifest, so do not use timestamp-based update-only mode.
    (cd "$normalized" && zip -q "$output" .st-launcher-manifest) >> "$LOG_FILE" 2>&1 || {
        rm -rf "$work"; rm -f "$archive" "$output"
        echo "변환 백업 메타데이터를 기록하지 못했습니다." >&2
        exit 18
    }
    if ! unzip -tq "$output" >> "$LOG_FILE" 2>&1; then
        rm -rf "$work"; rm -f "$archive" "$output"
        echo "변환된 백업 검증에 실패했습니다." >&2
        exit 19
    fi
    rm -rf "$work"
    rm -f "$archive"
    write_progress 100 "백업 가져오기 완료" "일반 ZIP을 검증된 런처 백업으로 변환했습니다." success
    echo "imported=$(basename "$output")"
    echo "items=$kinds"
}

list_user_folders() {
    [[ -d "$ST_HOME/data/default-user" ]] || return 0
    while IFS= read -r -d '' folder; do
        printf 'folder\t%s\n' "$(printf '%s' "$(basename "$folder")" | base64 -w 0)"
    done < <(find "$ST_HOME/data/default-user" -mindepth 1 -maxdepth 1 -type d -print0 2>/dev/null)
}

list_st_files() {
    [[ -d "$ST_HOME" ]] || { echo "SillyTavern 폴더를 찾을 수 없습니다." >&2; exit 8; }
    local encoded="${1:-}" relative target entry item_relative item_name kind size modified sensitive
    relative="$(printf '%s' "$encoded" | base64 -d 2>/dev/null || true)"
    [[ "$relative" != /* && "$relative" != *".."* ]] || { echo "허용되지 않는 폴더 경로입니다." >&2; exit 64; }
    target="$(realpath -m "$ST_HOME/${relative}")"
    [[ "$target" == "$ST_HOME" || "$target" == "$ST_HOME/"* ]] || { echo "SillyTavern 바깥의 폴더는 열 수 없습니다." >&2; exit 64; }
    [[ -d "$target" ]] || { echo "선택한 폴더를 찾을 수 없습니다." >&2; exit 6; }
    while IFS= read -r -d '' entry; do
        [[ -L "$entry" ]] && continue
        item_relative="${entry#"$ST_HOME"/}"
        item_name="$(basename "$entry")"
        if [[ -d "$entry" ]]; then kind="D"; size=0; else kind="F"; size="$(stat -c '%s' "$entry" 2>/dev/null || echo 0)"; fi
        modified="$(date -r "$entry" '+%Y-%m-%d %H:%M' 2>/dev/null || true)"
        sensitive=0
        case "$item_name" in
            secrets.json|config.yaml|.env|*.pem|*.key) sensitive=1 ;;
        esac
        printf 'entry\t%s\t%s\t%s\t%s\t%s\t%s\n' \
            "$kind" \
            "$(printf '%s' "$item_relative" | base64 -w 0)" \
            "$(printf '%s' "$item_name" | base64 -w 0)" \
            "$size" "$modified" "$sensitive"
    done < <(find "$target" -mindepth 1 -maxdepth 1 \( -type d -o -type f \) -print0 2>/dev/null | sort -z -f)
}

backup_storage_status() {
    if [[ -d "$DOWNLOAD_DIR" && -w "$DOWNLOAD_DIR" ]]; then
        echo "backup_storage_ready=1"
        echo "backup_storage_path_b64=$(printf '%s' "$DOWNLOAD_DIR" | base64 -w 0)"
    else
        echo "backup_storage_ready=0"
        echo "backup_storage_path_b64="
    fi
}

delete_backup() {
    local file_name="${1:-}"
    [[ "$file_name" =~ ^SillyTavern-Launcher-[0-9]{8}-[0-9]{6}\.zip$ ]] || {
        echo "허용되지 않는 백업 파일 이름입니다." >&2
        exit 64
    }
    local archive="$DOWNLOAD_DIR/$file_name"
    [[ -f "$archive" ]] || { echo "삭제할 백업 파일을 찾을 수 없습니다." >&2; exit 6; }
    local manifest
    manifest="$(unzip -p "$archive" .st-launcher-manifest 2>/dev/null || true)"
    [[ "$(printf '%s\n' "$manifest" | sed -n 's/^format=//p' | head -n 1)" == "st-launcher-backup-v1" ]] || {
        echo "런처에서 만든 백업만 앱에서 삭제할 수 있습니다." >&2
        exit 23
    }
    begin_operation "delete-backup"
    write_progress 40 "백업 삭제" "선택한 ZIP 백업을 Download 폴더에서 삭제하고 있습니다."
    rm -f "$archive"
    [[ ! -e "$archive" ]] || { echo "백업 파일을 삭제하지 못했습니다." >&2; exit 28; }
    write_progress 100 "삭제 완료" "선택한 백업 파일을 삭제했습니다." success
    echo "deleted=$file_name"
}

delete_backups() {
    local encoded="${1:-}"
    local decoded
    decoded="$(printf '%s' "$encoded" | base64 -d 2>/dev/null || true)"
    [[ -n "$decoded" ]] || { echo "삭제할 백업을 선택해 주세요." >&2; exit 64; }
    local -a archives=()
    local file_name archive manifest
    while IFS= read -r file_name; do
        [[ -n "$file_name" ]] || continue
        [[ "$file_name" =~ ^SillyTavern-Launcher-[0-9]{8}-[0-9]{6}\.zip$ ]] || {
            echo "허용되지 않는 백업 파일 이름입니다." >&2; exit 64;
        }
        archive="$DOWNLOAD_DIR/$file_name"
        [[ -f "$archive" ]] || { echo "삭제할 백업 파일을 찾을 수 없습니다: $file_name" >&2; exit 6; }
        manifest="$(unzip -p "$archive" .st-launcher-manifest 2>/dev/null || true)"
        [[ "$(printf '%s\n' "$manifest" | sed -n 's/^format=//p' | head -n 1)" == "st-launcher-backup-v1" ]] || {
            echo "런처에서 만든 백업만 앱에서 삭제할 수 있습니다." >&2; exit 23;
        }
        archives+=("$archive")
    done <<< "$decoded"
    (( ${#archives[@]} > 0 )) || { echo "삭제할 백업을 선택해 주세요." >&2; exit 64; }
    begin_operation "delete-backups"
    write_progress 40 "백업 일괄 삭제" "선택한 ${#archives[@]}개 ZIP 백업을 확인했습니다."
    local removed=0
    for archive in "${archives[@]}"; do
        rm -f "$archive"
        [[ ! -e "$archive" ]] || { echo "백업 파일을 삭제하지 못했습니다: $(basename "$archive")" >&2; exit 28; }
        removed=$((removed + 1))
        write_progress $((40 + removed * 55 / ${#archives[@]})) "백업 일괄 삭제" "$removed/${#archives[@]}개를 삭제했습니다."
    done
    write_progress 100 "삭제 완료" "선택한 백업 ${#archives[@]}개를 삭제했습니다." success
    echo "deleted_count=${#archives[@]}"
}

manifest_value() {
    local manifest="$1"
    local key="$2"
    sed -n "s/^${key}=//p" "$manifest" | head -n 1
}

valid_full_installation() {
    local root="$1" git_root
    [[ -d "$root/.git" && ! -L "$root/.git" && -f "$root/package.json" &&
       -f "$root/server.js" && -f "$root/start.sh" && -d "$root/data" ]] || return 1
    git_root="$(git -C "$root" rev-parse --show-toplevel 2>/dev/null)" || return 1
    [[ "$git_root" -ef "$root" ]] || return 1
    git -C "$root" cat-file -e 'HEAD^{commit}' 2>/dev/null
}

array_contains() {
    local expected="$1" value
    shift
    for value in "$@"; do [[ "$value" == "$expected" ]] && return 0; done
    return 1
}

prepare_restore_transaction() {
    RESTORE_TRANSACTION_KIND="$1"
    RESTORE_ROLLBACK_DIR="$2"
    RESTORE_TRANSACTION_ACTIVE=0
    RESTORE_HAD_INSTALLATION=0
    RESTORE_DEPENDENCY_SAVED=0
    RESTORE_RECOVERY_RETAINED=0
    RESTORE_FULL_ORIGINAL_MOVED=0
    RESTORE_FULL_DATA_RECOVERED=0
    RESTORE_AFFECTED_PATHS=()
    RESTORE_ORIGINAL_PATHS=()
    RESTORE_RECOVERED_PATHS=()
}

normalize_restore_paths() {
    local path other covered
    RESTORE_AFFECTED_PATHS=()
    for path in "$@"; do
        covered=0
        for other in "$@"; do
            [[ "$path" != "$other" && "$path" == "$other/"* ]] && covered=1
        done
        if (( ! covered )) && ! array_contains "$path" "${RESTORE_AFFECTED_PATHS[@]}"; then
            RESTORE_AFFECTED_PATHS+=("$path")
        fi
    done
}

restore_destination_safe() {
    local relative="$1" resolved expected
    [[ -n "$relative" && "$relative" != /* && "$relative" != *'..'* && "$relative" != *'\'* ]] || return 1
    resolved="$(realpath -m "$ST_HOME/$relative")" || return 1
    expected="$(realpath -m "$ST_HOME")" || return 1
    [[ "$resolved" == "$expected/"* ]]
}

write_restore_journal() {
    printf 'format=st-launcher-restore-recovery-v1\nmode=%s\ninstallation_b64=%s\nhad_installation=%s\ndependency_saved=%s\naffected_b64=%s\noriginal_paths_b64=%s\n' \
        "$RESTORE_TRANSACTION_KIND" "$(printf '%s' "$ST_HOME" | base64 -w 0)" \
        "$RESTORE_HAD_INSTALLATION" "$RESTORE_DEPENDENCY_SAVED" \
        "$(printf '%s\n' "${RESTORE_AFFECTED_PATHS[@]}" | base64 -w 0)" \
        "$(printf '%s\n' "${RESTORE_ORIGINAL_PATHS[@]}" | base64 -w 0)" > "$CURRENT_WORK_DIR/recovery.env"
}

rollback_restore_transaction() {
    (( RESTORE_TRANSACTION_ACTIVE )) || return 0
    [[ -n "$CURRENT_WORK_DIR" && "$RESTORE_ROLLBACK_DIR" == "$CURRENT_WORK_DIR/rollback" ]] || return 1
    local path
    if [[ "$RESTORE_TRANSACTION_KIND" == "full" ]]; then
        if (( ! RESTORE_FULL_DATA_RECOVERED )); then
            if (( RESTORE_HAD_INSTALLATION )); then
                if [[ -e "$RESTORE_ROLLBACK_DIR/full-install" || -L "$RESTORE_ROLLBACK_DIR/full-install" ]]; then
                    rm -rf "$ST_HOME" || return 1
                    mv "$RESTORE_ROLLBACK_DIR/full-install" "$ST_HOME" || return 1
                elif (( RESTORE_FULL_ORIGINAL_MOVED )) || [[ ! -e "$ST_HOME" ]]; then
                    return 1
                fi
            else
                rm -rf "$ST_HOME" || return 1
            fi
            RESTORE_FULL_DATA_RECOVERED=1
        fi
        if (( RESTORE_DEPENDENCY_SAVED )); then
            cp -a "$RESTORE_ROLLBACK_DIR/dependency-lock.sha256" "$DEPENDENCY_HASH_FILE" || return 1
        else
            rm -f "$DEPENDENCY_HASH_FILE" || return 1
        fi
    elif [[ "$RESTORE_TRANSACTION_KIND" == "partial" ]]; then
        for path in "${RESTORE_AFFECTED_PATHS[@]}"; do
            array_contains "$path" "${RESTORE_RECOVERED_PATHS[@]}" && continue
            restore_destination_safe "$path" || return 1
            if array_contains "$path" "${RESTORE_ORIGINAL_PATHS[@]}"; then
                [[ -e "$RESTORE_ROLLBACK_DIR/$path" || -L "$RESTORE_ROLLBACK_DIR/$path" ]] || return 1
            fi
            rm -rf "$ST_HOME/$path" || return 1
            if array_contains "$path" "${RESTORE_ORIGINAL_PATHS[@]}"; then
                mkdir -p "$ST_HOME/$(dirname "$path")" || return 1
                # Move the protected copy back on the same filesystem. A failed
                # move leaves it available for recovery; do not erase it later.
                mv "$RESTORE_ROLLBACK_DIR/$path" "$ST_HOME/$path" || return 1
            fi
            RESTORE_RECOVERED_PATHS+=("$path")
            printf 'recovered_b64=%s\n' "$(printf '%s' "$path" | base64 -w 0)" >> "$CURRENT_WORK_DIR/recovery.env" || return 1
        done
    else
        return 1
    fi
    RESTORE_TRANSACTION_ACTIVE=0
    RESTORE_RECOVERY_RETAINED=0
    echo "기존 설치와 데이터를 복구했습니다." >&2
}

restore_backup() {
    local file_name="${1:-}"
    [[ "$file_name" =~ ^SillyTavern-Launcher-[0-9]{8}-[0-9]{6}\.zip$ ]] || { echo "허용되지 않는 백업 파일 이름입니다." >&2; exit 64; }
    local archive="$DOWNLOAD_DIR/$file_name"
    [[ -f "$archive" ]] || { echo "선택한 백업 파일을 찾을 수 없습니다." >&2; exit 6; }
    if is_running; then
        echo "복원 전에 SillyTavern 서버를 종료해 주세요." >&2
        exit 10
    fi

    begin_operation "restore"
    ensure_archive_tools
    write_progress 8 "백업 검사" "ZIP 손상 여부와 내부 경로를 확인하고 있습니다."
    unzip -tq "$archive" >> "$LOG_FILE" 2>&1 || { echo "ZIP 파일이 손상되었습니다." >&2; exit 20; }
    if unzip -Z1 "$archive" | grep -Eq '(^/|(^|/)\.\.(/|$)|\\)'; then
        echo "ZIP 안에 안전하지 않은 경로가 있습니다." >&2
        exit 21
    fi
    if zipinfo -l "$archive" 2>/dev/null | awk '$1 ~ /^l/ {found=1} END {exit !found}'; then
        echo "심볼릭 링크가 포함된 백업은 복원할 수 없습니다." >&2
        exit 22
    fi

    write_progress 14 "복원 공간 확인" "백업 해제와 롤백 사본에 필요한 저장 공간을 확인하고 있습니다."
    ensure_restore_space "$archive" || exit $?

    local work="$BACKUP_DIR/restore-work-$$"
    track_current_work_dir "$work"
    local extracted="$work/extracted"
    local rollback="$work/rollback"
    mkdir -p "$extracted" "$rollback"
    unzip -q "$archive" -d "$extracted"
    local manifest="$extracted/.st-launcher-manifest"
    backup_metadata_valid "$(cat "$manifest" 2>/dev/null || true)" || { echo "호환되지 않거나 메타데이터가 손상된 백업입니다." >&2; exit 23; }
    local kinds custom_folders include_secrets
    kinds="$(manifest_value "$manifest" items)"
    custom_folders="$(manifest_value "$manifest" custom)"
    include_secrets="$(manifest_value "$manifest" secrets)"
    rm -f "$manifest"
    if [[ ! "$kinds" =~ ^(user_data|extensions|config|full|custom)(,(user_data|extensions|config|full|custom))*$ ]] ||
        [[ "$include_secrets" != "0" && "$include_secrets" != "1" ]]; then
        echo "백업 manifest의 항목 정보가 올바르지 않습니다." >&2
        rm -rf "$work"
        exit 23
    fi

    write_progress 24 "임시 복원" "실제 데이터에 적용하기 전에 임시 폴더에서 내용을 검증하고 있습니다."
    if [[ ",$kinds," == *,full,* ]]; then
        valid_full_installation "$extracted" || {
            echo "전체 설치 백업에 정상적인 Git 저장소(.git) 또는 필수 실행 파일이 없습니다. 기존 설치는 변경하지 않았습니다. 사용자 데이터·설정 백업으로 가져와 주세요." >&2
            exit 24
        }
    elif [[ ",$kinds," == *,user_data,* && ! -d "$extracted/data" ]]; then
        echo "사용자 데이터 폴더가 없는 백업입니다." >&2; rm -rf "$work"; exit 24
    fi

    write_progress 38 "현재 상태 보호" "문제가 생기면 되돌릴 수 있도록 현재 데이터를 임시 보관하고 있습니다."
    local -a affected=()
    if [[ ",$kinds," == *,full,* ]]; then
        prepare_restore_transaction "full" "$rollback"
        if [[ -e "$ST_HOME" || -L "$ST_HOME" ]]; then RESTORE_HAD_INSTALLATION=1; fi
        if [[ -f "$DEPENDENCY_HASH_FILE" ]]; then
            cp -a "$DEPENDENCY_HASH_FILE" "$rollback/dependency-lock.sha256" || exit 25
            RESTORE_DEPENDENCY_SAVED=1
        fi
        write_restore_journal || exit 25
        RESTORE_TRANSACTION_ACTIVE=1
        if (( RESTORE_HAD_INSTALLATION )); then
            mv "$ST_HOME" "$rollback/full-install" || exit 25
            RESTORE_FULL_ORIGINAL_MOVED=1
        fi
        mkdir -p "$ST_HOME" || exit 25
        local full_apply_failed=0
        cp -a "$extracted"/. "$ST_HOME"/ || full_apply_failed=1
        if [[ "$include_secrets" != "1" ]]; then
            while IFS= read -r secret; do
                local rel="${secret#"$rollback/full-install/"}"
                mkdir -p "$(dirname "$ST_HOME/$rel")" || full_apply_failed=1
                cp -a "$secret" "$ST_HOME/$rel" || full_apply_failed=1
            done < <(find "$rollback/full-install/data" -mindepth 2 -maxdepth 2 -type f -name secrets.json 2>/dev/null || true)
        fi
        if (( full_apply_failed != 0 )); then
            echo "복원 적용에 실패하여 기존 설치 복구를 시작합니다." >&2
            exit 25
        fi
        write_progress 68 "실행 패키지 확인" "복원한 설치에 필요한 Node 모듈을 맞추고 있습니다."
        if ! install_dependencies; then
            echo "패키지 확인에 실패하여 기존 설치 복구를 시작합니다." >&2
            exit 26
        fi
    else
        [[ ",$kinds," == *,user_data,* ]] && affected+=("data")
        if [[ ",$kinds," == *,extensions,* ]]; then
            affected+=("public/scripts/extensions/third-party")
            if [[ ",$kinds," != *,user_data,* ]]; then
                while IFS= read -r extension_path; do
                    [[ -n "$extension_path" ]] && affected+=("${extension_path#"$extracted/"}")
                done < <(find "$extracted/data" -mindepth 2 -maxdepth 2 -type d -name extensions 2>/dev/null || true)
            fi
        fi
        [[ ",$kinds," == *,config,* ]] && affected+=("config.yaml")
        if [[ ",$kinds," == *,custom,* && ",$kinds," != *,user_data,* ]]; then
            IFS=',' read -ra requested_custom <<< "$custom_folders"
            for custom in "${requested_custom[@]}"; do
                [[ "$custom" =~ ^[A-Za-z0-9_[:space:]-]+$ ]] || continue
                affected+=("data/default-user/$custom")
            done
        fi
        prepare_restore_transaction "partial" "$rollback"
        normalize_restore_paths "${affected[@]}"
        affected=("${RESTORE_AFFECTED_PATHS[@]}")
        local path
        for path in "${affected[@]}"; do
            restore_destination_safe "$path" || { echo "설치 바깥을 가리키는 복원 경로입니다: $path" >&2; exit 24; }
            if [[ -e "$ST_HOME/$path" || -L "$ST_HOME/$path" ]]; then
                mkdir -p "$rollback/$(dirname "$path")" || exit 25
                cp -a "$ST_HOME/$path" "$rollback/$path" || exit 25
                RESTORE_ORIGINAL_PATHS+=("$path")
            fi
        done
        if [[ "$include_secrets" != "1" && -d "$ST_HOME/data" ]]; then
            mkdir -p "$rollback/preserved-secrets"
            while IFS= read -r secret; do
                local rel="${secret#"$ST_HOME/data/"}"
                mkdir -p "$rollback/preserved-secrets/$(dirname "$rel")"
                cp -a "$secret" "$rollback/preserved-secrets/$rel"
            done < <(find "$ST_HOME/data" -mindepth 2 -maxdepth 2 -type f -name secrets.json 2>/dev/null || true)
        fi

        write_restore_journal || exit 25
        RESTORE_TRANSACTION_ACTIVE=1
        write_progress 58 "데이터 교체" "검증된 백업을 실제 데이터에 적용하고 있습니다."
        local apply_failed=0
        for path in "${affected[@]}"; do
            rm -rf "$ST_HOME/$path" || exit 25
            if [[ -e "$extracted/$path" ]]; then
                mkdir -p "$ST_HOME/$(dirname "$path")" || exit 25
                cp -a "$extracted/$path" "$ST_HOME/$path" || apply_failed=1
            fi
        done
        if (( apply_failed != 0 )); then
            echo "복원 적용에 실패하여 기존 데이터 복구를 시작합니다." >&2
            exit 25
        fi
        if [[ "$include_secrets" != "1" && -d "$rollback/preserved-secrets" ]]; then
            while IFS= read -r secret; do
                local rel="${secret#"$rollback/preserved-secrets/"}"
                mkdir -p "$(dirname "$ST_HOME/data/$rel")" || apply_failed=1
                cp -a "$secret" "$ST_HOME/data/$rel" || apply_failed=1
            done < <(find "$rollback/preserved-secrets" -type f -name secrets.json 2>/dev/null || true)
        fi
        if (( apply_failed != 0 )); then
            echo "비밀 설정 보존에 실패하여 기존 데이터 복구를 시작합니다." >&2
            exit 25
        fi
    fi

    write_progress 92 "복원 결과 검사" "복원된 데이터와 필수 설치 파일을 마지막으로 확인하고 있습니다."
    if [[ ! -f "$ST_HOME/package.json" || ! -d "$ST_HOME/data" ]]; then
        echo "복원 결과 검증에 실패하여 기존 상태 복구를 시작합니다." >&2
        exit 27
    fi
    RESTORE_TRANSACTION_ACTIVE=0
    rm -rf "$work"
    write_progress 100 "복원 완료" "선택한 백업을 안전하게 복원했습니다." success
    echo "restored=$file_name"
}

repair_st() {
    if [[ ! -d "$ST_HOME/.git" || ! -f "$ST_HOME/package.json" ]]; then
        write_progress 0 "복구 실패" "SillyTavern 설치를 찾을 수 없습니다." error repair
        echo "복구할 SillyTavern 설치를 찾을 수 없습니다: $ST_HOME" >&2
        exit 8
    fi
    if is_running; then
        write_progress 0 "복구 중단" "먼저 SillyTavern 서버를 종료해 주세요." error repair
        echo "패키지를 복구하기 전에 SillyTavern 서버를 종료해 주세요." >&2
        exit 10
    fi

    begin_operation "repair"
    write_progress 3 "설치 점검" "Termux 도구와 SillyTavern 패키지를 확인하고 있습니다."
    if ! refresh_package_indexes; then
        write_progress 0 "복구 실패" "Termux 패키지 목록을 갱신하지 못했습니다." error
        echo "Termux 패키지 목록 갱신에 실패했습니다." >&2
        exit 14
    fi
    write_progress 20 "필수 도구 복구" "Git과 Node.js를 다시 확인하고 있습니다."
    if ! apt_with_selected_sources install -y git nodejs-lts nano >> "$LOG_FILE" 2>&1; then
        write_progress 0 "복구 실패" "Git 또는 Node.js를 복구하지 못했습니다." error
        echo "Git 또는 Node.js 복구에 실패했습니다." >&2
        exit 15
    fi
    write_progress 38 "npm 캐시 점검" "다운로드 캐시의 무결성을 확인하고 있습니다."
    npm cache verify >> "$LOG_FILE" 2>&1 || true
    install_dependencies
    write_progress 100 "복구 완료" "사용자 데이터는 유지하고 실행 패키지를 다시 구성했습니다." success
    echo "repaired=1"
}

rollback_reset_installation() {
    local rollback_dir="$BACKUP_DIR/reset-rollback-$$"
    [[ -d "$rollback_dir/full-install" ]] || return 0
    rm -rf "$ST_HOME"
    mv "$rollback_dir/full-install" "$ST_HOME" || return 1
    if [[ -f "$rollback_dir/dependency-lock.sha256" ]]; then
        cp -a "$rollback_dir/dependency-lock.sha256" "$DEPENDENCY_HASH_FILE"
    else
        rm -f "$DEPENDENCY_HASH_FILE"
    fi
    rm -rf "$rollback_dir"
}

reset_installation() {
    [[ -d "$ST_HOME/.git" && -f "$ST_HOME/package.json" ]] || {
        echo "초기화할 SillyTavern Git 설치를 찾을 수 없습니다." >&2
        exit 8
    }
    is_running && { echo "초기화 전에 SillyTavern 서버를 종료해 주세요." >&2; exit 10; }
    local branch free_bytes rollback_dir
    branch="$(current_branch)"
    [[ "$branch" == "release" || "$branch" == "staging" ]] || {
        echo "지원하지 않는 현재 브랜치입니다: $branch" >&2
        exit 2
    }
    free_bytes="$(df -Pk "$HOME" 2>/dev/null | awk 'NR==2 {print $4 * 1024}' | cut -d. -f1)"
    free_bytes="${free_bytes:-0}"
    (( free_bytes >= 1073741824 )) || {
        echo "기존 설치를 임시 보관하고 다시 설치할 공간이 부족합니다. 최소 1GB의 여유 공간이 필요합니다." >&2
        exit 30
    }

    begin_operation "reset-installation"
    rollback_dir="$BACKUP_DIR/reset-rollback-$$"
    mkdir -p "$rollback_dir"
    write_progress 8 "현재 설치 보호" "재설치에 실패하면 되돌릴 수 있도록 현재 설치를 임시 보관합니다."
    mv "$ST_HOME" "$rollback_dir/full-install"
    [[ -f "$DEPENDENCY_HASH_FILE" ]] && cp -a "$DEPENDENCY_HASH_FILE" "$rollback_dir/dependency-lock.sha256"
    rm -f "$DEPENDENCY_HASH_FILE"

    write_progress 28 "새 설치 다운로드" "$branch 브랜치를 초기 상태로 다시 받고 있습니다."
    if ! git clone --progress --branch "$branch" --single-branch https://github.com/SillyTavern/SillyTavern.git "$ST_HOME" >> "$LOG_FILE" 2>&1; then
        echo "새 SillyTavern을 내려받지 못해 기존 설치로 되돌립니다." >&2
        exit 17
    fi
    write_progress 48 "새 설치 준비" "초기 상태에 필요한 Node 패키지를 준비합니다."
    if ! install_dependencies; then
        echo "새 설치의 패키지 준비에 실패해 기존 설치로 되돌립니다." >&2
        exit 12
    fi
    rm -rf "$rollback_dir"
    write_progress 100 "초기화 완료" "기존 데이터 없이 현재 브랜치를 새로 설치했습니다. Download 백업은 유지됩니다." success
    echo "reset=1"
    echo "branch=$branch"
}

rollback_update() {
    local old_commit="$1"
    local rollback_dir="$2"
    terminate_server_process
    git -C "$ST_HOME" reset --hard "$old_commit" >> "$LOG_FILE" 2>&1 || return 1
    rm -rf "$ST_HOME/node_modules"
    if [[ -d "$rollback_dir/node_modules" ]]; then
        mv "$rollback_dir/node_modules" "$ST_HOME/node_modules" || return 1
    fi
    if [[ -f "$rollback_dir/dependency-lock.sha256" ]]; then
        cp -a "$rollback_dir/dependency-lock.sha256" "$DEPENDENCY_HASH_FILE" || return 1
    else
        rm -f "$DEPENDENCY_HASH_FILE"
    fi
    # Android restarts a previously running server through the persistent
    # RUN_COMMAND task after the rollback result has been recorded.
    return 0
}

last_update_record() {
    local file
    file="$(find "$UPDATE_DIR" -maxdepth 1 -type f -name 'update-*.env' -print 2>/dev/null | sort -r | head -n 1)"
    [[ -n "$file" && -f "$file" ]] && cat "$file" || true
}

update_st() {
    local keep_running="${1:-0}"
    local allow_dirty="${2:-0}"
    [[ "$keep_running" == "0" || "$keep_running" == "1" ]] || { echo "서버 복원 설정이 올바르지 않습니다." >&2; exit 64; }
    [[ "$allow_dirty" == "0" || "$allow_dirty" == "1" ]] || { echo "수정 파일 진행 설정이 올바르지 않습니다." >&2; exit 64; }
    if [[ ! -d "$ST_HOME/.git" ]]; then
        echo "SillyTavern Git 설치를 찾을 수 없습니다." >&2
        exit 8
    fi
    if is_running; then
        echo "업데이트 전에 SillyTavern을 종료해 주세요." >&2
        exit 10
    fi
    local dirty_files
    dirty_files="$(git -C "$ST_HOME" status --porcelain=v1 --untracked-files=all || true)"
    if [[ -n "$dirty_files" && "$allow_dirty" != "1" ]]; then
        echo "수정된 파일이 있어 안전하게 업데이트할 수 없습니다." >&2
        exit 9
    fi

    local branch old_commit remote_commit package_json node_required required_major node_major
    local free_bytes modules_bytes required_bytes
    branch="$(current_branch)"
    [[ "$branch" == "release" || "$branch" == "staging" ]] || { echo "지원하지 않는 현재 브랜치입니다: $branch" >&2; exit 2; }
    if ! ensure_remote_branch "$branch" >> "$LOG_FILE" 2>&1; then
        echo "GitHub에서 업데이트 정보를 가져오지 못했습니다." >&2
        exit 18
    fi
    old_commit="$(git -C "$ST_HOME" rev-parse HEAD)"
    remote_commit="$(git -C "$ST_HOME" rev-parse "origin/$branch")"
    if [[ "$old_commit" == "$remote_commit" ]]; then
        write_progress 100 "이미 최신 상태" "$branch 브랜치가 이미 최신 커밋입니다. 패키지를 다시 설치하지 않았습니다." success update
        echo "updated=0"
        echo "already_current=1"
        echo "branch=$branch"
        echo "commit=${old_commit:0:7}"
        return 0
    fi
    if ! git -C "$ST_HOME" merge-base --is-ancestor "$old_commit" "$remote_commit"; then
        write_progress 0 "업데이트 중단" "현재 커밋이 원격 브랜치와 갈라져 자동 업데이트할 수 없습니다." error update
        echo "현재 설치가 원격 $branch 브랜치와 갈라져 있습니다. 진단 보고서를 확인해 주세요." >&2
        exit 34
    fi
    package_json="$(git -C "$ST_HOME" show "origin/$branch:package.json" 2>/dev/null || true)"
    node_required="$(printf '%s' "$package_json" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>console.log(JSON.parse(s).engines?.node||""))' 2>/dev/null || true)"
    required_major="$(required_node_major "$node_required")"
    required_major="${required_major:-0}"
    node_major="$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null || echo 0)"
    (( required_major > 0 && node_major >= required_major )) || {
        echo "Node.js 요구 버전을 충족하지 않습니다. 필요: $node_required, 현재: $(node --version 2>/dev/null || echo 없음)" >&2
        exit 29
    }
    free_bytes="$(df -Pk "$HOME" 2>/dev/null | awk 'NR==2 {print $4 * 1024}' | cut -d. -f1)"
    free_bytes="${free_bytes:-0}"
    modules_bytes="$(du -sk "$ST_HOME/node_modules" 2>/dev/null | awk '{print $1 * 1024}' | cut -d. -f1)"
    modules_bytes="${modules_bytes:-0}"
    required_bytes=$((modules_bytes + 536870912))
    (( free_bytes >= required_bytes )) || {
        echo "업데이트 및 롤백 패키지를 보관할 저장 공간이 부족합니다." >&2
        exit 30
    }

    begin_operation "update"
    local timestamp rollback_dir state_file old_version
    timestamp="$(date +%Y%m%d-%H%M%S)"
    rollback_dir="$BACKUP_DIR/update-rollback-$timestamp"
    state_file="$UPDATE_DIR/update-$timestamp.env"
    old_version="$(version_from_file "$ST_HOME/package.json")"
    mkdir -p "$rollback_dir"
    if [[ -n "$dirty_files" ]]; then
        local modified_backup="$BACKUP_DIR/before-forced-update-$timestamp.tar.gz"
        write_progress 8 "수정 파일 보호" "현재 수정 내용을 별도 안전 파일로 보관하고 있습니다."
        tar --exclude='node_modules' --exclude='.git' -czf "$modified_backup" -C "$ST_HOME" .
        git -C "$ST_HOME" reset --hard HEAD >> "$LOG_FILE" 2>&1
        git -C "$ST_HOME" clean -fd >> "$LOG_FILE" 2>&1
        echo "[launcher] Modified files saved to $modified_backup" >> "$LOG_FILE"
    fi
    printf 'created_at=%s\nbranch=%s\nold_commit=%s\ntarget_commit=%s\nold_version=%s\nnode_required=%s\nresult=running\n' \
        "$(date '+%Y-%m-%d %H:%M:%S')" "$branch" "$old_commit" "$remote_commit" "$old_version" "$node_required" > "$state_file"
    write_progress 12 "업데이트 상태 기록" "현재 브랜치와 커밋, 기존 패키지를 기록하고 있습니다."
    [[ -f "$DEPENDENCY_HASH_FILE" ]] && cp -a "$DEPENDENCY_HASH_FILE" "$rollback_dir/dependency-lock.sha256"
    [[ -d "$ST_HOME/node_modules" ]] && mv "$ST_HOME/node_modules" "$rollback_dir/node_modules"

    write_progress 32 "업데이트 적용" "$branch 브랜치의 새 커밋을 적용하고 있습니다."
    if ! git -C "$ST_HOME" merge --ff-only "$remote_commit" >> "$LOG_FILE" 2>&1; then
        if rollback_update "$old_commit" "$rollback_dir" "$keep_running"; then
            sed -i 's/^result=.*/result=rolled_back_git/' "$state_file"
            write_progress 0 "업데이트 롤백" "Git 업데이트에 실패하여 이전 커밋과 패키지로 되돌렸습니다." error
            echo "Git 업데이트에 실패하여 이전 상태로 롤백했습니다." >&2
        else
            sed -i 's/^result=.*/result=rollback_failed/' "$state_file"
            write_progress 0 "롤백 확인 필요" "Git 업데이트와 자동 롤백에 실패했습니다. 로그를 확인해 주세요." error
            echo "Git 업데이트와 자동 롤백에 실패했습니다." >&2
        fi
        exit 31
    fi

    write_progress 45 "새 패키지 설치" "업데이트된 버전에 맞는 Node 패키지를 설치하고 있습니다."
    if ! install_dependencies; then
        if rollback_update "$old_commit" "$rollback_dir" "$keep_running"; then
            sed -i 's/^result=.*/result=rolled_back_packages/' "$state_file"
            write_progress 0 "업데이트 롤백" "패키지 설치에 실패하여 이전 커밋과 기존 패키지로 되돌렸습니다." error
            echo "새 패키지 설치에 실패하여 이전 상태로 롤백했습니다." >&2
        else
            sed -i 's/^result=.*/result=rollback_failed/' "$state_file"
            write_progress 0 "롤백 확인 필요" "패키지 설치와 자동 롤백에 실패했습니다. 로그를 확인해 주세요." error
            echo "패키지 설치와 자동 롤백에 실패했습니다." >&2
        fi
        exit 32
    fi

    write_progress 82 "새 서버 검증" "업데이트된 SillyTavern을 임시로 시작해 정상 응답을 확인합니다."
    launch_server_process
    if ! wait_for_server_strict; then
        if rollback_update "$old_commit" "$rollback_dir" "$keep_running"; then
            sed -i 's/^result=.*/result=rolled_back_server/' "$state_file"
            write_progress 0 "업데이트 롤백" "새 서버가 응답하지 않아 이전 커밋과 기존 패키지로 되돌렸습니다." error
            echo "업데이트된 서버가 응답하지 않아 이전 상태로 롤백했습니다." >&2
        else
            sed -i 's/^result=.*/result=rollback_failed/' "$state_file"
            write_progress 0 "롤백 확인 필요" "자동 롤백을 완료하지 못했습니다. 작업 로그를 확인해 주세요." error
            echo "업데이트와 자동 롤백에 실패했습니다. 작업 로그를 확인해 주세요." >&2
        fi
        exit 33
    fi

    [[ "$keep_running" == "0" ]] && terminate_server_process
    sed -i 's/^result=.*/result=success/' "$state_file"
    printf 'new_commit=%s\nnew_version=%s\n' "$(git -C "$ST_HOME" rev-parse HEAD)" "$(version_from_file "$ST_HOME/package.json")" >> "$state_file"
    rm -rf "$rollback_dir"
    write_progress 100 "업데이트 완료" "패키지 설치와 서버 응답 검사를 모두 통과했습니다." success
    echo "updated=1"
    echo "previous_commit=$old_commit"
    echo "branch=$branch"
    echo "commit=$(git -C "$ST_HOME" rev-parse --short HEAD)"
    echo "state_file=$state_file"
}

switch_branch() {
    local branch="${1:-}"
    local allow_dirty="${2:-0}"
    if [[ "$branch" != "release" && "$branch" != "staging" ]]; then
        echo "지원하지 않는 브랜치입니다: $branch" >&2
        exit 2
    fi
    if [[ ! -d "$ST_HOME/.git" ]]; then
        echo "SillyTavern Git 설치를 찾을 수 없습니다." >&2
        exit 8
    fi
    if is_running; then
        echo "브랜치 변경 전에 SillyTavern을 종료해 주세요." >&2
        exit 10
    fi
    [[ "$allow_dirty" == "0" || "$allow_dirty" == "1" ]] || { echo "수정 파일 진행 설정이 올바르지 않습니다." >&2; exit 64; }
    local dirty_files
    dirty_files="$(git -C "$ST_HOME" status --porcelain=v1 --untracked-files=all || true)"
    if [[ -n "$dirty_files" && "$allow_dirty" != "1" ]]; then
        echo "수정된 파일이 있어 브랜치를 변경할 수 없습니다." >&2
        exit 9
    fi
    begin_operation "switch-branch"
    write_progress 10 "안전 백업" "브랜치 변경 전 현재 설정을 백업하고 있습니다."
    local safety_backup="$BACKUP_DIR/before-branch-$(date +%Y%m%d-%H%M%S).tar.gz"
    tar --exclude='SillyTavern/node_modules' --exclude='SillyTavern/.git' \
        -czf "$safety_backup" -C "$HOME" SillyTavern
    if [[ -n "$dirty_files" ]]; then
        write_progress 24 "수정 파일 정리" "확인된 수정 내용을 안전 백업 후 Git 작업 트리에서 정리합니다."
        git -C "$ST_HOME" reset --hard HEAD >> "$LOG_FILE" 2>&1
        git -C "$ST_HOME" clean -fd >> "$LOG_FILE" 2>&1
    fi
    write_progress 35 "브랜치 다운로드" "$branch 브랜치 정보를 받고 있습니다."
    local refspec="+refs/heads/$branch:refs/remotes/origin/$branch"
    if ! git -C "$ST_HOME" config --get-all remote.origin.fetch | grep -Fxq "$refspec"; then
        git -C "$ST_HOME" config --add remote.origin.fetch "$refspec"
    fi
    git -C "$ST_HOME" fetch origin "$refspec"
    write_progress 70 "브랜치 적용" "작업 트리를 $branch 브랜치로 변경하고 있습니다."
    if git -C "$ST_HOME" show-ref --verify --quiet "refs/heads/$branch"; then
        git -C "$ST_HOME" switch "$branch"
    else
        git -C "$ST_HOME" switch --track -c "$branch" "origin/$branch"
    fi
    git -C "$ST_HOME" branch --set-upstream-to="origin/$branch" "$branch"
    git -C "$ST_HOME" pull --ff-only
    write_progress 100 "브랜치 변경 완료" "다음 시작 시 필요한 Node 모듈을 자동으로 맞춥니다." success
    echo "switched=1"
    echo "branch=$branch"
    echo "safety_backup=$safety_backup"
}

bounded_log_tail() {
    local file="$1" lines="$2" bytes="$3"
    [[ -f "$file" ]] || return 0
    tail -c "$bytes" "$file" 2>/dev/null | tail -n "$lines"
}

server_log() {
    local lines server_lines access_lines source
    lines="$(unsigned_decimal "${1:-500}")" || { echo "로그 줄 수가 올바르지 않습니다." >&2; return 64; }
    (( lines > 0 && lines <= 5000 )) || return 64
    server_lines="$lines"
    access_lines=0
    if [[ -s "$ST_HOME/access.log" ]]; then
        access_lines=$((lines / 5))
        (( access_lines < 1 )) && access_lines=1
        server_lines=$((lines - access_lines))
        (( server_lines < 1 )) && server_lines=1
    fi
    source="$SERVER_LOG"
    [[ -s "$source" ]] || source="$LEGACY_SERVER_LOG"
    printf '===== SillyTavern server.log =====\n'
    bounded_log_tail "$source" "$server_lines" 65536
    if (( access_lines > 0 )); then
        printf '\n===== SillyTavern access.log =====\n'
        bounded_log_tail "$ST_HOME/access.log" "$access_lines" 16384
    fi
}

previous_server_log() {
    [[ -s "$PREVIOUS_SERVER_LOG" ]] || return 0
    head -n 1 "$PREVIOUS_SERVER_LOG"
    tail -n +2 "$PREVIOUS_SERVER_LOG" | tail -n "${1:-500}"
}

history_log() {
    tail -n "${1:-300}" "$HISTORY_LOG" 2>/dev/null || true
}

probe_termux_repository() {
    local repo_url="${1%/}"
    [[ -n "$repo_url" ]] || return 1
    local metadata
    local user_agent="Termux-PKG/2.0 SillyTavern-Launcher/$LAUNCHER_VERSION"
    for metadata in InRelease Release; do
        if curl -fsSL \
            --connect-timeout 5 \
            --max-time 10 \
            --range 0-1023 \
            --user-agent "$user_agent" \
            --output /dev/null \
            "$repo_url/dists/stable/$metadata"; then
            return 0
        fi
    done
    return 1
}

diagnose() {
    local previous_operation previous_error_code
    previous_operation="$(last_result_value operation)"
    previous_error_code="$(last_result_value error_code)"
    begin_operation "diagnose"
    write_progress 8 "저장 공간 확인" "Termux와 휴대폰 백업 공간을 확인하고 있습니다."

    local repo_url dirty_files last_error node_required node_compatible
    repo_url="$(awk '/^[[:space:]]*deb[[:space:]]/{print $2; exit}' "$PREFIX/etc/apt/sources.list" 2>/dev/null || true)"
    dirty_files="$(git -C "$ST_HOME" status --porcelain 2>/dev/null | head -n 30 || true)"
    last_error="$({ grep -iE 'error|failed|exception|fatal' "$SERVER_LOG" "$PREVIOUS_SERVER_LOG" "$LOG_FILE" 2>/dev/null || true; } | tail -n 1)"
    node_required="$(cd "$ST_HOME" 2>/dev/null && node -p "require('./package.json').engines?.node || ''" 2>/dev/null || true)"
    node_compatible=0
    if command -v node >/dev/null 2>&1 && [[ -n "$node_required" ]]; then
        local current_major required_major
        current_major="$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null || echo 0)"
        required_major="$(printf '%s' "$node_required" | grep -oE '[0-9]+' | head -n 1 || echo 0)"
        if (( current_major >= required_major )); then node_compatible=1; fi
    fi

    echo "termux_free_bytes=$(df -Pk "$HOME" 2>/dev/null | awk 'NR==2 {print $4 * 1024}' | cut -d. -f1)"
    if [[ -d "$DOWNLOAD_DIR" ]]; then
        echo "downloads_ready=1"
        echo "downloads_free_bytes=$(df -Pk "$DOWNLOAD_DIR" 2>/dev/null | awk 'NR==2 {print $4 * 1024}' | cut -d. -f1)"
    else
        echo "downloads_ready=0"
        echo "downloads_free_bytes=0"
    fi

    write_progress 35 "네트워크 확인" "GitHub와 Termux 저장소 연결을 확인하고 있습니다."
    if curl -sSIL --max-time 7 https://github.com/ >/dev/null 2>&1; then echo "github_reachable=1"; else echo "github_reachable=0"; fi
    if [[ -n "$repo_url" ]]; then echo "termux_repo_configured=1"; else echo "termux_repo_configured=0"; fi
    if probe_termux_repository "$repo_url"; then echo "termux_repo_reachable=1"; else echo "termux_repo_reachable=0"; fi
    echo "termux_repo=$repo_url"

    write_progress 62 "개발 도구 확인" "Git, Node.js, npm과 SillyTavern 설치를 확인하고 있습니다."
    echo "manager_version=$MANAGER_VERSION"
    echo "launcher_version=$LAUNCHER_VERSION"
    echo "last_operation=$previous_operation"
    echo "last_error_code=$previous_error_code"
    echo "git_version=$(git --version 2>/dev/null | awk '{print $3}' || true)"
    echo "node_version=$(node --version 2>/dev/null || true)"
    echo "npm_version=$(npm --version 2>/dev/null || true)"
    echo "node_required=$node_required"
    echo "node_compatible=$node_compatible"
    if [[ -d "$ST_HOME/.git" && -f "$ST_HOME/package.json" ]]; then echo "st_folder_ready=1"; else echo "st_folder_ready=0"; fi
    echo "st_branch=$(current_branch)"
    echo "st_version=$(version_from_file "$ST_HOME/package.json")"
    echo "dependencies_ready=$([[ -d "$ST_HOME" ]] && dependencies_ready && echo 1 || echo 0)"
    echo "dirty_count=$(printf '%s\n' "$dirty_files" | sed '/^$/d' | wc -l)"
    echo "dirty_files_b64=$(printf '%s' "$dirty_files" | base64 -w 0 2>/dev/null || true)"

    write_progress 82 "서버 상태 확인" "포트와 Node 프로세스, 최근 오류를 확인하고 있습니다."
    if command -v ss >/dev/null 2>&1 && ss -ltn 2>/dev/null | awk '{print $4}' | grep -Eq "(^|:)$PORT$"; then echo "port_listening=1"; else echo "port_listening=0"; fi
    if curl -sS --max-time 2 -o /dev/null "http://127.0.0.1:$PORT/" 2>/dev/null; then echo "server_reachable=1"; else echo "server_reachable=0"; fi
    if command -v pgrep >/dev/null 2>&1; then
        echo "node_process_count=$(pgrep -af 'node.*server.js' 2>/dev/null | wc -l || true)"
    else
        echo "node_process_count=$(ps -A 2>/dev/null | grep -c '[n]ode.*server.js' || true)"
    fi
    echo "last_error_b64=$(printf '%s' "$last_error" | base64 -w 0 2>/dev/null || true)"
    write_progress 100 "진단 완료" "전체 환경 검사를 완료했습니다." success
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
CURRENT_OPERATION="${1:-doctor}"
case "$CURRENT_OPERATION" in
    backup-select) CURRENT_OPERATION="backup" ;;
    delete-backups) CURRENT_OPERATION="delete-backup" ;;
    check-update) CURRENT_OPERATION="update-preflight" ;;
esac
trap 'command_cleanup $?' EXIT
case "${1:-doctor}" in
    doctor) doctor ;;
    install) install_st "${2:-release}" ;;
    start) start_st ;;
    prepare-persistent-start) prepare_persistent_start ;;
    server-task) run_persistent_server_task ;;
    finish-persistent-start) finish_persistent_start ;;
    stop) stop_st ;;
    restart) SESSION_ACTION=restart stop_st && SESSION_ACTION=restart start_st ;;
    cancel) cancel_operation ;;
    wake-lock-on) set_termux_wake_lock 1 ;;
    wake-lock-off) set_termux_wake_lock 0 ;;
    backup) backup_st ;;
    backup-select) backup_selected "${2:-}" "${3:-0}" "${4:-}" ;;
    list-backups) list_backups ;;
    import-backup) import_backup "${2:-}" ;;
    list-user-folders) list_user_folders ;;
    list-st-files) list_st_files "${2:-}" ;;
    backup-storage-status) backup_storage_status ;;
    delete-backup) delete_backup "${2:-}" ;;
    delete-backups) delete_backups "${2:-}" ;;
    restore) restore_backup "${2:-}" ;;
    repair) repair_st ;;
    reset-installation) reset_installation ;;
    update) update_st "${2:-0}" "${3:-0}" ;;
    last-update-record) last_update_record ;;
    switch-branch) switch_branch "${2:-}" "${3:-0}" ;;
    logs|server-logs) server_log "${2:-500}" ;;
    previous-server-logs) previous_server_log "${2:-500}" ;;
    history) history_log "${2:-300}" ;;
    diagnose) diagnose ;;
    save-server-connection) save_server_connection_settings "${2:-0}" "${3:-}" ;;
    progress) show_progress ;;
    check-update|update-preflight) update_preflight ;;
    *) echo "알 수 없는 명령입니다: ${1:-}" >&2; exit 64 ;;
esac
fi
