#!/usr/bin/env bash
# Exercise both real launch commands with a short-lived fixture, never a phone/browser.
set -Eeuo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sandbox="$(mktemp -d "${TMPDIR:-/tmp}/st-launcher-browser.XXXXXX")"
trap '[[ "$sandbox" == */st-launcher-browser.* ]] && rm -rf -- "$sandbox"' EXIT
export ST_HOME="$sandbox/install" ST_LAUNCHER_HOME="$sandbox/launcher" ST_PORT=12555
mkdir -p "$ST_HOME"
source "$root/app/src/main/assets/manager.sh"
printf 'browserLaunch:\n  enabled: true\nautorun: true\n' > "$ST_HOME/config.yaml"
cp "$ST_HOME/config.yaml" "$sandbox/original-config"
cat > "$ST_HOME/server.js" <<'JS'
const fs = require('node:fs');
const args = process.argv.slice(2);
if (args[args.indexOf('--port') + 1] !== '12555' ||
    !args.includes('--browserLaunchEnabled=false') || !args.includes('--autorun=false')) {
    throw Error('Managed process did not receive the port and both modern and legacy browser overrides');
}
fs.writeFileSync('fixture-started', 'ok');
JS
(
    # Await only this fixture child; never leave a detached process running.
    write_server_pid() { wait "$1"; }
    launch_server_process
)
test -s "$ST_HOME/fixture-started"
mv "$ST_HOME/fixture-started" "$sandbox/detached-start"
(
    is_running() { return 1; }; request_cancelled() { return 1; }; write_server_pid() { :; }
    run_persistent_server_task
)
test -s "$ST_HOME/fixture-started"
cmp "$sandbox/original-config" "$ST_HOME/config.yaml"
echo 'Managed persistent and detached starts suppress browser autorun without editing config.yaml.'
