#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HELPER="$ROOT_DIR/app/src/main/assets/install-validation.sh"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/st-install-validation.XXXXXX")"
cleanup() { case "$TEST_ROOT" in "${TMPDIR:-/tmp}"/st-install-validation.*) rm -rf -- "$TEST_ROOT" ;; esac; }
trap cleanup EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
native_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
expect_exit() {
    local expected="$1" actual=0; shift
    "$@" > "$TEST_ROOT/last-output" 2>&1 || actual=$?
    if [[ "$actual" != "$expected" ]]; then cat "$TEST_ROOT/last-output" >&2; fail "expected exit $expected, got $actual"; fi
}
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
export npm_config_cache="$TEST_ROOT/npm-cache" npm_config_userconfig="$TEST_ROOT/user.npmrc" npm_config_globalconfig="$TEST_ROOT/global.npmrc"
export npm_config_offline=true npm_config_registry=http://127.0.0.1:9 npm_config_update_notifier=false npm_config_engine_strict=false
touch "$TEST_ROOT/user.npmrc" "$TEST_ROOT/global.npmrc"
npm_path="$(command -v npm)"
mkdir "$TEST_ROOT/ranges"
node --input-type=commonjs - "$TEST_ROOT" <<'NODE'
const fs=require('fs'),path=require('path'),root=process.argv[2],version=process.versions.node;
const [major,minor]=version.split('.').map(Number);
const fixtures=[
 ['missing',undefined,0],['any','*',0],['exact',version,0],['caret',`^${major}.0.0`,0],
 ['tilde',`~${major}.${minor}.0`,0],['xrange',`${major}.x`,0],['empty','',0],
 ['and',`>=${major}.0.0 <${major+1}.0.0`,0],['or',`<1.0.0 || ^${major}.0.0`,0],
 ['hyphen',`${major}.0.0 - ${major}.999.999`,0],['future',`>=${major+1}`,56],
 ['upper',`<${major}.0.0`,56],['bounded-old','>=1 <2',56],['prerelease','1.0.0-beta.1',56],
 ['invalid','this is not a range',57],['object',{min:1},57],['number',20,57],
];
for(const [name,range,expected] of fixtures){
 const directory=path.join(root,'ranges',name);fs.mkdirSync(directory);
 const data={name:'offline-install-fixture',version:'1.0.0',private:true};
 if(range!==undefined)data.engines={node:range};
 fs.writeFileSync(path.join(directory,'package.json'),JSON.stringify(data));
}
fs.writeFileSync(path.join(root,'cases'),fixtures.map(([name,,expected])=>`${name}:${expected}`).join('\n')+'\n');
fs.writeFileSync(path.join(root,'missing-parser.cjs'),`const Module=require('module'),load=Module._load;Module._load=function(name,...args){if(String(name).endsWith('/semver')||String(name).endsWith('\\\\semver'))throw Error('fixture missing parser');return load.call(this,name,...args);};`);
fs.writeFileSync(path.join(root,'trace-parser.cjs'),`const fs=require('fs'),Module=require('module'),load=Module._load;Module._load=function(name,...args){if(String(name).endsWith('/semver')||String(name).endsWith('\\\\semver'))fs.appendFileSync(process.env.VALIDATION_TRACE,String(name)+'\\n');return load.call(this,name,...args);};`);
NODE
while IFS=: read -r name expected; do expect_exit "$expected" bash "$HELPER" runtime "$TEST_ROOT/ranges/$name" "$npm_path" ''; done < "$TEST_ROOT/cases"
printf '{broken-json\n' > "$TEST_ROOT/ranges/invalid/package.json"
expect_exit 57 bash "$HELPER" runtime "$TEST_ROOT/ranges/invalid" "$npm_path" ''
NODE_OPTIONS="--require=$(native_path "$TEST_ROOT/missing-parser.cjs")" expect_exit 57 bash "$HELPER" runtime "$TEST_ROOT/ranges/exact" "$npm_path" ''
echo 'PASS: actual npm semver validates exact/caret/tilde/x/OR/AND/hyphen/upper-bound ranges; malformed or unavailable parser fails closed'

