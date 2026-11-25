# Sourced by manager.sh. Uses its operation lock and existing partial journal.
SELECTIVE_RESTORE_HELPER="${ST_SELECTIVE_RESTORE_HELPER:-$(dirname "$MANAGER_SCRIPT_PATH")/selective-restore-data.sh}"

selective_restore_journal_path_safe() {
    local relative="$1" profile parent
    if [[ "$relative" =~ ^data/([^/]+)/characters/[^/]+\.[pP][nN][gG]$ ||
          "$relative" =~ ^data/([^/]+)/chats/[^/]+/[^/]+\.[jJ][sS][oO][nN][lL]$ ]]; then
        profile="${BASH_REMATCH[1]}"
    else return 1; fi
    [[ -d "$ST_HOME/data/$profile" ]] || return 1
    restore_destination_safe "$relative" || return 1
    parent="$(dirname "$ST_HOME/$relative")"
    while [[ "$parent" != "$ST_HOME" ]]; do
        [[ ! -L "$parent" && ( ! -e "$parent" || -d "$parent" ) ]] || return 1
        [[ "$parent" == "$ST_HOME/"* ]] || return 1
        parent="$(dirname "$parent")"
    done
}

verify_selection_recovery_guard() {
    local paths
    paths="$(printf '%s\n' "${RESTORE_AFFECTED_PATHS[@]}" | base64 -w 0)" || return 1
    bash "$SELECTIVE_RESTORE_HELPER" verify-guard "$CURRENT_WORK_DIR/selection-guard.json" - "$ST_HOME" "$paths" >/dev/null
}

selection_archive() {
    valid_backup_filename "$1" || return 64
    [[ -f "$DOWNLOAD_DIR/$1" && ! -L "$DOWNLOAD_DIR/$1" ]] || return 6
    [[ -d "$ST_HOME/data" && -f "$ST_HOME/package.json" && -f "$ST_HOME/server.js" && ! -L "$ST_HOME" ]] || return 8
    command -v node >/dev/null 2>&1 || return 62
}

selection_new_work() {
    local work="$BACKUP_DIR/restore-work-$$"
    mkdir -m 700 -- "$work" || return 25
    track_current_work_dir "$work"
    mkdir -- "$work/extracted" "$work/rollback" || return 25
}

restore_selection_list() {
    local file_name="${1:-}" cursor="${2:-0}" expanded
    selection_archive "$file_name" || exit $?
    begin_operation restore-selection-list
    selection_new_work || exit $?
    write_progress 0 "개별 복원 목록 확인" "압축을 풀지 않고 캐릭터·채팅 항목과 기존 파일 충돌을 확인합니다."
    expanded="$(validate_restore_archive "$DOWNLOAD_DIR/$file_name" "$CURRENT_WORK_DIR/archive-index.json")" || exit $?
    bash "$SELECTIVE_RESTORE_HELPER" list "$CURRENT_WORK_DIR/archive-index.json" "$DOWNLOAD_DIR/$file_name" "$ST_HOME" "$cursor" || exit $?
    cleanup_current_work_dir
    write_progress 100 "목록 확인 완료" "현재 데이터는 변경하지 않았습니다." success
}

