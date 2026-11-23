#!/usr/bin/env node
// Fault injection around the optional completion marker; no archive rehashing.
const assert = require('assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const vm = require('vm');
const helper = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/safe-backup.sh'), 'utf8');
const markerCode = helper.slice(helper.indexOf('function recordCompletion()'), helper.indexOf('function prepareStorage()'));
assert(markerCode.includes('function recordCompletion()'));
const base = fs.mkdtempSync(path.join(os.tmpdir(), 'st-safety-completion-test-'));
let count = 0;
function scenario(name, overrides, verify) {
    const parent = path.join(base, name); fs.mkdirSync(parent);
    const archive = path.join(parent, 'before-forced-update-20261123-010203.tar.gz');
    fs.writeFileSync(archive, 'durable original archive');
    const before = fs.lstatSync(archive), parentIdentity = fs.lstatSync(parent);
    const context = { fs: { ...fs, ...overrides }, path, parent, archive, parentIdentity, process,
        sameDirectory: (target, original) => { const stat = fs.lstatSync(target); return stat.isDirectory() && stat.dev === original.dev && stat.ino === original.ino; },
        sameArchive: stat => stat.dev === before.dev && stat.ino === before.ino,
        syncDirectory: () => {}, result: undefined };
    verify(context, parent, () => {
        vm.runInNewContext(`${markerCode}\nresult = recordCompletion();`, context);
        assert.equal(fs.readFileSync(archive, 'utf8'), 'durable original archive');
        assert(!fs.readdirSync(parent).some(name => name.endsWith('.tmp')));
        return context.result;
    });
    console.log(`PASS ${name}`); count++;
}
try {
    scenario('durable marker records exact file identity', {}, (context, parent, run) => {
        assert.equal(run(), true);
        const marker = JSON.parse(fs.readFileSync(path.join(parent, '.st-safety-complete.json'), 'utf8'));
        const actual = fs.lstatSync(context.archive, { bigint: true });
        assert.equal(marker.format, 'st-launcher-safety-complete-v1');
        for (const key of ['dev', 'ino', 'size', 'mtimeNs']) assert.equal(marker[key], actual[key].toString());
    });
    scenario('ENOSPC cannot fail or erase completed archive', { openSync: (...args) => {
        if (String(args[0]).endsWith('.tmp')) throw Object.assign(new Error('disk full'), { code: 'ENOSPC' });
        return fs.openSync(...args);
    } }, (_, parent, run) => { assert.equal(run(), false); assert(!fs.existsSync(path.join(parent, '.st-safety-complete.json'))); });
    scenario('half-written marker never becomes completion', { writeFileSync: (fd, ...args) => {
        fs.writeSync(fd, '{'); throw new Error('interrupted write');
    } }, (_, parent, run) => { assert.equal(run(), false); assert(!fs.existsSync(path.join(parent, '.st-safety-complete.json'))); });
    scenario('rename failure cleans only its own temporary', { renameSync: () => { throw new Error('rename failure'); } },
        (_, parent, run) => { assert.equal(run(), false); assert(!fs.existsSync(path.join(parent, '.st-safety-complete.json'))); });
    scenario('existing marker is never overwritten', {}, (_, parent, run) => {
        fs.writeFileSync(path.join(parent, '.st-safety-complete.json'), 'existing');
        assert.equal(run(), false);
        assert.equal(fs.readFileSync(path.join(parent, '.st-safety-complete.json'), 'utf8'), 'existing');
    });
    scenario('changed parent refuses marker publication', {}, (context, parent, run) => {
        context.sameDirectory = () => false;
        assert.equal(run(), false); assert(!fs.existsSync(path.join(parent, '.st-safety-complete.json')));
    });
    scenario('directory fsync failure cannot leave a half marker', {}, (context, parent, run) => {
        context.syncDirectory = () => { throw new Error('directory sync unsupported'); };
        assert.equal(run(), false);
        assert.equal(JSON.parse(fs.readFileSync(path.join(parent, '.st-safety-complete.json'), 'utf8')).format, 'st-launcher-safety-complete-v1');
    });
    console.log(`Passed ${count} safety completion fault-injection groups.`);
} finally {
    assert(path.basename(base).startsWith('st-safety-completion-test-') && path.dirname(base) === os.tmpdir());
    fs.rmSync(base, { recursive: true, force: true });
}
