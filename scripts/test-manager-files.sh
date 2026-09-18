#!/usr/bin/env bash
# No phone or user data: every file operation uses an isolated temporary fixture.
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/st-launcher-files.XXXXXX")"
trap '[[ "$TEST_ROOT" == */st-launcher-files.* && "$TEST_ROOT" != / ]] && rm -rf -- "$TEST_ROOT"' EXIT
export ST_HOME="$TEST_ROOT/SillyTavern"
export ST_LAUNCHER_HOME="$TEST_ROOT/launcher"
export ST_DOWNLOAD_DIR="$TEST_ROOT/downloads"
mkdir -p "$ST_HOME/data/default-user/chats" "$ST_HOME/.git" "$ST_HOME/node_modules" "$ST_DOWNLOAD_DIR"
source "$ROOT_DIR/app/src/main/assets/manager.sh"

fail() { echo "FAIL: $*" >&2; exit 1; }
b64() { printf '%s' "$1" | base64 -w 0; }
expect_code() {
    local expected="$1" result code
    shift
    set +e
    result="$("$@" 2>&1)"
    code=$?
    set -e
    [[ "$code" == "$expected" ]] || fail "expected $expected, got $code: $result"
}

printf '{"hello":"world"}\n\n' > "$ST_HOME/data/settings.json"
printf 'keep\n' > "$ST_HOME/data/default-user/chats/keep.jsonl"
printf 'SECRET' > "$TEST_ROOT/outside.txt"
read_result="$(read_st_file "$(b64 data/settings.json)")"
[[ "$(printf '%s\n' "$read_result" | sed -n 's/^content_b64=//p')" == "$(base64 -w 0 < "$ST_HOME/data/settings.json")" ]] || fail 'read changed exact bytes/trailing newlines'
revision="$(printf '%s\n' "$read_result" | sed -n 's/^sha256=//p')"
[[ "$revision" =~ ^[a-f0-9]{64}$ ]] || fail 'missing revision'
st_file_action write "$(b64 data/settings.json)" "$(b64 '{"hello":"saved"}')" "$revision" >/dev/null
[[ "$(cat "$ST_HOME/data/settings.json")" == '{"hello":"saved"}' ]] || fail 'text was not saved'
expect_code 42 st_file_action write "$(b64 data/settings.json)" "$(b64 stale)" "$revision"
[[ "$(cat "$ST_HOME/data/settings.json")" == '{"hello":"saved"}' ]] || fail 'conflict overwrote a newer edit'
[[ -z "$(find "$ST_HOME" -name '.st-launcher-edit-*' -print -quit)" ]] || fail 'failed save left a temporary file'

expect_code 64 read_st_file 'bad!'
expect_code 64 read_st_file "$(b64 ../outside.txt)"
expect_code 64 read_st_file "$(b64 /etc/passwd)"
expect_code 64 st_file_action mkdir "$(b64 $'data/bad\nname')"
expect_code 64 st_file_action trash ''
expect_code 44 st_file_action trash "$(b64 .git)"
expect_code 44 st_file_action mkdir "$(b64 node_modules/new)"
printf '\377\376\000' > "$ST_HOME/data/binary.bin"
expect_code 41 read_st_file "$(b64 data/binary.bin)"
head -c 65537 /dev/zero | tr '\0' x > "$ST_HOME/data/large.txt"
expect_code 40 read_st_file "$(b64 data/large.txt)"
printf 'ok' > "$ST_HOME/data/has..dots.txt"
read_st_file "$(b64 data/has..dots.txt)" >/dev/null || fail 'safe consecutive dots were rejected'

