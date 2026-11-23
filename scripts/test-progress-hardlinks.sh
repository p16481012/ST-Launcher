#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/st-copy-links.XXXXXX")"
cleanup() { case "$TEST_ROOT" in "${TMPDIR:-/tmp}"/st-copy-links.*) rm -rf -- "$TEST_ROOT" ;; esac; }
trap cleanup EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
native_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
node - "$TEST_ROOT" <<'NODE'
const fs = require('fs'), path = require('path'), root = process.argv[2];
fs.mkdirSync(path.join(root, 'source'));
fs.writeFileSync(path.join(root, 'source/a.txt'), 'hard-linked source');
fs.linkSync(path.join(root, 'source/a.txt'), path.join(root, 'source/b.txt'));
fs.writeFileSync(path.join(root, 'fault.cjs'), `
const fs = require('fs'), link = fs.linkSync, stat = fs.lstatSync;
let linked = false;
fs.linkSync = function(...args) {
    fs.appendFileSync(process.env.LINK_TRACE, 'link\\n');
    if (process.env.LINK_FAILURE !== 'verify') {
        const error = new Error('fixture ' + process.env.LINK_FAILURE); error.code = process.env.LINK_FAILURE; throw error;
    }
    link.apply(this, args); linked = true;
};
fs.lstatSync = function(filename, ...args) {
    if (linked && String(filename).replace(/\\\\/g, '/').endsWith('/source/b.txt')) {
        const error = new Error('verification must not fall back'); error.code = 'EXDEV'; throw error;
    }
    return stat.call(this, filename, ...args);
};
`);
NODE
for code in EACCES EPERM ENOTSUP EOPNOTSUPP EXDEV EIO verify; do
    target="$TEST_ROOT/$code"
    mkdir "$target"
    printf original-destination > "$target/b.txt"
    result=0
    LINK_FAILURE="$code" LINK_TRACE="$(native_path "$TEST_ROOT/trace")" NODE_OPTIONS="--require=$(native_path "$TEST_ROOT/fault.cjs")" \
        bash "$ROOT_DIR/app/src/main/assets/progress.sh" copy "$TEST_ROOT/source" "$target" '' > "$TEST_ROOT/output" 2>&1 || result=$?
    if [[ "$code" == EIO || "$code" == verify ]]; then
        [[ "$result" != 0 ]] || fail "$code should not fall back"
        [[ "$(cat "$target/b.txt")" == original-destination ]] || fail 'failed verification replaced destination'
        if [[ "$code" == verify ]]; then grep -Fxq 'progress-copy: EXDEV' "$TEST_ROOT/output" || fail 'verification failure was swallowed'; fi
    else
        [[ "$result" == 0 ]] || { cat "$TEST_ROOT/output"; fail "$code did not fall back"; }
        cmp "$TEST_ROOT/source/a.txt" "$target/a.txt" || fail 'first file differs'
        cmp "$TEST_ROOT/source/b.txt" "$target/b.txt" || fail 'fallback bytes differ'
    fi
    [[ -z "$(find "$target" -name '.st-copy-*' -print -quit)" ]] || fail 'partial copy left temporary files'
done
[[ "$(wc -l < "$TEST_ROOT/trace" | tr -d ' ')" == 7 ]] || fail 'fault cases did not reach actual hardlink preservation'
echo 'PASS: Android-denied hardlinks fall back to verified copies; I/O and post-link verification failures remain fatal'
