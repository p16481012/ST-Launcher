#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

MANAGER_VERSION="7"
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
SERVER_LOG_CONTEXT="$RUN_DIR/server-log-context.env"
PREVIOUS_SERVER_LOG="$LOG_DIR/server-previous.log"
LEGACY_SERVER_LOG="$LOG_DIR/sillytavern.log"
HISTORY_LOG="$LOG_DIR/launcher-history.log"
PROGRESS_FILE="$RUN_DIR/progress.env"
HEARTBEAT_FILE="$RUN_DIR/progress-heartbeat.env"
PROGRESS_HELPER="${ST_PROGRESS_HELPER:-$(dirname "$MANAGER_SCRIPT_PATH")/progress.sh}"
ARCHIVE_PROGRESS_HELPER="${ST_ARCHIVE_PROGRESS_HELPER:-$(dirname "$MANAGER_SCRIPT_PATH")/archive-progress.sh}"
SAFETY_BACKUP_HELPER="${ST_SAFETY_BACKUP_HELPER:-$(dirname "$MANAGER_SCRIPT_PATH")/safe-backup.sh}"
PROGRESS_MONITOR_PID=""
PROGRESS_MONITOR_START=""
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
CURRENT_IMPORT_ARCHIVE=""
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
    local now previous_phase phase_started mode=indeterminate
    now="$(date +%s)"
    previous_phase="$(sed -n 's/^phase=//p' "$PROGRESS_FILE" 2>/dev/null | head -n 1 || true)"
    phase_started="$(sed -n 's/^phase_started_at=//p' "$PROGRESS_FILE" 2>/dev/null | head -n 1 || true)"
    if [[ "$previous_phase" != "$phase" || ! "$phase_started" =~ ^[0-9]+$ ]]; then phase_started="$now"; fi
    [[ "$status" == success && "$percent" == 100 ]] && mode=complete
    # Existing stage weights remain a legacy protocol field only. The UI never
    # presents them as measured work. Only the transfer helpers provide totals.
    printf 'percent=%s\nphase=%s\ndetail=%s\nstatus=%s\noperation=%s\nerror_code=%s\nprogress_mode=%s\nphase_started_at=%s\noperation_started_at=%s\n' \
        "$percent" "$phase" "$detail" "$status" "$operation" "$error_code" "$mode" "$phase_started" "$OPERATION_STARTED_EPOCH" > "$temp"
    mv -f "$temp" "$PROGRESS_FILE"

    local progress_key="$operation|$phase|$status"
    if [[ "$progress_key" != "$LAST_LOGGED_PROGRESS" ]]; then
        # Repeated elapsed-time updates are not actual tool output/activity.
        printf '[%s] %s - %s\n' "$(date '+%H:%M:%S')" "$phase" "$detail" >> "$LOG_FILE"
        printf '[%s] [%s] %s - %s (%s)\n' \
            "$(date '+%Y-%m-%d %H:%M:%S')" "$operation" "$phase" "$detail" "$status" >> "$HISTORY_LOG"
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
        inspect-install:50|import-install:50) echo "INSTALL_IMPORT_INVALID_PATH" ;;
        inspect-install:51|import-install:51) echo "INSTALL_IMPORT_OVERLAP" ;;
        inspect-install:52|import-install:52) echo "INSTALL_IMPORT_INVALID_INSTALLATION" ;;
        inspect-install:53|import-install:53) echo "INSTALL_IMPORT_UNSAFE_ENTRY" ;;
        inspect-install:54|import-install:54) echo "INSTALL_IMPORT_NO_SPACE" ;;
        import-install:55) echo "INSTALL_IMPORT_COPY_FAILED" ;;
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
        *:35) echo "SAFETY_BACKUP_NO_SPACE" ;;
        *:36) echo "SAFETY_BACKUP_FAILED" ;;
        *:37) echo "SAFETY_BACKUP_SOURCE_CHANGED" ;;
        *:40) echo "FILE_EDIT_TOO_LARGE" ;;
        *:41) echo "FILE_NOT_TEXT" ;;
        *:42) echo "FILE_EDIT_CONFLICT" ;;
        *:43) echo "FILE_ALREADY_EXISTS" ;;
        *:44) echo "FILE_PROTECTED_PATH" ;;
        *:45) echo "FILE_OPERATION_FAILED" ;;
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
        install|start|stop|restart|backup|import-backup|import-install|delete-backup|restore|repair|reset-installation|update|update-preflight|switch-branch|diagnose|save-server-connection|write-st-file|create-st-file|import-st-file|import-st-folder|mkdir-st|rename-st|delete-st|restore-st-trash)
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

start_progress_monitor() {
    local owner="$BASHPID" owner_start
    owner_start="$(process_start_ticks "$owner")"
    (
        trap - EXIT INT TERM
        local previous_output="" output activity=0 now server_operation server_started temp="$HEARTBEAT_FILE.tmp.$BASHPID"
        local wait_seconds wait_detail progress_operation progress_started measured_activity key value
        while kill -0 "$owner" 2>/dev/null && [[ "$(process_start_ticks "$owner")" == "$owner_start" ]]; do
            now="$(date +%s)"
            output="$(stat -c '%s:%Y' "$LOG_FILE" 2>/dev/null || true)"
            if [[ "$CURRENT_OPERATION" == start || "$CURRENT_OPERATION" == restart || "$CURRENT_OPERATION" == update ]]; then
                server_operation="$(sed -n 's/^operation=//p' "$SERVER_LOG_CONTEXT" 2>/dev/null | head -n 1 || true)"
                server_started="$(sed -n 's/^operation_started_at=//p' "$SERVER_LOG_CONTEXT" 2>/dev/null | head -n 1 || true)"
                if [[ "$server_operation" == "$CURRENT_OPERATION" && "$server_started" == "$OPERATION_STARTED_EPOCH" ]]; then
                    output="$output:$(stat -c '%s:%Y' "$SERVER_LOG" 2>/dev/null || true)"
                fi
            fi
            if [[ "$output" != "$previous_output" ]]; then activity="$now"; previous_output="$output"; fi
            progress_operation=""; progress_started=""; measured_activity=0
            while IFS='=' read -r key value; do
                case "$key" in
                    operation) progress_operation="$value" ;;
                    operation_started_at) progress_started="$value" ;;
                    activity_at) measured_activity="$value" ;;
                esac
            done < "$PROGRESS_FILE" 2>/dev/null || true
            if [[ "$progress_operation" == "$CURRENT_OPERATION" && "$progress_started" == "$OPERATION_STARTED_EPOCH" &&
                "$measured_activity" =~ ^[0-9]+$ ]] && (( measured_activity > activity && measured_activity <= now )); then
                activity="$measured_activity"
            fi
            wait_seconds=0; wait_detail=""
            if (( activity > 0 && now - activity >= 10 )); then
                wait_seconds=$(( (now - activity) / 10 * 10 ))
                wait_detail="$(printf '작업 프로세스는 실행 중입니다. 최근 %s초 동안 새 처리 출력이 없어 현재 명령의 완료를 기다리고 있습니다.' "$wait_seconds" | base64 -w 0)"
            fi
            # Waiting metadata is never appended to operation.log: it is not
            # evidence of processed data and must not reset activity timers.
            printf 'monitor_operation=%s\nmonitor_heartbeat_at=%s\nobserved_activity_at=%s\nmonitor_started_at=%s\nmonitor_wait_seconds=%s\nmonitor_wait_detail_b64=%s\n' \
                "$CURRENT_OPERATION" "$now" "$activity" "$OPERATION_STARTED_EPOCH" "$wait_seconds" "$wait_detail" > "$temp"
            mv -f "$temp" "$HEARTBEAT_FILE"
            sleep 2
        done
        rm -f -- "$temp"
    ) &
    PROGRESS_MONITOR_PID=$!
    PROGRESS_MONITOR_START="$(process_start_ticks "$PROGRESS_MONITOR_PID")"
}

stop_progress_monitor() {
    if [[ -n "$PROGRESS_MONITOR_PID" && -n "$PROGRESS_MONITOR_START" &&
        "$(process_start_ticks "$PROGRESS_MONITOR_PID")" == "$PROGRESS_MONITOR_START" ]]; then
        kill -TERM "$PROGRESS_MONITOR_PID" 2>/dev/null || true
        wait "$PROGRESS_MONITOR_PID" 2>/dev/null || true
    fi
    PROGRESS_MONITOR_PID=""
}

measured_copy() {
    local source="$1" destination="$2" excludes="${3:-}" links="${4:-reject-links}"
    ST_PROGRESS_FILE="$PROGRESS_FILE" ST_PROGRESS_LOG="$LOG_FILE" ST_PROGRESS_HISTORY="$HISTORY_LOG" ST_PROGRESS_PHASE="${COPY_PHASE:-파일 복사}" \
        ST_PROGRESS_DETAIL="실제 복사한 파일과 용량을 확인하고 있습니다." ST_PROGRESS_OPERATION="$CURRENT_OPERATION" \
        ST_OPERATION_STARTED_EPOCH="$OPERATION_STARTED_EPOCH" \
        bash "$PROGRESS_HELPER" copy "$source" "$destination" "$excludes" "$links"
}

measured_archive() {
    ST_PROGRESS_FILE="$PROGRESS_FILE" ST_PROGRESS_LOG="$LOG_FILE" ST_PROGRESS_HISTORY="$HISTORY_LOG" ST_PROGRESS_PHASE="$1" ST_PROGRESS_DETAIL="$2" \
        ST_PROGRESS_OPERATION="$CURRENT_OPERATION" ST_OPERATION_STARTED_EPOCH="$OPERATION_STARTED_EPOCH" \
        bash "$ARCHIVE_PROGRESS_HELPER" "${@:3}"
}

safety_backup() {
    local archive="$1" layout="${2:-contents}" reserve="${3:-33554432}" code=0
    ST_PROGRESS_FILE="$PROGRESS_FILE" ST_PROGRESS_LOG="$LOG_FILE" ST_PROGRESS_HISTORY="$HISTORY_LOG" \
        ST_PROGRESS_OPERATION="$CURRENT_OPERATION" ST_PROGRESS_PHASE="${SAFETY_PHASE:-안전 백업}" \
        ST_OPERATION_STARTED_EPOCH="$OPERATION_STARTED_EPOCH" ST_SAFETY_RESERVE_BYTES="$reserve" \
        bash "$SAFETY_BACKUP_HELPER" "$ST_HOME" "$archive" "$layout" >> "$LOG_FILE" 2>&1 || code=$?
    if (( code != 0 )); then
        case "$code" in 35|36|37|130) ;; *) code=36 ;; esac
        return "$code"
    fi
}

reset_server_log() {
    local operation="$CURRENT_OPERATION" started="$OPERATION_STARTED_EPOCH"
    if [[ "$operation" == server-task || "$operation" == idle ]]; then
        operation="$(sed -n 's/^operation=//p' "$PROGRESS_FILE" 2>/dev/null | head -n 1 || true)"
        started="$(sed -n 's/^operation_started_at=//p' "$PROGRESS_FILE" 2>/dev/null | head -n 1 || true)"
    fi
    [[ "$started" =~ ^[0-9]+$ ]] || started=0
    archive_server_log
    : > "$SERVER_LOG"
    printf 'operation=%s\noperation_started_at=%s\n' "$operation" "$started" > "$SERVER_LOG_CONTEXT.tmp.$$"
    mv -f "$SERVER_LOG_CONTEXT.tmp.$$" "$SERVER_LOG_CONTEXT"
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

# Actual completed checks/actions only; elapsed time alone must never create a
# processing record. Never pass file contents or secret configuration values.
record_processing() {
    printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$1" >> "$LOG_FILE"
    record_activity "$1"
}

track_current_work_dir() {
    local path="$1"
    case "$path" in
        "$BACKUP_DIR/"*-work-"$$") CURRENT_WORK_DIR="$path" ;;
        *) echo "안전하지 않은 임시 작업 경로입니다: $path" >&2; return 1 ;;
    esac
}

