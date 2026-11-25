#!/usr/bin/env bash
# No package installation, network access or real server. Every file (including
# partial installations and protected user data) is created inside this fixture.
set -Eeuo pipefail
project="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
root="$(mktemp -d "${TMPDIR:-/tmp}/st-install-ownership.XXXXXXXX")"
trap 'case "$root" in "${TMPDIR:-/tmp}"/st-install-ownership.*) rm -rf -- "$root";; esac' EXIT
fail() { echo "FAIL $*" >&2; exit 1; }
scenario() (
    local name="$1" dir="$root/$1"
    export ST_HOME="$dir/install" ST_LAUNCHER_HOME="$dir/launcher" ST_DOWNLOAD_DIR="$dir/downloads"
    local dest="$ST_HOME"
    source "$project/app/src/main/assets/manager.sh"
    # Reuse real EXIT cleanup when the simulated lock has actually been granted.
    begin_operation() {
        if [[ "$name" == busy ]]; then exit 11; fi
        CURRENT_OPERATION="$1"; touch "$dir/locked"; trap 'operation_cleanup $?' EXIT
    }
    is_running() { return 1; }
    curl() { [[ "$name" == late-server && -f "$dir/built" ]]; }
    installation_server_running() { [[ "$name" == manual-* && -f "$dir/locked" && "$1" == "$dest" ]]; }
    cancellation_requested() { return 1; }
    refresh_package_indexes() { :; }; upgrade_install_packages() { :; }; apt_with_selected_sources() { :; }
    verify_node_runtime() { :; }; getprop() { echo arm64; }; validate_install_runtime() { :; }
    git() {
        [[ "$1" == clone && "${!#}" == "$RUN_DIR"/install-stage.*/install && "${!#}" != "$dest" ]] || exit 99
        mkdir -- "${!#}"; printf new > "${!#}/package.json"
    }
    install_dependencies() {
        [[ "$ST_HOME" == "$RUN_DIR"/install-stage.*/install && "$DEPENDENCY_HASH_FILE" == "$RUN_DIR"/install-stage.*/dependency-lock.sha256 ]] || exit 99
        printf new-hash > "$DEPENDENCY_HASH_FILE"; touch "$dir/built"
        if [[ "$name" == collision || "$name" == cancel ]]; then
            mkdir -p "$dest/data"; printf original > "$dest/data/keep"; printf old-hash > "$LAUNCHER_HOME/dependency-lock.sha256"
        fi
        [[ "$name" != cancel ]] || exit 130
    }
    case "$name" in
        owned|mismatch|busy)
            local stage previous
            stage="$(mktemp -d "$RUN_DIR/install-stage.XXXXXXXX")"
            printf '%s %s\n' "${stage##*/}" "$(restore_file_identity "$stage")" > "$INSTALL_MARKER"
            echo "$stage" > "$dir/old-stage"
            if [[ "$name" == mismatch ]]; then
                mv -- "$stage" "$stage.original"; mkdir -- "$stage"; printf original > "$stage/keep"
            fi
            ;;
        legacy)
            mkdir -p "$dest/data"; printf original > "$dest/data/keep"; : > "$INSTALL_MARKER"
            ;;
        manual-*)
            mkdir -p "$dest/.git" "$dest/data"; printf '{}' > "$dest/package.json"; printf original > "$dest/data/keep"
            current_branch() { echo release; }; available_bytes_for() { echo 2147483648; }
            case "$name" in
                manual-repair) repair_st ;;
                manual-reset) reset_installation ;;
                manual-update) update_st 0 0 ;;
                manual-branch) switch_branch staging 0 ;;
                manual-extension) extension_mutate disable unused unused ;;
                manual-lan) save_lan_security 10000 "$(printf '%064d' 1)" ;;
                *) exit 98 ;;
            esac
            exit 99 ;;
    esac
    install_st release
)
check() {
    local name="$1" expected="$2" dir="$root/$1" rc=0
    scenario "$name" > "$root/$name.log" 2>&1 || rc=$?
    [[ "$rc" == "$expected" ]] || { cat "$root/$name.log"; fail "$name expected $expected got $rc"; }
    case "$name" in
        owned)
            [[ ! -e "$(cat "$dir/old-stage")" && ! -e "$dir/launcher/run/install-owned" &&
               "$(cat "$dir/install/package.json")" == new && "$(cat "$dir/launcher/dependency-lock.sha256")" == new-hash ]] || fail "$name publication"
            ;;
        mismatch)
            [[ "$(cat "$(cat "$dir/old-stage")/keep")" == original && -f "$dir/launcher/run/install-owned" && ! -e "$dir/install" ]] || fail "$name changed unowned state" ;;
        busy)
            [[ -d "$(cat "$dir/old-stage")" && -s "$dir/launcher/run/install-owned" && ! -e "$dir/locked" ]] || fail 'cleaned before lock' ;;
        collision|cancel)
            [[ "$(cat "$dir/install/data/keep")" == original && "$(cat "$dir/launcher/dependency-lock.sha256")" == old-hash &&
               ! -e "$dir/launcher/run/install-owned" ]] || fail "$name overwrote existing installation" ;;
        late-server)
            [[ ! -e "$dir/install" && -f "$dir/built" && ! -e "$dir/launcher/run/install-owned" ]] || fail 'published after manual server started' ;;
        *) [[ -f "$dir/locked" && "$(cat "$dir/install/data/keep")" == original ]] || fail "$name changed user data or did not check under lock" ;;
    esac
    echo "PASS $name (exit $rc)"
}
check legacy 3; check busy 11; check mismatch 3; check owned 0
check collision 3; check cancel 130; check late-server 10
for op in repair reset update branch extension lan; do check "manual-$op" 10; done
