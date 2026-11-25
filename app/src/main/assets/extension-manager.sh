# Sourced by manager.sh; extension recovery is separate from restore journals.
EXTENSIONS_HELPER="${ST_EXTENSIONS_HELPER:-$(dirname "$MANAGER_SCRIPT_PATH")/extensions-helper.sh}"
EXTENSION_RECOVERY_STATE=none
EXTENSION_RECOVERY_GUARD_FAILED=0
EXTENSION_RECOVERY_OUTPUT=$'extension_recovery=none\nextension_recovery_message_b64='

inspect_extension_recovery() {
    EXTENSION_RECOVERY_STATE=none
    EXTENSION_RECOVERY_OUTPUT=$'extension_recovery=none\nextension_recovery_message_b64='
    [[ -e "$LAUNCHER_HOME/extension-update-journal.json" || -L "$LAUNCHER_HOME/extension-update-journal.json" ]] || return 0
    local output state
    if output="$(bash "$EXTENSIONS_HELPER" inspect-recovery "$ST_HOME" "$LAUNCHER_HOME" 2>/dev/null)"; then
        state="$(printf '%s\n' "$output" | sed -n 's/^extension_recovery=//p')"
        case "$state" in none|pending|blocked) EXTENSION_RECOVERY_STATE="$state"; EXTENSION_RECOVERY_OUTPUT="$output"; return 0 ;; esac
    fi
    EXTENSION_RECOVERY_STATE=blocked
    EXTENSION_RECOVERY_OUTPUT="$(printf 'extension_recovery=blocked\nextension_recovery_message_b64=%s' "$(printf '%s' '확장 교체 복구 기록을 안전하게 읽지 못했습니다. 확장 관리에서 점검해 주세요.' | base64 -w 0)")"
}

guard_extension_recovery() {
    case "$CURRENT_OPERATION" in
        server-config-write) ;;
        install|start|restart|prepare-persistent-start|server-task|finish-persistent-start|backup|import-backup|import-install|restore|restore-selected|change-version|repair|reset-installation|update|update-preflight|switch-branch|save-lan-security|write-st-file|create-st-file|import-st-file|import-st-folder|mkdir-st|rename-st|delete-st|restore-st-trash|delete-backup|safety-export|safety-delete|safety-restore|extension-update|extension-disable|extension-enable|extension-trash) ;;
        *) return 0 ;;
    esac
    inspect_extension_recovery
    [[ "$EXTENSION_RECOVERY_STATE" == none ]] && return 0
    EXTENSION_RECOVERY_GUARD_FAILED=1
    echo "error_code=EXTENSION_RECOVERY_PENDING" >&2
    echo "중단된 확장 교체가 있습니다. 확장 관리에서 점검·복구한 뒤 실행해 주세요." >&2
    return 76
}

extensions_list() {
    bash "$EXTENSIONS_HELPER" list "$ST_HOME" "$LAUNCHER_HOME" "${1:-0}"
}

extension_mutate() {
    local action="$1" id="${2:-}" revision="${3:-}" code=0
    is_running && { echo "확장을 변경하기 전에 서버를 종료해 주세요." >&2; exit 10; }
    begin_operation "extension-$action"
    ensure_restore_target_stopped
    inspect_restore_recovery
    (( ${#RESTORE_PENDING_WORKS[@]} == 0 )) || { echo "먼저 중단된 설치 작업을 점검·복구해 주세요." >&2; exit 25; }
    write_progress 0 "확장 관리" "선택한 확장의 상태를 확인하고 요청한 변경을 준비합니다."
    # The helper cooperatively observes cancellation. Never SIGKILL its tiny
    # durable-receipt/rename section; its own checkpoints defer cancellation.
    ST_CANCEL_REQUEST_FILE="$(cancellation_request_path)" ST_CANCEL_REQUEST_ID_FILE="$RUN_DIR/cancel-request-${ST_OPERATION_REQUEST_ID:-}" \
        bash "$EXTENSIONS_HELPER" "$action" "$ST_HOME" "$LAUNCHER_HOME" "$id" "$revision" || code=$?
    (( code == 0 )) || exit "$code"
    CANCELLATION_COMMITTED=1
    write_progress 100 "확장 작업 완료" "요청한 확장 변경을 완료했습니다." success
}

save_lan_security() {
    local port="${1:-}" token="${2:-}" code=0 result=""
    [[ "$port" =~ ^[0-9]{1,5}$ && "$token" =~ ^[0-9a-f]{64}$ ]] || exit 90
    (( 10#$port >= 1 && 10#$port <= 65535 )) || exit 90
    is_running && { echo "연결 보안을 변경하기 전에 서버를 종료해 주세요." >&2; exit 10; }
    begin_operation save-lan-security
    ensure_restore_target_stopped
    inspect_restore_recovery
    (( ${#RESTORE_PENDING_WORKS[@]} == 0 )) || exit 25
    write_progress 0 "연결 보안 설정" "일회성 로컬 연결로 설정을 받아 저장합니다. 비밀번호는 기록하지 않습니다."
    # Bound the transfer, keep credentials out of shell variables, argv and logs,
    # and defer process cancellation around the helper's atomic publication.
    enter_cancellation_guard
    result="$(curl --disable --noproxy '*' --proto '=http' --connect-timeout 2 --max-time 12 --max-filesize 16384 --fail --silent \
        "http://127.0.0.1:$port/$token" |
        ST_CANCEL_REQUEST_FILE="$(cancellation_request_path)" ST_CANCEL_REQUEST_ID_FILE="$RUN_DIR/cancel-request-${ST_OPERATION_REQUEST_ID:-}" \
        bash "$(dirname "$MANAGER_SCRIPT_PATH")/lan-security.sh" "$ST_HOME" "$LAUNCHER_HOME")" || code=$?
    if grep -Fxq saved=1 <<< "$result"; then
        code=0
    else
        case "$code" in 90|91|92|93|94|95|96|97|130) ;; *) code=93 ;; esac
    fi
    (( code == 0 )) || exit "$code"
    printf '%s\n' "$result"
    CANCELLATION_COMMITTED=1
    leave_cancellation_guard
    write_progress 100 "연결 보안 저장 완료" "다음 서버 실행부터 연결 보안 설정을 적용합니다." success
}