cleanup_current_work_dir() {
    # The Android picker creates this disposable copy; never remove its source URI.
    if [[ -n "$CURRENT_IMPORT_ARCHIVE" && "$CURRENT_IMPORT_ARCHIVE" == "$DOWNLOAD_DIR/SillyTavern-Import-"*.zip ]]; then
        rm -f -- "$CURRENT_IMPORT_ARCHIVE"
        CURRENT_IMPORT_ARCHIVE=""
    fi
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
    stop_progress_monitor
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
    export ST_OPERATION_STARTED_EPOCH="$OPERATION_STARTED_EPOCH"
    acquire_operation
    # Only the new lock owner may discard stale progress from the prior run.
    rm -f -- "$PROGRESS_FILE" "$HEARTBEAT_FILE"
    LAST_LOGGED_PROGRESS=""
    reset_current_log
    write_progress 0 "작업 준비" "요청한 작업을 안전하게 준비하고 있습니다."
    record_activity "작업 시작"
    start_progress_monitor
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
    record_processing "기존 Node 모듈 정리 시작 · 파일 수에 따라 시간이 걸리며 정리 명령의 완료를 기다립니다."
    rm -rf "$ST_HOME/node_modules"
    record_processing "기존 Node 모듈 정리 완료"

    write_progress 55 "Node 모듈 설치" "필수 패키지를 내려받고 있습니다. 몇 분 걸릴 수 있어요."
    (
        cd "$ST_HOME"
        export NODE_ENV=production
        npm install --no-save --no-audit --no-fund --loglevel=notice --no-progress --omit=dev --ignore-scripts
    ) >> "$LOG_FILE" 2>&1 &
    local npm_pid=$!
    local previous_count=-1
    while kill -0 "$npm_pid" 2>/dev/null; do
        local package_count=0
        package_count="$(find "$ST_HOME/node_modules" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | wc -l || true)"
        write_progress 0 "Node 모듈 설치" "상위 패키지 폴더 ${package_count}개 확인 · 설치 도구의 응답을 기다리고 있습니다."
        if [[ "$package_count" != "$previous_count" ]]; then
            record_processing "Node 패키지 폴더 ${package_count}개 확인"
            previous_count="$package_count"
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
    local attempt
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
            record_processing "로컬 서버 HTTP 응답 확인 · ${attempt}번째 검사"
            write_progress 100 "서버 준비 완료" "브라우저에서 열 수 있습니다." success
            echo "started=1"
            echo "pid=$(cat "$PID_FILE")"
            return 0
        fi

        write_progress 0 "서버 응답 대기" "서버 프로세스는 실행 중입니다. 127.0.0.1:$PORT 응답을 ${attempt}회 확인했지만 아직 준비되지 않았습니다."
        if (( attempt == 1 || attempt % 3 == 0 )); then
            record_processing "서버 프로세스 실행 확인 · HTTP 응답 대기 · ${attempt}회 검사"
        fi
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
        reset_server_log
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
    record_processing "설치 파일·의존성 검사 통과 · Termux 서버 실행 요청 준비"
    write_progress 82 "서버 작업 준비" "Termux에서 지속 실행할 서버 작업을 준비했습니다."
    echo "ready_to_launch=1"
}

run_persistent_server_task() {
    if is_running; then
        echo "already_running=1"
        return 0
    fi
    cd "$ST_HOME"
    reset_server_log
    write_server_pid "$$"
    exec node server.js --port "$PORT" >> "$SERVER_LOG" 2>&1
}

finish_persistent_start() {
    CURRENT_OPERATION="start"
    local prior_operation
    prior_operation="$(sed -n 's/^operation=//p' "$PROGRESS_FILE" 2>/dev/null | head -n 1 || true)"
    if [[ "$prior_operation" == start ]]; then
        OPERATION_STARTED_EPOCH="$(sed -n 's/^operation_started_at=//p' "$PROGRESS_FILE" 2>/dev/null | head -n 1 || true)"
    fi
    if [[ ! "$OPERATION_STARTED_EPOCH" =~ ^[1-9][0-9]*$ ]]; then OPERATION_STARTED_EPOCH="$(date +%s)"; fi
    export ST_OPERATION_STARTED_EPOCH="$OPERATION_STARTED_EPOCH"
    acquire_operation
    write_progress 0 "Termux 실행 응답 대기" "전달한 서버 실행 요청이 실제 프로세스로 시작되는지 확인하고 있습니다."
    record_processing "Termux 지속 실행 작업 확인 시작"
    start_progress_monitor

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
    record_processing "Termux 서버 프로세스 생성 확인 · 로컬 HTTP 응답 검사 시작"
    if wait_for_server; then
        record_server_session "${SESSION_ACTION:-start}" 1
    else
        record_server_session "start_failed" 0
        return 5
    fi
}

wait_for_server_strict() {
    local attempt
    for attempt in $(seq 1 150); do
        is_running || return 1
        if curl -sS --max-time 2 -o /dev/null "http://127.0.0.1:$PORT/" 2>/dev/null; then
            record_processing "업데이트 서버 HTTP 응답 확인 · 프로세스 유지 여부 재검사"
            sleep 1
            is_running && return 0
        fi
        write_progress 0 "업데이트 서버 검사" "새 서버 프로세스 실행 중 · 로컬 HTTP 응답을 ${attempt}회 확인했으며 아직 대기 중입니다."
        if (( attempt == 1 || attempt % 3 == 0 )); then
            record_processing "업데이트 서버 실행 확인 · HTTP 응답 대기 · ${attempt}회 검사"
        fi
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
    record_processing "관리 대상 서버에 종료 요청 전달 · 종료 여부 확인 중"
    for _ in 1 2 3 4 5; do
        kill -0 "$pid" 2>/dev/null || break
        sleep 1
    done
    pkill -KILL -P "$pid" 2>/dev/null || true
    kill -KILL "$pid" 2>/dev/null || true
    clear_server_pid
    record_processing "관리 대상 서버 종료 절차 완료 · 저장된 프로세스 정보 정리"
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
    git -C "$ST_HOME" fetch --progress origin "$refspec"
}

required_node_major() {
    printf '%s\n' "$1" | grep -oE '[0-9]+' | head -n 1 || true
}