# Create an npm-shaped system bundle and confirm that discovery uses its semver,
# not the host fallback. No npm package is downloaded or installed here.
node --input-type=commonjs - "$TEST_ROOT" "$npm_path" <<'NODE'
const fs=require('fs'),path=require('path'),root=process.argv[2],exe=fs.realpathSync(process.argv[3]);
const roots=[path.resolve(path.dirname(exe),'..'),path.join(path.dirname(exe),'node_modules/npm'),path.join(path.dirname(process.execPath),'node_modules/npm')];
const npm=roots.find(p=>{try{return JSON.parse(fs.readFileSync(path.join(p,'package.json'))).name==='npm';}catch{return false;}});
if(!npm)throw Error('test host npm bundle not found');
const target=path.join(root,'prefix/lib/node_modules/npm');fs.mkdirSync(path.join(target,'bin'),{recursive:true});
fs.copyFileSync(path.join(npm,'package.json'),path.join(target,'package.json'));
fs.cpSync(path.join(npm,'node_modules/semver'),path.join(target,'node_modules/semver'),{recursive:true});
fs.writeFileSync(path.join(target,'bin/npm-cli.js'),'// never executed fixture');
NODE
export NODE_OPTIONS="--require=$(native_path "$TEST_ROOT/trace-parser.cjs")" VALIDATION_TRACE="$(native_path "$TEST_ROOT/parser-trace")"
bash "$HELPER" runtime "$TEST_ROOT/ranges/exact" "$TEST_ROOT/prefix/lib/node_modules/npm/bin/npm-cli.js" "$TEST_ROOT/prefix" > /dev/null
grep -Fq 'prefix' "$TEST_ROOT/parser-trace" || fail 'npm-cli discovery did not use fixture bundle'
: > "$TEST_ROOT/parser-trace"
bash "$HELPER" runtime "$TEST_ROOT/ranges/exact" "$TEST_ROOT/no-such-npm" "$TEST_ROOT/prefix" > /dev/null
grep -Fq 'prefix' "$TEST_ROOT/parser-trace" || fail 'explicit prefix fallback did not use fixture bundle'
if [[ "$OSTYPE" != msys* ]]; then
    mkdir "$TEST_ROOT/prefix/bin"
    ln -s ../lib/node_modules/npm/bin/npm-cli.js "$TEST_ROOT/prefix/bin/npm"
    : > "$TEST_ROOT/parser-trace"
    bash "$HELPER" runtime "$TEST_ROOT/ranges/exact" "$TEST_ROOT/prefix/bin/npm" "$TEST_ROOT/prefix" > /dev/null
    grep -Fq 'prefix/lib/node_modules/npm/node_modules/semver' "$TEST_ROOT/parser-trace" || fail 'Termux-style npm symlink bundle was not resolved'
else
    echo 'SKIP: Termux-style npm symlink resolution requires Linux CI; direct npm-cli and prefix paths tested locally'
fi
unset NODE_OPTIONS VALIDATION_TRACE
echo 'PASS: npm bundled parser discovery supports npm-cli layout and system prefix'

export ST_HOME="$TEST_ROOT/unused" ST_LAUNCHER_HOME="$TEST_ROOT/launcher" ST_DOWNLOAD_DIR="$TEST_ROOT/downloads" PREFIX="$TEST_ROOT/prefix-unused"
source "$ROOT_DIR/app/src/main/assets/manager.sh"
mkdir -p "$TEST_ROOT/content/node_modules/package" "$TEST_ROOT/content/data"
printf before > "$TEST_ROOT/content/data/state.txt"
printf dependency > "$TEST_ROOT/content/node_modules/package/file.txt"
touch "$TEST_ROOT/timestamp"; touch -r "$TEST_ROOT/content/data/state.txt" "$TEST_ROOT/timestamp"
first="$(install_source_snapshot "$TEST_ROOT/content")"
[[ "$first" == "$(install_source_snapshot "$TEST_ROOT/content")" ]] || fail 'unchanged snapshot was not stable'
printf edited > "$TEST_ROOT/content/data/state.txt"
touch -r "$TEST_ROOT/timestamp" "$TEST_ROOT/content/data/state.txt"
[[ "$first" != "$(install_source_snapshot "$TEST_ROOT/content")" ]] || fail 'same-size preserved-mtime content edit escaped snapshot'
second="$(install_source_snapshot "$TEST_ROOT/content")"
printf new-depend > "$TEST_ROOT/content/node_modules/package/file.txt"
[[ "$second" != "$(install_source_snapshot "$TEST_ROOT/content")" ]] || fail 'excluded dependency content was omitted from deletion protection'
echo 'PASS: content+ctime snapshot detects same-size preserved-mtime edits and also covers excluded dependencies'

