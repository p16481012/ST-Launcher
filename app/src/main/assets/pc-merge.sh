# Sourced by manager.sh. PC import never replaces a live file or user.
pc_merge_data() {
    ST_PC_PROGRESS="$PROGRESS_FILE" ST_PC_LOG="$LOG_FILE" ST_PC_OPERATION="$CURRENT_OPERATION" ST_PC_STARTED="$OPERATION_STARTED_EPOCH" \
        bash "$(dirname "$MANAGER_SCRIPT_PATH")/pc-merge-data.sh" "$@"
}

pc_merge_inspect_pending() {
    local pending="$BACKUP_DIR/pc-merge-pending"
    if [[ -e "$pending" || -L "$pending" ]]; then
        RESTORE_PENDING_WORKS+=("$pending")
        if [[ -L "$pending" || ! -d "$pending" || ! -f "$pending/journal.json" || -L "$pending/journal.json" ]]; then RESTORE_PENDING_BLOCKED=1; fi
    fi
    return 0
}

pc_merge_rollback_pending() {
    local pending="$BACKUP_DIR/pc-merge-pending" garbage
    [[ -d "$pending" && ! -L "$pending" ]] || return 153
    transaction_writers_quiet "$pending" || { echo "이전 합치기 작업이 종료되었는지 아직 확인할 수 없어요." >&2; return 153; }
    if [[ -e "$pending/committed.json" || -L "$pending/committed.json" ]]; then
        # No writer is needed to validate a completed transaction. Do not replay it.
        pc_merge_data verify-recorded-commit "$ST_HOME" "$pending" || return 153
    else
        GIT_TRANSACTION_ACTIVE=1 GIT_TRANSACTION_DIR="$pending" run_transaction_command bash "$(dirname "$MANAGER_SCRIPT_PATH")/pc-merge-data.sh" recover "$ST_HOME" "$pending" || return 153
        transaction_writers_quiet "$pending" || return 153
    fi
    # Free a potentially large extraction interrupted by a manager/OS restart.
    pc_merge_data discard-work "$ST_HOME" "$pending" || return 153
    # Never leave a half-deleted journal visible to recovery after power loss.
    garbage="$(mktemp -d "$BACKUP_DIR/pc-merge-done.XXXXXXXX")" || return 153
    mv -T -- "$pending" "$garbage" || return 153
    sync -f "$BACKUP_DIR" 2>/dev/null || sync || return 153
    rm -rf -- "$garbage" || true
    return 0
}

pc_merge_check_archive() {
    [[ "${1:-}" =~ ^SillyTavern-Import-[A-Za-z0-9-]+\.zip$ && "${2:-}" =~ ^[a-f0-9]{64}$ ]] || return 72
    [[ "$(pc_merge_data stamp "$DOWNLOAD_DIR/$1")" == "$2" ]] || { echo "선택한 ZIP이 바뀌었어요. 다시 선택해 주세요." >&2; return 72; }
}

# A killed process can leave gigabytes of CRC-checked files *before* a live
# transaction is published. Only sweep our marked private staging inode after
# acquiring the shared lock and proving both the owner and its extractor dead.
pc_merge_scrub_staging() {
    local old marker oldpid oldstart oldid extra
    [[ ! -e "$BACKUP_DIR/pc-merge-pending" && ! -L "$BACKUP_DIR/pc-merge-pending" ]] || return 0
    for old in "$BACKUP_DIR"/pc-merge-work-*; do
        [[ -d "$old" && ! -L "$old" ]] || continue
        marker="$old/pc-stage-owner"
        [[ -f "$marker" && ! -L "$marker" && "$(stat -c %s "$marker")" -le 160 ]] || continue
        read -r oldpid oldstart oldid extra < "$marker" || continue
        [[ -z "$extra" && "$oldpid" =~ ^[1-9][0-9]{0,9}$ && "$oldstart" =~ ^[1-9][0-9]{0,17}$ &&
           "$old" == "$BACKUP_DIR/pc-merge-work-$oldpid" && "$oldid" == "$(stat -c '%d:%i' "$old")" ]] || continue
        process_identity_live "$oldpid" "$oldstart" && continue
        transaction_writers_quiet "$old" >/dev/null 2>&1 || continue
        rm -rf -- "$old" || true
    done
    return 0
}

