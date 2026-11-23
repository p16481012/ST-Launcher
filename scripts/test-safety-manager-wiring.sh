#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/st-safety-wiring.XXXXXX")"
trap '[[ "$TEST_ROOT" == */st-safety-wiring.* && "$TEST_ROOT" != / ]] && rm -rf -- "$TEST_ROOT"' EXIT
export ST_HOME="$TEST_ROOT/install" ST_LAUNCHER_HOME="$TEST_ROOT/launcher" ST_DOWNLOAD_DIR="$TEST_ROOT/downloads"
mkdir -p "$ST_HOME" "$ST_DOWNLOAD_DIR"
source "$ROOT_DIR/app/src/main/assets/manager.sh"
eval "$(declare -f begin_operation | sed '1s/begin_operation/real_begin_operation/')"
fail() { echo "FAIL: $*" >&2; exit 1; }
began=0 pending=0 calls=0 restored=0
trash=1234567890-12345678-1234-1234-1234-123456789abc
begin_operation() { began=$((began + 1)); CURRENT_OPERATION="$1"; }
inspect_restore_recovery() { RESTORE_PENDING_WORKS=(); (( pending == 0 )) || RESTORE_PENDING_WORKS=(blocked); }
write_progress() { :; }
is_running() { return 1; }
installation_server_running() { return 1; }
curl() { return 1; }
st_file_action() { [[ "$1" == untrash && "$2" == "$trash" ]] || fail 'wrong trash target'; restored=1; }
bash() {
    [[ "$1" == "$SAFETY_INVENTORY_HELPER" && "$3" == "$LAUNCHER_HOME" && "$4" == "$ST_HOME" && "$5" == "$DOWNLOAD_DIR" ]] || fail 'wrong helper roots'
    [[ "$2" == list || "$began" -gt 0 ]] || fail 'mutation skipped lock'
    calls=$((calls + 1))
    case "$2" in
        list) [[ "$6" == 2 ]] || fail 'cursor lost' ;;
        export|delete|validate-trash)
            [[ "$6" == encoded-id && "$7" == revision ]] || fail 'selection revision lost'
            [[ "$2" != validate-trash ]] || printf 'trash_id=%s\n' "$trash"
            ;;
        *) fail 'unsupported helper action' ;;
    esac
}
safety_list 2
[[ "$began" == 0 && "$calls" == 1 ]] || fail 'readonly list mutates operation state'
safety_export encoded-id revision
[[ "$began" == 1 && "$CURRENT_OPERATION" == safety-export ]] || fail 'export missing lock'
safety_delete encoded-id revision
[[ "$began" == 2 && "$CURRENT_OPERATION" == safety-delete ]] || fail 'delete missing lock'
safety_restore encoded-id revision
[[ "$restored" == 1 && "$began" == 3 ]] || fail 'validated trash not restored'
pending=1
before="$calls"
if safety_export encoded-id revision >/dev/null 2>&1; then fail 'pending recovery export allowed'; else [[ $? == 67 ]] || fail 'wrong recovery error'; fi
[[ "$calls" == "$before" ]] || fail 'helper ran before recovery rejection'
pending=0
is_running() { return 0; }
if safety_restore encoded-id revision >/dev/null 2>&1; then fail 'live server trash restore allowed'; else [[ $? == 10 ]] || fail 'wrong live server error'; fi
[[ "$(manager_error_code safety-export 65)" == SAFETY_ITEM_CHANGED ]] || fail 'safety failure misclassified'
[[ "$(manager_error_code backup 65)" == ARCHIVE_TOOL_INSTALL_FAILED ]] || fail 'existing archive failure changed'
(
    acquire_operation() { :; }
    recover_pending_restore() { fail 'safety management started automatic restoration'; }
    reset_current_log() { :; }
    record_activity() { :; }
    start_progress_monitor() { :; }
    real_begin_operation safety-export
    real_begin_operation safety-delete
    real_begin_operation safety-restore
)
echo 'PASS: safety operations lock/recovery/server guards, revisions and scoped error codes'
