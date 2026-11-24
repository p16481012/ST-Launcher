#!/usr/bin/env node
// Isolated regression tests; no Android/Termux installation is accessed.
const assert = require('assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');
const script = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/safety-inventory.sh'), 'utf8');
const source = script.split("<<'NODE'\n")[1]?.split('\nNODE')[0];
assert(source, 'embedded Node program missing');
const base = fs.mkdtempSync(path.join(os.tmpdir(), 'st-safety-inventory-test-'));
const launcher = path.join(base, 'launcher'), install = path.join(base, 'SillyTavern'), downloads = path.join(base, 'Downloads');
const backups = path.join(launcher, 'backups'), trash = path.join(launcher, 'file-trash');
for (const directory of [backups, trash, install, downloads]) fs.mkdirSync(directory, { recursive: true });
const b64 = value => Buffer.from(value).toString('base64');
const archive = 'before-forced-update-20261123-010203.tar.gz';
const snapshot = path.join(backups, 'safety-snapshot-123.abcdefgh');
const rows = output => output.split('\n').filter(line => line.startsWith('safety\t')).map(line => line.split('\t'));
let tests = 0;
let injection = '';
function run(action, id = '', rev = '', expected = 0) {
    const result = spawnSync(process.execPath, ['-e', injection + source, '--', '-', action, launcher, install, downloads, id, rev], { encoding: 'utf8', timeout: 15000 });
    assert.equal(result.status, expected, `${action}: ${result.stderr || result.error}`);
    return result.stdout;
}
function check(name, callback) { callback(); console.log(`PASS ${name}`); tests++; }
function find(name) {
    let cursor = '';
    do {
        const output = run('list', cursor), found = rows(output).find(row => Buffer.from(row[2], 'base64').toString() === name);
        if (found) return found;
        cursor = output.match(/^next_cursor=(-?\d+)$/m)[1];
    } while (cursor !== '-1');
}
function makeTrash(id, relative, content = 'file') {
    const target = path.join(trash, id); fs.mkdirSync(target);
    fs.writeFileSync(path.join(target, 'metadata.json'), JSON.stringify({ root: fs.realpathSync(install), relative, deleted_at: 1234 }));
    fs.writeFileSync(path.join(target, 'item'), content);
    return target;
}
try {
    check('lists only exact owned archive/config/trash patterns', () => {
        fs.writeFileSync(path.join(backups, archive), 'legacy');
        fs.mkdirSync(snapshot); fs.writeFileSync(path.join(snapshot, archive), 'snapshot');
        fs.mkdirSync(path.join(backups, 'network-settings'));
        fs.writeFileSync(path.join(backups, 'network-settings/config-20261123-010203.yaml'), 'secret: local');
        fs.writeFileSync(path.join(backups, 'user-file.tar.gz'), 'keep');
        makeTrash('1234-12345678-abcd-abcd-abcd-123456789abc', 'data/한글');
        const list = rows(run('list'));
        assert.equal(list.length, 4); assert.equal(list.filter(row => row[1] === 'trash')[0][9], '1');
        assert.equal(run('count').trim(), 'safety_copies=3', 'do not count trash or unrelated files');
    });
    check('incomplete snapshot scratch is never selectable', () => {
        fs.mkdirSync(path.join(snapshot, '.st-safety-unfinished'));
        assert.equal(find(`backups/safety-snapshot-123.abcdefgh/${archive}`), undefined);
        run('delete', b64(`backups/safety-snapshot-123.abcdefgh/${archive}`), 'a'.repeat(64), 66);
        fs.rmdirSync(path.join(snapshot, '.st-safety-unfinished'));
    });
    check('completion is recorded only by matching bounded marker', () => {
        const relative = `backups/safety-snapshot-123.abcdefgh/${archive}`;
        assert.equal(find(relative)[10], '0');
        const current = fs.lstatSync(path.join(snapshot, archive), { bigint: true });
        const record = { format: 'st-launcher-safety-complete-v1', file: archive };
        for (const key of ['dev', 'ino', 'size', 'mtimeNs']) record[key] = current[key].toString();
        const marker = path.join(snapshot, '.st-safety-complete.json');
        fs.writeFileSync(marker, JSON.stringify(record));
        assert.equal(find(relative)[10], '1');
        fs.writeFileSync(marker, JSON.stringify({ ...record, size: '1000000' }));
        assert.equal(find(relative), undefined);
        fs.writeFileSync(marker, JSON.stringify(record));
    });
    check('exports bytes without overwriting originals or earlier exports', () => {
        const row = find(`backups/safety-snapshot-123.abcdefgh/${archive}`);
        const first = run('export', row[2], row[3]), second = run('export', row[2], row[3]);
        const name1 = Buffer.from(first.trim().split('=')[1], 'base64').toString();
        const name2 = Buffer.from(second.trim().split('=')[1], 'base64').toString();
        assert.notEqual(name1, name2);
        assert.equal(fs.readFileSync(path.join(downloads, name1), 'utf8'), 'snapshot');
        assert.equal(fs.readFileSync(path.join(snapshot, archive), 'utf8'), 'snapshot');
    });
    check('revisions reject stale archive or config selections', () => {
        const row = find(`backups/${archive}`);
        fs.appendFileSync(path.join(backups, archive), 'changed');
        run('delete', row[2], row[3], 65); run('export', row[2], row[3], 65);
        assert(fs.existsSync(path.join(backups, archive)));
    });
    check('export collision preserves existing file and cleans its partial', () => {
        const row = find(`backups/${archive}`);
        const container = path.join(downloads, 'ST-Launcher-internal-fixed'); fs.mkdirSync(container);
        const destination = path.join(container, archive);
        fs.writeFileSync(destination, 'existing');
        injection = "require('crypto').randomUUID = () => 'fixed';\n";
        try { run('export', row[2], row[3], 68); } finally { injection = ''; }
        assert.equal(fs.readFileSync(destination, 'utf8'), 'existing');
        assert.deepEqual(fs.readdirSync(container), [archive]);
    });
    check('real byte progress is published without changing stdout protocol', () => {
        const progress = path.join(base, 'progress.env'), row = find(`backups/${archive}`);
        injection = `process.env.ST_PROGRESS_FILE = ${JSON.stringify(progress)};\n`;
        try { assert(run('export', row[2], row[3]).startsWith('exported_name_b64=')); }
        finally { injection = ''; }
        assert(fs.readFileSync(progress, 'utf8').includes('completed_bytes=13\n'));
    });
    check('export never asks a child process to traverse parent descriptors', () => {
        const row = find(`backups/${archive}`);
        injection = "require('child_process').execFileSync = () => { throw Object.assign(new Error('parent fd denied'), {code:'EACCES'}); };\n";
        try {
            const output = run('export', row[2], row[3]);
            const relative = Buffer.from(output.trim().split('=')[1], 'base64').toString();
            assert.equal(fs.readFileSync(path.join(downloads, relative), 'utf8'), 'legacychanged');
            assert(!fs.existsSync(path.join(downloads, path.dirname(relative), 'INCOMPLETE.txt')));
        } finally { injection = ''; }
    });
    check('cooperative cancellation cleans only this export and keeps source', () => {
        const row = find(`backups/${archive}`), before = fs.readdirSync(downloads).sort();
        const request = path.join(base, 'cancel-request');
        injection = `process.env.ST_CANCEL_REQUEST_FILE = ${JSON.stringify(request)};
const cancelFs = require('fs'), originalWrite = cancelFs.writeSync; let requested = false;
cancelFs.writeSync = function(...args) { const result = originalWrite.apply(this, args); if (!requested && args[1]?.length === 256 * 1024) { requested = true; cancelFs.writeFileSync(process.env.ST_CANCEL_REQUEST_FILE, '1'); } return result; };
`;
        try { run('export', row[2], row[3], 130); } finally { injection = ''; fs.unlinkSync(request); }
        assert.deepEqual(fs.readdirSync(downloads).sort(), before);
        assert.equal(fs.readFileSync(path.join(backups, archive), 'utf8'), 'legacychanged');
    });
    check('abrupt process death leaves an explicit incomplete marker', () => {
        const row = find(`backups/${archive}`), before = new Set(fs.readdirSync(downloads));
        injection = `const abruptFs = require('fs'), abruptWrite = abruptFs.writeSync;
abruptFs.writeSync = function(...args) { const result = abruptWrite.apply(this, args); if (args[1]?.length === 256 * 1024) process.exit(77); return result; };
`;
        try { run('export', row[2], row[3], 77); } finally { injection = ''; }
        const added = fs.readdirSync(downloads).filter(name => !before.has(name));
        assert.equal(added.length, 1);
        assert(fs.existsSync(path.join(downloads, added[0], 'INCOMPLETE.txt')));
        assert.equal(fs.readFileSync(path.join(backups, archive), 'utf8'), 'legacychanged');
    });
    check('pending and uncertain legacy recovery blocks all mutations', () => {
        for (const name of ['restore-work-1', 'update-work-2', 'branch-work-3', 'reset-work-4', 'update-rollback-old', 'reset-rollback-old']) {
            const pending = path.join(backups, name); fs.mkdirSync(pending);
            fs.writeFileSync(path.join(pending, 'keep'), 'original');
            const output = run('list'); assert(output.includes('mutation_blocked=1'));
            assert(!rows(output).some(row => Buffer.from(row[2], 'base64').toString().includes(name)));
            const row = find(`backups/${archive}`); run('delete', row[2], row[3], 67);
            assert.equal(fs.readFileSync(path.join(pending, 'keep'), 'utf8'), 'original');
            fs.unlinkSync(path.join(pending, 'keep')); fs.rmdirSync(pending);
        }
    });
    check('traversal and arbitrary paths cannot be selected', () => {
        for (const candidate of ['../SillyTavern', 'backups/../file-trash', '/etc/passwd', 'backups/user-file.tar.gz', `backups/update-work-1/${archive}`]) run('delete', b64(candidate), 'a'.repeat(64), candidate.includes('..') || candidate.startsWith('/') ? 66 : 69);
        assert.equal(fs.readFileSync(path.join(backups, 'user-file.tar.gz'), 'utf8'), 'keep');
    });
    check('oversized or malformed trash metadata is excluded and preserved', () => {
        const id = '2345-12345678-abcd-abcd-abcd-123456789abc', directory = makeTrash(id, 'data/a');
        fs.writeFileSync(path.join(directory, 'metadata.json'), 'x'.repeat(8193));
        assert.equal(find(`file-trash/${id}`), undefined);
        run('delete', b64(`file-trash/${id}`), 'a'.repeat(64), 66);
        assert.equal(fs.readFileSync(path.join(directory, 'item'), 'utf8'), 'file');
    });
    check('trash changed after selection cannot be deleted', () => {
        const id = '1234-12345678-abcd-abcd-abcd-123456789abc';
        const row = find(`file-trash/${id}`); fs.appendFileSync(path.join(trash, id, 'item'), 'changed');
        run('delete', row[2], row[3], 65);
        run('validate-trash', row[2], row[3], 65);
        const updated = find(`file-trash/${id}`);
        assert.equal(run('validate-trash', updated[2], updated[3]).trim(), `trash_id=${id}`);
    });
    check('trash from a different installation is not restorable', () => {
        const id = '3456-12345678-abcd-abcd-abcd-123456789abc', directory = makeTrash(id, 'data/a');
        fs.writeFileSync(path.join(directory, 'metadata.json'), JSON.stringify({ root: path.join(base, 'another'), relative: 'data/a', deleted_at: 1234 }));
        assert.equal(find(`file-trash/${id}`)[9], '0');
        const row = find(`file-trash/${id}`); run('validate-trash', row[2], row[3], 69);
    });
    check('nested trash deletion removes only selected entry', () => {
        const id = '4567-12345678-abcd-abcd-abcd-123456789abc', directory = makeTrash(id, 'data/folder');
        fs.unlinkSync(path.join(directory, 'item')); fs.mkdirSync(path.join(directory, 'item/sub'), { recursive: true });
        fs.writeFileSync(path.join(directory, 'item/sub/hello'), 'hello');
        const row = find(`file-trash/${id}`); assert.equal(row[5], '5');
        run('delete', row[2], row[3]); assert(!fs.existsSync(directory));
        assert(fs.existsSync(path.join(trash, '1234-12345678-abcd-abcd-abcd-123456789abc')));
    });
    check('symlink targets are not followed', () => {
        const external = path.join(base, 'outside'); fs.mkdirSync(external); fs.writeFileSync(path.join(external, archive), 'outside');
        const alias = path.join(backups, 'safety-snapshot-456.abcdefgh');
        fs.symlinkSync(external, alias, process.platform === 'win32' ? 'junction' : 'dir');
        assert.equal(find(`backups/safety-snapshot-456.abcdefgh/${archive}`), undefined);
        run('delete', b64(`backups/safety-snapshot-456.abcdefgh/${archive}`), 'a'.repeat(64), 66);
        assert.equal(fs.readFileSync(path.join(external, archive), 'utf8'), 'outside');
    });
    check('pagination remains below callback budget', () => {
        const configs = path.join(backups, 'network-settings');
        for (let index = 0; index < 400; index++) fs.writeFileSync(path.join(configs, `config-20261123-${String(index).padStart(6, '0')}.yaml`), 'config');
        let cursor = '', count = 0; const seen = new Set();
        do {
            const output = run('list', cursor); assert(Buffer.byteLength(output) <= 40 * 1024);
            for (const row of rows(output)) { assert(!seen.has(row[2])); seen.add(row[2]); count++; }
            cursor = output.match(/^next_cursor=(-?\d+)$/m)[1];
        } while (cursor !== '-1');
        assert(count >= 400);
    });
    check('selected archive deletion keeps unrelated files and parent', () => {
        const row = find(`backups/safety-snapshot-123.abcdefgh/${archive}`);
        run('delete', row[2], row[3]); assert(!fs.existsSync(path.join(snapshot, archive)));
        assert(fs.existsSync(snapshot)); assert(fs.existsSync(path.join(backups, 'user-file.tar.gz')));
    });
    console.log(`Passed ${tests} safety-inventory regression groups.`);
} finally {
    // The only recursively removed path is this test's own fresh mkdtemp root.
    assert(path.basename(base).startsWith('st-safety-inventory-test-') && path.dirname(base) === os.tmpdir());
    fs.rmSync(base, { recursive: true, force: true });
}
