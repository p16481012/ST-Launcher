#!/data/data/com.termux/files/usr/bin/bash
# Explicit, read-only storage scan. Never run automatically during app startup.
set -Eeuo pipefail
exec node --input-type=commonjs - "$@" <<'NODE'
const fs = require('fs');
const path = require('path');
const [installation, launcher, downloads, termuxHome, budgetText = '15000', limitText = '250000'] = process.argv.slice(2);
const started = Date.now(), deadline = started + Math.min(30000, Math.max(1, Number(budgetText) || 15000));
const entryLimit = Math.min(500000, Math.max(1, Number(limitText) || 250000));
const categories = ['data', 'node_modules', 'git', 'installation_other', 'safety', 'trash', 'download_backups', 'npm_cache'];
const rows = new Map(categories.map(key => [key, { key, bytes: 0n, files: 0, complete: true, skipped: 0 }]));
const seen = new Set(); let entries = 0, stopped = false, cancelled = false, current;
const backupName = /^SillyTavern-Launcher-[0-9]{8}-[0-9]{6}(-[0-9]+)?( \([0-9]+\))*\.zip$/;
const exportedFolder = /^ST-Launcher-internal-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => { cancelled = true; });
function checkpoint() {
    if (cancelled || [process.env.ST_CANCEL_REQUEST_FILE, process.env.ST_CANCEL_REQUEST_ID_FILE].some(file => file && fs.existsSync(file))) throw Object.assign(new Error('저장 공간 검사를 중단했습니다. 파일은 변경하지 않았습니다.'), { exitCode: 130, errorCode: 'OPERATION_CANCELLED' });
    if (Date.now() >= deadline || entries >= entryLimit) { stopped = true; if (current) current.complete = false; }
}
function safeName(name) { return name && name !== '.' && name !== '..' && !/[\\/\x00-\x1f\x7f]/.test(name); }
function nodeKey(stat) { return `${stat.dev}:${stat.ino}`; }
function lstat(target) { return fs.lstatSync(target, { bigint: true }); }
function classifyError(error, row) {
    if (error.exitCode) throw error;
    row.complete = false; row.skipped++;
}
async function walk(target, row, depth = 0, select = () => true) {
    current = row; checkpoint();
    if (stopped) return;
    if (depth > 64) { row.complete = false; row.skipped++; return; }
    let before;
    try { before = lstat(target); } catch (error) { if (depth === 0 && error.code === 'ENOENT') return; classifyError(error, row); return; }
    entries++;
    if (before.isSymbolicLink()) { row.skipped++; row.complete = false; return; }
    if (!before.isFile() && !before.isDirectory()) { row.skipped++; row.complete = false; return; }
    const key = nodeKey(before);
    if (seen.has(key)) return;
    seen.add(key);
    if (before.isFile()) { row.bytes += before.size; row.files++; }
    else {
        let fd, iterator;
        try {
            fd = fs.openSync(target, fs.constants.O_RDONLY | (fs.constants.O_DIRECTORY || 0) | (fs.constants.O_NOFOLLOW || 0));
            const opened = fs.fstatSync(fd, { bigint: true });
            if (nodeKey(opened) !== key || !opened.isDirectory()) { row.complete = false; row.skipped++; return; }
            const anchored = process.platform === 'linux' ? `/proc/self/fd/${fd}` : target;
            iterator = fs.opendirSync(anchored);
            let item;
            while (!stopped && (item = iterator.readSync())) {
                if (!safeName(item.name)) { entries++; row.skipped++; row.complete = false; }
                else if (!select(item.name)) entries++;
                else await walk(path.join(anchored, item.name), row, depth + 1);
                if (entries % 128 === 0) await new Promise(resolve => setImmediate(resolve));
                checkpoint();
            }
            const after = lstat(target);
            if (nodeKey(after) !== key || before.mtimeNs !== after.mtimeNs || before.ctimeNs !== after.ctimeNs) row.complete = false;
        } catch (error) { classifyError(error, row); }
        finally { if (iterator) iterator.closeSync(); if (fd !== undefined) fs.closeSync(fd); }
    }
    if (entries % 128 === 0) { await new Promise(resolve => setImmediate(resolve)); checkpoint(); }
}
function resolveRoot(value, allowConfiguredAlias = false, allowMissing = false) {
    if (!value || !path.isAbsolute(value)) throw new Error('저장 공간 검사 경로를 확인할 수 없습니다.');
    const absolute = path.resolve(value);
    // Only Downloads may be the manager's configured ~/storage alias. Private
    // installation/launcher/home roots themselves must not be symbolic links.
    let before;
    try { before = lstat(absolute); } catch (error) { if (allowMissing && error.code === 'ENOENT') return absolute; throw error; }
    if (before.isSymbolicLink() && !allowConfiguredAlias) throw new Error('심볼릭 링크인 설치 또는 관리 폴더는 검사하지 않습니다.');
    return fs.realpathSync(absolute);
}
async function main() {
    try {
        const root = resolveRoot(installation, false, true), owner = resolveRoot(launcher, false, true), home = resolveRoot(termuxHome);
        const requests = [
            ['data', path.join(root, 'data')], ['node_modules', path.join(root, 'node_modules')], ['git', path.join(root, '.git')],
            ['installation_other', root, name => !['data', 'node_modules', '.git'].includes(name)],
            ['safety', path.join(owner, 'backups')], ['trash', path.join(owner, 'file-trash')],
        ];
        try { requests.push(['download_backups', resolveRoot(downloads, true), name => backupName.test(name) || exportedFolder.test(name)]); }
        catch (_) { rows.get('download_backups').complete = false; rows.get('download_backups').skipped++; }
        requests.push(['npm_cache', path.join(home, '.npm'), name => name === '_cacache']);
        for (const [key, target, select] of requests) {
            const row = rows.get(key);
            if (stopped) { row.complete = false; continue; }
            await walk(target, row, 0, select);
        }
        checkpoint();
        const total = [...rows.values()].reduce((sum, row) => sum + row.bytes, 0n);
        console.log('storage_usage=1');
        console.log(`elapsed_ms=${Date.now() - started}`);
        console.log(`scanned_entries=${entries}`);
        console.log(`total_bytes=${total}`);
        console.log(`complete=${[...rows.values()].every(row => row.complete) ? 1 : 0}`);
        for (const row of rows.values()) console.log(['storage', row.key, row.bytes, row.files, row.complete ? 1 : 0, row.skipped].join('\t'));
    } catch (error) {
        console.error(`error_code=${error.errorCode || 'STORAGE_SCAN_FAILED'}`);
        console.error(error.exitCode ? error.message : `저장 공간 검사를 완료하지 못했습니다 (${error.code || 'INVALID_PATH'}). 파일은 변경하지 않았습니다.`);
        process.exitCode = error.exitCode || 71;
    }
}
main();
NODE
