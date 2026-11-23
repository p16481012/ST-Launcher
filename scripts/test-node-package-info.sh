#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/st-node-package-info.XXXXXX")"
trap '[[ "$TEST_ROOT" == */st-node-package-info.* && "$TEST_ROOT" != / ]] && rm -rf -- "$TEST_ROOT"' EXIT
export ST_HOME="$TEST_ROOT/install" ST_LAUNCHER_HOME="$TEST_ROOT/launcher" ST_DOWNLOAD_DIR="$TEST_ROOT/downloads"
mkdir -p "$ST_HOME" "$ST_DOWNLOAD_DIR"
source "$ROOT_DIR/app/src/main/assets/manager.sh"
fail() { echo "FAIL: $*" >&2; exit 1; }
dpkg-query() { [[ "$1" == -W && "$3" == nodejs-lts ]] || fail 'wrong installed package'; printf 'installed\t22.1.0-1\n'; }
apt-cache() { [[ "$1" == policy && "$2" == nodejs-lts ]] || fail 'unexpected package operation'; printf 'nodejs-lts:\n  Installed: 22.1.0-1\n  Candidate: 22.2.0-1\n'; }
apt() { fail 'package query must not install or refresh'; }
pkg() { fail 'package query must not install or refresh'; }
out="$(node_package_info)"
[[ "$out" == *"installed_version_b64=$(printf %s '22.1.0-1' | base64 -w 0)"* ]] || fail 'installed version missing'
[[ "$out" == *"candidate_version_b64=$(printf %s '22.2.0-1' | base64 -w 0)"* ]] || fail 'cached candidate missing'
[[ "$out" == *package_cache_only=1* ]] || fail 'cache-only scope missing'
dpkg-query() { printf 'not-installed\t22.1.0-1\n'; }
apt-cache() { printf '  Candidate: (none)\n'; }
out="$(node_package_info)"
[[ "$out" == *$'installed_version_b64=\ncandidate_version_b64=' ]] || fail 'unknown treated as installed'
dpkg-query() { return 1; }
apt-cache() { return 1; }
node_package_info >/dev/null
echo 'PASS: Node package info uses installed status and local candidate cache only'
