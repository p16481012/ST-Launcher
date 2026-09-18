#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/st-manager-progress.XXXXXX")"
cleanup() {
    if declare -F stop_progress_monitor >/dev/null; then stop_progress_monitor; fi
    [[ "$TEST_ROOT" == */st-manager-progress.* && "$TEST_ROOT" != / ]] && rm -rf -- "$TEST_ROOT"
}
trap cleanup EXIT
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
value() { sed -n "s/^$2=//p" "$1" 2>/dev/null | head -n 1; }
wait_greater() {
    local file="$1" key="$2" previous="$3" current attempt
    for ((attempt = 0; attempt < 80; attempt++)); do
        current="$(value "$file" "$key" || true)"
        if [[ "$current" =~ ^[0-9]+$ ]] && (( current > previous )); then return 0; fi
        sleep 0.1
    done
    fail "$key did not advance beyond $previous"
}

export ST_HOME="$TEST_ROOT/install"
export ST_LAUNCHER_HOME="$TEST_ROOT/launcher"
export ST_DOWNLOAD_DIR="$TEST_ROOT/downloads"
export PREFIX="$TEST_ROOT/prefix"
source "$ROOT_DIR/app/src/main/assets/manager.sh"

CURRENT_OPERATION=restore
OPERATION_STARTED_EPOCH=12345
write_progress 26 "구조 확인" "구조를 확인하고 있습니다."
[[ "$(value "$PROGRESS_FILE" operation_started_at)" == 12345 ]] || fail "operation start is missing"
phase_started="$(value "$PROGRESS_FILE" phase_started_at)"
[[ "$phase_started" =~ ^[0-9]+$ ]] || fail "phase start is missing"
[[ "$(value "$PROGRESS_FILE" progress_mode)" == indeterminate ]] || fail "legacy stage percent was presented as measured"
before_log="$(< "$LOG_FILE")"
before_history="$(< "$HISTORY_LOG")"
sleep 1.1
write_progress 72 "구조 확인" "시간은 지났지만 처리량은 아직 모릅니다."
write_progress 100 "구조 확인" "같은 단계에서 대기 중입니다."
[[ "$(value "$PROGRESS_FILE" phase_started_at)" == "$phase_started" ]] || fail "repeated same-phase status reset elapsed time"
[[ "$(value "$PROGRESS_FILE" progress_mode)" == indeterminate ]] || fail "running stage at 100 became fake completion"
[[ "$(value "$PROGRESS_FILE" percent)" == 100 ]] || fail "legacy percent compatibility field disappeared"
[[ -z "$(value "$PROGRESS_FILE" activity_at)" ]] || fail "elapsed-time status fabricated activity"
[[ -z "$(value "$PROGRESS_FILE" heartbeat_at)" ]] || fail "elapsed-time status fabricated worker heartbeat"
[[ "$(< "$LOG_FILE")" == "$before_log" && "$(< "$HISTORY_LOG")" == "$before_history" ]] || fail "same-phase updates spammed activity logs"
write_progress 100 "작업 완료" "검사를 완료했습니다." success
[[ "$(value "$PROGRESS_FILE" progress_mode)" == complete ]] || fail "actual successful completion was not identified"
printf 'PASS: legacy percentages stay indeterminate; repeated phase writes do not fabricate activity\n'