update_preflight() {
    local branch dirty_files dirty_count current_commit remote_commit package_json target_version node_required commits_behind
    local node_major required_major free_bytes modules_bytes required_bytes node_compatible space_ready
    begin_operation "update-preflight"
    write_progress 0 "설치 상태 확인" "업데이트할 Git 설치와 현재 브랜치를 확인하고 있습니다."
    [[ -d "$ST_HOME/.git" ]] || { echo "SillyTavern Git 설치를 찾을 수 없습니다." >&2; exit 8; }
    branch="$(current_branch)"
    [[ "$branch" == "release" || "$branch" == "staging" ]] || { echo "지원하지 않는 현재 브랜치입니다: $branch" >&2; exit 2; }
    record_processing "업데이트 대상 설치 확인 완료 · $branch 브랜치"
    write_progress 0 "수정 파일 확인" "업데이트를 방해할 수 있는 로컬 변경을 실제로 검사하고 있습니다."
    dirty_files="$(git -C "$ST_HOME" status --porcelain=v1 --untracked-files=all | sed 's/^...//' || true)"
    dirty_count="$(printf '%s\n' "$dirty_files" | sed '/^$/d' | wc -l)"
    record_processing "수정 파일 검사 완료 · 변경 항목 ${dirty_count}개"
    write_progress 0 "업데이트 정보 가져오기" "$branch 브랜치의 최신 커밋 정보를 GitHub에서 가져오고 있습니다."
    if ! ensure_remote_branch "$branch" >> "$LOG_FILE" 2>&1; then
        record_processing "최신 브랜치 정보 가져오기 실패"
        echo "GitHub에서 최신 브랜치 정보를 가져오지 못했습니다." >&2
        exit 18
    fi
    record_processing "최신 브랜치 정보 가져오기 완료"
    write_progress 0 "업데이트 비교" "현재 설치와 최신 커밋의 차이를 확인하고 있습니다."
    current_commit="$(git -C "$ST_HOME" rev-parse HEAD)"
    remote_commit="$(git -C "$ST_HOME" rev-parse "origin/$branch")"
    commits_behind="$(git -C "$ST_HOME" rev-list --count "$current_commit..$remote_commit" 2>/dev/null || echo 0)"
    record_processing "커밋 비교 완료 · 받을 커밋 ${commits_behind:-0}개 · $([[ "$current_commit" == "$remote_commit" ]] && echo '현재 최신 상태' || echo '설치와 원격 상태가 다름')"
    write_progress 0 "Node.js 요구 사항 확인" "최신 SillyTavern이 요구하는 Node.js와 설치된 실행 환경을 비교하고 있습니다."
    package_json="$(git -C "$ST_HOME" show "origin/$branch:package.json" 2>/dev/null || true)"
    target_version="$(printf '%s' "$package_json" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>console.log(JSON.parse(s).version||""))' 2>/dev/null || true)"
    node_required="$(printf '%s' "$package_json" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>console.log(JSON.parse(s).engines?.node||""))' 2>/dev/null || true)"
    node_major="$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null || echo 0)"
    required_major="$(required_node_major "$node_required")"
    required_major="${required_major:-0}"
    node_compatible=0
    (( node_major >= required_major && required_major > 0 )) && node_compatible=1
    record_processing "Node.js 호환성 검사 완료 · $([[ "$node_compatible" == 1 ]] && echo '요구 사항 충족' || echo '실행 환경 확인 필요')"

    write_progress 0 "남은 저장 공간 확인" "Termux 저장소의 실제 남은 공간을 확인하고 있습니다."
    free_bytes="$(df -Pk "$HOME" 2>/dev/null | awk 'NR==2 {print $4 * 1024}' | cut -d. -f1)"
    free_bytes="${free_bytes:-0}"
    record_processing "남은 저장 공간 확인 완료 · $(awk -v bytes="$free_bytes" 'BEGIN {printf "%.2f GiB", bytes / 1073741824}')"
    write_progress 0 "필요 저장 공간 계산" "기존 node_modules를 보존할 실제 용량과 여유 공간을 계산하고 있습니다."
    modules_bytes="$(du -sk "$ST_HOME/node_modules" 2>/dev/null | awk '{print $1 * 1024}' | cut -d. -f1)"
    modules_bytes="${modules_bytes:-0}"
    required_bytes=$((modules_bytes + 536870912))
    space_ready=0
    (( free_bytes >= required_bytes )) && space_ready=1
    record_processing "필요 공간 계산 완료 · $(awk -v bytes="$required_bytes" 'BEGIN {printf "%.2f GiB", bytes / 1073741824}') · $([[ "$space_ready" == 1 ]] && echo '공간 충분' || echo '공간 부족')"

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
    echo "dirty_count=$dirty_count"
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
    ensure_import_runtime
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
    measured_archive "ZIP 생성" "압축 도구가 완료한 파일 수를 표시합니다. 큰 파일 하나를 처리할 때는 파일 수가 유지될 수 있습니다." \
        compress "$ST_HOME" "$output" "${paths[@]}" -x "${excludes[@]}" >> "$LOG_FILE" 2>&1 || {
        rm -rf "$work"; rm -f "$output"
        echo "선택한 파일을 ZIP으로 만드는 데 실패했습니다." >&2
        exit 18
    }
    write_progress 0 "백업 메타데이터 기록" "압축 정보와 항목 수를 기록하고 있습니다. ZIP 후처리 명령의 완료를 기다립니다."
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
    if ! validate_restore_archive "$output" "$work/verify-index.json" >> "$LOG_FILE" 2>&1 ||
        ! measured_archive "ZIP 검사" "실제 검사한 용량과 파일을 표시합니다. 파일을 다시 풀어 저장하지 않습니다." \
            verify "$output" "$work/verify-index.json" >> "$LOG_FILE" 2>&1; then
        rm -rf "$work"; rm -f "$output"
        echo "생성된 ZIP 검증에 실패하여 불완전한 파일을 삭제했습니다." >&2
        exit 19
    fi
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
    [[ -f "$archive" && ! -L "$archive" ]] || { echo "선택한 ZIP 파일을 Download 폴더에서 찾을 수 없습니다." >&2; exit 6; }
    if is_running; then
        echo "백업을 가져와 복원하기 전에 SillyTavern 서버를 종료해 주세요." >&2
        exit 10
    fi
    begin_operation "import-backup"
    CURRENT_IMPORT_ARCHIVE="$archive"
    ensure_import_runtime
    ensure_archive_tools
    local work="$BACKUP_DIR/import-work-$$"
    track_current_work_dir "$work"
    local extracted="$work/extracted"
    local normalized="$work/normalized"
    mkdir -p "$extracted" "$normalized" "$work/rollback"
    extract_restore_archive "$archive" "$extracted"

    # Native backups keep their manifest semantics, but use the same single
    # extraction/apply path as foreign backups instead of creating another ZIP.
    if [[ -f "$extracted/.st-launcher-manifest" ]]; then
        restore_extracted_tree "$extracted" "$file_name"
        echo "imported_restore=1"
        return 0
    fi

    write_progress 30 "백업 구조 분석" "한 번 해제한 파일에서 복원할 데이터 구조를 확인하고 있습니다."
    local root="$extracted"
    local -a top_entries=()
    while IFS= read -r -d '' entry; do top_entries+=("$entry"); done < <(find "$extracted" -mindepth 1 -maxdepth 1 -print0)
    # Keep known data roots intact. A single default-user/data folder is not a
    # generic archive wrapper; unwrapping it would lose other users/settings.
    if (( ${#top_entries[@]} == 1 )) && [[ -d "${top_entries[0]}" &&
        ! -d "$root/data" && ! -d "$root/default-user" && ! -d "$root/characters" && ! -d "$root/chats" ]]; then
        root="${top_entries[0]}"
    fi

    local kinds=""
    if valid_full_installation "$root"; then
        # Renaming within the staging filesystem is cheap, even for huge data.
        rmdir "$normalized"
        mv "$root" "$normalized"
        kinds="full"
    else
        if [[ -f "$root/package.json" && -f "$root/server.js" && -d "$root/data" ]]; then
            record_activity "Git 설치 정보가 없는 ZIP에서 사용자 데이터·설정·확장 프로그램만 가져옵니다."
        fi
        if [[ -d "$root/data" ]]; then
            mv "$root/data" "$normalized/data"
            kinds="user_data"
        elif [[ -d "$root/default-user" ]]; then
            mv "$root" "$normalized/data"
            root="$normalized/data"
            kinds="user_data"
        elif [[ -d "$root/characters" || -d "$root/chats" || -f "$root/settings.json" ]]; then
            mkdir -p "$normalized/data"
            mv "$root" "$normalized/data/default-user"
            root="$normalized/data/default-user"
            kinds="user_data"
        fi
        if [[ -f "$root/config.yaml" ]]; then
            mv "$root/config.yaml" "$normalized/config.yaml"
            kinds="${kinds:+$kinds,}config"
        fi
        if [[ -d "$root/public/scripts/extensions/third-party" ]]; then
            mkdir -p "$normalized/public/scripts/extensions"
            mv "$root/public/scripts/extensions/third-party" "$normalized/public/scripts/extensions/third-party"
            kinds="${kinds:+$kinds,}extensions"
        elif [[ -d "$root/third-party" ]]; then
            mkdir -p "$normalized/public/scripts/extensions"
            mv "$root/third-party" "$normalized/public/scripts/extensions/third-party"
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

    restore_extracted_tree "$normalized" "$file_name"
    echo "imported_restore=1"
    echo "items=$kinds"
}

ensure_import_runtime() {
    if command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1 &&
        command -v git >/dev/null 2>&1 && command -v tar >/dev/null 2>&1; then
        return 0
    fi
    write_progress 5 "가져오기 도구 준비" "기존 설치를 내려받지 않고 Git·Node.js 실행 도구만 준비합니다."
    refresh_package_indexes || exit 14
    apt_with_selected_sources install -y git nodejs-lts tar >> "$LOG_FILE" 2>&1 || exit 15
}

validate_restore_archive() {
    # Read ZIP metadata without inflating any content. In particular, do not run
    # unzip -t before extraction: CRC is checked by the one actual extraction.
    ST_PROGRESS_FILE="$PROGRESS_FILE" ST_PROGRESS_OPERATION="$CURRENT_OPERATION" ST_OPERATION_STARTED_EPOCH="$OPERATION_STARTED_EPOCH" \
        ST_PROGRESS_LOG="$LOG_FILE" ST_PROGRESS_HISTORY="$HISTORY_LOG" \
        node --input-type=commonjs - "$1" "${2:-}" <<'NODE'
const fs = require('fs');
const fail = (code, message) => { console.error(message); process.exit(code); };
const phaseStarted = Math.floor(Date.now() / 1000);
let lastPublished = 0, lastLogged = 0, lastLogState = '';
const cleanProgress = value => String(value ?? '').replace(/[\x00-\x1f\x7f]/g, ' ').slice(0, 1024);
const logScan = (completed, total, item, force) => {
    const fingerprint = `${completed}/${total}/${item}`;
    if (fingerprint === lastLogState || (!force && Date.now() - lastLogged < 2000)) return;
    lastLogged = Date.now(); lastLogState = fingerprint;
    const stamp = new Date().toLocaleString('sv-SE', { hour12: false });
    const line = `[${stamp}] [${cleanProgress(process.env.ST_PROGRESS_OPERATION)}] ZIP 구조 검사 · ${cleanProgress(item).slice(0, 240) || '전체 경로 확인'} · 항목 ${completed}/${total}개\n`;
    if (process.env.ST_PROGRESS_LOG) fs.appendFileSync(process.env.ST_PROGRESS_LOG, line, { mode: 0o600 });
    const history = process.env.ST_PROGRESS_HISTORY;
    if (!history || history === process.env.ST_PROGRESS_LOG) return;
    fs.appendFileSync(history, line, { mode: 0o600 });
    const size = fs.statSync(history).size;
    if (size <= 1000000) return;
    const fd = fs.openSync(history, 'r'), tail = Buffer.alloc(750000);
    try { fs.readSync(fd, tail, 0, tail.length, size - tail.length); } finally { fs.closeSync(fd); }
    const newline = tail.indexOf(10), temporary = `${history}.scan.${process.pid}.tmp`;
    fs.writeFileSync(temporary, newline >= 0 ? tail.subarray(newline + 1) : tail, { mode: 0o600 });
    fs.renameSync(temporary, history);
};
const publishScan = (completed, total, item = '', force = false) => {
    if (!process.env.ST_PROGRESS_FILE || (!force && Date.now() - lastPublished < 500)) return;
    lastPublished = Date.now();
    const now = Math.floor(lastPublished / 1000);
    logScan(completed, total, item, force);
    const values = {
        percent: total > 0 ? Math.floor(completed * 100 / total) : 0,
        phase: 'ZIP 구조 검사', detail: '실제 ZIP 항목의 경로·크기·압축 정보를 검사하고 있습니다.',
        status: 'running', operation: cleanProgress(process.env.ST_PROGRESS_OPERATION), error_code: '',
        progress_mode: 'files', completed_bytes: 0, total_bytes: 0,
        completed_files: completed, total_files: total,
        current_item_b64: Buffer.from(cleanProgress(item)).toString('base64'),
        heartbeat_at: now, activity_at: now, phase_started_at: phaseStarted,
        operation_started_at: Number(process.env.ST_OPERATION_STARTED_EPOCH) || 0,
    };
    const target = process.env.ST_PROGRESS_FILE, temporary = `${target}.scan.${process.pid}.tmp`;
    fs.writeFileSync(temporary, Object.entries(values).map(([key, value]) => `${key}=${value}\n`).join(''), { mode: 0o600 });
    fs.renameSync(temporary, target);
};
try {
    const fd = fs.openSync(process.argv[2], 'r');
    const archiveStat = fs.fstatSync(fd);
    const size = archiveStat.size;
    const indexEntries = [];
    const read = (offset, length) => {
        if (!Number.isSafeInteger(offset) || offset < 0 || length < 0 || offset + length > size) throw Error('ZIP bounds');
        const value = Buffer.alloc(length);
        if (fs.readSync(fd, value, 0, length, offset) !== length) throw Error('ZIP truncated');
        return value;
    };
    const n64 = (b, o) => {
        const n = b.readBigUInt64LE(o);
        if (n > BigInt(Number.MAX_SAFE_INTEGER)) throw Error('ZIP integer overflow');
        return Number(n);
    };
    const tailStart = Math.max(0, size - 65557), tail = read(tailStart, size - tailStart);
    let end = -1;
    for (let i = tail.length - 22; i >= 0; i--) {
        if (tail.readUInt32LE(i) === 0x06054b50 && i + 22 + tail.readUInt16LE(i + 20) === tail.length) { end = i; break; }
    }
    if (end < 0 || tail.readUInt16LE(end + 4) !== 0 || tail.readUInt16LE(end + 6) !== 0) throw Error('ZIP end/disk');
    let count = tail.readUInt16LE(end + 10), centralSize = tail.readUInt32LE(end + 12), cursor = tail.readUInt32LE(end + 16);
    if (count === 65535 || centralSize === 0xffffffff || cursor === 0xffffffff) {
        const locator = read(tailStart + end - 20, 20);
        if (locator.readUInt32LE(0) !== 0x07064b50 || locator.readUInt32LE(4) !== 0 || locator.readUInt32LE(16) !== 1) throw Error('ZIP64 locator');
        const z = read(n64(locator, 8), 56);
        if (z.readUInt32LE(0) !== 0x06064b50 || z.readUInt32LE(16) !== 0 || z.readUInt32LE(20) !== 0 || n64(z, 24) !== n64(z, 32)) throw Error('ZIP64 disk');
        count = n64(z, 32); centralSize = n64(z, 40); cursor = n64(z, 48);
    } else if (tail.readUInt16LE(end + 8) !== count) throw Error('ZIP split');
    if (!count || count > 500000 || centralSize > 268435456 || cursor + centralSize > tailStart + end) throw Error('ZIP entry limits');
    publishScan(0, count, '', true);
    const centralStart = cursor, centralEnd = cursor + centralSize, names = new Map(), ranges = [];
    let expanded = 0;
    for (let i = 0; i < count; i++) {
        const h = read(cursor, 46);
        if (h.readUInt32LE(0) !== 0x02014b50) throw Error('ZIP central directory');
        const flags = h.readUInt16LE(8), method = h.readUInt16LE(10), nameLength = h.readUInt16LE(28), extraLength = h.readUInt16LE(30);
        if ((flags & 1) || (method !== 0 && method !== 8)) fail(23, '암호화되었거나 지원하지 않는 압축 방식의 ZIP입니다.');
        const rawName = read(cursor + 46, nameLength), name = rawName.toString('utf8');
        if (!Buffer.from(name, 'utf8').equals(rawName)) fail(23, 'UTF-8로 읽을 수 없는 ZIP 파일 이름입니다. UTF-8 ZIP으로 저장해 주세요.');
        const canonical = name.replace(/^(\.\/)+/, '').replace(/\/$/, '');
        if (!canonical || /[\x00-\x1f\x7f\\:]/.test(name) || name.startsWith('/') || canonical.split('/').some(p => p === '..' || p === '.' || !p)) fail(21, 'ZIP 안에 안전하지 않은 경로가 있습니다.');
        if (names.has(canonical)) fail(21, 'ZIP 안에 중복되거나 충돌하는 경로가 있습니다.');
        names.set(canonical, name.endsWith('/'));
        let compressed = h.readUInt32LE(20), unpacked = h.readUInt32LE(24), offset = h.readUInt32LE(42), disk = h.readUInt16LE(34);
        const extra = read(cursor + 46 + nameLength, extraLength);
        for (let e = 0; e < extra.length;) {
            if (e + 4 > extra.length) throw Error('ZIP extra');
            const id = extra.readUInt16LE(e), length = extra.readUInt16LE(e + 2); e += 4;
            if (e + length > extra.length) throw Error('ZIP extra bounds');
            const value = extra.subarray(e, e + length);
            if (id === 1) {
                let z = 0;
                if (unpacked === 0xffffffff) { unpacked = n64(value, z); z += 8; }
                if (compressed === 0xffffffff) { compressed = n64(value, z); z += 8; }
                if (offset === 0xffffffff) { offset = n64(value, z); z += 8; }
                if (disk === 65535) disk = value.readUInt32LE(z);
            }
            // Info-ZIP may prefer the Unicode path over the central name. Only
            // accept it if it denotes exactly the already-validated UTF-8 name.
            if (id === 0x7075 && (value.length < 5 || value.subarray(5).toString('utf8') !== name)) fail(21, 'ZIP 파일 이름의 문자 인코딩을 안전하게 확인할 수 없습니다.');
            e += length;
        }
        if (disk !== 0 || compressed === 0xffffffff || unpacked === 0xffffffff || offset === 0xffffffff) throw Error('ZIP64 fields');
        const type = (h.readUInt32LE(38) >>> 16) & 0xf000;
        const skipped = canonical.split('/').includes('node_modules');
        if (!skipped && type !== 0 && type !== 0x4000 && type !== 0x8000) fail(22, '심볼릭 링크 또는 특수 파일이 포함된 ZIP입니다.');
        if ((type === 0x4000 && !name.endsWith('/')) || (type === 0x8000 && name.endsWith('/'))) throw Error('ZIP entry type mismatch');
        const local = read(offset, 30);
        if (local.readUInt32LE(0) !== 0x04034b50 || local.readUInt16LE(6) !== flags || local.readUInt16LE(8) !== method ||
            !read(offset + 30, local.readUInt16LE(26)).equals(rawName)) throw Error('ZIP local header mismatch');
        let localCompressed = local.readUInt32LE(18), localUnpacked = local.readUInt32LE(22);
        const localExtra = read(offset + 30 + local.readUInt16LE(26), local.readUInt16LE(28));
        for (let e = 0; e < localExtra.length;) {
            if (e + 4 > localExtra.length) throw Error('ZIP local extra');
            const id = localExtra.readUInt16LE(e), length = localExtra.readUInt16LE(e + 2); e += 4;
            if (e + length > localExtra.length) throw Error('ZIP local extra bounds');
            const value = localExtra.subarray(e, e + length);
            if (id === 1) {
                let z = 0;
                if (localUnpacked === 0xffffffff) { localUnpacked = n64(value, z); z += 8; }
                if (localCompressed === 0xffffffff) localCompressed = n64(value, z);
            }
            if (id === 0x7075 && (value.length < 5 || value.subarray(5).toString('utf8') !== name)) fail(21, 'ZIP 내부 파일 이름이 일치하지 않습니다.');
            e += length;
        }
        if ((localCompressed !== compressed && !((flags & 8) && localCompressed === 0)) ||
            (localUnpacked !== unpacked && !((flags & 8) && localUnpacked === 0)) ||
            (!(flags & 8) && local.readUInt32LE(14) !== h.readUInt32LE(16))) throw Error('ZIP local size/CRC mismatch');
        const dataStart = offset + 30 + local.readUInt16LE(26) + local.readUInt16LE(28);
        if (dataStart + compressed > centralStart) throw Error('ZIP entry bounds');
        ranges.push([offset, dataStart + compressed]);
        if (!skipped) {
            expanded += unpacked;
            if (!Number.isSafeInteger(expanded) || expanded > 1099511627776) fail(29, 'ZIP 해제 크기가 안전 한도를 초과합니다.');
            if (canonical.endsWith('.st-launcher-manifest') && unpacked > 65536) fail(23, '백업 메타데이터가 너무 큽니다.');
            const date = h.readUInt16LE(14), time = h.readUInt16LE(12);
            const mtime = new Date(1980 + (date >>> 9), ((date >>> 5) & 15) - 1, date & 31,
                time >>> 11, (time >>> 5) & 63, (time & 31) * 2).getTime();
            indexEntries.push({ path: canonical, dataOffset: dataStart, compressedBytes: compressed,
                bytes: unpacked, method, crc32: h.readUInt32LE(16),
                mode: (h.readUInt32LE(38) >>> 16) & 0o777, mtime: Number.isFinite(mtime) ? mtime : 0,
                isDirectory: name.endsWith('/') });
        }
        cursor += 46 + nameLength + extraLength + h.readUInt16LE(32);
        if (cursor > centralEnd) throw Error('ZIP central bounds');
        // Publish only after real metadata I/O and validation, not a timer.
        publishScan(i + 1, count, canonical);
    }
    if (cursor !== centralEnd) throw Error('ZIP central length');
    ranges.sort((a, b) => a[0] - b[0]);
    for (let i = 1; i < ranges.length; i++) if (ranges[i][0] < ranges[i - 1][1]) fail(21, '서로 겹치는 ZIP 항목은 해제하지 않습니다.');
    for (const name of names.keys()) {
        const parts = name.split('/'); parts.pop();
        while (parts.length) { const parent = parts.join('/'); if (names.has(parent) && !names.get(parent)) fail(21, 'ZIP 파일과 폴더 경로가 충돌합니다.'); parts.pop(); }
    }
    if (process.argv[3]) {
        const index = { archive: { size, mtimeMs: archiveStat.mtimeMs, dev: archiveStat.dev, ino: archiveStat.ino }, entries: indexEntries };
        fs.writeFileSync(process.argv[3], JSON.stringify(index), { mode: 0o600, flag: 'wx' });
    }
    fs.closeSync(fd);
    publishScan(count, count, '', true);
    console.log(expanded);
} catch (error) { fail(error.code === 'ENOSPC' ? 29 : 20, 'ZIP 구조가 손상되었거나 안전하게 읽을 수 없습니다.'); }
NODE
}

extract_restore_archive() {
    local archive="$1" extracted="$2" expanded free_bytes required_bytes unzip_pid unzip_start elapsed=0
    local index="$CURRENT_WORK_DIR/archive-index.json"
    write_progress 8 "ZIP 구조 검사" "압축을 풀기 전에 내부 경로·크기·중복 항목을 검사하고 있습니다."
    expanded="$(validate_restore_archive "$archive" "$index")" || exit $?
    expanded="$(unsigned_decimal "$expanded")" || exit 20
    free_bytes="$(unsigned_decimal "$(df -Pk "$BACKUP_DIR" | awk 'NR==2 {printf "%.0f", $4 * 1024}')")" || exit 29
    required_bytes=$((expanded + 268435456))
    (( free_bytes >= required_bytes )) || { echo "ZIP을 임시 해제할 저장 공간이 부족합니다." >&2; exit 29; }
    echo "restore_expanded_bytes=$expanded"
    write_progress 16 "ZIP 해제" "압축을 한 번만 해제하며 파일 손상(CRC)도 함께 검사합니다."
    (
        ulimit -f $(((expanded + 1048576 + 1023) / 1024))
        measured_archive "ZIP 해제" "실제 해제한 용량을 세며 파일 손상(CRC)도 함께 검사합니다." extract "$archive" "$extracted" "$index"
    ) >> "$LOG_FILE" 2>&1 &
    unzip_pid=$!
    unzip_start="$(process_start_ticks "$unzip_pid")"
    while kill -0 "$unzip_pid" 2>/dev/null; do
        sleep 2
        elapsed=$((elapsed + 2))
        free_bytes="$(unsigned_decimal "$(df -Pk "$BACKUP_DIR" | awk 'NR==2 {printf "%.0f", $4 * 1024}')")" || free_bytes=0
        if (( free_bytes < 67108864 )); then
            if [[ -n "$unzip_start" && "$(process_start_ticks "$unzip_pid")" == "$unzip_start" ]]; then
                signal_descendants "$unzip_pid" "$unzip_start"
                kill -TERM "$unzip_pid" 2>/dev/null || true
            fi
            wait "$unzip_pid" 2>/dev/null || true
            echo "해제 중 실제 여유 공간이 부족해져 중단했습니다. 기존 데이터는 변경하지 않았습니다." >&2
            exit 29
        fi
    done
    local extract_status=0
    wait "$unzip_pid" || extract_status=$?
    if (( extract_status != 0 )); then
        echo "ZIP 해제 또는 CRC 검사에 실패했습니다. 기존 데이터는 변경하지 않았습니다." >&2
        exit "$extract_status"
    fi
    if find "$extracted" -type l -print -quit | grep -q .; then exit 22; fi
    write_progress 28 "해제 완료" "압축을 한 번 해제했습니다. 데이터 구조와 실제 사용 공간을 확인합니다."
}

list_user_folders() {
    [[ -d "$ST_HOME/data/default-user" ]] || return 0
    while IFS= read -r -d '' folder; do
        printf 'folder\t%s\n' "$(printf '%s' "$(basename "$folder")" | base64 -w 0)"
    done < <(find "$ST_HOME/data/default-user" -mindepth 1 -maxdepth 1 -type d -print0 2>/dev/null)
}

list_st_files() {
    st_file_action list "${1:-}" "${2:-0}"
}

read_st_file() {
    st_file_action read "${1:-}"
}

begin_st_file_mutation() {
    begin_operation "$1"
    # Check after acquiring the shared lock so another launcher operation cannot
    # start the server between the check and the file change. A reachable server
    # also covers installations started outside the launcher without its PID file.
    if is_running || installation_server_running "$ST_HOME" || curl -sS --connect-timeout 1 --max-time 1 -o /dev/null "http://127.0.0.1:$PORT/" 2>/dev/null; then
        echo "파일을 변경하려면 먼저 SillyTavern 서버를 종료해 주세요." >&2
        exit 10
    fi
}

write_st_file() {
    begin_st_file_mutation write-st-file
    st_file_action write "${1:-}" "${2:-}" "${3:-}"
    write_progress 100 "파일 저장 완료" "다른 변경 사항과 충돌하지 않는지 확인하고 저장했습니다." success
}

mkdir_st() {
    begin_st_file_mutation mkdir-st
    st_file_action mkdir "${1:-}"
    write_progress 100 "폴더 생성 완료" "SillyTavern 안에 새 폴더를 만들었습니다." success
}

create_st_file() {
    begin_st_file_mutation create-st-file
    st_file_action create-file "${1:-}"
    write_progress 100 "파일 생성 완료" "기존 항목을 덮어쓰지 않고 빈 파일을 만들었습니다." success
}

rename_st() {
    begin_st_file_mutation rename-st
    st_file_action rename "${1:-}" "${2:-}"
    write_progress 100 "이름 변경 완료" "기존 파일을 덮어쓰지 않고 이름을 변경했습니다." success
}

delete_st() {
    begin_st_file_mutation delete-st
    st_file_action trash "${1:-}"
    write_progress 100 "휴지통으로 이동" "원본을 Termux의 런처 휴지통에 보관했습니다. 삭제 취소로 되돌릴 수 있습니다." success
}

restore_st_trash() {
    begin_st_file_mutation restore-st-trash
    st_file_action untrash "${1:-}"
    write_progress 100 "삭제 취소 완료" "휴지통의 항목을 원래 위치로 되돌렸습니다." success
}

import_st_file() {
    begin_st_file_mutation import-st-file
    st_file_action import "${1:-}" "${2:-}"
    write_progress 100 "파일 가져오기 완료" "선택한 파일을 기존 항목을 덮어쓰지 않고 가져왔습니다." success
}

import_st_folder() {
    begin_st_file_mutation import-st-folder
    st_file_action import-folder "${1:-}" "${2:-}"
    write_progress 100 "폴더 가져오기 완료" "선택한 폴더와 하위 내용을 복사했습니다. 원본 폴더는 그대로 유지했습니다." success
}

st_file_action() {
    command -v node >/dev/null 2>&1 || { echo "파일 관리에 필요한 Node.js를 찾을 수 없습니다." >&2; return 12; }
    # Keep the text limit below Linux's per-argument limit after base64 expansion
    # and below the Termux result transport budget. No text contents enter logs.
    ST_PROGRESS_HELPER="$PROGRESS_HELPER" ST_PROGRESS_FILE="$PROGRESS_FILE" ST_PROGRESS_LOG="$LOG_FILE" \
        ST_PROGRESS_HISTORY="$HISTORY_LOG" ST_PROGRESS_OPERATION="$CURRENT_OPERATION" ST_OPERATION_STARTED_EPOCH="$OPERATION_STARTED_EPOCH" \
        ST_PROGRESS_PHASE="SillyTavern에 파일 저장" ST_PROGRESS_DETAIL="실제 기록한 용량을 세며 선택한 파일을 가져옵니다." \
        node - "$ST_HOME" "$LAUNCHER_HOME" "$DOWNLOAD_DIR" "$HOME" "$@" <<'ST_LAUNCHER_FILES_JS'
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { execFileSync } = require('child_process');
const { TextDecoder } = require('util');
const [rootArgument, launcherArgument, downloadArgument, homeArgument, action, encoded = '', value = '', revision = ''] = process.argv.slice(2);
const MAX_TEXT_BYTES = 65536;
const fail = (code, message) => { const error = new Error(message); error.fileCode = code; throw error; };
const exists = filename => { try { fs.lstatSync(filename); return true; } catch (error) { if (error.code === 'ENOENT') return false; throw error; } };
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const b64 = text => Buffer.from(text, 'utf8').toString('base64');
function decode(encodedValue) {
    if (!/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(encodedValue)) fail(64, '올바르지 않은 인코딩입니다.');
    const bytes = Buffer.from(encodedValue, 'base64');
    if (bytes.toString('base64') !== encodedValue) fail(64, '올바르지 않은 인코딩입니다.');
    return bytes;
}
function utf8(bytes) {
    try { return new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes); }
    catch (_) { fail(41, 'UTF-8 텍스트 파일만 앱에서 편집할 수 있습니다.'); }
}
function relativePath(encodedValue, allowRoot = false) {
    const relative = utf8(decode(encodedValue));
    if (relative === '' && allowRoot) return '';
    const components = relative.split('/');
    if (!relative || relative.startsWith('/') || /[\\\x00-\x1f\x7f]/.test(relative) || components.some(part => !part || part === '.' || part === '..')) {
        fail(64, '허용되지 않는 파일 경로입니다.');
    }
    return relative;
}
let root;
function resolve(relative, allowMissing = false) {
    let current = root;
    const components = relative ? relative.split('/') : [];
    for (let index = 0; index < components.length; index++) {
        current = path.join(current, components[index]);
        if (allowMissing && index === components.length - 1 && !exists(current)) return current;
        const stat = fs.lstatSync(current);
        if (stat.isSymbolicLink()) fail(44, '심볼릭 링크를 통한 파일 변경이나 탐색은 지원하지 않습니다.');
        if (index < components.length - 1 && !stat.isDirectory()) fail(6, '상위 폴더를 찾을 수 없습니다.');
    }
    return current;
}
function writable(relative) {
    if (!relative || relative.split('/').some(part => part === '.git' || part === 'node_modules')) {
        fail(44, '설치 루트, Git 정보와 node_modules는 앱에서 변경할 수 없습니다.');
    }
}
function readText(target) {
    if (!fs.lstatSync(target).isFile()) fail(41, '일반 텍스트 파일을 선택해 주세요.');
    const fd = fs.openSync(target, fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW || 0) | (fs.constants.O_NONBLOCK || 0));
    try {
        const stat = fs.fstatSync(fd);
        if (!stat.isFile()) fail(41, '일반 텍스트 파일을 선택해 주세요.');
        if (stat.size > MAX_TEXT_BYTES) fail(40, '앱 내 텍스트 편집은 64 KiB 이하 파일만 지원합니다.');
        // Bound the read itself, not just its initial stat: another app could
        // grow a log/file while this read is in progress.
        const buffer = Buffer.alloc(MAX_TEXT_BYTES + 1);
        let length = 0;
        let count;
        while (length < buffer.length && (count = fs.readSync(fd, buffer, length, buffer.length - length, null)) > 0) length += count;
        if (length > MAX_TEXT_BYTES) fail(40, '앱 내 텍스트 편집은 64 KiB 이하 파일만 지원합니다.');
        const bytes = buffer.subarray(0, length);
        if (/[\x00-\x08\x0b\x0c\x0e-\x1f]/.test(utf8(bytes))) fail(41, '바이너리 파일은 텍스트로 편집할 수 없습니다.');
        return { bytes, stat, sha256: hash(bytes) };
    } finally { fs.closeSync(fd); }
}
function moveNoReplace(source, destination) {
    if (exists(destination)) fail(43, '같은 이름의 항목이 이미 있습니다. 덮어쓰지 않았습니다.');
    // Termux coreutils uses no-clobber rename on the same filesystem. -T also
    // prevents an existing directory from being interpreted as a container.
    execFileSync('mv', ['-T', '-n', '--', source, destination], { stdio: ['ignore', 'ignore', 'pipe'] });
    if (exists(source)) fail(43, '대상 위치가 변경되어 이동하지 않았습니다.');
}
function trashRoot() {
    const launcher = fs.realpathSync(launcherArgument);
    const directory = path.join(launcher, 'file-trash');
    if (!exists(directory)) fs.mkdirSync(directory, { mode: 0o700 });
    if (fs.lstatSync(directory).isSymbolicLink() || !fs.lstatSync(directory).isDirectory()) fail(44, '안전한 휴지통 경로를 열 수 없습니다.');
    fs.chmodSync(directory, 0o700);
    return directory;
}
function directoryIdentity(stat) {
    return [stat.dev, stat.ino, stat.mtimeNs, stat.ctimeNs].map(value => value.toString()).join(':');
}
function within(parent, candidate) {
    return candidate === parent || candidate.startsWith(parent + path.sep);
}
function folderImportSource(encodedSource) {
    const supplied = utf8(decode(encodedSource));
    if (!path.isAbsolute(supplied) || /[\\\x00-\x1f\x7f]/.test(supplied) || supplied.split('/').some(part => part === '.' || part === '..')) fail(64, '가져올 폴더의 안전한 전체 경로가 필요합니다.');
    const absolute = path.resolve(supplied);
    // DOWNLOAD_DIR and ST_HOME are trusted configured roots too (and allow the
    // isolated regression harness to run without Android-specific directories).
    // Check only roots that lexically contain the requested source. An unrelated
    // shared-storage permission denial must not break a Termux-local import.
    const candidates = [homeArgument, '/storage/emulated/0', downloadArgument, rootArgument]
        .map(candidate => path.resolve(candidate)).filter(candidate => within(candidate, absolute))
        .sort((a, b) => b.length - a.length);
    const suppliedBase = candidates.find(candidate => exists(candidate));
    const base = suppliedBase ? { supplied: suppliedBase, real: fs.realpathSync(suppliedBase) } : null;
    if (!base) fail(44, '내부 저장소 또는 Termux 홈에서 접근 가능한 폴더만 가져올 수 있습니다.');
    let current = base.real;
    const components = path.relative(base.supplied, absolute).split(path.sep).filter(Boolean);
    for (const component of components) {
        current = path.join(current, component);
        const stat = fs.lstatSync(current);
        if (stat.isSymbolicLink()) fail(44, '심볼릭 링크 경로의 폴더는 가져올 수 없습니다.');
        if (!stat.isDirectory()) fail(64, '일반 폴더를 선택해 주세요.');
    }
    if (!fs.lstatSync(current).isDirectory()) fail(64, '일반 폴더를 선택해 주세요.');
    const launcher = fs.realpathSync(launcherArgument);
    if (within(launcher, current) || within(current, launcher) || current === path.parse(current).root) fail(44, '런처 관리 폴더 또는 저장소 전체는 가져올 수 없습니다.');
    if (current === fs.realpathSync(homeArgument) || absolute === path.resolve('/storage/emulated/0')) fail(44, '저장소 전체 대신 가져올 하위 폴더를 선택해 주세요.');
    if (components.some(component => component === '.git' || component === 'node_modules')) fail(44, 'Git 정보 또는 node_modules 폴더는 가져올 수 없습니다.');
    return current;
}
try {
    root = fs.realpathSync(rootArgument);
    if (!fs.statSync(root).isDirectory()) fail(8, 'SillyTavern 폴더를 찾을 수 없습니다.');
    if (action === 'untrash') {
        if (!/^[0-9]+-[a-f0-9-]{36}$/.test(encoded)) fail(64, '올바르지 않은 휴지통 항목입니다.');
        const container = path.join(trashRoot(), encoded);
        if (fs.lstatSync(container).isSymbolicLink()) fail(44, '허용되지 않는 휴지통 경로입니다.');
        const metadataPath = path.join(container, 'metadata.json');
        if (fs.lstatSync(metadataPath).isSymbolicLink() || fs.statSync(metadataPath).size > 8192) fail(44, '허지통 복원 정보를 확인할 수 없습니다.');
        const metadata = JSON.parse(fs.readFileSync(metadataPath, 'utf8'));
        if (metadata.root !== root || typeof metadata.relative !== 'string') fail(44, '다른 설치의 휴지통 항목은 복원할 수 없습니다.');
        const relative = relativePath(b64(metadata.relative));
        writable(relative);
        const destination = resolve(relative, true);
        const source = path.join(container, 'item');
        if (fs.lstatSync(source).isSymbolicLink()) fail(44, '심볼릭 링크는 복원하지 않습니다.');
        moveNoReplace(source, destination);
        fs.unlinkSync(metadataPath);
        fs.rmdirSync(container);
        console.log(`restored_b64=${b64(relative)}`);
    } else {
        const relative = relativePath(encoded, action === 'list');
        const target = resolve(relative, action === 'mkdir' || action === 'create-file' || action === 'import' || action === 'import-folder');
        if (action === 'list') {
            if (!/^(0|[1-9][0-9]{0,9})$/.test(value)) fail(64, '올바르지 않은 목록 페이지입니다.');
            const before = fs.lstatSync(target, { bigint: true });
            if (!before.isDirectory()) fail(6, '선택한 폴더를 찾을 수 없습니다.');
            // Termux halves its 100 KiB result budget when stderr is present.
            // Keep each complete page under 40 KiB, including protocol metadata.
            const pageLimit = 40 * 1024;
            const rowLimit = 16 * 1024;
            const metadataReserve = 256;
            const names = fs.readdirSync(target).sort((a, b) => a < b ? -1 : a > b ? 1 : 0);
            let cursor = Number(value);
            if (cursor > names.length) fail(64, '폴더 목록이 변경되었습니다. 다시 열어 주세요.');
            const listingRevision = hash(Buffer.from(directoryIdentity(before) + '\n' + JSON.stringify(names), 'utf8'));
            const rows = [];
            let pageBytes = 0;
            while (cursor < names.length) {
                const name = names[cursor];
                if (/[\\\x00-\x1f\x7f]/.test(name)) { cursor++; continue; }
                const item = path.join(target, name);
                let stat;
                try { stat = fs.lstatSync(item); } catch (error) { if (error.code === 'ENOENT') { cursor++; continue; } throw error; }
                if (!stat.isDirectory() && !stat.isFile()) { cursor++; continue; }
                const itemRelative = relative ? `${relative}/${name}` : name;
                const two = number => String(number).padStart(2, '0');
                const modified = `${stat.mtime.getFullYear()}-${two(stat.mtime.getMonth() + 1)}-${two(stat.mtime.getDate())} ${two(stat.mtime.getHours())}:${two(stat.mtime.getMinutes())}`;
                const sensitive = /^(secrets\.json|config\.yaml|\.env(?:\..*)?|.*\.(?:pem|key))$/i.test(name) ? 1 : 0;
                const row = ['entry', stat.isDirectory() ? 'D' : 'F', b64(itemRelative), b64(name), stat.isDirectory() ? 0 : stat.size, modified, sensitive].join('\t') + '\n';
                const rowBytes = Buffer.byteLength(row, 'utf8');
                if (rowBytes > rowLimit) fail(64, '경로가 너무 길어 폴더 목록을 안전하게 표시할 수 없습니다.');
                if (pageBytes + rowBytes + metadataReserve > pageLimit) break;
                rows.push(row);
                pageBytes += rowBytes;
                cursor++;
            }
            if (directoryIdentity(fs.lstatSync(resolve(relative), { bigint: true })) !== directoryIdentity(before)) fail(42, '폴더 목록을 읽는 동안 내용이 바뀌었습니다. 다시 열어 주세요.');
            process.stdout.write(rows.join('') + `next_cursor=${cursor < names.length ? cursor : ''}\nlisting_revision=${listingRevision}\n`);
        } else if (action === 'read') {
            const snapshot = readText(target);
            console.log(`content_b64=${snapshot.bytes.toString('base64')}\nsha256=${snapshot.sha256}\nsize=${snapshot.bytes.length}`);
        } else if (action === 'write') {
            writable(relative);
            const bytes = decode(value);
            if (bytes.length > MAX_TEXT_BYTES) fail(40, '앱 내 텍스트 편집은 64 KiB 이하 파일만 지원합니다.');
            if (/[\x00-\x08\x0b\x0c\x0e-\x1f]/.test(utf8(bytes))) fail(41, '바이너리 내용은 저장할 수 없습니다.');
            const snapshot = readText(target);
            if (!/^[a-f0-9]{64}$/.test(revision) || snapshot.sha256 !== revision) fail(42, '파일이 다른 곳에서 변경되었습니다. 다시 열어 확인해 주세요.');
            const temporary = path.join(path.dirname(target), `.st-launcher-edit-${crypto.randomUUID()}`);
            let fd;
            try {
                fd = fs.openSync(temporary, fs.constants.O_WRONLY | fs.constants.O_CREAT | fs.constants.O_EXCL, snapshot.stat.mode & 0o777);
                fs.writeFileSync(fd, bytes);
                fs.fsyncSync(fd);
                fs.closeSync(fd); fd = undefined;
                const current = readText(resolve(relative));
                if (current.sha256 !== revision || current.stat.ino !== snapshot.stat.ino || current.stat.dev !== snapshot.stat.dev) fail(42, '저장 중 원본이 변경되어 덮어쓰지 않았습니다.');
                fs.renameSync(temporary, target);
            } finally {
                if (fd !== undefined) fs.closeSync(fd);
                if (exists(temporary)) fs.unlinkSync(temporary);
            }
            console.log(`saved=1\nsha256=${hash(bytes)}`);
        } else if (action === 'import') {
            writable(relative);
            if (!/^SillyTavern-File-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\.tmp$/.test(value)) fail(64, '허용되지 않는 가져오기 파일입니다.');
            if (exists(target)) fail(43, '같은 이름의 항목이 이미 있습니다. 덮어쓰지 않았습니다.');
            const source = path.join(fs.realpathSync(downloadArgument), value);
            if (fs.lstatSync(source).isSymbolicLink()) fail(44, '심볼릭 링크 파일은 가져오지 않습니다.');
            if (!fs.lstatSync(source).isFile()) fail(64, '일반 파일만 가져올 수 있습니다.');
            const input = fs.openSync(source, fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW || 0) | (fs.constants.O_NONBLOCK || 0));
            const temporary = path.join(path.dirname(target), `.st-launcher-import-${crypto.randomUUID()}`);
            let output;
            try {
                const before = fs.fstatSync(input);
                if (!before.isFile()) fail(64, '일반 파일만 가져올 수 있습니다.');
                if (before.size > 8 * 1024 ** 3) fail(30, '8 GiB보다 큰 파일은 앱에서 가져올 수 없습니다.');
                const free = fs.statfsSync(path.dirname(target), { bigint: true });
                if (BigInt(before.size) + 32n * 1024n * 1024n > free.bavail * free.bsize) fail(30, '파일을 안전하게 가져올 저장 공간이 부족합니다.');
                execFileSync('bash', [process.env.ST_PROGRESS_HELPER, 'copy', source, temporary, '', 'reject-links'], {
                    env: { ...process.env, ST_PROGRESS_ITEM: relative }, stdio: ['ignore', 'ignore', 'pipe'],
                });
                const after = fs.fstatSync(input);
                const named = fs.lstatSync(source);
                if (fs.statSync(temporary).size !== before.size || after.size !== before.size ||
                    after.mtimeMs !== before.mtimeMs || after.ctimeMs !== before.ctimeMs ||
                    named.ino !== before.ino || named.dev !== before.dev || named.isSymbolicLink()) {
                    fail(42, '가져오는 동안 원본 파일이 변경되었습니다. 다시 선택해 주세요.');
                }
                output = fs.openSync(temporary, fs.constants.O_WRONLY | (fs.constants.O_NOFOLLOW || 0));
                fs.fchmodSync(output, 0o600);
                fs.fsyncSync(output);
                fs.closeSync(output); output = undefined;
                if (resolve(relative, true) !== target) fail(44, '저장 위치가 변경되어 가져오기를 중단했습니다.');
                moveNoReplace(temporary, target);
            } finally {
                fs.closeSync(input);
                if (output !== undefined) fs.closeSync(output);
                if (exists(temporary)) fs.unlinkSync(temporary);
            }
            console.log(`imported_b64=${b64(relative)}`);
        } else if (action === 'import-folder') {
            writable(relative);
            const sourcePermissionMessage = '선택한 원본 폴더를 Termux에서 읽을 권한이 없습니다. 내부 저장소 폴더라면 Termux에서 termux-setup-storage를 실행하고 저장소 접근 권한을 확인해 주세요.';
            let source;
            try { source = folderImportSource(value); }
            catch (error) {
                if (error.code === 'EACCES' || error.code === 'EPERM') fail(44, sourcePermissionMessage);
                throw error;
            }
            if (path.basename(source) !== path.basename(target)) fail(64, '선택한 원본 폴더 이름으로 가져와야 합니다.');
            if (within(source, target) || within(target, source)) fail(44, '원본과 대상 폴더가 같거나 서로 포함되어 있어 복사할 수 없습니다.');
            if (exists(target)) fail(43, '같은 이름의 항목이 이미 있습니다. 덮어쓰지 않았습니다.');
            const parent = path.dirname(target);
            const parentBefore = fs.lstatSync(parent);
            const container = fs.mkdtempSync(path.join(parent, '.st-launcher-folder-'));
            fs.chmodSync(container, 0o700);
            const containerBefore = fs.lstatSync(container);
            const staged = path.join(container, 'item');
            try {
                try {
                    execFileSync('bash', [process.env.ST_PROGRESS_HELPER, 'copy', source, staged, '', 'reject-links'], {
                        env: { ...process.env, ST_PROGRESS_PHASE: 'SillyTavern에 폴더 복사',
                            ST_PROGRESS_DETAIL: '원본 폴더를 유지하면서 하위 파일과 용량을 세어 복사합니다.',
                            ST_PROGRESS_MIN_FREE_BYTES: '33554432', ST_PROGRESS_BLOCK_PROTECTED_NAMES: '1' },
                        stdio: ['ignore', 'ignore', 'pipe'],
                    });
                } catch (error) {
                    const detail = String(error.stderr || '');
                    if (/COPY_NO_SPACE|ENOSPC/.test(detail)) fail(30, '폴더를 안전하게 가져올 저장 공간이 부족합니다.');
                    if (/COPY_SOURCE_ACCESS_DENIED/.test(detail)) fail(44, sourcePermissionMessage);
                    if (/COPY_SOURCE_CHANGED/.test(detail)) fail(42, '복사 중 원본 폴더가 변경되었습니다. 다시 가져와 주세요.');
                    if (/COPY_UNSAFE|COPY_OVERLAP|COPY_PROTECTED/.test(detail)) fail(44, '폴더에 심볼릭 링크, 특수 파일 또는 보호된 경로가 있어 가져오기를 중단했습니다.');
                    fail(45, '폴더를 복사하지 못했습니다. 원본과 기존 항목은 유지했습니다.');
                }
                const parentAfter = fs.lstatSync(path.dirname(resolve(relative, true)));
                if (parentAfter.ino !== parentBefore.ino || parentAfter.dev !== parentBefore.dev) fail(44, '대상 폴더가 변경되어 가져오기를 중단했습니다.');
                moveNoReplace(staged, target);
            } finally {
                // Only this command's randomly-created staging container is
                // removed, never the selected source or an existing destination.
                if (exists(container)) {
                    const parentNow = fs.lstatSync(parent);
                    const containerNow = fs.lstatSync(container);
                    if (parentNow.ino !== parentBefore.ino || parentNow.dev !== parentBefore.dev || parentNow.isSymbolicLink() ||
                        containerNow.ino !== containerBefore.ino || containerNow.dev !== containerBefore.dev || containerNow.isSymbolicLink()) {
                        fail(44, '임시 폴더 위치가 변경되어 자동 정리하지 않았습니다.');
                    }
                    fs.rmSync(container, { recursive: true, force: true });
                }
            }
            console.log(`imported_b64=${b64(relative)}`);
        } else if (action === 'create-file') {
            writable(relative);
            // Exclusive creation also rejects a file, directory, or symlink
            // created by another app after path validation. Never truncate.
            const fd = fs.openSync(target, fs.constants.O_WRONLY | fs.constants.O_CREAT | fs.constants.O_EXCL | (fs.constants.O_NOFOLLOW || 0), 0o600);
            try { fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
            console.log(`created_b64=${b64(relative)}`);
        } else if (action === 'mkdir') {
            writable(relative);
            if (exists(target)) fail(43, '같은 이름의 항목이 이미 있습니다.');
            fs.mkdirSync(target, { mode: 0o700 });
            console.log(`created_b64=${b64(relative)}`);
        } else if (action === 'rename') {
            writable(relative);
            const destinationRelative = relativePath(value);
            writable(destinationRelative);
            if (destinationRelative.startsWith(`${relative}/`)) fail(64, '폴더를 자기 하위로 이동할 수 없습니다.');
            moveNoReplace(target, resolve(destinationRelative, true));
            console.log(`renamed_b64=${b64(destinationRelative)}`);
        } else if (action === 'trash') {
            writable(relative);
            const id = `${Date.now()}-${crypto.randomUUID()}`;
            const container = path.join(trashRoot(), id);
            fs.mkdirSync(container, { mode: 0o700 });
            fs.writeFileSync(path.join(container, 'metadata.json'), JSON.stringify({ root, relative, deleted_at: Date.now() }), { flag: 'wx', mode: 0o600 });
            moveNoReplace(target, path.join(container, 'item'));
            console.log(`trash_id=${id}`);
        } else fail(64, '지원하지 않는 파일 작업입니다.');
    }
} catch (error) {
    const code = error.fileCode || ({ ENOENT: 6, ENOTDIR: 6, EEXIST: 43, ELOOP: 44, ENOSPC: 30 }[error.code]) || 45;
    console.error(error.fileCode ? error.message : `파일 작업을 완료하지 못했습니다 (${error.code || 'IO_ERROR'}).`);
    process.exitCode = code;
}
ST_LAUNCHER_FILES_JS
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
    # A linked worktree/object database could consult files outside the supplied
    # tree. Imported installations must be self-contained repositories.
    [[ ! -e "$root/.git/commondir" && ! -e "$root/.git/objects/info/alternates" && ! -e "$root/.git/objects/info/http-alternates" ]] || return 1
    git_root="$(git -c safe.directory="$root" -c core.fsmonitor=false -c core.hooksPath=/dev/null -C "$root" rev-parse --show-toplevel 2>/dev/null)" || return 1
    [[ "$git_root" -ef "$root" ]] || return 1
    git -c safe.directory="$root" -c core.fsmonitor=false -c core.hooksPath=/dev/null -C "$root" cat-file -e 'HEAD^{commit}' 2>/dev/null
}