# Deterministic concurrent-change and EACCES injection, confined to a fixture.
# The earlier file has already been hashed when a later file triggers the edit.
node --input-type=commonjs - "$TEST_ROOT" <<'NODE'
const fs=require('fs'),path=require('path'),root=process.argv[2];
const directory=path.join(root,'during-read');fs.mkdirSync(directory);
fs.writeFileSync(path.join(directory,'a.txt'),'before');fs.writeFileSync(path.join(directory,'z.txt'),'trigger');
fs.writeFileSync(path.join(root,'during-read.cjs'),`
const fs=require('fs'),path=require('path'),open=fs.openSync,read=fs.readSync,tracked=new Set();let fired=false;
fs.openSync=function(file,...args){const fd=open.call(this,file,...args);if(path.basename(String(file))==='z.txt')tracked.add(fd);return fd;};
fs.readSync=function(fd,...args){if(tracked.has(fd)&&!fired){fired=true;
 if(process.env.VALIDATION_FAULT==='denied')throw Object.assign(Error('fixture read denied'),{code:'EACCES'});
 const file=path.join(process.env.VALIDATION_SOURCE,'a.txt'),before=fs.statSync(file);
 fs.writeFileSync(file,'edited');fs.utimesSync(file,before.atime,before.mtime);
}return read.call(this,fd,...args);};`);
NODE
NODE_OPTIONS="--require=$(native_path "$TEST_ROOT/during-read.cjs")" VALIDATION_SOURCE="$(native_path "$TEST_ROOT/during-read")" VALIDATION_FAULT=changed \
    expect_exit 53 bash "$HELPER" snapshot "$TEST_ROOT/during-read"
[[ "$(cat "$TEST_ROOT/during-read/a.txt")" == edited ]] || fail 'concurrent edit fixture did not run'
NODE_OPTIONS="--require=$(native_path "$TEST_ROOT/during-read.cjs")" VALIDATION_SOURCE="$(native_path "$TEST_ROOT/during-read")" VALIDATION_FAULT=denied \
    expect_exit 53 bash "$HELPER" snapshot "$TEST_ROOT/during-read"
echo 'PASS: changes to already-hashed files and read permission failures reject a snapshot'

missing_helper() ( INSTALL_VALIDATION_HELPER="$TEST_ROOT/absent-helper"; validate_install_runtime "$TEST_ROOT/ranges/exact"; )
missing_node() (
    command() { if [[ "${1:-}" == -v && "${2:-}" == node ]]; then return 1; fi; builtin command "$@"; }
    validate_install_runtime "$TEST_ROOT/ranges/exact"
)
expect_exit 57 missing_helper
expect_exit 57 missing_node
expect_exit 56 validate_install_runtime "$TEST_ROOT/ranges/future"
grep -Fq 'Node.js' "$TEST_ROOT/last-output" || fail 'runtime mismatch detail was not returned to caller'
echo 'PASS: missing helper/Node fail closed and runtime failure details reach the caller'

