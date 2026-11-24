const assert = require('assert/strict'), fs = require('fs'), path = require('path'), os = require('os');
const { spawnSync } = require('child_process');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/lan-security.sh'), 'utf8').replace(/\r\n/g, '\n');
const code = source.split("<<'NODE'\n")[1]?.split('\nNODE')[0]; assert(code);
const yamlPackage = require.resolve('yaml/package.json', { paths: [process.env.ST_TEST_YAML_ROOT || process.cwd()] });
const YAML = require(path.dirname(yamlPackage));
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'st-lan-settings-test-'));
const secret = 'DoNotLog-SECRET123', username = 'DoNotLog-USER';
let count = 0, fixtureNumber = 0;
function fixture(config = 'port: 8000\nlisten: false\nwhitelistMode: true\nwhitelist: ["127.0.0.1", "::1"]\nbasicAuthMode: false\nperUserBasicAuth: false\notherSetting: keep-me\n') {
    const dir = path.join(root, String(++fixtureNumber)), install = path.join(dir, 'SillyTavern'), owner = path.join(dir, '.st-launcher');
    fs.mkdirSync(install, { recursive: true }); fs.mkdirSync(owner);
    fs.cpSync(path.dirname(yamlPackage), path.join(install, 'node_modules/yaml'), { recursive: true });
    fs.writeFileSync(path.join(install, 'package.json'), '{}');
    const configPath = path.join(install, 'config.yaml'); if (config !== null) fs.writeFileSync(configPath, config);
    return { dir, install, owner, configPath, original: config };
}
function request(extra = {}) {
    return { protocol: 1, listen: true, allowedNetworks: ['192.168.1.99/24'], basicAuthEnabled: true, credentials: { username, password: secret }, ...extra };
}
function run(f, data = request(), inject = '', env = {}) {
    const inputPath = path.join(f.dir, 'input.json'); fs.writeFileSync(inputPath, typeof data === 'string' ? data : JSON.stringify(data));
    const input = fs.openSync(inputPath, 'r');
    let result;
    try { result = spawnSync(process.execPath, ['-e', inject + code, '--', '-', f.install, f.owner], { encoding: 'utf8', timeout: 15000, stdio: ['ignore', 'pipe', 'pipe', input], env: { ...process.env, ...env } }); }
    finally { fs.closeSync(input); fs.unlinkSync(inputPath); }
    assert(!result.error, String(result.error));
    assert(!(result.stdout + result.stderr).includes(secret), 'password leaked to command output');
    assert(!(result.stdout + result.stderr).includes(username), 'username leaked to command output');
    return result;
}
function check(name, task) { task(); console.log(`PASS ${name}`); count++; }
function failure(result, code, status) {
    assert.equal(result.status, status, result.stderr);
    assert.equal(result.stderr.split(/\r?\n/)[0], `error_code=${code}`);
}
function unchanged(f) { assert.equal(fs.readFileSync(f.configPath, 'utf8'), f.original); }
function backups(f) { const dir = path.join(f.owner, 'backups/network-settings'); return fs.existsSync(dir) ? fs.readdirSync(dir) : []; }
try {
    check('enables private LAN and credentials atomically while preserving unrelated settings', () => {
        const f = fixture(), result = run(f); assert.equal(result.status, 0, result.stderr); assert.match(result.stdout, /^saved=1/);
        const parsed = YAML.parse(fs.readFileSync(f.configPath, 'utf8'));
        assert.equal(parsed.listen, true); assert.equal(parsed.basicAuthMode, true); assert.equal(parsed.basicAuthUser.password, secret);
        assert.deepEqual(parsed.whitelist, ['127.0.0.1', '::1', '192.168.1.0/24']); assert.equal(parsed.port, 8000); assert.equal(parsed.otherSetting, 'keep-me');
        assert.equal(backups(f).length, 1); assert.equal(fs.readFileSync(path.join(f.owner, 'backups/network-settings', backups(f)[0]), 'utf8'), f.original);
    });
    check('supports exact IPv6 loopback and ULA subnets', () => {
        const f = fixture(), result = run(f, request({ allowedNetworks: ['::1', 'fd12:3456::1234/64'] }));
        assert.equal(result.status, 0, result.stderr); assert(YAML.parse(fs.readFileSync(f.configPath, 'utf8')).whitelist.includes('fd12:3456:0:0:0:0:0:0/64'));
    });
    check('public wildcard mapped DNS and too-broad CIDRs never reach config writes', () => {
        for (const entry of ['*', '0.0.0.0/0', '10.0.0.1/7', '172.16.0.1/11', '192.168.1.1/15', 'fd12::1/6', '::1/127', '::ffff:192.168.1.1', 'fe80::1%wlan0', '8.8.8.8', 'localhost', '10.01.1.1']) {
            const f = fixture(); failure(run(f, request({ allowedNetworks: [entry] })), 'LAN_SETTINGS_INPUT_INVALID', 90); unchanged(f); assert.equal(backups(f).length, 0);
        }
    });
    check('bad or overlong credentials and unknown protocol fields are rejected', () => {
        for (const change of [{ credentials: { username: 'bad:name', password: secret } }, { credentials: { username, password: 'short' } }, { credentials: { username, password: ' '.repeat(8) } }, { credentials: { username, password: '\u3000'.repeat(8) } }, { credentials: { username, password: '\n' + secret } }, { credentials: { username, password: 'x'.repeat(1025) } }, { listen: 1 }, { unexpected: true }]) {
            const f = fixture(); failure(run(f, request(change)), 'LAN_SETTINGS_INPUT_INVALID', 90); unchanged(f);
        }
    });
    check('bounded malformed JSON and malformed YAML do not leak parser source', () => {
        for (const data of ['{ "password": "' + secret + '"', 'x'.repeat(16385)]) { const f = fixture(); failure(run(f, data), 'LAN_SETTINGS_INPUT_INVALID', 90); unchanged(f); }
        const f = fixture('basicAuthUser: ["' + secret + '"\n'); failure(run(f), 'LAN_SETTINGS_CONFIG_INVALID', 94); unchanged(f);
    });
    check('empty or unreadable payload reports transfer failure without writing config', () => {
        const f = fixture(); failure(run(f, ''), 'LAN_SETTINGS_TRANSFER_FAILED', 93); unchanged(f);
        const g = fixture();
        const inject = `const fixtureFs=require('fs'), oldRead=fixtureFs.readSync;fixtureFs.readSync=function(fd,...args){if(fd===3)throw Error(${JSON.stringify(secret)});return oldRead.call(this,fd,...args);};\n`;
        failure(run(g, request(), inject), 'LAN_SETTINGS_TRANSFER_FAILED', 93); unchanged(g);
    });
    check('legacy wildcard list is retained only while turning external access off', () => {
        const f = fixture('listen: true\nwhitelist: ["*"]\nbasicAuthMode: false\n');
        assert.equal(run(f, request({ listen: false, allowedNetworks: null, basicAuthEnabled: false, credentials: null })).status, 0);
        const parsed = YAML.parse(fs.readFileSync(f.configPath, 'utf8')); assert.equal(parsed.listen, false); assert.deepEqual(parsed.whitelist, ['*']);
        const rejected = fixture(); assert.notEqual(run(rejected, request({ allowedNetworks: null })).status, 0); unchanged(rejected);
    });
    check('turning off only changes the current listen flag, not credentials, auth mode or legacy lists', () => {
        const disable = { protocol: 2, action: 'disable-listen' };
        for (const auth of [
            { basicAuthMode: true, perUserBasicAuth: true, basicAuthUser: { username, password: secret } },
            { basicAuthMode: true, perUserBasicAuth: false, basicAuthUser: 'legacy-malformed' },
            { basicAuthMode: false, basicAuthUser: { username, password: secret } },
            {},
        ]) {
            const f = fixture(); // What an earlier inspection saw, before an external edit.
            const current = { ...auth, listen: true, whitelistMode: false, whitelist: ['*'], unrelated: { keep: 7 } };
            const before = YAML.stringify(current);
            fs.writeFileSync(f.configPath, before);
            const result = run(f, disable);
            assert.equal(result.status, 0, result.stderr);
            assert.deepEqual(YAML.parse(fs.readFileSync(f.configPath, 'utf8')), { ...current, listen: false });
            assert.equal(fs.readFileSync(path.join(f.owner, 'backups/network-settings', backups(f)[0]), 'utf8'), before);
        }
        const invalid = fixture();
        const extra = run(invalid, { ...disable, basicAuthEnabled: false });
        assert.notEqual(extra.status, 0); unchanged(invalid);
        const shared = fixture('listen: &shared true\nbasicAuthMode: *shared\n');
        const refused = run(shared, disable);
        failure(refused, 'LAN_SETTINGS_CONFIG_INVALID', 94); unchanged(shared); assert.equal(backups(shared).length, 0);
        assert.match(refused.stderr, /YAML 별칭/);
    });
    check('per-user authentication cannot be replaced by single-account credentials', () => {
        const original = 'listen: true\nperUserBasicAuth: true\nbasicAuthMode: true\n';
        const f = fixture(original); failure(run(f), 'LAN_SETTINGS_PER_USER_AUTH', 95); unchanged(f);
        const g = fixture(original); assert.equal(run(g, request({ credentials: null })).status, 0); assert.equal(YAML.parse(fs.readFileSync(g.configPath, 'utf8')).perUserBasicAuth, true);
    });
    check('empty credentials cannot enable authentication but may retain existing credentials', () => {
        const f = fixture(); failure(run(f, request({ credentials: null })), 'LAN_SETTINGS_CREDENTIALS_REQUIRED', 96); unchanged(f);
        const g = fixture('basicAuthMode: true\nbasicAuthUser:\n  username: existing\n  password: existing-password\n');
        assert.equal(run(g, request({ credentials: null })).status, 0); assert.equal(YAML.parse(fs.readFileSync(g.configPath, 'utf8')).basicAuthUser.password, 'existing-password');
    });
    check('hardlinked settings are rejected without modifying either link', () => {
        const f = fixture(), linked = path.join(f.dir, 'linked.yaml'); fs.linkSync(f.configPath, linked);
        failure(run(f), 'LAN_SETTINGS_PATH_UNSAFE', 91); unchanged(f); assert.equal(fs.readFileSync(linked, 'utf8'), f.original);
    });
    check('a missing config uses a regular default without changing that default', () => {
        const f = fixture(null), defaults = path.join(f.install, 'default'); fs.mkdirSync(defaults); fs.writeFileSync(path.join(defaults, 'config.yaml'), 'port: 8000\nlisten: false\n');
        assert.equal(run(f).status, 0); assert.equal(YAML.parse(fs.readFileSync(f.configPath, 'utf8')).listen, true); assert.equal(fs.readFileSync(path.join(defaults, 'config.yaml'), 'utf8'), 'port: 8000\nlisten: false\n');
    });
    check('concurrent config changes are preserved instead of overwritten', () => {
        const f = fixture(), changed = f.original + 'externalEditor: changed\n';
        const inject = `const fixtureFs=require('fs'), oldWrite=fixtureFs.writeFileSync;let writes=0;fixtureFs.writeFileSync=function(...args){if(typeof args[0]==='number'&&++writes===2)oldWrite(${JSON.stringify(f.configPath)},${JSON.stringify(changed)});return oldWrite.apply(this,args);};\n`;
        const result = run(f, request(), inject); failure(result, 'LAN_SETTINGS_CHANGED', 92); assert.equal(fs.readFileSync(f.configPath, 'utf8'), changed);
    });
    check('disk write failure keeps original and removes only its incomplete temp file', () => {
        const f = fixture();
        const inject = `const fixtureFs=require('fs'), oldWrite=fixtureFs.writeFileSync;let writes=0;fixtureFs.writeFileSync=function(...args){if(typeof args[0]==='number'&&++writes===2)throw Object.assign(Error(${JSON.stringify(secret)}),{code:'ENOSPC'});return oldWrite.apply(this,args);};\n`;
        failure(run(f, request(), inject), 'LAN_SETTINGS_SAVE_FAILED', 97); unchanged(f); assert.equal(fs.readdirSync(f.install).filter(name => name.startsWith('.config-lan-')).length, 0);
    });
    check('existing settings backup is never overwritten', () => {
        const f = fixture(), inject = `const RealDate=Date;global.Date=class extends RealDate{constructor(...args){super(...(args.length?args:['2026-11-24T00:00:00']));}};\n`;
        assert.equal(run(f, request(), inject).status, 0); const first = fs.readFileSync(f.configPath, 'utf8');
        failure(run(f, request({ listen: false }), inject), 'LAN_SETTINGS_SAVE_FAILED', 97); assert.equal(fs.readFileSync(f.configPath, 'utf8'), first);
        assert.equal(fs.readFileSync(path.join(f.owner, 'backups/network-settings', backups(f)[0]), 'utf8'), f.original);
    });
    check('cancellation before and after backup preserves config', () => {
        const f = fixture(), marker = path.join(f.dir, 'cancel'); fs.writeFileSync(marker, '1');
        failure(run(f, request(), '', { ST_CANCEL_REQUEST_FILE: marker }), 'OPERATION_CANCELLED', 130); unchanged(f);
        const g = fixture(), late = path.join(g.dir, 'cancel');
        const inject = `const fixtureFs=require('fs'), oldWrite=fixtureFs.writeFileSync;let writes=0;fixtureFs.writeFileSync=function(...args){const result=oldWrite.apply(this,args);if(typeof args[0]==='number'&&++writes===1)oldWrite(${JSON.stringify(late)},'1');return result;};\n`;
        failure(run(g, request(), inject, { ST_CANCEL_REQUEST_FILE: late }), 'OPERATION_CANCELLED', 130); unchanged(g); assert.equal(backups(g).length, 1);
    });
    if (process.platform !== 'win32') {
        check('symlink config and replaced backup directory are rejected', () => {
            const f = fixture(), outside = path.join(f.dir, 'outside.yaml'); fs.renameSync(f.configPath, outside); fs.symlinkSync(outside, f.configPath);
            failure(run(f), 'LAN_SETTINGS_PATH_UNSAFE', 91); assert.equal(fs.readFileSync(outside, 'utf8'), f.original);
            const g = fixture(), other = path.join(g.dir, 'outside'); fs.mkdirSync(other); fs.mkdirSync(path.join(g.owner, 'backups')); fs.symlinkSync(other, path.join(g.owner, 'backups/network-settings'));
            failure(run(g), 'LAN_SETTINGS_PATH_UNSAFE', 91); unchanged(g); assert.equal(fs.readdirSync(other).length, 0);
        });
        check('post-commit directory sync failure is success with durability warning', () => {
            const f = fixture(), inject = `const fixtureFs=require('fs'), oldSync=fixtureFs.fsyncSync;fixtureFs.fsyncSync=function(fd){if(fixtureFs.readlinkSync('/proc/self/fd/'+fd)===${JSON.stringify(f.install)})throw Error('fixture sync failure');return oldSync.call(this,fd);};\n`;
            const result = run(f, request(), inject); assert.equal(result.status, 0); assert.match(result.stdout, /saved=1\ndurability_warning=1/); assert.equal(YAML.parse(fs.readFileSync(f.configPath, 'utf8')).listen, true);
        });
    } else console.log('NOTE Linux CI additionally checks symlink and directory-fsync boundaries.');
    console.log(`Passed ${count} LAN settings regression groups.`);
} finally {
    assert(path.dirname(root) === os.tmpdir() && path.basename(root).startsWith('st-lan-settings-test-'));
    fs.rmSync(root, { recursive: true, force: true });
}
