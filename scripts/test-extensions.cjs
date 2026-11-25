// Isolated local fixtures only. Fetch is redirected to a local fixture by a test-only child_process shim.
const fs = require('fs'), os = require('os'), path = require('path'), cp = require('child_process'), assert = require('assert');
const helper = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/extensions-helper.sh'), 'utf8').replace(/\r\n/g, '\n').split("<<'NODE'\n")[1]?.replace(/\nNODE\s*$/, '').replace('const code = e.extensionCode', 'console.error(e.stack); const code = e.extensionCode');
assert(helper, 'embedded helper');
const base = fs.mkdtempSync(path.join(os.tmpdir(), 'st-extension-test-'));
const root = path.join(base, 'SillyTavern'), launcher = path.join(base, 'launcher'), globalRoot = path.join(root, 'public/scripts/extensions/third-party');
fs.mkdirSync(globalRoot, { recursive: true }); fs.mkdirSync(launcher);
const b64 = s => Buffer.from(s).toString('base64');
const git = (cwd, ...args) => cp.execFileSync('git', ['-c', `safe.directory=${cwd}`, '-C', cwd, ...args], { encoding: 'utf8', env: { ...process.env, GIT_CONFIG_NOSYSTEM: '1', GIT_CONFIG_GLOBAL: process.platform === 'win32' ? 'NUL' : '/dev/null', GIT_TERMINAL_PROMPT: '0' }, stdio: ['ignore', 'pipe', 'pipe'] }).trim();
function run(action, id = '', revision = '', env = {}) {
    return cp.spawnSync(process.execPath, ['--input-type=commonjs', '-', action, root, launcher, id, revision], {
        input: helper, encoding: 'utf8', timeout: 30000, env: { ...process.env, ...env }, windowsHide: true,
    });
}
function ok(result) { assert.strictEqual(result.status, 0, result.stdout + result.stderr); return result.stdout; }
function list() { return ok(run('list')).split('\n').filter(l => l.startsWith('extension\t')).map(l => l.split('\t')); }
function row(name) { const entry = list().find(r => Buffer.from(r[1], 'base64').toString().endsWith('/' + name)); assert(entry, name); return entry; }
function action(command, name, env) { const r = row(name); return run(command, r[1], r[2], env); }
function extension(name, gitRepo = true) {
    const dir = path.join(globalRoot, name); fs.mkdirSync(dir);
    fs.writeFileSync(path.join(dir, 'manifest.json'), JSON.stringify({ display_name: name, version: '1.0' })); fs.writeFileSync(path.join(dir, 'index.js'), 'old');
    if (gitRepo) {
        git(dir, 'init', '-b', 'main'); git(dir, 'config', 'user.name', 'Fixture'); git(dir, 'config', 'user.email', 'fixture@example.invalid');
        git(dir, 'add', '.'); git(dir, 'commit', '-m', 'initial'); git(dir, 'remote', 'add', 'origin', 'https://github.com/fixture/extensions.git');
    }
    return dir;
}
let checks = 0;
try {
    const clean = extension('clean'); assert.strictEqual(row('clean')[9], '1', Buffer.from(row('clean')[10], 'base64').toString()); checks++;
    // Count only real character/chat files and installed extension manifests; never read file contents.
    for (const rel of ['data/user-a/characters/a.png', 'data/user-a/chats/A/one.jsonl',
        'data/user-b/group chats/team.jsonl', 'data/user-b/extensions/off/.st-launcher-disabled-manifest.json',
        'data/user-a/chats/A/notes.txt', 'public/characters/legacy.PNG', 'data/user-b/extensions/not-installed/readme.md']) {
        const f = path.join(root, rel); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, '');
    }
    assert.deepStrictEqual(ok(run('reset-summary')).trim().split(/\r?\n/), ['characters=2','chats=2','extensions=2']); checks++;
    const downloads = path.join(base, 'Download'); fs.mkdirSync(downloads);
    assert.match(ok(run('reset-summary', downloads)), /backup_files=0\b/);
    for (const name of ['SillyTavern-Launcher-20261125-120000.zip','SillyTavern-Launcher-20261125-120000 (1).zip','other.zip'])
        fs.writeFileSync(path.join(downloads, name), 'not even a ZIP: count names without reading payloads');
    fs.mkdirSync(path.join(downloads, 'SillyTavern-Launcher-20261125-120001.zip'));
    if (process.platform !== 'win32') fs.symlinkSync('other.zip', path.join(downloads, 'SillyTavern-Launcher-20261125-120002.zip'));
    assert.match(ok(run('reset-summary', downloads)), /backup_files=2\b/);
    assert(!ok(run('reset-summary', path.join(base,'missing-downloads'))).includes('backup_files='));
    checks++;
    assert.strictEqual(Buffer.from(row('clean')[11], 'base64').toString(), 'fixture/extensions');
    assert.strictEqual(Buffer.from(row('clean')[12], 'base64').toString(), 'main'); checks++;
    git(clean, 'config', 'branch.main.remote', 'origin'); git(clean, 'config', 'branch.main.merge', 'refs/heads/release/mobile');
    assert.strictEqual(Buffer.from(row('clean')[12], 'base64').toString(), 'release/mobile');
    git(clean, 'config', '--remove-section', 'branch.main'); checks++;
    const shallowFile = path.join(clean, '.git', 'shallow');
    fs.writeFileSync(shallowFile, git(clean, 'rev-parse', 'HEAD') + '\n');
    const shallowItem = row('clean'); assert.strictEqual(shallowItem[9], '0');
    assert.strictEqual(shallowItem[8], git(clean, 'rev-parse', 'HEAD'));
    assert.strictEqual(Buffer.from(shallowItem[11], 'base64').toString(), 'fixture/extensions');
    assert.strictEqual(action('update', 'clean').status, 74); fs.unlinkSync(shallowFile); checks++;
    const original = fs.readFileSync(path.join(clean, 'manifest.json'));
    ok(action('disable', 'clean')); assert(!fs.existsSync(path.join(clean, 'manifest.json'))); assert.deepStrictEqual(fs.readFileSync(path.join(clean, '.st-launcher-disabled-manifest.json')), original);
    assert.strictEqual(row('clean')[6], 'disabled'); assert.strictEqual(row('clean')[9], '0'); ok(action('enable', 'clean')); checks++;
    const stale = row('clean'); fs.writeFileSync(path.join(clean, 'manifest.json'), '{"display_name":"changed"}');
    assert.strictEqual(run('disable', stale[1], stale[2]).status, 73); fs.writeFileSync(path.join(clean, 'manifest.json'), original); checks++;
    fs.writeFileSync(path.join(clean, 'index.js'), 'modified'); assert.strictEqual(row('clean')[7], 'dirty'); assert.strictEqual(action('update', 'clean').status, 74);
    fs.writeFileSync(path.join(clean, 'index.js'), 'old'); fs.writeFileSync(path.join(clean, '.gitignore'), 'ignored\n'); git(clean, 'add', '.gitignore'); git(clean, 'commit', '-m', 'ignore');
    fs.writeFileSync(path.join(clean, 'ignored'), 'keep'); assert.strictEqual(row('clean')[9], '0'); fs.unlinkSync(path.join(clean, 'ignored')); checks++;
    const config = extension('unsafe-config'); git(config, 'config', 'core.hooksPath', path.join(base, 'evil'));
    assert.strictEqual(row('unsafe-config')[9], '0'); assert.strictEqual(action('update', 'unsafe-config').status, 74); checks++;
    const sharedGit = extension('shared-git');
    for (const name of ['commondir', 'gitdir', 'objects/info/http-alternates']) {
        const file = path.join(sharedGit, '.git', name); fs.writeFileSync(file, path.join(clean, '.git'));
        assert.strictEqual(row('shared-git')[9], '0'); assert.strictEqual(action('update', 'shared-git').status, 74);
        fs.unlinkSync(file);
    }
    checks++;
    const unsupported = extension('plain', false); assert.strictEqual(row('plain')[9], '0');
    const collision = row('plain'); fs.writeFileSync(path.join(unsupported, '.st-launcher-disabled-manifest.json'), 'keep');
    assert.notStrictEqual(run('disable', collision[1], collision[2]).status, 0); assert(fs.existsSync(path.join(unsupported, 'manifest.json'))); fs.unlinkSync(path.join(unsupported, '.st-launcher-disabled-manifest.json')); checks++;
    assert.strictEqual(run('trash', b64('../../outside'), 'a'.repeat(64)).status, 72); checks++;
    const doomed = extension('trash'); const trashOutput = ok(action('trash', 'trash')); assert(!fs.existsSync(doomed));
    const trashId = /protected_trash_id=(.+)/.exec(trashOutput)[1]; assert(fs.existsSync(path.join(launcher, 'file-trash', trashId, 'item', 'manifest.json'))); checks++;
    const linked = extension('symlink'); fs.symlinkSync(clean, path.join(linked, 'linked-directory'), process.platform === 'win32' ? 'junction' : 'dir');
    assert.strictEqual(action('trash', 'symlink').status, 74); assert(fs.existsSync(linked)); checks++;
    const incoming = path.join(base, 'remote'); git(base, 'clone', '--no-local', clean, incoming);
    git(incoming, 'config', 'user.name', 'Fixture'); git(incoming, 'config', 'user.email', 'fixture@example.invalid');
    fs.writeFileSync(path.join(incoming, 'index.js'), 'updated'); git(incoming, 'add', '.'); git(incoming, 'commit', '-m', 'update');
    const shim = path.join(base, 'shim.cjs');
    fs.writeFileSync(shim, `const cp = require('child_process'), fs = require('fs'), path = require('path');
const originalSpawn = cp.spawnSync, originalAsyncSpawn = cp.spawn;
function rewrite(command,args,options) {
 const index = args.indexOf('fetch');
 if(command === 'git' && index >= 0) { args = args.map(a => a === 'https://github.com/fixture/extensions.git' ? process.env.ST_TEST_REMOTE : a);
 args = args.flatMap(a => a === 'protocol.file.allow=never' ? ['protocol.file.allow=always'] : [a]); options = {...options,env:{...options.env,GIT_ALLOW_PROTOCOL:'file'}}; }
 return [command,args,options];
}
cp.spawnSync = function(command,args,options) {
 const from=args.at(-2)||'', to=args.at(-1)||'', boundary=process.env.ST_TEST_CRASH;
 if(command==='mv' && boundary==='before-original' && to.endsWith('/item')) process.exit(91);
 if(command==='mv' && boundary==='after-original' && from.endsWith('/candidate')) process.exit(92);
 const result=originalSpawn.call(this,...rewrite(command,args,options));
 if(command==='mv' && boundary==='after-promote' && from.endsWith('/candidate') && result.status===0) process.exit(93);
 return result;
};
cp.spawn = function(command,args,options) {
 if(command==='git' && args.includes('fetch') && process.env.ST_TEST_CRASH==='mid-fetch') {
  fs.writeFileSync(path.join(args[args.indexOf('-C')+1],'.git','FETCH_HEAD'),'interrupted fetch fixture');
  process.kill(process.pid,'SIGKILL');
 }
 if(command==='git' && args.includes('fetch') && process.env.ST_TEST_WAIT_FETCH) {
  setTimeout(()=>fs.writeFileSync(process.env.ST_TEST_WAIT_FETCH,'1'),300);
  return originalAsyncSpawn.call(this,process.execPath,['-e','setTimeout(()=>{},60000)'],options);
 }
 return originalAsyncSpawn.call(this,...rewrite(command,args,options));
};
const originalWrite=fs.writeSync;
fs.writeSync=function(...args) {
 const result=originalWrite.apply(this,args);
 if(process.env.ST_TEST_CRASH==='mid-copy' && fs.existsSync(path.join(process.argv[4],'extension-update-journal.json'))) process.kill(process.pid,'SIGKILL');
 return result;
};
const originalUnlink=fs.unlinkSync;
fs.unlinkSync=function(file) {
 const result=originalUnlink.call(this,file);
 if(process.env.ST_TEST_CRASH==='mid-cleanup') process.kill(process.pid,'SIGKILL');
 return result;
};
const originalRename = fs.renameSync;
fs.renameSync = function(from,to) {
 const boundary = process.env.ST_TEST_CRASH;
 if(boundary === 'before-original' && to.endsWith(path.sep+'item')) process.exit(91);
 if(boundary === 'after-original' && from.endsWith(path.sep+'candidate')) process.exit(92);
 const result = originalRename.call(this,from,to);
 if(boundary === 'after-promote' && from.endsWith(path.sep+'candidate')) process.exit(93);
 return result;
};`);
    const env = { NODE_OPTIONS: `--require=${shim}`, ST_TEST_REMOTE: incoming };
    const result = ok(action('update', 'clean', env)); assert(result.includes('extension_result=updated')); assert.strictEqual(fs.readFileSync(path.join(clean, 'index.js'), 'utf8'), 'updated');
    const protection = /protected_trash_id=(.+)/.exec(result)[1]; assert.strictEqual(fs.readFileSync(path.join(launcher, 'file-trash', protection, 'item', 'index.js'), 'utf8'), 'old');
    assert(!fs.existsSync(path.join(launcher, 'extension-update-journal.json'))); checks++;
    const noChange = ok(action('update', 'clean', env)), nowInstalled = git(clean, 'rev-parse', 'HEAD');
    assert.strictEqual(nowInstalled, git(incoming, 'rev-parse', 'HEAD'));
    assert(result.includes('extension_commit=' + nowInstalled));
    assert(noChange.includes('extension_result=already-current\nextension_commit=' + nowInstalled)); checks++;
    const fetchCancel = path.join(base, 'fetch-cancel'); const cancelledAt = Date.now();
    const cancelledFetch = action('update', 'clean', { ...env, ST_TEST_WAIT_FETCH: fetchCancel, ST_CANCEL_REQUEST_FILE: fetchCancel });
    assert.strictEqual(cancelledFetch.status, 130, cancelledFetch.stderr); assert(Date.now() - cancelledAt < 15000);
    assert.strictEqual(fs.readFileSync(path.join(clean, 'index.js'), 'utf8'), 'updated'); checks++;
    for (const boundary of ['mid-copy', 'mid-fetch', 'before-original', 'after-original', 'after-promote']) {
        const name = `crash-${boundary}`, target = path.join(globalRoot, name);
        git(base, 'clone', '--no-local', clean, target); git(target, 'remote', 'set-url', 'origin', 'https://github.com/fixture/extensions.git');
        fs.writeFileSync(path.join(incoming, 'index.js'), boundary); git(incoming, 'add', '.'); git(incoming, 'commit', '-m', boundary);
        const result = action('update', name, { ...env, ST_TEST_CRASH: boundary }); assert.notStrictEqual(result.status, 0, result.stderr);
        assert(ok(run('inspect-recovery')).includes('extension_recovery=pending'));
        assert.strictEqual(action('disable', 'clean').status, 76);
        if(boundary === 'mid-fetch') {
            assert.notStrictEqual(run('recover', '', '', { ...env, ST_TEST_CRASH: 'mid-cleanup' }).status, 0);
            assert(ok(run('inspect-recovery')).includes('extension_recovery=pending')); checks++;
        }
        ok(run('recover')); assert(ok(run('inspect-recovery')).includes('extension_recovery=none'));
        assert.strictEqual(fs.readdirSync(launcher).filter(name=>name.startsWith('extension-work-')).length, 0);
        assert.strictEqual(fs.readFileSync(path.join(target, 'index.js'), 'utf8'), boundary === 'after-promote' ? boundary : 'updated'); checks++;
    }
    const cancel = path.join(base, 'cancel'); fs.writeFileSync(cancel, '1');
    assert.strictEqual(action('disable', 'clean', { ST_CANCEL_REQUEST_FILE: cancel }).status, 130); assert(fs.existsSync(path.join(clean, 'manifest.json'))); checks++;
    fs.writeFileSync(path.join(launcher, 'extension-update-journal.json'), '{"version":999}');
    assert(ok(run('inspect-recovery')).includes('extension_recovery=blocked')); assert.strictEqual(run('recover').status, 76); checks++;
    console.log(`extensions: ${checks} isolated regression groups passed`);
} finally {
    if (!base.startsWith(path.join(os.tmpdir(), 'st-extension-test-'))) throw Error('unsafe cleanup');
    fs.rmSync(base, { recursive: true, force: true });
}
