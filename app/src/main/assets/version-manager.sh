# Sourced by manager.sh. Ordinary update keeps its existing fast Git path.
VERSION_STATE_HELPER="${ST_VERSION_STATE_HELPER:-$(dirname "$MANAGER_SCRIPT_PATH")/version-state.sh}"

previous_versions() {
    bash "$VERSION_STATE_HELPER" versions "$ST_HOME" "$LAUNCHER_HOME" "$DOWNLOAD_DIR"
}

version_snapshots() {
    bash "$VERSION_STATE_HELPER" snapshots "$ST_HOME" "$LAUNCHER_HOME" "$DOWNLOAD_DIR"
}

change_version() {
    local target="${1:-}" current="${2:-}" installation_revision="${3:-}" mode="${4:-}" snapshot="${5:-}" snapshot_revision="${6:-}"
    [[ "$mode" == keep-current || "$mode" == snapshot ]] || exit 64
    [[ "$target" =~ ^[a-f0-9]{40}$ && "$current" =~ ^[a-f0-9]{40}$ && "$installation_revision" =~ ^[a-f0-9]{64}$ ]] || exit 64
    [[ "$mode" != snapshot || ( "$snapshot_revision" =~ ^[a-f0-9]{64}$ && -n "$snapshot" ) ]] || exit 64
    is_running && { echo "버전을 변경하기 전에 서버를 종료해 주세요." >&2; exit 10; }
    begin_operation change-version
    ensure_restore_target_stopped
    bash "$VERSION_STATE_HELPER" validate-target "$ST_HOME" "$LAUNCHER_HOME" "$DOWNLOAD_DIR" "$target" "$current" "$installation_revision" || exit $?
    if [[ "$mode" == snapshot ]]; then
        bash "$VERSION_STATE_HELPER" verify-snapshot "$ST_HOME" "$LAUNCHER_HOME" "$DOWNLOAD_DIR" "$snapshot" "$snapshot_revision" "$target" || exit $?
    fi
    write_progress 0 "현재 설치 전체 백업" "버전 변경 전 현재 채팅·캐릭터·설정·API 키를 포함한 전체 ZIP을 Download에 보존합니다."
    BACKUP_PARENT_OPERATION=change-version
    backup_selected full 1 ""
    BACKUP_PARENT_OPERATION=""
    local current_backup="$BACKUP_CREATED_PATH" backup_work="$CURRENT_WORK_DIR" current_receipt current_revision source expected_source_commit current_branch_name
    current_branch_name="$(current_branch)"
    [[ "$current_branch_name" == release || "$current_branch_name" == staging ]] || exit 82
    [[ -n "$current_backup" && -f "$current_backup" && ! -L "$current_backup" ]] || exit 83
    current_receipt="$(bash "$VERSION_STATE_HELPER" snapshot-info "$ST_HOME" "$LAUNCHER_HOME" "$DOWNLOAD_DIR" "${current_backup##*/}")" || exit $?
    current_revision="$(printf '%s\n' "$current_receipt" | sed -n 's/^snapshot_revision=//p')"
    [[ "$(printf '%s\n' "$current_receipt" | sed -n 's/^snapshot_commit=//p')" == "$current" ]] || exit 81
    echo "protected_backup_b64=$(printf '%s' "${current_backup##*/}" | base64 -w 0)"
    bash "$VERSION_STATE_HELPER" validate-target "$ST_HOME" "$LAUNCHER_HOME" "$DOWNLOAD_DIR" "$target" "$current" "$installation_revision" >/dev/null || exit $?
    if [[ "$mode" == keep-current ]]; then
        source="$current_backup"; snapshot_revision="$current_revision"; expected_source_commit="$current"
    else
        source="$DOWNLOAD_DIR/$snapshot"; expected_source_commit="$target"
        bash "$VERSION_STATE_HELPER" verify-snapshot "$ST_HOME" "$LAUNCHER_HOME" "$DOWNLOAD_DIR" "$snapshot" "$snapshot_revision" "$target" >/dev/null || exit $?
    fi
    selection_new_work || exit $?
    local work="$CURRENT_WORK_DIR" extracted="$CURRENT_WORK_DIR/extracted" manifest source_commit
    [[ "$backup_work" == "$BACKUP_DIR/backup-work-$$" && -f "$backup_work/source-plan.json" && ! -L "$backup_work/source-plan.json" ]] || exit 83
    VERSION_SOURCE_PLAN="$work/pre-version-source-plan.json"
    mv -- "$backup_work/source-plan.json" "$VERSION_SOURCE_PLAN" || exit 83
    rm -rf -- "$backup_work"
    write_progress 0 "선택 버전 임시 준비" "검증된 전체 ZIP을 한 번 해제합니다. 아직 현재 설치는 바꾸지 않습니다."
    extract_restore_archive "$source" "$extracted"
    bash "$VERSION_STATE_HELPER" verify-snapshot "$ST_HOME" "$LAUNCHER_HOME" "$DOWNLOAD_DIR" "${source##*/}" "$snapshot_revision" "$expected_source_commit" >/dev/null || exit $?
    manifest="$extracted/.st-launcher-manifest"
    backup_metadata_valid "$(cat "$manifest" 2>/dev/null || true)" || exit 83
    [[ "$(manifest_value "$manifest" items)" == full && "$(manifest_value "$manifest" secrets)" == 1 ]] || exit 83
    valid_full_installation "$extracted" || exit 83
    sanitize_imported_git "$extracted" || exit 83
    source_commit="$(GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null git -C "$extracted" rev-parse HEAD)" || exit 83
    [[ "$source_commit" == "$expected_source_commit" ]] || { echo "전체 ZIP의 실제 Git 커밋과 기록이 다릅니다. 현재 설치는 변경하지 않았습니다." >&2; exit 83; }
    if [[ "$mode" == keep-current ]]; then
        prepare_version_staging "$extracted" "$work" "$target" || exit $?
    else
        # A matching commit may occur on both branches. Choosing snapshot data
        # must not silently switch the user's release/staging preference.
        GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null git -C "$extracted" update-ref "refs/heads/$current_branch_name" "$target" || exit 82
        GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null git -C "$extracted" symbolic-ref HEAD "refs/heads/$current_branch_name" || exit 82
    fi
    validate_install_tree "$extracted" || exit $?
    validate_install_runtime "$extracted" || exit $?
    [[ "$(GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null git -C "$extracted" rev-parse HEAD)" == "$target" ]] || exit 82
    bash "$VERSION_STATE_HELPER" validate-target "$ST_HOME" "$LAUNCHER_HOME" "$DOWNLOAD_DIR" "$target" "$current" "$installation_revision" >/dev/null || exit $?
    RESTORE_VALIDATE_SERVER=1
    restore_extracted_tree "$extracted" "${source##*/}"
    echo "version_changed=1"
    echo "target_commit=$target"
    echo "data_mode=$mode"
}

prepare_version_staging() {
    local extracted="$1" work="$2" target="$3" item
    [[ "$work" == "$CURRENT_WORK_DIR" && "$extracted" == "$work/extracted" && ! -L "$extracted" ]] || return 83
    mkdir -- "$work/preserved-user-state" || return 83
    # Preserve current data/config explicitly OUTSIDE checkout's tree. No git
    # reset, filter, or tracked historical path may replace these current files.
    for item in data config.yaml .st-launcher-manifest; do
        if [[ -e "$extracted/$item" || -L "$extracted/$item" ]]; then
            [[ ! -L "$extracted/$item" ]] || return 83
            mv -- "$extracted/$item" "$work/preserved-user-state/$item" || return 83
        fi
    done
    write_progress 0 "이전 프로그램 버전 준비" "현재 사용자 데이터와 config.yaml을 분리 보존한 뒤 임시 코드만 변경합니다."
    GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null run_cancellable git -c core.hooksPath=/dev/null -c core.fsmonitor=false -C "$extracted" reset --hard "$target" >> "$LOG_FILE" 2>&1 || return 82
    for item in data config.yaml .st-launcher-manifest; do
        rm -rf -- "$extracted/$item" || return 83
        if [[ -e "$work/preserved-user-state/$item" ]]; then mv -- "$work/preserved-user-state/$item" "$extracted/$item" || return 83; fi
    done
    rmdir -- "$work/preserved-user-state" || return 83
    return 0
}