restore_selected() {
    local file_name="${1:-}" archive_revision="${2:-}" installation_revision="${3:-}" request="${4:-}"
    selection_archive "$file_name" || exit $?
    is_running && { echo "개별 복원 전에 서버를 종료해 주세요." >&2; exit 10; }
    begin_operation restore-selected
    selection_new_work || exit $?
    local work="$CURRENT_WORK_DIR" archive="$DOWNLOAD_DIR/$file_name" expanded plan="$CURRENT_WORK_DIR/selection.json" summary count bytes old_bytes free required
    write_progress 0 "선택 항목 재확인" "백업·설치 위치·현재 파일이 선택 이후 바뀌지 않았는지 확인합니다."
    expanded="$(validate_restore_archive "$archive" "$work/archive-index.json")" || exit $?
    summary="$(bash "$SELECTIVE_RESTORE_HELPER" plan "$work/archive-index.json" "$archive" "$ST_HOME" "$request" "$plan" "$archive_revision" "$installation_revision")" || exit $?
    printf '%s\n' "$summary"
    count="$(printf '%s\n' "$summary" | sed -n 's/^selection_count=//p')"
    if [[ "$count" == 0 ]]; then
        cleanup_current_work_dir
        write_progress 100 "개별 복원 완료" "같은 이름의 기존 파일을 모두 유지했습니다." success
        return 0
    fi
    bytes="$(unsigned_decimal "$(printf '%s\n' "$summary" | sed -n 's/^selection_bytes=//p')")" || exit 29
    old_bytes="$(unsigned_decimal "$(printf '%s\n' "$summary" | sed -n 's/^selection_protected_bytes=//p')")" || exit 29
    free="$(available_bytes_for "$BACKUP_DIR")" || { echo "개별 복원 전에 여유 공간을 확인할 수 없어 중단했습니다." >&2; exit 29; }
    required=$((bytes + old_bytes + 268435456))
    (( required > bytes )) || exit 29
    (( free >= required )) || { echo "선택 항목을 복원할 저장 공간이 부족합니다. 필요: ${required}바이트, 여유: ${free}바이트" >&2; exit 29; }
    [[ "$(stat -c %d "$work/extracted")" == "$(stat -c %d "$ST_HOME")" ]] || exit 25
    measured_archive "선택 파일 압축 해제" "선택한 캐릭터·채팅 파일만 한 번 해제하고 CRC를 검증합니다." extract "$archive" "$work/extracted" "$plan.index" >> "$LOG_FILE" 2>&1 || exit $?
    apply_selected_restore "$plan" "$work/extracted"
    echo "restored=$file_name"
}

apply_selected_restore() {
    local plan="$1" extracted="$2" work="$CURRENT_WORK_DIR" rollback="$CURRENT_WORK_DIR/rollback" relative
    ensure_restore_target_stopped
    bash "$SELECTIVE_RESTORE_HELPER" verify-targets "$plan" - "$ST_HOME" >/dev/null || exit $?
    local -a affected=()
    mapfile -t affected < <(node -e 'const p=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")); for(const x of p.items) console.log(x.path)' "$plan")
    (( ${#affected[@]} > 0 && ${#affected[@]} <= 100 )) || exit 75
    prepare_restore_transaction partial "$rollback"
    normalize_restore_paths "${affected[@]}"
    for relative in "${RESTORE_AFFECTED_PATHS[@]}"; do
        restore_destination_safe "$relative" || exit 75
        [[ -f "$extracted/$relative" && ! -L "$extracted/$relative" ]] || exit 24
        if [[ -e "$ST_HOME/$relative" || -L "$ST_HOME/$relative" ]]; then
            mkdir -p -- "$rollback/$(dirname "$relative")" || exit 25
            COPY_PHASE="기존 파일 보호 · $relative" measured_copy "$ST_HOME/$relative" "$rollback/$relative" || exit 25
            RESTORE_ORIGINAL_PATHS+=("$relative")
        fi
    done
    # Recheck after copying protected originals, before the first live change.
    bash "$SELECTIVE_RESTORE_HELPER" verify-targets "$plan" - "$ST_HOME" >/dev/null || exit $?
    ensure_restore_target_stopped
    bash "$SELECTIVE_RESTORE_HELPER" prepare-guard "$plan" - "$ST_HOME" "" "$extracted" || exit $?
    RESTORE_SELECTION_GUARD=1
    write_restore_journal || exit 25
    RESTORE_TRANSACTION_ACTIVE=1
    enter_cancellation_guard
    for relative in "${RESTORE_AFFECTED_PATHS[@]}"; do
        write_progress 0 "선택 파일 적용" "$relative"
        selective_restore_journal_path_safe "$relative" || exit 25
        # Same-filesystem rename consumes the one extracted copy. The journal
        # already owns protected originals and covers every selected path.
        run_transaction_command bash "$SELECTIVE_RESTORE_HELPER" apply-item "$plan" - "$ST_HOME" "$relative" "$extracted" >> "$LOG_FILE" 2>&1 || exit $?
        [[ -f "$ST_HOME/$relative" && ! -L "$ST_HOME/$relative" ]] || exit 27
    done
    leave_cancellation_guard
    commit_restore_transaction || exit 25
    CANCELLATION_COMMITTED=1
    enter_cancellation_guard
    cleanup_current_work_dir
    write_progress 100 "개별 복원 완료" "선택한 캐릭터·채팅만 복원했습니다. 나머지 데이터와 API 키는 유지했습니다." success
}