CURRENT_OPERATION=restore
write_progress 0 "복사 대기" "실제 출력 변화를 기다립니다."
start_progress_monitor
monitor_pid="$PROGRESS_MONITOR_PID"
monitor_start="$PROGRESS_MONITOR_START"
[[ -n "$monitor_pid" && -n "$monitor_start" ]] || fail "monitor identity was not recorded"
wait_greater "$HEARTBEAT_FILE" monitor_heartbeat_at 0
first_heartbeat="$(value "$HEARTBEAT_FILE" monitor_heartbeat_at)"
first_activity="$(value "$HEARTBEAT_FILE" observed_activity_at)"
[[ "$(value "$HEARTBEAT_FILE" monitor_operation)" == restore ]] || fail "monitor operation identity is missing"
[[ "$(value "$HEARTBEAT_FILE" monitor_started_at)" == 12345 ]] || fail "monitor operation start is missing"
before_log="$(< "$LOG_FILE")"
write_progress 31 "복사 대기" "응답 대기 시간이 늘었지만 실제 출력은 없습니다."
wait_greater "$HEARTBEAT_FILE" monitor_heartbeat_at "$first_heartbeat"
[[ "$(value "$HEARTBEAT_FILE" observed_activity_at)" == "$first_activity" ]] || fail "heartbeat-only tick was falsely counted as processing activity"
[[ "$(< "$LOG_FILE")" == "$before_log" ]] || fail "monitor wrote synthetic activity to operation log"
for ((attempt = 0; attempt < 140; attempt++)); do
    [[ "$(value "$HEARTBEAT_FILE" monitor_wait_seconds)" == 10 ]] && break
    sleep 0.1
done
[[ "$(value "$HEARTBEAT_FILE" monitor_wait_seconds)" == 10 ]] || fail "silent live command did not publish separate waiting metadata"
[[ -n "$(value "$HEARTBEAT_FILE" monitor_wait_detail_b64)" ]] || fail "waiting explanation missing"
[[ "$(value "$HEARTBEAT_FILE" observed_activity_at)" == "$first_activity" && "$(< "$LOG_FILE")" == "$before_log" ]] || fail "waiting explanation fabricated processing activity"
printf 'activity_at=%s\n' "$(date +%s)" >> "$PROGRESS_FILE"
wait_greater "$HEARTBEAT_FILE" observed_activity_at "$first_activity"
[[ "$(value "$HEARTBEAT_FILE" monitor_wait_seconds)" == 0 ]] || fail "measured worker activity did not clear waiting state"
first_activity="$(value "$HEARTBEAT_FILE" observed_activity_at)"
sleep 1.1
printf 'actual child process output\n' >> "$LOG_FILE"
wait_greater "$HEARTBEAT_FILE" observed_activity_at "$first_activity"
stop_progress_monitor
[[ -z "$PROGRESS_MONITOR_PID" ]] || fail "monitor PID was not cleared"
if [[ "$(process_start_ticks "$monitor_pid")" == "$monitor_start" ]] && kill -0 "$monitor_pid" 2>/dev/null; then
    fail "monitor was left running after stop"
fi
stopped_snapshot="$(< "$HEARTBEAT_FILE")"
sleep 2.2
[[ "$(< "$HEARTBEAT_FILE")" == "$stopped_snapshot" ]] || fail "stopped monitor continued publishing"
printf 'PASS: monitor heartbeat is separate from observed output activity and stops cleanly\n'

CURRENT_OPERATION=start
write_progress 0 "서버 응답 대기" "서버 출력을 기다립니다."
reset_server_log
start_progress_monitor
# A preceding operation's heartbeat file must not satisfy this monitor check.
for ((attempt = 0; attempt < 80; attempt++)); do
    [[ "$(value "$HEARTBEAT_FILE" monitor_operation)" == start ]] && break
    sleep 0.1
done
[[ "$(value "$HEARTBEAT_FILE" monitor_operation)" == start ]] || fail "server monitor did not publish"
server_activity="$(value "$HEARTBEAT_FILE" observed_activity_at)"
sleep 1.1
printf 'actual server startup output\n' >> "$SERVER_LOG"
wait_greater "$HEARTBEAT_FILE" observed_activity_at "$server_activity"
stop_progress_monitor
printf 'PASS: server output changes are observed during startup\n'

CURRENT_OPERATION=update
OPERATION_STARTED_EPOCH=23456
write_progress 0 "수정 파일 보호" "현재 서버 출력은 이 작업에 속하지 않습니다."
start_progress_monitor
for ((attempt = 0; attempt < 80; attempt++)); do
    [[ "$(value "$HEARTBEAT_FILE" monitor_operation)" == update ]] && break
    sleep 0.1
