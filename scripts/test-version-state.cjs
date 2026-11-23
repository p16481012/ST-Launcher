#!/usr/bin/env node
const assert = require('assert/strict'), fs = require('fs'), path = require('path'), os = require('os'), { spawnSync } = require('child_process');
const helper = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/version-state.sh'), 'utf8').replace(/\r\n/g, '\n');
const code = helper.split("<<'NODE'\n")[1]?.split('\nNODE')[0]; assert(code);
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'st-version-state-test-'));
const install = path.join(root, 'install'), launcher = path.join(root, 'launcher'), downloads = path.join(root, 'Downloads');
const name = 'SillyTavern-Launcher-20261124-010203.zip'; let count = 0;
for (const folder of [install, launcher, downloads]) fs.mkdirSync(folder);
fs.mkdirSync(path.join(launcher, 'update-history'));
function git(...args) { const r = spawnSync('git', ['-C', install, ...args], { encoding: 'utf8', timeout: 10000 }); assert.equal(r.status, 0, r.stderr); return r.stdout.trim(); }
function run(action, arg = '', expected = '', installation = '', status = 0) {
    const r = spawnSync(process.execPath, ['-e', code, '--', '-', action, install, launcher, downloads, arg, expected, installation], { encoding: 'utf8', timeout: 15000 });
    assert.equal(r.status, status, r.stderr || String(r.error));
    return { raw: r.stdout, values: Object.fromEntries(r.stdout.split('\n').filter(x => x.includes('=')).map(x => x.split('='))) };
}
function check(name, f) { f(); console.log(`PASS ${name}`); count++; }
try {
    git('init', '-b', 'release'); git('config', 'user.name', 'Fixture'); git('config', 'user.email', 'fixture@localhost');
    fs.writeFileSync(path.join(install, 'package.json'), '{"version":"1.0.0"}'); git('add', '.'); git('commit', '-m', 'old'); const old = git('rev-parse', 'HEAD');
    fs.writeFileSync(path.join(install, 'server.js'), 'new code'); git('add', '.'); git('commit', '-m', 'new'); const head = git('rev-parse', 'HEAD');
    const report = run('versions');
    check('brand-new launcher without history has an empty snapshot list', () => {
        fs.rmdirSync(path.join(launcher, 'update-history'));
        assert.equal(run('snapshots').raw, 'version_snapshots=1\nsnapshots_truncated=0\n');
        fs.mkdirSync(path.join(launcher, 'update-history'));
    });
    check('only local prior commits are offered with current identity', () => { assert(report.raw.includes(`version\t${old}\t`)); assert(!report.raw.includes(`version\t${head}\t`)); assert.equal(report.values.current_commit, head); });
    check('stale head, stale installation and non-ancestor are rejected', () => {
        run('validate-target', old, head, report.values.installation_revision);
        run('validate-target', old, old, report.values.installation_revision, 81);
        run('validate-target', old, head, '0'.repeat(64), 81);
        run('validate-target', 'f'.repeat(40), head, report.values.installation_revision, 82);
        run('validate-target', head, head, report.values.installation_revision, 82);
    });
    check('old ZIP without completion receipt is never inferred as snapshot', () => {
        fs.writeFileSync(path.join(downloads, name), 'verified archive fixture');
        assert(!run('snapshots').raw.includes('snapshot\t'));
        run('verify-snapshot', name, 'a'.repeat(64), head, 83);
    });
    let revision;
    check('verified completion receipt links filename revision and commit', () => {
        revision = run('record', name, head).values.snapshot_revision;
        run('verify-snapshot', name, revision, head);
        assert(run('snapshots').raw.includes(`\t${revision}\t${head}\t`));
        assert.equal(run('snapshot-info', name).values.snapshot_commit, head);
        run('record', name, head, '', 83); // no overwrite
    });
    check('wrong commit and altered archive invalidate snapshot without deletion', () => {
        run('verify-snapshot', name, revision, old, 83);
        fs.appendFileSync(path.join(downloads, name), 'changed');
        run('verify-snapshot', name, revision, head, 83);
        assert(!run('snapshots').raw.includes('snapshot\t')); assert(fs.existsSync(path.join(downloads, name)));
    });
    check('unknown or oversized receipts do not become usable snapshots', () => {
        const folder = path.join(launcher, 'update-history/version-snapshots');
        fs.writeFileSync(path.join(folder, 'a'.repeat(64) + '.json'), '{bad');
        fs.writeFileSync(path.join(folder, 'b'.repeat(64) + '.json'), 'x'.repeat(9000));
        assert(!run('snapshots').raw.includes('snapshot\t'));
    });
    check('archive symlink cannot obtain a new completion receipt', () => {
        const other = 'SillyTavern-Launcher-20261124-010204.zip';
        const outside = path.join(root, 'outside.zip'); fs.writeFileSync(outside, 'outside');
        try { fs.symlinkSync(outside, path.join(downloads, other)); }
        catch (e) { if (process.platform === 'win32' && e.code === 'EPERM') { console.log('SKIP file symlink fixture requires Linux CI'); return; } throw e; }
        run('record', other, head, '', 83); assert.equal(fs.readFileSync(outside, 'utf8'), 'outside');
    });
    console.log(`Passed ${count} version receipt regression groups.`);
} finally {
    assert(path.basename(root).startsWith('st-version-state-test-') && path.dirname(root) === os.tmpdir());
    fs.rmSync(root, { recursive: true, force: true });
}
