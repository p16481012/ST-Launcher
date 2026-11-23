#!/usr/bin/env node
const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { spawnSync } = require('child_process');
const helper = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/storage-usage.sh'), 'utf8');
const code = helper.split("<<'NODE'\n")[1]?.split('\nNODE')[0]; assert(code);
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'st-storage-usage-test-'));
const install = path.join(root, 'SillyTavern'), launcher = path.join(root, '.st-launcher'), downloads = path.join(root, 'Downloads');
const nativeBackup = 'SillyTavern-Launcher-20261124-000000.zip';
let injection = '', count = 0;
function file(relative, size) { const target = path.join(root, relative); fs.mkdirSync(path.dirname(target), { recursive: true }); fs.writeFileSync(target, Buffer.alloc(size, 1)); return target; }
function scan(options = {}) {
    const result = spawnSync(process.execPath, ['-e', injection + code, '--', '-', options.install || install, launcher, downloads, root, options.time || '15000', options.limit || '250000'], { encoding: 'utf8', timeout: 35000 });
    assert.equal(result.status, options.status || 0, result.stderr || String(result.error));
    const lines = result.stdout.split('\n');
    return { raw: result.stdout, header: Object.fromEntries(lines.filter(line => line.includes('=')).map(line => line.split('='))),
        rows: Object.fromEntries(lines.filter(line => line.startsWith('storage\t')).map(line => { const fields = line.split('\t'); return [fields[1], { bytes: Number(fields[2]), files: Number(fields[3]), complete: fields[4] === '1', skipped: Number(fields[5]) }]; })) };
}
function check(name, task) { task(); console.log(`PASS ${name}`); count++; }
try {
    file('SillyTavern/data/user.json', 10); file('SillyTavern/node_modules/module.js', 20); file('SillyTavern/.git/objects/object', 30); file('SillyTavern/server.js', 40);
    file('.st-launcher/backups/backup.tar.gz', 50); file('.st-launcher/file-trash/item', 60);
    file(`Downloads/${nativeBackup}`, 70); file('Downloads/ST-Launcher-internal-12345678-abcd-abcd-abcd-123456789abc/backup.tar.gz', 80);
    file('Downloads/unrelated-video', 999); file('.npm/_cacache/cache', 90); file('.npm/_logs/unrelated-log', 999);
    check('manual categories cover data once and ignore unrelated Downloads', () => {
        const report = scan(); assert.equal(report.header.total_bytes, '450'); assert.equal(report.header.complete, '1');
        assert.equal(report.rows.download_backups.bytes, 150); assert.equal(report.rows.npm_cache.bytes, 90); assert(report.raw.length < 8192);
    });
    check('hardlinked bytes are never counted twice across categories', () => {
        const alias = path.join(install, 'node_modules/linked-data'); fs.linkSync(path.join(install, 'data/user.json'), alias);
        const report = scan(); assert.equal(report.header.total_bytes, '450'); assert.equal(report.rows.node_modules.bytes, 20);
        fs.unlinkSync(alias);
    });
    check('symlinks and nested npm alias are not followed', () => {
        const outside = path.join(root, 'outside'); file('outside/_cacache/secret', 99999);
        const alias = path.join(install, 'data/link'); fs.symlinkSync(outside, alias, process.platform === 'win32' ? 'junction' : 'dir');
        const cache = path.join(root, '.npm'), real = path.join(root, '.npm-real'); fs.renameSync(cache, real);
        fs.symlinkSync(outside, cache, process.platform === 'win32' ? 'junction' : 'dir');
        const report = scan(); assert.equal(report.header.total_bytes, '360'); assert.equal(report.rows.data.skipped, 1); assert.equal(report.rows.npm_cache.skipped, 1);
        fs.unlinkSync(cache); fs.renameSync(real, cache); fs.unlinkSync(alias);
        assert.equal(fs.statSync(path.join(outside, '_cacache/secret')).size, 99999);
    });
    check('entry budget returns honest partial result without deleting anything', () => {
        const report = scan({ limit: '2' }); assert.equal(report.header.complete, '0'); assert(Number(report.header.scanned_entries) <= 2);
        assert.equal(fs.statSync(path.join(install, 'data/user.json')).size, 10);
    });
    check('permission denied is a partial category rather than a false zero success', () => {
        injection = `const storageFs = require('fs'), originalStat = storageFs.lstatSync;
storageFs.lstatSync = function(target, ...args) { if (String(target).endsWith('user.json')) throw Object.assign(new Error('fixture denied'), {code:'EACCES'}); return originalStat.call(this,target,...args); };
`;
        try { const report = scan(); assert.equal(report.header.complete, '0'); assert(!report.rows.data.complete); assert(report.rows.data.skipped > 0); }
        finally { injection = ''; }
    });
    check('cancellation marker stops scan without any file mutation', () => {
        const request = file('cancel-request', 1); injection = `process.env.ST_CANCEL_REQUEST_FILE = ${JSON.stringify(request)};\n`;
        try { scan({ status: 130 }); } finally { injection = ''; fs.unlinkSync(request); }
        assert.equal(scan().header.total_bytes, '450');
    });
    check('missing installation still reports backups and npm cache', () => {
        const report = scan({ install: path.join(root, 'not-installed') }); assert.equal(report.header.total_bytes, '350'); assert.equal(report.rows.data.bytes, 0);
    });
    check('time budget and unrelated download enumeration are bounded', () => {
        for (let index = 0; index < 1500; index++) file(`Downloads/unrelated-${index}`, 1);
        const started = Date.now(), report = scan({ time: '1' });
        assert.equal(report.header.complete, '0'); assert(Date.now() - started < 3000);
        assert(fs.existsSync(path.join(downloads, 'unrelated-1499')));
    });
    console.log(`Passed ${count} storage usage regression groups.`);
} finally {
    assert(path.basename(root).startsWith('st-storage-usage-test-') && path.dirname(root) === os.tmpdir());
    fs.rmSync(root, { recursive: true, force: true });
}