done
stale_activity="$(value "$HEARTBEAT_FILE" observed_activity_at)"
sleep 1.1
printf 'output from a previous server context\n' >> "$SERVER_LOG"
sleep 2.2
[[ "$(value "$HEARTBEAT_FILE" observed_activity_at)" == "$stale_activity" ]] || fail "stale server output counted as current update activity"
stop_progress_monitor
printf 'PASS: unrelated server context is excluded from current operation activity\n'

CURRENT_OPERATION=start
OPERATION_STARTED_EPOCH=34567
write_progress 0 "서버 작업 준비" "준비가 완료되었습니다."
CURRENT_OPERATION=server-task
OPERATION_STARTED_EPOCH=0
reset_server_log
[[ "$(value "$SERVER_LOG_CONTEXT" operation)" == start && "$(value "$SERVER_LOG_CONTEXT" operation_started_at)" == 34567 ]] || fail "persistent server log did not inherit preparation identity"
CURRENT_OPERATION=start
OPERATION_STARTED_EPOCH=34567

(
    # Exercise begin_operation's stale-state reset without acquiring a real
    # launcher lock or letting its EXIT handler replace this fixture's cleanup.
    acquire_operation() { return 0; }
    start_progress_monitor() { return 0; }
    printf 'phase=작업 준비\nphase_started_at=1\noperation=restore\nstatus=running\n' > "$PROGRESS_FILE"
    printf 'monitor_operation=restore\nmonitor_heartbeat_at=1\n' > "$HEARTBEAT_FILE"
    LAST_LOGGED_PROGRESS='restore|작업 준비|running'
    begin_operation restore
    [[ "$(value "$PROGRESS_FILE" phase_started_at)" -gt 1 ]] || fail "new operation reused a previous same-phase timestamp"
    [[ ! -e "$HEARTBEAT_FILE" ]] || fail "new operation retained stale heartbeat"
    grep -Fq '작업 준비' "$LOG_FILE" || fail "new operation suppressed its first log because old phase key matched"
)
printf 'PASS: new operation resets stale phase, heartbeat, and log deduplication state\n'

(
    trap 'stop_progress_monitor' EXIT
    acquire_operation() { return 0; }
    is_running() { return 0; }
    record_server_session() { return 0; }
    wait_for_server() {
        [[ -n "$PROGRESS_MONITOR_PID" ]] || fail "persistent-start completion did not start monitor"
        for ((attempt = 0; attempt < 80; attempt++)); do
            [[ "$(value "$HEARTBEAT_FILE" monitor_operation)" == start ]] && break
            sleep 0.1
        done
        [[ "$(value "$HEARTBEAT_FILE" monitor_operation)" == start ]] || fail "persistent-start monitor did not respond"
        grep -Fq 'prior server preparation output' "$LOG_FILE" || fail "persistent-start completion erased preparation log"
        local prior_activity
        prior_activity="$(value "$HEARTBEAT_FILE" observed_activity_at)"
        sleep 1.1
        printf 'persistent server actual output\n' >> "$SERVER_LOG"
        wait_greater "$HEARTBEAT_FILE" observed_activity_at "$prior_activity"
        write_progress 100 "서버 응답 확인" "실제 서버 응답을 확인했습니다." success
    }
    printf 'prior server preparation output\n' > "$LOG_FILE"
    printf 'monitor_operation=old-fixture\nmonitor_heartbeat_at=1\n' > "$HEARTBEAT_FILE"
    finish_persistent_start
    [[ "$OPERATION_STARTED_EPOCH" == 34567 ]] || fail "persistent completion reset preparation start time"
    [[ "$(value "$PROGRESS_FILE" status)" == success ]] || fail "persistent-start completion did not preserve success"
    stop_progress_monitor
)
printf 'PASS: persistent-start completion monitors output without erasing earlier preparation logs\n'

printf 'Manager progress and liveness regression tests passed.\n'