created_path='data/new 한글 file.txt'
created_result="$(st_file_action create-file "$(b64 "$created_path")")"
[[ "$created_result" == "created_b64=$(b64 "$created_path")" ]] || fail 'new file returned the wrong path'
[[ -f "$ST_HOME/$created_path" && ! -s "$ST_HOME/$created_path" ]] || fail 'new file is not an empty regular file'
read_st_file "$(b64 "$created_path")" | grep -Fxq 'content_b64=' || fail 'new empty file cannot be opened in the text editor'
printf 'keep-new-file' > "$ST_HOME/$created_path"
expect_code 43 st_file_action create-file "$(b64 "$created_path")"
[[ "$(cat "$ST_HOME/$created_path")" == keep-new-file ]] || fail 'new file creation overwrote an existing file'
expect_code 43 st_file_action create-file "$(b64 data/default-user)"
grep -Fxq keep "$ST_HOME/data/default-user/chats/keep.jsonl" || fail 'new file creation damaged a directory collision'
expect_code 44 st_file_action create-file "$(b64 .git/new-file)"
expect_code 44 st_file_action create-file "$(b64 node_modules/new-file)"
expect_code 64 st_file_action create-file "$(b64 ../outside.txt)"
expect_code 64 st_file_action create-file "$(b64 /outside.txt)"
expect_code 64 st_file_action create-file ''
expect_code 64 st_file_action create-file "$(b64 $'data/bad\nfile')"
expect_code 6 st_file_action create-file "$(b64 missing-parent/new-file)"
[[ "$(cat "$TEST_ROOT/outside.txt")" == SECRET ]] || fail 'new file creation changed a path outside SillyTavern'
should_record_operation create-st-file || fail 'new file operation is not recorded'
[[ "$(manager_error_code create-st-file 43)" == FILE_ALREADY_EXISTS ]] || fail 'new file collision has no stable error code'
if [[ "$OSTYPE" != msys* ]]; then
    [[ "$(stat -c '%a' "$ST_HOME/$created_path")" == 600 ]] || fail 'new file permissions are not private'
fi

st_file_action mkdir "$(b64 'data/new folder')" >/dev/null
expect_code 43 st_file_action mkdir "$(b64 'data/new folder')"
st_file_action rename "$(b64 'data/new folder')" "$(b64 'data/renamed folder')" >/dev/null
[[ -d "$ST_HOME/data/renamed folder" && ! -e "$ST_HOME/data/new folder" ]] || fail 'directory rename failed'
expect_code 43 st_file_action rename "$(b64 data/settings.json)" "$(b64 data/has..dots.txt)"
expect_code 64 st_file_action rename "$(b64 data)" "$(b64 data/nested)"
trash_result="$(st_file_action trash "$(b64 data/default-user)")"
trash_id="${trash_result#trash_id=}"
[[ ! -e "$ST_HOME/data/default-user" && -n "$trash_id" ]] || fail 'trash did not remove the live entry'
st_file_action untrash "$trash_id" >/dev/null
grep -Fxq keep "$ST_HOME/data/default-user/chats/keep.jsonl" || fail 'undo lost nested user data'
trash_result="$(st_file_action trash "$(b64 data/has..dots.txt)")"
trash_id="${trash_result#trash_id=}"
printf 'replacement' > "$ST_HOME/data/has..dots.txt"
expect_code 43 st_file_action untrash "$trash_id"
[[ "$(cat "$ST_HOME/data/has..dots.txt")" == replacement ]] || fail 'undo overwrote replacement'

staged='SillyTavern-File-11111111-2222-3333-4444-555555555555.tmp'
printf '\000\377\001binary-image' > "$ST_DOWNLOAD_DIR/$staged"
st_file_action import "$(b64 'data/imported image.png')" "$staged" >/dev/null
cmp "$ST_DOWNLOAD_DIR/$staged" "$ST_HOME/data/imported image.png" || fail 'file import changed binary bytes'
expect_code 43 st_file_action import "$(b64 'data/imported image.png')" "$staged"
expect_code 64 st_file_action import "$(b64 data/escape)" '../outside.txt'
[[ -f "$ST_DOWNLOAD_DIR/$staged" ]] || fail 'import removed Android-owned staging before MediaStore cleanup'

# Long names make this listing larger than Termux's entire callback budget.
# Every page must remain complete and bounded, with a stable revision and no gaps.
mkdir "$ST_HOME/many-files"
long_name="$(printf '%0100d' 0)"
for number in $(seq 1 600); do
    printf -v file_name '%04d-%s.jsonl' "$number" "$long_name"
    printf x > "$ST_HOME/many-files/$file_name"
done
cursor=0
page_count=0
first_revision=''
: > "$TEST_ROOT/listed-paths"
while :; do
    page="$(list_st_files "$(b64 many-files)" "$cursor")"
    (( $(printf '%s\n' "$page" | wc -c) <= 40960 )) || fail 'folder page exceeded 40 KiB'
    listing_revision="$(printf '%s\n' "$page" | sed -n 's/^listing_revision=//p')"
    [[ "$listing_revision" =~ ^[a-f0-9]{64}$ ]] || fail 'folder page has no valid revision'
    [[ -z "$first_revision" || "$listing_revision" == "$first_revision" ]] || fail 'unchanged folder got inconsistent page revisions'
    first_revision="$listing_revision"
    printf '%s\n' "$page" | awk -F '\t' '$1 == "entry" { print $3 }' >> "$TEST_ROOT/listed-paths"
    next_cursor="$(printf '%s\n' "$page" | sed -n 's/^next_cursor=//p')"
    page_count=$((page_count + 1))
    [[ -n "$next_cursor" ]] || break
    [[ "$next_cursor" =~ ^[0-9]+$ ]] && (( next_cursor > cursor && page_count < 100 )) || fail 'folder page cursor did not advance'
    cursor="$next_cursor"
