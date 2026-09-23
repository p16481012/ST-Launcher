#!/data/data/com.termux/files/usr/bin/bash
# Private tar.gz safety snapshots. The manager owns the operation lock/result.
set -Eeuo pipefail
exec node --input-type=commonjs - "$@" <<'NODE'
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const { Transform } = require('stream');
const { pipeline } = require('stream/promises');
const { spawn } = require('child_process');
const [rootArgument, archiveArgument, layout = 'contents'] = process.argv.slice(2);
const now = () => Math.floor(Date.now() / 1000);
const clean = value => String(value ?? '').replace(/[\x00-\x1f\x7f]/g, ' ').slice(0, 1024);
const phase = clean(process.env.ST_PROGRESS_PHASE || '안전 백업');
const started = now();
const state = { progress_mode: 'indeterminate', completed_bytes: 0, total_bytes: 0,
    completed_files: 0, total_files: 0, current_item_b64: '', heartbeat_at: started,
    activity_at: started, phase_started_at: started };
let lastWrite = 0, lastLog = 0, lastFingerprint = '', lastSpace = 0;
let currentDetail = '';
let child, output, work, workIdentity, parentIdentity, parent, root, archive, timer;
let committed = false, interrupted = false, timerError = null, reserve = 33554432;
const entries = [];
function failure(message, code = 36) { return Object.assign(new Error(message), { exitCode: code }); }
function checkAbort() {
    if (timerError) throw timerError;
    if (interrupted) throw failure('안전 백업이 중단되었습니다.', 130);
}
function bytes(value) {
    const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB']; let index = 0;
    while (value >= 1024 && index < units.length - 1) { value /= 1024; index++; }
    return `${index ? value.toFixed(1) : value} ${units[index]}`;
}
function append(target, text) {
    if (!target) return;
    fs.appendFileSync(target, text, { mode: 0o600 });
    if (target !== process.env.ST_PROGRESS_HISTORY || fs.statSync(target).size <= 1000000) return;
    const fd = fs.openSync(target, 'r'), tail = Buffer.alloc(750000);
    try { fs.readSync(fd, tail, 0, tail.length, fs.fstatSync(fd).size - tail.length); }
    finally { fs.closeSync(fd); }
    const newline = tail.indexOf(10), temporary = `${target}.safety.${process.pid}.tmp`;
    fs.writeFileSync(temporary, tail.subarray(newline < 0 ? 0 : newline + 1), { mode: 0o600 });
    fs.renameSync(temporary, target);
}
function log(text) {
    const line = `[${new Date().toLocaleString('sv-SE', { hour12: false })}] [${clean(process.env.ST_PROGRESS_OPERATION)}] ${phase} · ${clean(text)}\n`;
    append(process.env.ST_PROGRESS_LOG, line);
    if (process.env.ST_PROGRESS_HISTORY !== process.env.ST_PROGRESS_LOG) append(process.env.ST_PROGRESS_HISTORY, line);
}
function publish(force = false, detail = '') {
    if (!force && Date.now() - lastWrite < 500) return;
    if (detail) currentDetail = detail;
    lastWrite = Date.now(); state.heartbeat_at = now();
    const fingerprint = [state.progress_mode, state.completed_bytes, state.completed_files, state.current_item_b64, currentDetail].join('|');
    if (fingerprint !== lastFingerprint && (force || Date.now() - lastLog >= 2000)) {
        lastFingerprint = fingerprint; lastLog = Date.now();
        const item = clean(Buffer.from(state.current_item_b64, 'base64').toString('utf8'));
        const counters = state.progress_mode === 'indeterminate' ? '' : `${bytes(state.completed_bytes)} / ${bytes(state.total_bytes)} · 파일 ${state.completed_files}/${state.total_files}개`;
        log([detail || (state.progress_mode === 'indeterminate' ? '대상 확인 중' : ''), counters, item].filter(Boolean).join(' · '));
    }
    if (!process.env.ST_PROGRESS_FILE) return;
    const total = state.progress_mode === 'bytes' ? state.total_bytes : state.total_files;
    const completed = state.progress_mode === 'bytes' ? state.completed_bytes : state.completed_files;
    const values = { percent: state.progress_mode === 'indeterminate' || !total ? 0 : Math.min(100, Math.floor(completed * 100 / total)),
        phase, detail: currentDetail || (state.progress_mode === 'indeterminate' ? '대상과 저장 공간을 확인하고 있습니다.' : '실제로 압축에 전달한 원본 바이트와 파일 수입니다. 압축 마무리까지 원본을 변경하지 않습니다.'),
        status: 'running', operation: clean(process.env.ST_PROGRESS_OPERATION), error_code: '',
        operation_started_at: Number(process.env.ST_OPERATION_STARTED_EPOCH) || 0, ...state };
    const temporary = `${process.env.ST_PROGRESS_FILE}.safety.${process.pid}.tmp`;
    fs.writeFileSync(temporary, Object.entries(values).map(([key, value]) => `${key}=${value}\n`).join(''), { mode: 0o600 });
    fs.renameSync(temporary, process.env.ST_PROGRESS_FILE);
}
function activity(relative) {
    state.activity_at = now();
    if (relative !== undefined) state.current_item_b64 = Buffer.from(clean(relative)).toString('base64');
}
function same(a, b) { return ['dev', 'ino', 'mode', 'size', 'mtimeMs', 'ctimeMs'].every(key => a[key] === b[key]); }
function sameDirectory(target, identity) {
    const current = fs.lstatSync(target);
    return current.isDirectory() && !current.isSymbolicLink() && current.dev === identity.dev && current.ino === identity.ino;
}
function checkSpace(required = reserve, force = false) {
    if (!force && Date.now() - lastSpace < 500) return;
    lastSpace = Date.now();
    const stat = fs.statfsSync(parent, { bigint: true });
    if (stat.bavail * stat.bsize < BigInt(Math.ceil(required))) throw failure('안전 백업을 저장할 공간이 부족합니다. 원본은 정리하지 않았습니다.', 35);
}
async function plan(relative = '') {
    checkAbort();
    const target = relative ? path.join(root, relative) : root;
    const stat = fs.lstatSync(target);
    if (!stat.isFile() && !stat.isDirectory() && !stat.isSymbolicLink() && !stat.isFIFO() && !stat.isCharacterDevice() && !stat.isBlockDevice()) {
        throw failure(`안전하게 보관할 수 없는 항목입니다: ${clean(relative)}`);
    }
    const entry = { relative, stat, names: null };
    entries.push(entry);
    if (entries.length > 500000) throw failure('안전 백업 대상 항목이 안전 한도를 초과합니다.');
    if (stat.isFile()) state.total_bytes += stat.size;
    if (!stat.isDirectory()) state.total_files++;
    if (!Number.isSafeInteger(state.total_bytes)) throw failure('안전 백업 크기가 안전 한도를 초과합니다.');
    activity(relative); publish();
    if (entries.length % 128 === 0) await new Promise(resolve => setImmediate(resolve));
    if (stat.isDirectory()) {
        entry.names = fs.readdirSync(target).filter(name => name !== '.git' && name !== 'node_modules').sort();
        for (const name of entry.names) await plan(relative ? `${relative}/${name}` : name);
    }
}
function verify(entry) {
    const target = entry.relative ? path.join(root, entry.relative) : root;
    let current;
    try { current = fs.lstatSync(target); } catch (_) { throw failure('안전 백업 중 원본 항목이 변경되었습니다. 다시 시도해 주세요.', 37); }
    if (!same(entry.stat, current)) throw failure('안전 백업 중 원본 파일이 변경되었습니다. 다시 시도해 주세요.', 37);
    if (entry.names && JSON.stringify(entry.names) !== JSON.stringify(fs.readdirSync(target).filter(name => name !== '.git' && name !== 'node_modules').sort())) {
        throw failure('안전 백업 중 원본 폴더 내용이 변경되었습니다. 다시 시도해 주세요.', 37);
    }
}
// Parse only the tar framing, without storing file contents. GNU tar remains
// responsible for metadata, hard links, long names, permissions and timestamps.
function meter() {
    let header = Buffer.alloc(0), remaining = 0, padding = 0, type = '', metadata = [], metadataBytes = 0;
    let pax = {}, index = 0, current = null, regular = false;
    function number(block) {
        if (block[0] & 0x80) {
            let value = BigInt(block[0] & 0x7f);
            for (const byte of block.subarray(1)) value = value * 256n + BigInt(byte);
            if (value > BigInt(Number.MAX_SAFE_INTEGER)) throw failure('안전 백업 항목 크기가 안전 한도를 초과합니다.');
            return Number(value);
        }
        const text = block.toString('ascii').replace(/\0.*$/, '').trim();
        if (text && !/^[0-7]+$/.test(text)) throw failure('안전 백업 TAR 헤더가 올바르지 않습니다.');
        return text ? parseInt(text, 8) : 0;
    }
    function finish() {
        if (type === 'x' || type === 'g') {
            const data = Buffer.concat(metadata); let cursor = 0;
            while (cursor < data.length) {
                const space = data.indexOf(32, cursor), length = Number(data.subarray(cursor, space).toString('ascii'));
                if (space < cursor || !Number.isSafeInteger(length) || length <= space - cursor + 1 || cursor + length > data.length) throw failure('안전 백업 메타데이터가 올바르지 않습니다.');
                const text = data.subarray(space + 1, cursor + length - 1).toString('utf8'), equals = text.indexOf('=');
                if (equals > 0) pax[text.slice(0, equals)] = text.slice(equals + 1);
                cursor += length;
            }
        } else if (current) {
            if (!current.stat.isDirectory()) state.completed_files++;
            activity(); publish(); current = null;
        }
    }
    return new Transform({ transform(chunk, encoding, callback) {
        try {
            checkSpace(); let cursor = 0;
            while (cursor < chunk.length) {
                if (remaining) {
                    const count = Math.min(remaining, chunk.length - cursor);
                    if (type === 'x' || type === 'g') {
                        metadataBytes += count;
                        if (metadataBytes > 4194304) throw failure('안전 백업 메타데이터가 너무 큽니다.');
                        metadata.push(chunk.subarray(cursor, cursor + count));
                    } else if (regular) { state.completed_bytes += count; activity(); publish(); }
                    cursor += count; remaining -= count;
                    if (!remaining) finish();
                } else if (padding) {
                    const count = Math.min(padding, chunk.length - cursor); cursor += count; padding -= count;
                } else {
                    const count = Math.min(512 - header.length, chunk.length - cursor);
                    header = Buffer.concat([header, chunk.subarray(cursor, cursor + count)]); cursor += count;
                    if (header.length !== 512) continue;
                    if (header.every(byte => byte === 0)) { header = Buffer.alloc(0); continue; }
                    type = String.fromCharCode(header[156] || 48); remaining = number(header.subarray(124, 136));
                    metadata = []; metadataBytes = 0; regular = type === '0' || type === '7';
                    if (type !== 'x' && type !== 'g') {
                        current = entries[index++];
                        if (!current) throw failure('예상하지 못한 안전 백업 항목입니다.', 37);
                        if (pax.size !== undefined) {
                            remaining = Number(pax.size);
                            if (!Number.isSafeInteger(remaining) || remaining < 0) throw failure('안전 백업 크기가 올바르지 않습니다.');
                        }
                        pax = {}; verify(current);
                        if (regular && (current.hardLink || remaining !== current.stat.size)) throw failure('안전 백업 원본 크기 또는 링크가 변경되었습니다.', 37);
                        if (type === '1' && !current.hardLink) throw failure('안전 백업 원본 링크가 변경되었습니다.', 37);
                        activity(current.relative); publish();
                    }
                    padding = (512 - remaining % 512) % 512;
                    header = Buffer.alloc(0);
                    if (!remaining) finish();
                }
            }
            callback(null, chunk);
        } catch (error) { callback(error); }
    }, flush(callback) {
        if (remaining || padding || header.length || index !== entries.length || state.completed_files !== state.total_files || state.completed_bytes !== state.total_bytes) callback(failure('안전 백업 처리량 검사가 일치하지 않습니다.'));
        else {
            try {
                state.progress_mode = 'indeterminate';
                publish(true, '원본 데이터 읽기를 마쳤습니다. 압축 파일의 마무리와 디스크 기록 완료를 기다립니다.');
                callback();
            } catch (error) { callback(error); }
        }
    } });
}
async function main() {
    if (!rootArgument || !archiveArgument || !['contents', 'directory'].includes(layout)) throw failure('안전 백업 인수가 올바르지 않습니다.', 64);
    root = fs.realpathSync(rootArgument); archive = path.resolve(archiveArgument); parent = fs.realpathSync(path.dirname(archive));
    archive = path.join(parent, path.basename(archive));
    if (archive === root || archive.startsWith(root + path.sep)) throw failure('안전 백업은 원본 폴더 밖에 저장해야 합니다.');
    if (fs.existsSync(archive)) throw failure('같은 이름의 안전 백업이 이미 있어 덮어쓰지 않았습니다.');
    if (!fs.lstatSync(root).isDirectory()) throw failure('안전 백업 원본 폴더를 찾을 수 없습니다.');
    parentIdentity = fs.lstatSync(parent);
    const reserveText = process.env.ST_SAFETY_RESERVE_BYTES || String(reserve);
    if (!/^\d+$/.test(reserveText) || !Number.isSafeInteger(Number(reserveText))) throw failure('안전 백업 여유 공간 설정이 올바르지 않습니다.');
    reserve = Number(reserveText);
    publish(true);
    await plan();
    checkAbort();
    const hardLinks = new Set();
    for (const entry of entries) if (entry.stat.isFile() && entry.stat.nlink > 1) {
        const identity = `${entry.stat.dev}:${entry.stat.ino}`;
        entry.hardLink = hardLinks.has(identity);
        if (entry.hardLink) state.total_bytes -= entry.stat.size;
        else hardLinks.add(identity);
    }
    // Conservative bound includes per-entry PAX/long-path metadata, padding,
    // incompressible gzip overhead and space reserved for the following work.
    const metadata = entries.reduce((sum, entry) => sum + 8192 + Buffer.byteLength(entry.relative) * 4 + (entry.stat.isSymbolicLink() ? Buffer.byteLength(fs.readlinkSync(path.join(root, entry.relative))) * 4 : 0), 10240);
    const required = Math.ceil((state.total_bytes + metadata) * 1.02) + reserve;
    checkSpace(required, true);
    log(`대상 확인 완료 · 원본 ${bytes(state.total_bytes)} · 파일 ${state.total_files}개 · 안전 여유 포함 필요 공간 ${bytes(required)}`);
    const workParent = process.env.ST_SAFETY_WORK_PARENT || parent;
    work = fs.mkdtempSync(path.join(workParent, '.st-safety-')); fs.chmodSync(work, 0o700); workIdentity = fs.lstatSync(work);
    const list = path.join(work, 'paths'), temporary = path.join(work, 'archive.tar.gz');
    const prefix = layout === 'directory' ? path.basename(root) : '.';
    fs.writeFileSync(list, Buffer.from(entries.map(entry => `${prefix}${entry.relative ? `/${entry.relative}` : ''}\0`).join('')), { mode: 0o600 });
    output = fs.createWriteStream(temporary, { flags: 'wx', mode: 0o600 });
    state.progress_mode = state.total_bytes > 0 ? 'bytes' : 'files'; publish(true);
    child = spawn('tar', ['--format=pax', '--no-recursion', '--null', '--verbatim-files-from', '-cf', '-', '-C', layout === 'directory' ? path.dirname(root) : root, '-T', list],
        { env: { ...process.env, LC_ALL: 'C', TAR_OPTIONS: '' }, stdio: ['ignore', 'pipe', 'pipe'] });
    let errors = '';
    child.stderr.on('data', chunk => { errors = (errors + chunk.toString('utf8')).slice(-16384); process.stderr.write(chunk); });
    const closed = new Promise(resolve => {
        child.once('error', error => resolve({ error }));
        child.once('close', (code, signal) => resolve({ code, signal }));
    });
    try { await pipeline(child.stdout, meter(), zlib.createGzip(), output); }
    catch (error) { child.kill('SIGTERM'); await closed; throw error; }
    const result = await closed; child = null;
    if (result.error || result.signal || result.code !== 0) throw failure(`안전 백업 압축에 실패했습니다.${errors ? ` ${clean(errors)}` : ''}`, result.code === 1 ? 37 : 36);
    state.progress_mode = 'indeterminate'; publish(true, '압축은 끝났습니다. 원본 변경 여부와 저장 결과를 검증하고 있습니다.');
    for (let index = 0; index < entries.length; index++) {
        checkAbort();
        verify(entries[index]); checkSpace();
        if (index % 128 === 0) { activity(entries[index].relative); publish(); await new Promise(resolve => setImmediate(resolve)); }
    }
    checkAbort();
    checkSpace(reserve, true);
    if (!sameDirectory(parent, parentIdentity) || !sameDirectory(work, workIdentity)) throw failure('안전 백업 저장 위치가 변경되었습니다.');
    const fd = fs.openSync(temporary, 'r+'); try { fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
    // Hard-link publication is atomic and exclusive: never replace another snapshot.
    fs.linkSync(temporary, archive);
    // Persist the published directory entry before allowing destructive Git
    // cleanup. Windows is only a development test host; Android/Linux must
    // support and successfully complete directory fsync.
    if (process.platform !== 'win32') {
        const directory = fs.openSync(parent, fs.constants.O_RDONLY | fs.constants.O_DIRECTORY | (fs.constants.O_NOFOLLOW || 0));
        try { fs.fsyncSync(directory); } finally { fs.closeSync(directory); }
    }
    committed = true;
    state.progress_mode = state.total_bytes > 0 ? 'bytes' : 'files'; activity(); publish(true, '안전 백업 저장과 원본 변경 검사를 완료했습니다.');
    log(`안전 백업 저장 및 원본 변경 검사 완료 · 압축 파일 ${bytes(fs.statSync(archive).size)}`);
    console.log(`safety_backup_b64=${Buffer.from(archive).toString('base64')}`);
}
for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => {
    interrupted = true;
    if (child) child.kill(signal);
    if (output) output.destroy(failure('안전 백업이 중단되었습니다.', 130));
});
timer = setInterval(() => {
    try { publish(); if (parent && work) checkSpace(); }
    catch (error) { timerError = error; if (child) child.kill('SIGTERM'); if (output) output.destroy(error); }
}, 500);
main().catch(error => {
    error = timerError || error;
    const code = error.code === 'ENOSPC' ? 35 : (interrupted ? 130 : (error.exitCode || 36));
    try { log(`실패 · ${clean(error.message)} · 미완성 압축은 정리하며 원본은 유지합니다.`); } catch (_) {}
    console.error(clean(error.message)); process.exitCode = code;
}).finally(() => {
    clearInterval(timer);
    if (work) {
        try {
            if (sameDirectory(parent, parentIdentity) && sameDirectory(work, workIdentity)) fs.rmSync(work, { recursive: true });
            else throw failure('안전 백업 임시 위치가 변경되어 자동 정리하지 않았습니다.');
        } catch (error) { console.error(clean(error.message)); if (!committed) process.exitCode = process.exitCode || 36; }
    }
});
NODE