sanitize_imported_git() {
    local root="$1" origin branch
    origin="$(git config --file "$root/.git/config" --no-includes --get remote.origin.url 2>/dev/null || true)"
    if [[ ! "$origin" =~ ^https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]; then
        origin="https://github.com/SillyTavern/SillyTavern.git"
    fi
    branch="$(git -c safe.directory="$root" -c core.fsmonitor=false -c core.hooksPath=/dev/null -C "$root" symbolic-ref --short HEAD 2>/dev/null || true)"
    # Never carry executable hooks, SSH commands, filters, fsmonitor, included
    # config, or arbitrary npm cache/script configuration into the new install.
    rm -rf -- "$root/.git/hooks"
    rm -f -- "$root/.git/config" "$root/.npmrc"
    git config --file "$root/.git/config" core.repositoryformatversion 0 || return 1
    git config --file "$root/.git/config" core.bare false || return 1
    git config --file "$root/.git/config" core.filemode false || return 1
    git config --file "$root/.git/config" core.hooksPath /dev/null || return 1
    git config --file "$root/.git/config" core.fsmonitor false || return 1
    git config --file "$root/.git/config" remote.origin.url "$origin" || return 1
    git config --file "$root/.git/config" remote.origin.fetch '+refs/heads/*:refs/remotes/origin/*' || return 1
    if [[ "$branch" == release || "$branch" == staging ]]; then
        git config --file "$root/.git/config" "branch.$branch.remote" origin || return 1
        git config --file "$root/.git/config" "branch.$branch.merge" "refs/heads/$branch" || return 1
    fi
}

resolve_install_source() {
    local encoded="${1:-}" source resolved destination
    [[ "$encoded" =~ ^[A-Za-z0-9+/]+={0,2}$ ]] || return 50
    source="$(printf '%s' "$encoded" | base64 -d 2>/dev/null)" || return 50
    [[ "$(printf '%s' "$source" | base64 -w 0)" == "$encoded" && "$source" == /* &&
        "$source" != *$'\n'* && "$source" != *$'\r'* && "$source" != *$'\t'* && ! -L "$source" ]] || return 50
    resolved="$(realpath -e -- "$source" 2>/dev/null)" || return 50
    [[ -d "$resolved" && -r "$resolved" && "$resolved" != / && "$resolved" != /data &&
        "$resolved" != /storage/emulated/0 && "$resolved" != "$(realpath -m "$HOME")" ]] || return 50
    destination="$(realpath -m "$ST_HOME")" || return 50
    if [[ "$resolved" != "$destination" && ( "$resolved" == "$destination/"* || "$destination" == "$resolved/"* ) ]]; then return 51; fi
    [[ "$resolved" != "$(realpath -m "$LAUNCHER_HOME")" && "$resolved" != "$(realpath -m "$LAUNCHER_HOME")/"* ]] || return 51
    printf '%s\n' "$resolved"
}

validate_install_tree() {
    local source="$1" invalid
    invalid="$(find "$source" -name node_modules -prune -o ! -type f ! -type d -print -quit 2>/dev/null)" || return 53
    [[ -z "$invalid" ]] || { echo "설치 폴더에 심볼릭 링크 또는 특수 파일이 있습니다." >&2; return 53; }
    valid_full_installation "$source" || { echo "정상적인 SillyTavern Git 설치(.git, package.json, server.js, start.sh, data)를 찾지 못했습니다." >&2; return 52; }
}

install_source_snapshot() {
    # Include ignored dependencies too: cleanup must preserve the original if
    # another launcher writes anywhere in it while the copy is being prepared.
    find "$1" -printf '%y %i %s %T@ %p\0' | LC_ALL=C sort -z | sha256sum | awk '{print $1}'
}

installation_server_running() {
    # Other launchers do not use our PID file. Inspect only readable Node
    # processes whose actual server.js resolves into this installation; never
    # terminate them and do not mistake a shell with this cwd for a server.
    local root="$1" entry pid started executable cwd argument candidate
    local -a arguments=()
    [[ -f "$root/server.js" ]] || return 1
    for entry in /proc/[0-9]*/cmdline; do
        [[ -r "$entry" ]] || continue
        pid="${entry#/proc/}"; pid="${pid%/cmdline}"
        executable="$(readlink -f "/proc/$pid/exe" 2>/dev/null || true)"
        [[ "${executable##*/}" == node || "${executable##*/}" == nodejs ]] || continue
        started="$(process_start_ticks "$pid")"
        [[ -n "$started" ]] || continue
        cwd="$(readlink -f "/proc/$pid/cwd" 2>/dev/null || true)"
        arguments=()
        while IFS= read -r -d '' argument; do arguments+=("$argument"); done < "$entry" 2>/dev/null || continue
        for argument in "${arguments[@]:1}"; do
            [[ "${argument##*/}" == server.js ]] || continue
            if [[ "$argument" == /* ]]; then
                candidate="$argument"
            else
                [[ -n "$cwd" ]] || continue
                candidate="$cwd/$argument"
            fi
            if [[ "$candidate" -ef "$root/server.js" && "$(process_start_ticks "$pid")" == "$started" ]] && kill -0 "$pid" 2>/dev/null; then
                return 0
            fi
        done
    done
    return 1
}

ensure_restore_target_stopped() {
    if is_running || installation_server_running "$ST_HOME" ||
        curl -sS --connect-timeout 1 --max-time 1 -o /dev/null "http://127.0.0.1:$PORT/" 2>/dev/null; then
        echo "설치·데이터를 변경하기 전에 실행 중인 SillyTavern 서버를 종료해 주세요. 다른 런처에서 시작한 서버도 확인해 주세요." >&2
        exit 10
    fi
}

inspect_install() {
    local source destination bytes invalid same=0 tools_ready=1
    source="$(resolve_install_source "${1:-}")" || exit $?
    if command -v git >/dev/null 2>&1; then
        validate_install_tree "$source" || exit $?
    else
        # Inspection stays read-only on a fresh Termux. A structural preview is
        # enough for confirmation; import installs tools and validates Git before
        # copying or changing either installation.
        tools_ready=0
        [[ -d "$source/.git" && -f "$source/package.json" && -f "$source/server.js" &&
            -f "$source/start.sh" && -d "$source/data" ]] || exit 52
        invalid="$(find "$source" -name node_modules -prune -o ! -type f ! -type d -print -quit 2>/dev/null)" || exit 53
        [[ -z "$invalid" ]] || exit 53
    fi
    destination="$(realpath -m "$ST_HOME")"
    [[ "$source" == "$destination" ]] && same=1
    bytes="$(du -sk --exclude=node_modules "$source" | awk '{printf "%.0f", $1 * 1024}')"
    echo "source_path_b64=$(printf '%s' "$source" | base64 -w 0)"
    echo "source_version_b64=$(printf '%s' "$(version_from_file "$source/package.json")" | base64 -w 0)"
    echo "source_bytes=$bytes"
    echo "destination_path_b64=$(printf '%s' "$destination" | base64 -w 0)"
    echo "same_installation=$same"
    echo "tools_ready=$tools_ready"
}

import_install() {
    local source destination identity snapshot after_snapshot bytes free_bytes work extracted
    source="$(resolve_install_source "${1:-}")" || exit $?
    if is_running; then echo "설치 폴더를 옮기기 전에 SillyTavern 서버를 종료해 주세요." >&2; exit 10; fi
    begin_operation "import-install"
    ensure_restore_target_stopped
    if installation_server_running "$source"; then
        echo "가져올 원본 설치의 서버가 실행 중입니다. 해당 런처·Termux에서 서버를 종료한 뒤 다시 시도해 주세요." >&2
        exit 10
    fi
    ensure_import_runtime
    write_progress 0 "설치 구조 검사" "기존 설치의 파일 구조를 검사하고 있습니다. 전체 검사 명령의 완료를 기다립니다."
    validate_install_tree "$source" || exit $?
    record_processing "원본 설치 구조 검사 완료"
    destination="$(realpath -m "$ST_HOME")"
    if [[ "$source" == "$destination" ]]; then
        write_progress 100 "설치 확인 완료" "이미 런처의 설치 위치에 있는 SillyTavern입니다. 폴더를 이동하지 않았습니다." success
        echo "imported_installation=1"
        echo "same_installation=1"
        echo "source_removed=0"
        return 0
    fi
    [[ ! -L "$ST_HOME" ]] || exit 51
    identity="$(stat -c '%d:%i' -- "$source")" || exit 50
    write_progress 0 "원본 상태 기록" "작업 중 변경 여부를 비교할 원본 파일 정보를 기록하고 있습니다."
    snapshot="$(install_source_snapshot "$source")" || exit 53
    record_processing "원본 파일 상태 기록 완료"
    write_progress 0 "설치 이동 공간 계산" "원본 설치의 실제 사용 용량과 남은 공간을 계산하고 있습니다."
    bytes="$(unsigned_decimal "$(du -sk --exclude=node_modules "$source" | awk '{printf "%.0f", $1 * 1024}')")" || exit 54
    free_bytes="$(unsigned_decimal "$(df -Pk "$BACKUP_DIR" | awk 'NR==2 {printf "%.0f", $4 * 1024}')")" || exit 54
    (( free_bytes >= bytes * 2 + 268435456 )) || { echo "설치를 안전하게 옮길 저장 공간이 부족합니다." >&2; exit 54; }
    record_processing "설치 이동 공간 검사 완료 · 원본 ${bytes}바이트 · 사용 가능 ${free_bytes}바이트"
    work="$BACKUP_DIR/install-import-work-$$"
    track_current_work_dir "$work"
    extracted="$work/extracted"
    mkdir -p "$extracted" "$work/rollback"
    write_progress 15 "기존 설치 준비" "원본을 유지한 채 설치와 데이터를 임시 위치에 준비합니다. Node 모듈은 기기에 맞게 다시 구성합니다."
    if ! COPY_PHASE="기존 설치 복사" measured_copy "$source" "$extracted" node_modules; then
        echo "기존 설치 복사에 실패했습니다. 원본은 변경하지 않았습니다." >&2
        exit 55
    fi
    write_progress 0 "복사 결과 검사" "복사된 설치 구조와 원본 변경 여부를 검사하고 있습니다."
    validate_install_tree "$extracted" || exit $?
    after_snapshot="$(install_source_snapshot "$source")" || exit 55
    [[ "$snapshot" == "$after_snapshot" && "$(stat -c '%d:%i' -- "$source")" == "$identity" ]] || {
        echo "작업 중 원본 설치가 변경되었습니다. 다른 런처·서버를 종료한 뒤 다시 시도해 주세요. 원본은 유지했습니다." >&2
        exit 55
    }
    printf 'format=st-launcher-backup-v1\nitems=full\ncustom=\nsecrets=1\n' > "$extracted/.st-launcher-manifest"
    restore_extracted_tree "$extracted" "existing-installation"
    # Destination is now complete and the rollback transaction has committed.
    # A late source change or cleanup failure does not invalidate that success.
    write_progress 0 "원본 변경 재확인" "새 설치는 준비되었습니다. 원본 정리 전에 파일 변경 여부를 다시 검사합니다."
    if ! installation_server_running "$source" && [[ ! -L "$source" && "$(realpath -e -- "$source" 2>/dev/null || true)" == "$source" &&
        "$(stat -c '%d:%i' -- "$source" 2>/dev/null || true)" == "$identity" &&
        "$(install_source_snapshot "$source" 2>/dev/null || true)" == "$snapshot" ]]; then
        write_progress 0 "원본 정리" "검증된 이전 설치 폴더를 정리하고 있습니다. 정리 명령의 완료를 기다립니다."
        if rm -rf -- "$source" && [[ ! -e "$source" && ! -L "$source" ]]; then
            echo "source_removed=1"
            write_progress 100 "설치 이동 완료" "기존 설치를 런처 위치로 옮겼습니다. 검증된 원본 폴더를 정리했습니다." success
        else
            echo "source_cleanup_failed=1"
            write_progress 100 "설치 이동 완료 · 원본 정리 필요" "새 설치는 정상입니다. 원본 폴더 정리는 완료하지 못했습니다." success
        fi
    else
        echo "source_cleanup_failed=1"
        write_progress 100 "설치 이동 완료 · 원본 유지" "새 설치는 정상입니다. 원본이 변경되었거나 원본 서버가 실행되어 원본 폴더를 지우지 않았습니다." success
    fi
    echo "imported_installation=1"
    echo "same_installation=0"
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
    [[ -f "$archive" && ! -L "$archive" ]] || { echo "선택한 백업 파일을 찾을 수 없습니다." >&2; exit 6; }
    if is_running; then
        echo "복원 전에 SillyTavern 서버를 종료해 주세요." >&2
        exit 10
    fi

    begin_operation "restore"
    ensure_import_runtime
    ensure_archive_tools
    local work="$BACKUP_DIR/restore-work-$$"
    track_current_work_dir "$work"
    local extracted="$work/extracted"
    mkdir -p "$extracted" "$work/rollback"
    extract_restore_archive "$archive" "$extracted"
    restore_extracted_tree "$extracted" "$file_name"
}

restore_extracted_tree() {
    local extracted="$1" file_name="$2"
    local work="$CURRENT_WORK_DIR" rollback="$CURRENT_WORK_DIR/rollback"
    [[ ! -L "$ST_HOME" ]] || { echo "설치 경로가 심볼릭 링크입니다. 원본 폴더를 직접 선택해 가져와 주세요." >&2; exit 24; }
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

    write_progress 32 "임시 복원 검사" "실제 데이터에 적용하기 전에 임시 폴더에서 내용을 검증하고 있습니다."
    if [[ ",$kinds," == *,full,* ]]; then
        valid_full_installation "$extracted" || {
            echo "전체 설치 백업에 정상적인 Git 저장소(.git) 또는 필수 실행 파일이 없습니다. 기존 설치는 변경하지 않았습니다. 사용자 데이터·설정 백업으로 가져와 주세요." >&2
            exit 24
        }
    elif [[ ",$kinds," == *,user_data,* && ! -d "$extracted/data" ]]; then
        echo "사용자 데이터 폴더가 없는 백업입니다." >&2; rm -rf "$work"; exit 24
    fi
    if [[ ",$kinds," != *,full,* && (! -f "$ST_HOME/package.json" || ! -f "$ST_HOME/server.js") ]]; then
        echo "데이터·설정만 있는 백업은 기존 SillyTavern 설치가 필요합니다. 먼저 설치하거나 정상적인 전체 설치 폴더를 가져와 주세요." >&2
        exit 24
    fi
    if [[ ",$kinds," != *,full,* ]]; then
        if [[ ",$kinds," == *,config,* && ! -f "$extracted/config.yaml" ]]; then
            echo "설정 복원을 요청한 백업에 config.yaml이 없습니다." >&2; exit 24
        fi
        if [[ ",$kinds," == *,extensions,* && ! -d "$extracted/public/scripts/extensions/third-party" ]] &&
            ! find "$extracted/data" -mindepth 2 -maxdepth 2 -type d -name extensions -print -quit 2>/dev/null | grep -q .; then
            echo "확장 프로그램 복원을 요청한 백업에 해당 폴더가 없습니다." >&2; exit 24
        fi
        if [[ ",$kinds," == *,custom,* && ",$kinds," != *,user_data,* ]]; then
            local custom
            local -a requested_custom=()
            IFS=',' read -ra requested_custom <<< "$custom_folders"
            (( ${#requested_custom[@]} > 0 )) || exit 23
            for custom in "${requested_custom[@]}"; do
                [[ "$custom" =~ ^[A-Za-z0-9_[:space:]-]+$ && -d "$extracted/data/default-user/$custom" ]] || {
                    echo "사용자 지정 복원 폴더 정보가 올바르지 않습니다." >&2; exit 24
                }
            done
        fi
    fi
    local incoming_bytes current_bytes free_bytes required_bytes
    write_progress 0 "복원 공간 계산" "가져온 데이터와 현재 데이터의 실제 사용 공간을 계산하고 있습니다."
    incoming_bytes="$(du -sk "$extracted" | awk '{printf "%.0f", $1 * 1024}')"
    current_bytes=0
    if [[ ",$kinds," != *,full,* && -d "$ST_HOME" ]]; then
        current_bytes="$(du -sk --exclude=node_modules --exclude=.git "$ST_HOME" | awk '{printf "%.0f", $1 * 1024}')"
    fi
    free_bytes="$(unsigned_decimal "$(df -Pk "$BACKUP_DIR" | awk 'NR==2 {printf "%.0f", $4 * 1024}')")" || exit 29
    incoming_bytes="$(unsigned_decimal "$incoming_bytes")" || exit 29
    current_bytes="$(unsigned_decimal "$current_bytes")" || exit 29
    required_bytes=$((incoming_bytes + current_bytes + 268435456))
    (( free_bytes >= required_bytes )) || { echo "기존 데이터 보호와 복원 적용에 필요한 저장 공간이 부족합니다." >&2; exit 29; }
    record_processing "복원 공간 검사 완료 · 필요 ${required_bytes}바이트 · 사용 가능 ${free_bytes}바이트"

    ensure_restore_target_stopped
    write_progress 38 "현재 상태 보호" "문제가 생기면 되돌릴 수 있도록 현재 데이터를 임시 보관하고 있습니다."
    local -a affected=()
    if [[ ",$kinds," == *,full,* ]]; then
        sanitize_imported_git "$extracted" || { echo "가져온 Git 설정을 안전하게 구성하지 못했습니다." >&2; exit 24; }
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
        COPY_PHASE="전체 설치 적용" measured_copy "$extracted" "$ST_HOME" || full_apply_failed=1
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
                COPY_PHASE="현재 상태 보호 · $path" measured_copy "$ST_HOME/$path" "$rollback/$path" "" preserve-links || exit 25
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
            write_progress 0 "기존 항목 정리" "안전 사본을 보관한 기존 항목을 정리하고 있습니다. 정리 명령의 완료를 기다립니다."
            rm -rf "$ST_HOME/$path" || exit 25
            if [[ -e "$extracted/$path" ]]; then
                mkdir -p "$ST_HOME/$(dirname "$path")" || exit 25
                COPY_PHASE="데이터 교체 · $path" measured_copy "$extracted/$path" "$ST_HOME/$path" || apply_failed=1
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
    write_progress 0 "임시 복원 파일 정리" "복원 결과 검사를 통과했습니다. 임시 파일 정리 명령의 완료를 기다립니다."
    rm -rf "$work"
    if [[ -n "$CURRENT_IMPORT_ARCHIVE" ]]; then rm -f -- "$CURRENT_IMPORT_ARCHIVE"; CURRENT_IMPORT_ARCHIVE=""; fi
    if [[ "$CURRENT_OPERATION" == import-install ]]; then
        write_progress 0 "새 설치 준비 완료" "새 설치 검증을 마쳤습니다. 이전 설치의 정리 여부를 확인합니다."
    else
        write_progress 100 "복원 완료" "선택한 백업을 안전하게 복원했습니다." success
    fi
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
    if npm cache verify >> "$LOG_FILE" 2>&1; then
        record_processing "npm 캐시 검사 완료"
    else
        record_processing "npm 캐시 검사가 실패했습니다. 실행 패키지 재설치로 복구를 계속합니다."
    fi
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
    begin_operation "update"
    write_progress 0 "업데이트 준비 검사" "서버 종료 상태와 Git 수정 파일을 확인하고 있습니다."
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
    record_processing "현재 브랜치 확인 · $branch · Git 수정 파일 검사 완료"
    write_progress 0 "최신 커밋 조회" "$branch 브랜치의 원격 변경 정보를 가져오고 있습니다."
    if ! ensure_remote_branch "$branch" >> "$LOG_FILE" 2>&1; then
        echo "GitHub에서 업데이트 정보를 가져오지 못했습니다." >&2
        exit 18
    fi
    old_commit="$(git -C "$ST_HOME" rev-parse HEAD)"
    remote_commit="$(git -C "$ST_HOME" rev-parse "origin/$branch")"
    record_processing "커밋 조회 완료 · 현재 ${old_commit:0:7} → 원격 ${remote_commit:0:7}"
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
    write_progress 0 "업데이트 요구 사항 검사" "새 버전의 Node.js 요구 사항과 롤백용 여유 공간을 확인하고 있습니다."
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
    write_progress 0 "업데이트 공간 계산" "기존 Node 모듈의 실제 용량을 계산하고 있습니다. 전체 용량 검사 명령의 완료를 기다립니다."
    modules_bytes="$(du -sk "$ST_HOME/node_modules" 2>/dev/null | awk '{print $1 * 1024}' | cut -d. -f1)"
    modules_bytes="${modules_bytes:-0}"
    required_bytes=$((modules_bytes + 536870912))
    (( free_bytes >= required_bytes )) || {
        echo "업데이트 및 롤백 패키지를 보관할 저장 공간이 부족합니다." >&2
        exit 30
    }

    record_processing "Node.js 호환성·저장 공간 검사 통과 · 이전 설치 보호 시작"
    local timestamp rollback_dir state_file old_version
    timestamp="$(date +%Y%m%d-%H%M%S)"
    rollback_dir="$BACKUP_DIR/update-rollback-$timestamp"
    state_file="$UPDATE_DIR/update-$timestamp.env"
    old_version="$(version_from_file "$ST_HOME/package.json")"
    mkdir -p "$rollback_dir"
    if [[ -n "$dirty_files" ]]; then
        local modified_backup="$BACKUP_DIR/before-forced-update-$timestamp.tar.gz"
        write_progress 8 "수정 파일 보호" "사용자 데이터를 포함한 현재 설치를 별도 안전 파일로 압축하고 있습니다."
        SAFETY_PHASE="수정 파일 보호" safety_backup "$modified_backup" contents "$required_bytes" || exit $?
        record_processing "수정 파일 안전 보관 완료 · Git 작업 트리 정리 시작"
        write_progress 0 "Git 작업 트리 정리" "안전 백업을 저장했습니다. Git 수정 파일 정리 명령의 완료를 기다립니다."
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
    begin_operation "switch-branch"
    write_progress 0 "브랜치 변경 준비 검사" "Git 수정 파일을 검사하고 있습니다. 검사 명령의 완료를 기다립니다."
    local dirty_files
    dirty_files="$(git -C "$ST_HOME" status --porcelain=v1 --untracked-files=all || true)"
    if [[ -n "$dirty_files" && "$allow_dirty" != "1" ]]; then
        echo "수정된 파일이 있어 브랜치를 변경할 수 없습니다." >&2
        exit 9
    fi
    write_progress 10 "안전 백업" "브랜치 변경 전 현재 설정을 백업하고 있습니다."
    local safety_backup="$BACKUP_DIR/before-branch-$(date +%Y%m%d-%H%M%S).tar.gz"
    SAFETY_PHASE="브랜치 변경 안전 백업" safety_backup "$safety_backup" directory || exit $?
    record_processing "브랜치 변경 전 안전 백업 생성 완료"
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
    git -C "$ST_HOME" fetch --progress origin "$refspec" >> "$LOG_FILE" 2>&1
    record_processing "브랜치 정보 수신 완료 · $branch 작업 트리 적용 시작"
    write_progress 70 "브랜치 적용" "작업 트리를 $branch 브랜치로 변경하고 있습니다."
    if git -C "$ST_HOME" show-ref --verify --quiet "refs/heads/$branch"; then
        git -C "$ST_HOME" switch "$branch" >> "$LOG_FILE" 2>&1
    else
        git -C "$ST_HOME" switch --track -c "$branch" "origin/$branch" >> "$LOG_FILE" 2>&1
    fi
    git -C "$ST_HOME" branch --set-upstream-to="origin/$branch" "$branch" >> "$LOG_FILE" 2>&1
    git -C "$ST_HOME" pull --progress --ff-only >> "$LOG_FILE" 2>&1
    record_processing "브랜치 변경·최신 커밋 반영 확인 완료"
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
    bounded_log_tail "$HISTORY_LOG" "${1:-300}" 40000
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

    local repo_url dirty_files dirty_count last_error node_required node_compatible
    local termux_free downloads_free github_ready repo_ready git_version node_version npm_version
    local installation_ready dependencies_status port_listening server_reachable node_process_count
    write_progress 0 "Termux 저장 공간 확인" "Termux 저장소의 실제 남은 공간을 확인하고 있습니다."
    termux_free="$(df -Pk "$HOME" 2>/dev/null | awk 'NR==2 {print $4 * 1024}' | cut -d. -f1)"
    echo "termux_free_bytes=$termux_free"
    record_processing "Termux 저장 공간 확인 완료 · $(awk -v bytes="${termux_free:-0}" 'BEGIN {printf "%.2f GiB", bytes / 1073741824}')"

    write_progress 0 "백업 저장 공간 확인" "휴대폰 Download 연결과 남은 공간을 확인하고 있습니다."
    if [[ -d "$DOWNLOAD_DIR" ]]; then
        echo "downloads_ready=1"
        downloads_free="$(df -Pk "$DOWNLOAD_DIR" 2>/dev/null | awk 'NR==2 {print $4 * 1024}' | cut -d. -f1)"
        echo "downloads_free_bytes=$downloads_free"
        record_processing "백업 저장 공간 확인 완료 · Download 연결됨 · $(awk -v bytes="${downloads_free:-0}" 'BEGIN {printf "%.2f GiB", bytes / 1073741824}') 남음"
    else
        echo "downloads_ready=0"
        echo "downloads_free_bytes=0"
        record_processing "백업 저장 공간 확인 완료 · Download 연결 필요"
    fi

    write_progress 0 "GitHub 연결 확인" "GitHub의 응답을 기다리고 있습니다. 최대 7초 동안 확인합니다."
    github_ready=0
    if curl -sSIL --max-time 7 https://github.com/ >/dev/null 2>&1; then github_ready=1; fi
    echo "github_reachable=$github_ready"
    record_processing "GitHub 연결 검사 완료 · $([[ "$github_ready" == 1 ]] && echo '응답 확인' || echo '응답 확인 실패')"

    write_progress 0 "Termux 저장소 연결 확인" "설정된 저장소의 패키지 메타데이터 응답을 확인하고 있습니다."
    repo_url="$(awk '/^[[:space:]]*deb[[:space:]]/{print $2; exit}' "$PREFIX/etc/apt/sources.list" 2>/dev/null || true)"
    if [[ -n "$repo_url" ]]; then echo "termux_repo_configured=1"; else echo "termux_repo_configured=0"; fi
    repo_ready=0
    if probe_termux_repository "$repo_url"; then repo_ready=1; fi
    echo "termux_repo_reachable=$repo_ready"
    echo "termux_repo=$repo_url"
    if [[ -z "$repo_url" ]]; then
        record_processing "Termux 저장소 검사 완료 · 저장소 설정 없음"
    else
        record_processing "Termux 저장소 검사 완료 · $([[ "$repo_ready" == 1 ]] && echo '패키지 메타데이터 응답 확인' || echo '패키지 메타데이터 응답 확인 실패')"
    fi

    # Keep the existing protocol fields for copied diagnostics only. These
    # internal versions are intentionally absent from live processing history.
    echo "manager_version=$MANAGER_VERSION"
    echo "launcher_version=$LAUNCHER_VERSION"
    echo "last_operation=$previous_operation"
    echo "last_error_code=$previous_error_code"

    write_progress 0 "Git 실행 확인" "Git 명령이 정상적으로 실행되는지 확인하고 있습니다."
    git_version="$(git --version 2>/dev/null | awk '{print $3}' || true)"
    echo "git_version=$git_version"
    record_processing "Git 실행 검사 완료 · $([[ -n "$git_version" ]] && echo '실행 확인' || echo '실행 확인 실패')"

    write_progress 0 "Node.js 실행 확인" "Node.js 실행과 SillyTavern의 요구 사항을 확인하고 있습니다."
    node_version="$(node --version 2>/dev/null || true)"
    echo "node_version=$node_version"
    node_required="$(cd "$ST_HOME" 2>/dev/null && node -p "require('./package.json').engines?.node || ''" 2>/dev/null || true)"
    node_compatible=0
    if command -v node >/dev/null 2>&1 && [[ -n "$node_required" ]]; then
        local current_major required_major
        current_major="$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null || echo 0)"
        required_major="$(printf '%s' "$node_required" | grep -oE '[0-9]+' | head -n 1 || echo 0)"
        if (( current_major >= required_major )); then node_compatible=1; fi
    fi
    echo "node_required=$node_required"
    echo "node_compatible=$node_compatible"
    record_processing "Node.js 검사 완료 · $([[ -n "$node_version" ]] && echo '실행 확인' || echo '실행 확인 실패') · $([[ "$node_compatible" == 1 ]] && echo '요구 사항 충족' || echo '요구 사항 확인 필요')"

    write_progress 0 "npm 실행 확인" "npm이 정상적으로 실행되는지 확인하고 있습니다."
    npm_version="$(npm --version 2>/dev/null || true)"
    echo "npm_version=$npm_version"
    record_processing "npm 실행 검사 완료 · $([[ -n "$npm_version" ]] && echo '실행 확인' || echo '실행 확인 실패')"

    write_progress 0 "SillyTavern 설치 확인" "설치 폴더, Git 정보와 프로그램 버전을 확인하고 있습니다."
    installation_ready=0
    if [[ -d "$ST_HOME/.git" && -f "$ST_HOME/package.json" ]]; then installation_ready=1; fi
    echo "st_folder_ready=$installation_ready"
    echo "st_branch=$(current_branch)"
    echo "st_version=$(version_from_file "$ST_HOME/package.json")"
    record_processing "설치 폴더 검사 완료 · $([[ "$installation_ready" == 1 ]] && echo '설치 구조 확인' || echo '설치 구조 확인 필요')"

    write_progress 0 "실행 패키지 확인" "SillyTavern에 필요한 Node.js 패키지가 준비되어 있는지 검사하고 있습니다."
    dependencies_status="$([[ -d "$ST_HOME" ]] && dependencies_ready && echo 1 || echo 0)"
    echo "dependencies_ready=$dependencies_status"
    record_processing "실행 패키지 검사 완료 · $([[ "$dependencies_status" == 1 ]] && echo '준비됨' || echo '복구 또는 설치 필요')"

    write_progress 0 "수정 파일 확인" "설치 파일의 로컬 변경 사항을 확인하고 있습니다. 최대 30개를 표시합니다."
    dirty_files="$(git -C "$ST_HOME" status --porcelain 2>/dev/null | head -n 30 || true)"
    dirty_count="$(printf '%s\n' "$dirty_files" | sed '/^$/d' | wc -l)"
    echo "dirty_count=$dirty_count"
    echo "dirty_files_b64=$(printf '%s' "$dirty_files" | base64 -w 0 2>/dev/null || true)"
    record_processing "수정 파일 검사 완료 · 표시할 변경 항목 ${dirty_count}개 (최대 30개)"

    write_progress 0 "서버 포트 확인" "설정한 포트에서 연결을 기다리는 서버가 있는지 확인하고 있습니다."
    port_listening=0
    if command -v ss >/dev/null 2>&1 && ss -ltn 2>/dev/null | awk '{print $4}' | grep -Eq "(^|:)$PORT$"; then port_listening=1; fi
    echo "port_listening=$port_listening"
    record_processing "서버 포트 검사 완료 · $([[ "$port_listening" == 1 ]] && echo '포트 사용 중' || echo '대기 포트 확인되지 않음')"

    write_progress 0 "서버 응답 확인" "현재 서버 주소의 HTTP 응답을 확인하고 있습니다. 최대 2초 동안 확인합니다."
    server_reachable=0
    if curl -sS --max-time 2 -o /dev/null "http://127.0.0.1:$PORT/" 2>/dev/null; then server_reachable=1; fi
    echo "server_reachable=$server_reachable"
    record_processing "서버 HTTP 검사 완료 · $([[ "$server_reachable" == 1 ]] && echo '응답 확인' || echo '응답 없음')"

    write_progress 0 "서버 프로세스 확인" "실행 중인 SillyTavern Node 프로세스 수를 확인하고 있습니다."
    if command -v pgrep >/dev/null 2>&1; then
        node_process_count="$(pgrep -af 'node.*server.js' 2>/dev/null | wc -l || true)"
    else
        node_process_count="$(ps -A 2>/dev/null | grep -c '[n]ode.*server.js' || true)"
    fi
    echo "node_process_count=$node_process_count"
    record_processing "서버 프로세스 검사 완료 · 확인된 프로세스 ${node_process_count:-0}개"

    write_progress 0 "최근 오류 확인" "현재·이전 서버 로그에서 가장 최근의 오류 기록을 확인하고 있습니다."
    last_error="$({ grep -iE 'error|failed|exception|fatal' "$SERVER_LOG" "$PREVIOUS_SERVER_LOG" "$LOG_FILE" 2>/dev/null || true; } | tail -n 1)"
    echo "last_error_b64=$(printf '%s' "$last_error" | base64 -w 0 2>/dev/null || true)"
    record_processing "최근 오류 검사 완료 · $([[ -n "$last_error" ]] && echo '오류 기록 있음 (진단 결과에서 확인)' || echo '오류 기록 없음')"
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
    inspect-install) inspect_install "${2:-}" ;;
    import-install) import_install "${2:-}" ;;
    list-user-folders) list_user_folders ;;
    list-st-files) list_st_files "${2:-}" "${3:-0}" ;;
    read-st-file) read_st_file "${2:-}" ;;
    write-st-file) write_st_file "${2:-}" "${3:-}" "${4:-}" ;;
    create-st-file) create_st_file "${2:-}" ;;
    import-st-file) import_st_file "${2:-}" "${3:-}" ;;
    import-st-folder) import_st_folder "${2:-}" "${3:-}" ;;
    mkdir-st) mkdir_st "${2:-}" ;;
    rename-st) rename_st "${2:-}" "${3:-}" ;;
    delete-st) delete_st "${2:-}" ;;
    restore-st-trash) restore_st_trash "${2:-}" ;;
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
