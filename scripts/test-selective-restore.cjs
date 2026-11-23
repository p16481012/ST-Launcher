#!/usr/bin/env node
const assert = require('assert/strict'), fs = require('fs'), path = require('path'), os = require('os'), { spawnSync } = require('child_process');
const helper = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/selective-restore-data.sh'), 'utf8').replace(/\r\n/g, '\n');
const code = helper.split("<<'NODE'\n")[1]?.split('\nNODE')[0]; assert(code);
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'st-selective-restore-test-'));
const install = path.join(root, 'SillyTavern'), archive = path.join(root, 'backup.zip'), index = path.join(root, 'index.json');
const card = 'data/default-user/characters/Alice.png', chat = 'data/default-user/chats/Alice/chat.jsonl';
let count = 0, planCount = 0;
function write(relative, data = 'original') { const target = path.join(root, relative); fs.mkdirSync(path.dirname(target), { recursive: true }); fs.writeFileSync(target, data); return target; }
function makeIndex(paths = [card, chat, 'data/default-user/secrets.json', 'server.js']) {
    const source = fs.lstatSync(archive);
    fs.writeFileSync(index, JSON.stringify({ archive: Object.fromEntries(['size', 'mtimeMs', 'dev', 'ino'].map(k => [k, source[k]])), entries: paths.map(p => ({ path: p, isDirectory: false, bytes: 7, method: 0, dataOffset: 0, compressedBytes: 7, crc32: 0, mode: 33152 })) }));
}
function run(action, argument = '0', options = {}) {
    const prefix = (options.oversizeArgument ? "process.argv[6] = 'x'.repeat(65540);\n" : '') + (options.injection || '');
    const result = spawnSync(process.execPath, ['-e', prefix + code, '--', '-', action, options.index || index, archive, install, argument, options.output || '', options.archiveRevision || '', options.installationRevision || ''], { encoding: 'utf8', timeout: 20000 });
    assert.equal(result.status, options.status || 0, result.stderr || String(result.error));
    const lines = result.stdout.trim().split('\n');
    return { raw: result.stdout, header: Object.fromEntries(lines.filter(l => l.includes('=')).map(l => l.split('='))), rows: lines.filter(l => l.startsWith('restore_item\t')).map(l => { const f = l.split('\t'); return { kind: f[1], path: Buffer.from(f[2], 'base64').toString(), bytes: Number(f[3]), exists: f[4] === '1', targetRevision: f[5], missing: f[6] === '1', profileExists: f[7] === '1' }; }) };
}
function plan(report, selections, status = 0) {
    const output = path.join(root, `plan-${planCount++}.json`), options = { output, archiveRevision: report.header.archive_revision, installationRevision: report.header.installation_revision, status };
    const result = run('plan', Buffer.from(JSON.stringify(selections)).toString('base64'), options);
    return { ...result, output, value: status ? null : JSON.parse(fs.readFileSync(output, 'utf8')) };
}
function selected(row, policy = 'skip') { return { path: row.path, targetRevision: row.targetRevision, policy }; }
function check(name, fn) { fn(); count++; console.log(`PASS ${name}`); }
try {
    write('backup.zip', 'fixture'); write(`SillyTavern/${card}`); makeIndex();
    check('only character/chat entries, no secrets or program files', () => {
        const report = run('list'); assert.equal(report.rows.length, 2); assert.equal(report.rows[0].path, card); assert(report.rows[0].exists); assert(!report.rows[1].missing);
    });
    check('skip is default and replacement must be individually explicit', () => {
        const report = run('list'), skipped = plan(report, report.rows.map(row => selected(row)));
        assert.equal(skipped.header.selection_skipped, '1'); assert.deepEqual(skipped.value.items.map(x => x.path), [chat]);
        const replaced = plan(report, report.rows.map(row => selected(row, 'replace')));
        assert.deepEqual(replaced.value.items.map(x => x.path), [card, chat]);
        assert.equal(JSON.parse(fs.readFileSync(`${replaced.output}.index`)).entries.length, 2);
        assert.equal(fs.readFileSync(path.join(install, card), 'utf8'), 'original');
    });
    check('new or changed target invalidates old selection', () => {
        const report = run('list'); write(`SillyTavern/${chat}`, 'new live chat');
        plan(report, [selected(report.rows[1], 'replace')], 73); fs.unlinkSync(path.join(install, chat));
        const ready = plan(run('list'), [selected(run('list').rows[0], 'replace')]);
        write(`SillyTavern/${card}`, 'edited while preparing');
        run('verify-targets', '0', { index: ready.output, status: 73 });
    });
    check('missing character blocks chat alone, paired card succeeds', () => {
        fs.unlinkSync(path.join(install, card)); const report = run('list'); assert(report.rows[1].missing);
        plan(report, [selected(report.rows[1])], 74);
        const ready = plan(report, report.rows.map(row => selected(row))); assert.equal(ready.value.items.length, 2);
        run('verify-targets', '0', { index: ready.output });
    });
    check('unknown profile cannot silently create or replace a user', () => {
        makeIndex(['data/other-user/characters/Alice.png']); const report = run('list'); assert(!report.rows[0].profileExists);
        plan(report, [selected(report.rows[0])], 76); makeIndex();
    });
    check('archive change, malformed requests, duplicates and bounds fail closed', () => {
        const report = run('list'); write('backup.zip', 'changed archive'); makeIndex(); plan(report, [selected(report.rows[0])], 72);
        const fresh = run('list'); plan(fresh, [selected(fresh.rows[0]), selected(fresh.rows[0])], 75);
        plan(fresh, [{ ...selected(fresh.rows[0]), path: '../outside.png' }], 75);
        run('plan', '0', { oversizeArgument: true, archiveRevision: fresh.header.archive_revision, installationRevision: fresh.header.installation_revision, status: 75 });
    });
    check('target ancestor symlink is rejected, outside file is untouched', () => {
        const folder = path.join(install, 'data/default-user/characters'), outside = path.join(root, 'outside');
        fs.mkdirSync(outside); fs.writeFileSync(path.join(outside, 'Alice.png'), 'outside'); fs.rmdirSync(folder);
        fs.symlinkSync(outside, folder, process.platform === 'win32' ? 'junction' : 'dir');
        try { run('list', '0', { status: 75 }); assert.equal(fs.readFileSync(path.join(outside, 'Alice.png'), 'utf8'), 'outside'); }
        finally { fs.unlinkSync(folder); fs.mkdirSync(folder); }
    });
    check('apply pins destination parents and rejects an in-installation symlink swap', () => {
        const targetFolder = path.join(install, 'data/default-user/chats/Alice'), saved = targetFolder + '-saved';
        const other = path.join(install, 'data/default-user/chats/Other'); fs.mkdirSync(other, { recursive: true });
        fs.writeFileSync(path.join(other, 'chat.jsonl'), 'unselected live chat');
        write(`SillyTavern/${card}`);
        const staged = path.join(root, 'extracted'); write('extracted/' + chat, 'newdata');
        const report = run('list'), planned = plan(report, [selected(report.rows.find(row => row.path === chat))]);
        const injection = `const sf=require('fs'), oldOpen=sf.openSync; let opens=0; sf.openSync=function(p,...args){
if(String(p).replace(/\\\\/g,'/').endsWith('/Alice') && ++opens===2){sf.renameSync(${JSON.stringify(targetFolder)},${JSON.stringify(saved)});sf.symlinkSync(${JSON.stringify(other)},${JSON.stringify(targetFolder)},process.platform==='win32'?'junction':'dir');}
return oldOpen.call(this,p,...args);};\n`;
        try {
            run('apply-item', chat, { index: planned.output, output: staged, injection, status: process.platform === 'win32' ? 73 : 75 });
            assert.equal(fs.readFileSync(path.join(other, 'chat.jsonl'), 'utf8'), 'unselected live chat');
            assert.equal(fs.readFileSync(path.join(staged, chat), 'utf8'), 'newdata');
        } finally { if (fs.existsSync(saved)) { fs.unlinkSync(targetFolder); fs.renameSync(saved, targetFolder); } }
    });
    check('bounded pages preserve cursor and unicode names', () => {
        makeIndex(Array.from({ length: 800 }, (_, i) => `data/default-user/characters/캐릭터-${String(i).padStart(4, '0')}.png`));
        const first = run('list'); assert(Buffer.byteLength(first.raw) <= 40960); assert(Number(first.header.next_cursor) > 0);
        const second = run('list', first.header.next_cursor); assert.notEqual(first.rows[0].path, second.rows[0].path);
    });
    console.log(`Passed ${count} selective restore planning regression groups.`);
} finally {
    assert(path.basename(root).startsWith('st-selective-restore-test-') && path.dirname(root) === os.tmpdir());
    fs.rmSync(root, { recursive: true, force: true });
}