pc_merge_command() {
    local op="$1" name="${2:-}" archive stamp work index cache expected="" target_revision="" mapping="" free code=0
    local -x ST_PC_INCLUDE_AVATARS=1
    [[ "$name" =~ ^SillyTavern-Import-[A-Za-z0-9-]+\.zip$ ]] || return 64
    archive="$DOWNLOAD_DIR/$name"
    if [[ "$op" == import-preview ]]; then
        [[ $# -le 3 || ( $# -eq 4 && "${4:-}" == replace-impact ) ||
            ( ( $# -eq 5 || ( $# -eq 6 && "${6:-}" == skip-avatars ) ) && ( "$4" == replace-impact || "$4" == merge-preview ) && "$5" =~ ^[a-f0-9]{64}$ ) ]] || return 64
        mapping="${3:-}"
        expected="${5:-}"
        begin_operation "$op"
    elif [[ "$op" == import-merge ]]; then
        [[ $# -ge 4 && ( $# -le 5 || ( $# -eq 6 && "${6:-}" == skip-avatars ) ) && "$3" =~ ^[a-f0-9]{64}$ && "$4" =~ ^[a-f0-9]{64}$ ]] || return 64
        expected="$3"; target_revision="$4"; mapping="${5:-}"
        begin_st_file_mutation "$op"
    else return 64; fi
    [[ "${6:-}" != skip-avatars ]] || ST_PC_INCLUDE_AVATARS=0
    if [[ ! -f "$archive" || -L "$archive" ]]; then
        if [[ -n "$expected" ]]; then echo "선택한 ZIP을 다시 확인할 수 없어요. 원본 ZIP을 다시 선택해 주세요." >&2; return 72; fi
        return 6
    fi
    pc_merge_scrub_staging
    work="$BACKUP_DIR/pc-merge-work-$$"
    mkdir -m 700 -- "$work" || return 25
    track_current_work_dir "$work"
    stamp="$(pc_merge_data stamp "$archive")" || return $?
    if [[ -n "$expected" && "$stamp" != "$expected" ]]; then echo "미리보기 이후 ZIP이 바뀌었어요." >&2; return 72; fi
    # Keep a single private validated index across account choices / preview and apply.
    cache="$RUN_DIR/pc-merge-index.json"; index="$cache"
    if [[ ! -f "$cache" || -L "$cache" || "$(cat "$RUN_DIR/pc-merge-index.stamp" 2>/dev/null || true)" != "$stamp" ]]; then
        write_progress 5 "ZIP 목록 읽기" "압축을 풀지 않고 경로와 크기를 확인해요."
        validate_restore_archive "$archive" "$work/index.json" >/dev/null || return $?
        pc_merge_check_archive "$name" "$stamp" || return $?
        rm -f -- "$RUN_DIR/pc-merge-index.stamp"
        mv -fT -- "$work/index.json" "$cache" || return 20
        printf '%s\n' "$stamp" > "$RUN_DIR/pc-merge-index.stamp"
    fi
    write_progress 10 "넣을 파일 확인" "기존 파일과 사용자는 유지하고, 새로 넣을 항목을 확인해요."
    if [[ "$op" == import-preview ]]; then
        free="$(available_bytes_for "$BACKUP_DIR")" || free=""
        local impact=0
        [[ "${4:-}" != replace-impact ]] || impact=1
        ST_PC_REPLACE_IMPACT="$impact" ST_PC_AVAILABLE_BYTES="$free" run_cancellable pc_merge_data plan "$ST_HOME" "$archive" "$index" "$mapping" "$stamp" || return $?
        write_progress 100 "ZIP 확인 완료" "아직 휴대폰 데이터는 바꾸지 않았어요." success
        return 0
    fi
    free="$(available_bytes_for "$BACKUP_DIR")" || return 29
    run_cancellable pc_merge_data plan "$ST_HOME" "$archive" "$index" "$mapping" "$stamp" "$target_revision" "$work" "$free" || return $?
    mkdir -m 700 -- "$work/files" "$work/guard"
    printf '%s %s %s\n' "$$" "$(process_start_ticks "$$")" "$(stat -c '%d:%i' "$work")" > "$work/pc-stage-owner" || return 150
    GIT_TRANSACTION_ACTIVE=1 GIT_TRANSACTION_DIR="$work" run_transaction_command env ST_PROGRESS_FILE="$PROGRESS_FILE" ST_PROGRESS_LOG="$LOG_FILE" ST_PROGRESS_HISTORY="$HISTORY_LOG" ST_PROGRESS_PHASE="새 파일 압축 풀기" ST_PROGRESS_DETAIL="실제로 넣을 파일만 한 번 풀고 CRC를 확인해요." ST_PROGRESS_OPERATION="$op" ST_OPERATION_STARTED_EPOCH="$OPERATION_STARTED_EPOCH" bash "$ARCHIVE_PROGRESS_HELPER" extract "$archive" "$work/files" "$work/selected.json" >> "$LOG_FILE" || return $?
    pc_merge_check_archive "$name" "$stamp" || return $?
    local request_id
    request_id="$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')" || return 153
    [[ "$request_id" =~ ^[0-9a-f]{32}$ ]] || return 153
    run_cancellable pc_merge_data prepare "$ST_HOME" "$work" "$request_id" || return $?
    run_cancellable pc_merge_data recheck "$ST_HOME" "$archive" "$index" "$mapping" "$stamp" "$target_revision" || return $?
    if is_running || installation_server_running "$ST_HOME" || curl -sS --connect-timeout 1 --max-time 1 -o /dev/null "http://127.0.0.1:$PORT/" 2>/dev/null; then return 10; fi
    local pending="$BACKUP_DIR/pc-merge-pending"
    [[ ! -e "$pending" && ! -L "$pending" ]] || return 153
    write_progress 85 "새 파일 넣기" "기존 파일은 덮어쓰지 않아요."
    enter_cancellation_guard
    mv -Tn -- "$work/guard" "$pending" && [[ ! -e "$work/guard" ]] || return 153
    sync -f "$BACKUP_DIR" 2>/dev/null || sync || return 153
    GIT_TRANSACTION_ACTIVE=1 GIT_TRANSACTION_DIR="$pending" run_transaction_command env ST_PC_PROGRESS="$PROGRESS_FILE" ST_PC_LOG="$LOG_FILE" ST_PC_OPERATION="$op" ST_PC_STARTED="$OPERATION_STARTED_EPOCH" bash "$(dirname "$MANAGER_SCRIPT_PATH")/pc-merge-data.sh" apply "$ST_HOME" "$pending" > /dev/null || code=$?
    if (( code != 0 )) && [[ ! -e "$pending/committed.json" && ! -L "$pending/committed.json" ]]; then
        pc_merge_rollback_pending || { RESTORE_RECOVERY_RETAINED=1; return 153; }
        echo "합치지 못해 이번에 추가한 파일을 되돌렸어요. 기존 파일은 바꾸지 않았어요." >&2
        return 152
    fi
    # Don't label an ambiguous/mismatched receipt a success, even if the worker exited zero.
    # Conversely, loss of stdout or a worker dying after a durable publication isn't a rollback.
    pc_merge_data verify-current-commit "$ST_HOME" "$pending" "$work" "$request_id" || { RESTORE_RECOVERY_RETAINED=1; return 153; }
    CANCELLATION_COMMITTED=1
    if ! pc_merge_rollback_pending; then
        RESTORE_RECOVERY_RETAINED=1
        leave_cancellation_guard
        printf 'cleanup_pending=1\n'
        write_progress 100 "ZIP 합치기 완료 · 정리 대기" "파일은 이미 추가됐어요. 남은 임시 파일은 안전을 확인한 뒤 정리해요. ZIP을 다시 가져올 필요는 없어요." success
        return 0
    fi
    leave_cancellation_guard
    printf 'cleanup_pending=0\n'
    write_progress 100 "ZIP 합치기 완료" "기존 파일과 사용자를 그대로 유지했어요." success
    rm -f -- "$cache" "$RUN_DIR/pc-merge-index.stamp"
}
