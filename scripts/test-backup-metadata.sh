#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/st-backup-metadata.XXXXXX")"
trap '[[ "$TEST_ROOT" == */st-backup-metadata.* && "$TEST_ROOT" != / ]] && rm -rf -- "$TEST_ROOT"' EXIT
export ST_HOME="$TEST_ROOT/install" ST_LAUNCHER_HOME="$TEST_ROOT/launcher" ST_DOWNLOAD_DIR="$TEST_ROOT/downloads"
mkdir -p "$ST_HOME" "$ST_DOWNLOAD_DIR"
source "$ROOT_DIR/app/src/main/assets/manager.sh"
fail() { echo "FAIL: $*" >&2; exit 1; }
(
    unzip() {
        [[ "$1" == -Z1 && "$2" == fixture.zip ]] || fail 'unexpected archive scan'
        printf x >> "$TEST_ROOT/scans"
        printf '%s\n' 'data/user/characters/card.png' 'data/user/characters/CARD2.PNG' \
            'data/other/characters/한글.png' 'public/characters/legacy.png' \
            'data/user/characters/sprites/expression.png' 'data/user/characters/metadata.json' \
            'data/user/chats/card/first.jsonl' 'data/user/group chats/group.jsonl' \
            'data/other/chats/card/second.JSONL' 'public/chats/legacy/chat.jsonl' \
            'data/user/chats/not-conversation.json' 'data/user/groups/group.json' \
            'data/user/backgrounds/card.png' 'data/user/chats/' \
            'data/user/extensions/example/manifest.json' 'public/scripts/extensions/third-party/global/manifest.json' \
            'public/scripts/extensions/third-party/global/tests/manifest.json'
    }
    counts="$(archive_content_counts fixture.zip)"
    [[ "$counts" == $'entry_count=17\ncharacter_files=4\nchat_files=4\nextension_count=2' ]] || fail "wrong counts: $counts"
    [[ "$(wc -c < "$TEST_ROOT/scans")" == 1 ]] || fail 'counting added an archive scan'
)
base=$'format=st-launcher-backup-v1\nitems=user_data\nsecrets=0\nexpanded_bytes=12\nentry_count=2'
backup_metadata_valid "$base" || fail 'old metadata rejected'
backup_metadata_valid "$base"$'\ncharacter_files=0\nchat_files=2' || fail 'valid counts rejected'
for bad in '-1' '500001' '99999999999999999999999999999999999' 'text' '1+1'; do
    if backup_metadata_valid "$base"$'\ncharacter_files='"$bad"; then fail 'unsafe count accepted'; fi
    if backup_metadata_valid "$base"$'\nextension_count='"$bad"; then fail 'unsafe extension count accepted'; fi
done
if backup_metadata_valid "$base"$'\nchat_files=1\nchat_files=2'; then fail 'duplicate counts accepted'; fi
(
    unzip() { return 9; }
    if archive_content_counts broken.zip >/dev/null; then fail 'failed ZIP listing counted as empty'; fi
)
echo 'PASS: single-pass character/chat/extension metadata, old backups, invalid counts and scan failure'