done
[[ "$(wc -l < "$TEST_ROOT/listed-paths" | tr -d ' ')" == 600 ]] || fail 'folder pages omitted or duplicated files'
[[ "$(sort -u "$TEST_ROOT/listed-paths" | wc -l | tr -d ' ')" == 600 ]] || fail 'folder pages repeated a path'
(( page_count > 1 )) || fail 'large directory did not paginate'
printf x > "$ST_HOME/many-files/another.txt"
changed_page="$(list_st_files "$(b64 many-files)" 0)"
[[ "$(printf '%s\n' "$changed_page" | sed -n 's/^listing_revision=//p')" != "$first_revision" ]] || fail 'listing revision missed a directory change'
expect_code 64 list_st_files "$(b64 many-files)" -1
expect_code 64 list_st_files "$(b64 many-files)" 1000
mkdir "$ST_HOME/empty-folder"
empty_page="$(list_st_files "$(b64 empty-folder)")"
grep -Fxq 'next_cursor=' <<< "$empty_page" || fail 'empty directory did not terminate pagination'

if [[ "$OSTYPE" != msys* ]]; then
    ln -s "$TEST_ROOT" "$ST_HOME/escape"
    ln -s "$TEST_ROOT/outside.txt" "$ST_HOME/data/linked.txt"
    ln -s "$TEST_ROOT/does-not-exist.txt" "$ST_HOME/data/dangling.txt"
    expect_code 44 list_st_files "$(b64 escape)"
    expect_code 44 read_st_file "$(b64 data/linked.txt)"
    expect_code 44 st_file_action mkdir "$(b64 escape/new)"
    expect_code 44 st_file_action rename "$(b64 data/settings.json)" "$(b64 escape/overwrite)"
    expect_code 44 st_file_action trash "$(b64 data/linked.txt)"
    expect_code 44 st_file_action create-file "$(b64 data/linked.txt)"
    expect_code 44 st_file_action create-file "$(b64 data/dangling.txt)"
    expect_code 44 st_file_action create-file "$(b64 escape/new-file)"
    [[ -L "$ST_HOME/data/linked.txt" && -L "$ST_HOME/data/dangling.txt" ]] || fail 'new file creation replaced a symlink'
    [[ ! -e "$TEST_ROOT/does-not-exist.txt" && ! -e "$TEST_ROOT/new-file" ]] || fail 'new file creation followed an outside symlink'
    [[ "$(cat "$TEST_ROOT/outside.txt")" == SECRET ]] || fail 'outside file changed'
else
    echo 'NOTE: Windows symlink privileges unavailable; Linux CI checks symlink boundaries.'
fi

# Verify the server check is performed under the operation lock, not before it.
expect_code 10 bash -c '
    source "$1"
    begin_operation() { printf acquired > "$RUN_DIR/test-lock"; }
    is_running() { [[ -f "$RUN_DIR/test-lock" ]] || exit 99; return 0; }
    begin_st_file_mutation write-st-file
' _ "$ROOT_DIR/app/src/main/assets/manager.sh"
expect_code 11 bash -c '
    source "$1"
    begin_operation() { exit 11; }
    st_file_action() { exit 99; }
    write_st_file unused unused unused
' _ "$ROOT_DIR/app/src/main/assets/manager.sh"

expect_code 10 bash -c '
    source "$1"
    begin_operation() { [[ "$1" == create-st-file ]] || exit 99; }
    is_running() { return 0; }
    st_file_action() { exit 99; }
    create_st_file unused
' _ "$ROOT_DIR/app/src/main/assets/manager.sh"
expect_code 11 bash -c '
    source "$1"
    begin_operation() { exit 11; }
    st_file_action() { exit 99; }
    create_st_file unused
' _ "$ROOT_DIR/app/src/main/assets/manager.sh"

echo 'PASS: file read/write/create, revision conflict, rename, recoverable deletion, import and path safety'
