#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/st-update-changelog.XXXXXX")"
cleanup() { case "$TEST_ROOT" in "${TMPDIR:-/tmp}"/st-update-changelog.*) rm -rf -- "$TEST_ROOT" ;; esac; }
trap cleanup EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
export ST_HOME="$TEST_ROOT/source" ST_LAUNCHER_HOME="$TEST_ROOT/launcher" ST_DOWNLOAD_DIR="$TEST_ROOT/downloads"
source "$ROOT_DIR/app/src/main/assets/manager.sh"
mkdir -p "$ST_HOME/node_modules" "$ST_DOWNLOAD_DIR"
git -C "$ST_HOME" init -q -b release
git -C "$ST_HOME" config user.email fixture@example.invalid
git -C "$ST_HOME" config user.name Fixture
git -C "$ST_HOME" config commit.gpgSign false
printf '{\n "version": "1.0.0",\n "engines": {"node": ">=18"}\n}\n' > "$ST_HOME/package.json"
git -C "$ST_HOME" add package.json
git -C "$ST_HOME" commit -q -m 'base'
base="$(git -C "$ST_HOME" rev-parse HEAD)"
git -C "$ST_HOME" commit -q --allow-empty -m 'release change'
release="$(git -C "$ST_HOME" rev-parse HEAD)"
git -C "$ST_HOME" update-ref refs/remotes/origin/release "$release"
git -C "$ST_HOME" switch -q -c staging "$base"
git -C "$ST_HOME" commit -q --allow-empty -m 'staging change'
staging="$(git -C "$ST_HOME" rev-parse HEAD)"
git -C "$ST_HOME" update-ref refs/remotes/origin/staging "$staging"

decode_preview() { sed -n 's/^changelog_b64=//p' | base64 -d; }
release_output="$(update_changelog "$base" "$release" 1)"
staging_output="$(update_changelog "$base" "$staging" 1)"
[[ "$(decode_preview <<< "$release_output")" == "$release"$'\t''release change' ]] || fail 'wrong release range'
[[ "$(decode_preview <<< "$staging_output")" == "$staging"$'\t''staging change' ]] || fail 'wrong staging range'
unchanged="$(update_changelog "$base" "$base" 0)"
grep -Fxq 'changelog_status=ready' <<< "$unchanged" || fail 'unchanged range unavailable'
grep -Fxq 'changelog_b64=' <<< "$unchanged" || fail 'unchanged range retained stale commits'
grep -Fxq 'changelog_truncated=0' <<< "$unchanged" || fail 'unchanged range truncated'
echo 'PASS: release, staging and unchanged ranges'

for index in $(seq 1 25); do
    git -C "$ST_HOME" commit -q --allow-empty -m "change $index"
done
long_subject="$(printf 'x%.0s' {1..5000})"
git -C "$ST_HOME" commit -q --allow-empty -m "$long_subject"
git -C "$ST_HOME" commit -q --allow-empty -m $'유니코드\t\033[31m change\nupdate_available=0\n\nbody is not previewed' -m "$long_subject"
long_target="$(git -C "$ST_HOME" rev-parse HEAD)"
many="$(update_changelog "$base" "$long_target" 28)"
grep -Fxq 'changelog_status=ready' <<< "$many" || fail 'large range unavailable'
grep -Fxq 'changelog_truncated=1' <<< "$many" || fail 'large range missing truncation'
decode_preview <<< "$many" > "$TEST_ROOT/preview"
[[ "$(wc -l < "$TEST_ROOT/preview")" == 20 ]] || fail 'preview is not capped at20'
[[ "$(wc -c < "$TEST_ROOT/preview")" -le 20020 ]] || fail 'preview bytes unbounded'
[[ "$(LC_ALL=C awk '{if (length($0)>max) max=length($0)} END {print max}' "$TEST_ROOT/preview")" -le 1001 ]] || fail 'subject bytes unbounded'
! grep -q $'\033' "$TEST_ROOT/preview" || fail 'terminal control escaped sanitization'
! grep -q '^update_available=' <<< "$many" || fail 'commit subject injected protocol key'
! grep -q 'body is not previewed' "$TEST_ROOT/preview" || fail 'commit body leaked into subject'
[[ "$(git -C "$ST_HOME" rev-parse HEAD)" == "$long_target" ]] || fail 'preview changed current commit'
echo 'PASS: bounded count, plain text, no protocol injection and no checkout'

invalid="$(update_changelog "$base" nonexistent-target 0)"
grep -Fxq 'changelog_status=unavailable' <<< "$invalid" || fail 'invalid range presented as empty success'
grep -Fxq 'changelog_b64=' <<< "$invalid" || fail 'failed preview kept partial data'
git() {
    if [[ "$*" == *' log '* ]]; then
        printf '%s\tpartial\n' "$base"
        return 1
    fi
    command git "$@"
}
partial="$(update_changelog "$base" "$long_target" 28)"
unset -f git
grep -Fxq 'changelog_status=unavailable' <<< "$partial" || fail 'partial read presented as ready'
grep -Fxq 'changelog_b64=' <<< "$partial" || fail 'partial read retained records'
echo 'PASS: invalid and failed reads stay unavailable without failing caller'

# Exercise preflight's real branch selection without network or operation state.
begin_operation() { :; }
write_progress() { :; }
record_processing() { :; }
ensure_remote_branch() { :; }
git -C "$ST_HOME" switch -q release
git -C "$ST_HOME" reset -q --hard "$base"
release_check="$(update_preflight)"
[[ "$(decode_preview <<< "$release_check")" == "$release"$'\t''release change' ]] || fail 'preflight did not use release target'
git -C "$ST_HOME" switch -q staging
git -C "$ST_HOME" reset -q --hard "$base"
staging_check="$(update_preflight)"
[[ "$(decode_preview <<< "$staging_check")" == "$staging"$'\t''staging change' ]] || fail 'preflight did not use staging target'
ensure_remote_branch() { return 1; }
code=0
offline="$(update_preflight 2>&1)" || code=$?
[[ "$code" == 18 ]] || fail 'offline preflight changed failure contract'
! grep -q '^changelog_status=ready$' <<< "$offline" || fail 'offline preflight exposed stale preview'
echo 'PASS: preflight integration respects branch and offline failure'