setup_case() {
    export ST_HOME="$TEST_ROOT/$1/destination" ST_LAUNCHER_HOME="$TEST_ROOT/$1/launcher" ST_DOWNLOAD_DIR="$TEST_ROOT/$1/downloads" PREFIX="$TEST_ROOT/$1/prefix"
    source "$ROOT_DIR/app/src/main/assets/manager.sh"
    # Network, server probes and lock owners are isolated stubs. Real copy,
    # validation, restore transaction and source cleanup functions remain used.
    is_running() { return 1; }
    installation_server_running() { return 1; }
    curl() { return 1; }
    ensure_import_runtime() { return 0; }
    begin_operation() { CURRENT_OPERATION="$1"; OPERATION_STARTED_EPOCH="$(date +%s)"; }
}
make_install() {
    local target="$1" manifest="${2:-$TEST_ROOT/ranges/missing/package.json}"
    mkdir -p "$target/data/default-user"
    cp "$manifest" "$target/package.json"
    printf '// offline fixture\n' > "$target/server.js"
    printf '# fixture\n' > "$target/start.sh"
    printf before > "$target/data/default-user/state.txt"
    git -c init.defaultBranch=release init -q "$target"
    git -C "$target" -c core.autocrlf=false add --all
    git -C "$target" -c user.name='Install Test' -c user.email=test@example.invalid -c commit.gpgsign=false -c core.hooksPath=/dev/null commit -qm fixture
}
run_import_case() (
    local kind="$1" old timestamp
    setup_case "$kind"
    old="$TEST_ROOT/$kind/source/SillyTavern"
    make_install "$old" "$TEST_ROOT/ranges/exact/package.json"
    make_install "$ST_HOME"
    printf destination-original > "$ST_HOME/data/default-user/state.txt"
    case "$kind" in
        incompatible)
            cp "$TEST_ROOT/ranges/future/package.json" "$old/package.json"
            ;;
        changed)
            timestamp="$TEST_ROOT/$kind/timestamp"; touch "$timestamp"; touch -r "$old/data/default-user/state.txt" "$timestamp"
            install_dependencies() {
                printf edited > "$old/data/default-user/state.txt"
                touch -r "$timestamp" "$old/data/default-user/state.txt"
                mkdir -p "$ST_HOME/node_modules"
            }
            ;;
        delete-failure)
            install_dependencies() { mkdir -p "$ST_HOME/node_modules"; }
            rm() { if [[ "${*: -1}" == "$old" ]]; then return 1; fi; command rm "$@"; }
            ;;
        final-read-failure|server-started)
            install_dependencies() { mkdir -p "$ST_HOME/node_modules"; }
            eval "$(declare -f install_source_snapshot | sed '1s/install_source_snapshot/original_source_snapshot/')"
            install_source_snapshot() {
                local count=0
                [[ ! -f "$TEST_ROOT/$kind/check-count" ]] || count="$(cat "$TEST_ROOT/$kind/check-count")"
                count=$((count + 1)); printf '%s' "$count" > "$TEST_ROOT/$kind/check-count"
                if [[ "$count" == 3 && "$kind" == final-read-failure ]]; then
                    echo 'fixture: final snapshot read permission denied' >&2; return 53
                fi
                original_source_snapshot "$@"
                if [[ "$count" == 3 && "$kind" == server-started ]]; then touch "$TEST_ROOT/$kind/server-running"; fi
            }
            installation_server_running() { [[ -f "$TEST_ROOT/$kind/server-running" && "$1" == "$old" ]]; }
            ;;
        offline-success) : ;; # Use the actual npm installer with offline config.
    esac
    import_install "$(printf '%s' "$old" | base64 -w 0)"
)
expect_exit 56 run_import_case incompatible
[[ "$(cat "$TEST_ROOT/incompatible/destination/data/default-user/state.txt")" == destination-original ]] || fail 'incompatible install changed destination'
[[ -f "$TEST_ROOT/incompatible/source/SillyTavern/data/default-user/state.txt" ]] || fail 'incompatible install removed source'
expect_exit 0 run_import_case changed
grep -Fxq source_cleanup_failed=1 "$TEST_ROOT/last-output" || fail 'source edit preservation was not reported'
[[ "$(cat "$TEST_ROOT/changed/source/SillyTavern/data/default-user/state.txt")" == edited ]] || fail 'late edit was deleted'
[[ "$(cat "$TEST_ROOT/changed/destination/data/default-user/state.txt")" == before ]] || fail 'copy fixture changed unexpectedly'
expect_exit 0 run_import_case delete-failure
grep -Fxq source_cleanup_failed=1 "$TEST_ROOT/last-output" || fail 'delete failure not reported'
[[ -f "$TEST_ROOT/delete-failure/source/SillyTavern/data/default-user/state.txt" ]] || fail 'delete-failure source was not retained'
for kind in final-read-failure server-started; do
    expect_exit 0 run_import_case "$kind"
    grep -Fxq source_cleanup_failed=1 "$TEST_ROOT/last-output" || fail "$kind was not reported"
    [[ -f "$TEST_ROOT/$kind/source/SillyTavern/data/default-user/state.txt" ]] || fail "$kind source was not retained"
    [[ "$(cat "$TEST_ROOT/$kind/check-count")" == 3 ]] || fail "$kind did not reach the final verification"
done
expect_exit 0 run_import_case offline-success
grep -Fxq source_removed=1 "$TEST_ROOT/last-output" || fail 'validated unchanged source was not removed'
[[ ! -e "$TEST_ROOT/offline-success/source/SillyTavern" ]] || fail 'unchanged source remains after success'
echo 'PASS: real imports reject incompatible runtime, preserve late edits/read failures/new servers/deletion failures, and succeed with actual offline npm'

full_restore_incompatible() (
    setup_case full-restore
    make_install "$ST_HOME"
    work="$BACKUP_DIR/restore-work-$$"; track_current_work_dir "$work"
    mkdir -p "$work/rollback"
    make_install "$work/extracted" "$TEST_ROOT/ranges/future/package.json"
    printf 'format=st-launcher-backup-v1\nitems=full\ncustom=\nsecrets=1\n' > "$work/extracted/.st-launcher-manifest"
    CURRENT_OPERATION=restore
    restore_extracted_tree "$work/extracted" fixture.zip
)
expect_exit 56 full_restore_incompatible
[[ "$(cat "$TEST_ROOT/full-restore/destination/data/default-user/state.txt")" == before ]] || fail 'full restore replaced existing data before runtime validation'
echo 'PASS: common full-install restore rejects incompatible runtime before replacing original data'
