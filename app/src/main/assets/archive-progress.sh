#!/data/data/com.termux/files/usr/bin/bash
# Standalone worker: manager.sh validates the ZIP and owns operation completion.
set -Eeuo pipefail
exec node --input-type=commonjs - "$@" <<'NODE'
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const { Transform, Writable } = require('stream');
const { pipeline } = require('stream/promises');
const { spawn } = require('child_process');

const [action, ...args] = process.argv.slice(2);
const now = () => Math.floor(Date.now() / 1000);
const clean = value => String(value ?? '').replace(/[\x00-\x1f\x7f]/g, ' ').slice(0, 1024);
const started = now();
const progress = {
    progress_mode: 'indeterminate', completed_bytes: 0, total_bytes: 0,
    completed_files: 0, total_files: 0, current_item_b64: '',
    heartbeat_at: started, activity_at: started, phase_started_at: started,
};
let lastWrite = 0, currentOutput = null, currentBase = 0, child = null;
let lastActivityLog = 0, lastActivityState = '';
function readableBytes(bytes) {
    const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];
    let unit = 0, value = bytes;
    while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
    return `${unit ? value.toFixed(1) : value} ${units[unit]}`;
}
function appendHistory(target, line) {
    fs.appendFileSync(target, line, { mode: 0o600 });
    const size = fs.statSync(target).size;
    if (size <= 1000000) return;
    const fd = fs.openSync(target, 'r'), tail = Buffer.alloc(750000);
    try { fs.readSync(fd, tail, 0, tail.length, size - tail.length); }
    finally { fs.closeSync(fd); }
    const newline = tail.indexOf(10), temporary = `${target}.archive.${process.pid}.tmp`;
    fs.writeFileSync(temporary, newline >= 0 ? tail.subarray(newline + 1) : tail, { mode: 0o600 });
    fs.renameSync(temporary, target);
}
function writeActivityLog(force) {
    const fingerprint = [progress.progress_mode, progress.completed_bytes, progress.total_bytes, progress.completed_files,
        progress.total_files, progress.current_item_b64].join('|');
    if (fingerprint === lastActivityState || (!force && Date.now() - lastActivityLog < 2000)) return;
    lastActivityLog = Date.now();
    lastActivityState = fingerprint;
    const stamp = new Date().toLocaleString('sv-SE', { hour12: false });
    const item = clean(Buffer.from(progress.current_item_b64, 'base64').toString('utf8')).slice(0, 240);
    const counters = progress.progress_mode === 'bytes' ?
        `${readableBytes(progress.completed_bytes)} / ${readableBytes(progress.total_bytes)} · ` : '';
    const measured = progress.progress_mode === 'indeterminate' ? '처리량 확인 중' : `${counters}파일 ${progress.completed_files}/${progress.total_files}개`;
    const line = `[${stamp}] [${clean(process.env.ST_PROGRESS_OPERATION)}] ${clean(process.env.ST_PROGRESS_PHASE)} · ${item || '처리 항목 확인'} · ${measured}\n`;
    if (process.env.ST_PROGRESS_LOG) fs.appendFileSync(process.env.ST_PROGRESS_LOG, line, { mode: 0o600 });
    if (process.env.ST_PROGRESS_HISTORY && process.env.ST_PROGRESS_HISTORY !== process.env.ST_PROGRESS_LOG) {
        appendHistory(process.env.ST_PROGRESS_HISTORY, line);
    }
}
function activity(item) {
    progress.activity_at = now();
    if (item !== undefined) progress.current_item_b64 = Buffer.from(clean(item)).toString('base64');
}
function writeProgress(force = false) {
    if (!force && Date.now() - lastWrite < 500) return;
    lastWrite = Date.now();
    if (currentOutput) {
        const written = currentBase + currentOutput.bytesWritten;
        if (written > progress.completed_bytes) activity();
        progress.completed_bytes = written;
    }
    progress.heartbeat_at = now();
    writeActivityLog(force);
    const total = progress.progress_mode === 'bytes' ? progress.total_bytes : progress.total_files;
    const completed = progress.progress_mode === 'bytes' ? progress.completed_bytes : progress.completed_files;
    const percent = total > 0 ? Math.min(100, Math.floor(completed * 100 / total)) : 0;
    const target = process.env.ST_PROGRESS_FILE;
    if (!target) return;
    const values = {
        percent, phase: clean(process.env.ST_PROGRESS_PHASE), detail: clean(process.env.ST_PROGRESS_DETAIL),
        status: 'running', operation: clean(process.env.ST_PROGRESS_OPERATION), error_code: '', ...progress,
    };
    const temporary = `${target}.archive.${process.pid}.tmp`;
    fs.writeFileSync(temporary, Object.entries(values).map(([key, value]) => `${key}=${value}\n`).join(''), { mode: 0o600 });
    fs.renameSync(temporary, target);
}
function fail(code, message) { const error = new Error(message); error.exitCode = code; throw error; }
function safeInteger(value) { return Number.isSafeInteger(value) && value >= 0; }
function safeRelative(value) {
    return typeof value === 'string' && value.length > 0 && !/[\x00-\x1f\x7f\\:]/.test(value) &&
        !value.startsWith('/') && value.split('/').every(part => part && part !== '.' && part !== '..');
}

// Node 22+ provides a native CRC implementation. Slicing-by-eight keeps the
// older Node fallback bounded and avoids an expensive per-byte stream transform.
const crcTables = Array.from({ length: 8 }, () => new Uint32Array(256));
for (let i = 0; i < 256; i++) {
    let c = i;
    for (let bit = 0; bit < 8; bit++) c = (c >>> 1) ^ ((c & 1) ? 0xedb88320 : 0);
    crcTables[0][i] = c >>> 0;
}
for (let n = 1; n < 8; n++) for (let i = 0; i < 256; i++) {
    const c = crcTables[n - 1][i];
    crcTables[n][i] = ((c >>> 8) ^ crcTables[0][c & 255]) >>> 0;
}
function crcFallback(buffer, previous = 0) {
    let crc = (previous ^ 0xffffffff) >>> 0, i = 0;
    for (; i + 8 <= buffer.length; i += 8) {
        crc ^= buffer.readUInt32LE(i);
        crc = crcTables[7][crc & 255] ^ crcTables[6][(crc >>> 8) & 255] ^
            crcTables[5][(crc >>> 16) & 255] ^ crcTables[4][crc >>> 24] ^
            crcTables[3][buffer[i + 4]] ^ crcTables[2][buffer[i + 5]] ^
            crcTables[1][buffer[i + 6]] ^ crcTables[0][buffer[i + 7]];
    }
    for (; i < buffer.length; i++) crc = (crc >>> 8) ^ crcTables[0][(crc ^ buffer[i]) & 255];
    return (crc ^ 0xffffffff) >>> 0;
}
const crc32 = typeof zlib.crc32 === 'function' ? zlib.crc32 : crcFallback;

async function extract(archive, destination, indexPath, verifyOnly = false) {
    if (!archive || (!destination && !verifyOnly) || !indexPath) fail(64, 'ZIP 처리 인수가 부족합니다.');
    if (fs.statSync(indexPath).size > 268435456) fail(20, 'ZIP 항목 정보가 너무 큽니다.');
    const index = JSON.parse(fs.readFileSync(indexPath, 'utf8'));
    if (!index || !index.archive || !Array.isArray(index.entries) || index.entries.length > 500000) fail(20, 'ZIP 항목 정보가 올바르지 않습니다.');
    const fd = fs.openSync(archive, fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW || 0));
    try {
        const source = fs.fstatSync(fd);
        if (!source.isFile() || ['size', 'mtimeMs', 'dev', 'ino'].some(key => source[key] !== index.archive[key])) {
            fail(20, '검사 후 ZIP 파일이 변경되었습니다. 다시 가져와 주세요.');
        }
        const root = verifyOnly ? null : path.resolve(destination);
        if (!verifyOnly) {
            if (fs.lstatSync(root).isSymbolicLink() || !fs.statSync(root).isDirectory()) fail(22, 'ZIP 해제 폴더가 안전하지 않습니다.');
            if (fs.readdirSync(root).length) fail(21, 'ZIP은 빈 임시 폴더에만 해제할 수 있습니다.');
        }
        const seen = new Map();
        let total = 0;
        for (const entry of index.entries) {
            if (!safeRelative(entry.path) || seen.has(entry.path)) fail(21, 'ZIP 항목 경로가 안전하지 않습니다.');
            seen.set(entry.path, entry.isDirectory);
            if (typeof entry.isDirectory !== 'boolean' || ![0, 8].includes(entry.method) ||
                !['dataOffset', 'compressedBytes', 'bytes', 'crc32', 'mode'].every(key => safeInteger(entry[key])) ||
                entry.crc32 > 0xffffffff || entry.mode > 0xffff || entry.dataOffset + entry.compressedBytes > source.size ||
                (entry.method === 0 && entry.compressedBytes !== entry.bytes) ||
                (entry.isDirectory && entry.bytes !== 0)) fail(20, 'ZIP 항목 크기 또는 종류가 올바르지 않습니다.');
            total += entry.bytes;
            if (!Number.isSafeInteger(total) || total > 1099511627776) fail(29, 'ZIP 해제 크기가 안전 한도를 초과합니다.');
        }
        for (const name of seen.keys()) {
            const parts = name.split('/'); parts.pop();
            while (parts.length) {
                const parent = parts.join('/');
                if (seen.has(parent) && !seen.get(parent)) fail(21, 'ZIP 파일과 폴더 경로가 충돌합니다.');
                parts.pop();
            }
        }
        progress.total_bytes = total;
        progress.total_files = index.entries.filter(entry => !entry.isDirectory).length;
        progress.progress_mode = total > 0 ? 'bytes' : 'files';
        writeProgress(true);
        const directories = new Set([root]);
        const makeParents = relative => {
            let current = root;
            for (const part of relative.split('/').slice(0, -1)) {
                current = path.join(current, part);
                if (!directories.has(current)) {
                    try { fs.mkdirSync(current, { mode: 0o700 }); }
                    catch (error) { if (error.code !== 'EEXIST') throw error; }
                    const stat = fs.lstatSync(current);
                    if (!stat.isDirectory() || stat.isSymbolicLink()) fail(22, 'ZIP 경로에 심볼릭 링크가 있습니다.');
                    directories.add(current);
                }
            }
        };
        for (const entry of index.entries) {
            const output = verifyOnly ? null : path.join(root, entry.path);
            if (!verifyOnly) makeParents(entry.path);
            activity(entry.path);
            writeProgress();
            if (entry.isDirectory) {
                if (!verifyOnly && !directories.has(output)) {
                    fs.mkdirSync(output, { mode: 0o700 });
                    directories.add(output);
                }
                if (entry.crc32 !== 0) fail(20, 'ZIP 폴더 항목의 CRC가 올바르지 않습니다.');
                if (entry.compressedBytes === 0 && entry.method === 0) continue;
            }
            let crc = 0, inflated = 0, created = false;
            // Verification drains decompressed data directly, never materializing
            // a second extraction tree. bytesWritten means CRC-checked bytes here.
            const discardOutput = verifyOnly || entry.isDirectory;
            const outputStream = discardOutput ? new Writable({ write(chunk, encoding, callback) {
                this.bytesWritten += chunk.length;
                callback();
            } }) : fs.createWriteStream(output, { flags: 'wx', mode: 0o600 });
            if (discardOutput) outputStream.bytesWritten = 0;
            else outputStream.once('open', () => { created = true; });
            currentBase = progress.completed_bytes;
            currentOutput = outputStream;
            try {
                if (entry.compressedBytes === 0) {
                    // Stored empty files still need a verified zero CRC and size.
                    if (entry.bytes !== 0 || entry.method !== 0) fail(20, '빈 ZIP 항목이 손상되었습니다.');
                    await pipeline(require('stream').Readable.from([]), outputStream);
                } else {
                    const input = fs.createReadStream(archive, {
                        fd, autoClose: false, start: entry.dataOffset,
                        end: entry.dataOffset + entry.compressedBytes - 1, highWaterMark: 262144,
                    });
                    const check = new Transform({
                        transform(chunk, encoding, callback) {
                            inflated += chunk.length;
                            if (inflated > entry.bytes) return callback(Object.assign(new Error('ZIP 항목이 선언된 크기를 초과했습니다.'), { exitCode: 20 }));
                            crc = crc32(chunk, crc);
                            callback(null, chunk);
                        },
                    });
                    const inflater = entry.method === 8 ? zlib.createInflateRaw({ chunkSize: 262144 }) : null;
                    const streams = inflater ? [input, inflater, check, outputStream] : [input, check, outputStream];
                    await pipeline(...streams);
                    if (inflater && inflater.bytesWritten !== entry.compressedBytes) fail(20, 'ZIP 압축 데이터 길이가 일치하지 않습니다.');
                }
                if (inflated !== entry.bytes || crc !== entry.crc32) fail(20, 'ZIP 파일의 CRC 또는 해제 크기가 일치하지 않습니다.');
                progress.completed_bytes = currentBase + outputStream.bytesWritten;
                currentOutput = null;
                if (!discardOutput) {
                    fs.chmodSync(output, (entry.mode & 0o777) || 0o600);
                    if (Number.isFinite(entry.mtime) && entry.mtime >= 0) fs.utimesSync(output, new Date(entry.mtime), new Date(entry.mtime));
                }
                if (!entry.isDirectory) progress.completed_files++;
                activity();
                writeProgress();
            } catch (error) {
                currentOutput = null;
                outputStream.destroy();
                if (created) { try { fs.unlinkSync(output); } catch (_) {} }
                throw error;
            }
        }
        const after = fs.fstatSync(fd);
        if (['size', 'mtimeMs', 'dev', 'ino'].some(key => source[key] !== after[key])) fail(20, '해제 중 ZIP 파일이 변경되었습니다.');
        // Apply directory timestamps only after all child files are written.
        for (const entry of [...index.entries].reverse()) if (!verifyOnly && entry.isDirectory) {
            const output = path.join(root, entry.path);
            fs.chmodSync(output, (entry.mode & 0o777) || 0o700);
            if (Number.isFinite(entry.mtime) && entry.mtime >= 0) fs.utimesSync(output, new Date(entry.mtime), new Date(entry.mtime));
        }
        writeProgress(true);
    } finally { fs.closeSync(fd); }
}

async function compress(root, archive, ...selection) {
    if (!root || !archive || !selection.length) fail(64, 'ZIP 생성 인수가 부족합니다.');
    let buffer = '', outputTail = '';
    // Info-ZIP -dc prints exact entry counts (not rounded byte estimates).
    // -dd/-ds emits actual input-processing dots even during one large file.
    // We use those as activity evidence, never as a fabricated byte percentage.
    const inspectOutput = chunk => {
        activity();
        buffer += chunk.toString('utf8');
        if (buffer.length > 131072) buffer = buffer.slice(-65536);
        const lines = buffer.split(/\r?\n/);
        buffer = lines.pop() || '';
        for (const line of [...lines, buffer]) {
            const match = /^\s*(\d+)\/\s*(\d+)\s+(?:adding|updating|copying):\s*(.*)/.exec(line);
            if (!match) continue;
            const done = Number(match[1]), remaining = Number(match[2]);
            if (!safeInteger(done) || !safeInteger(remaining) || done + remaining > 500000) continue;
            progress.progress_mode = 'files';
            progress.completed_files = Math.max(progress.completed_files, done);
            progress.total_files = done + remaining;
            const name = match[3].replace(/\s+\((?:deflated|stored)\b.*$/, '').replace(/\s+\.+$/, '').trim();
            if (name) activity(name);
        }
        writeProgress();
    };
    writeProgress(true);
    const exitCode = await new Promise((resolve, reject) => {
        child = spawn('zip', ['-r', '-dc', '-dd', '-ds', '1m', '-MM', archive, ...selection], {
            cwd: root, env: { ...process.env, LC_ALL: 'C', ZIPOPT: '' }, stdio: ['ignore', 'pipe', 'pipe'],
        });
        child.stdout.on('data', chunk => {
            outputTail = (outputTail + chunk.toString('utf8')).slice(-8192);
            inspectOutput(chunk);
        });
        child.stderr.on('data', chunk => { process.stderr.write(chunk); activity(); writeProgress(); });
        child.once('error', reject);
        child.once('close', (code, signal) => resolve(signal ? 130 : (code ?? 18)));
    });
    child = null;
    if (exitCode !== 0) {
        if (outputTail) console.error(outputTail);
        fail(exitCode, 'ZIP 생성 작업을 완료하지 못했습니다.');
    }
    progress.completed_files = progress.total_files;
    activity();
    writeProgress(true);
    console.log(`archive_entries=${progress.total_files}`);
}

const timer = setInterval(() => {
    try { writeProgress(); }
    catch (error) { console.error(clean(error.message)); if (child) child.kill('SIGTERM'); process.exit(error.code === 'ENOSPC' ? 29 : 18); }
}, 500);
for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => {
    if (child) child.kill(signal);
    if (currentOutput) currentOutput.destroy();
    process.exit(130);
});
(async () => {
    if (action === 'extract') await extract(...args);
    else if (action === 'verify') await extract(args[0], null, args[1], true);
    else if (action === 'compress') await compress(...args);
    else if (action === 'crc-self-test') {
        if (crcFallback(Buffer.from('123456789')) !== 0xcbf43926) fail(20, 'CRC self-test failed');
        const part = crcFallback(Buffer.from('1234'));
        if (crcFallback(Buffer.from('56789'), part) !== 0xcbf43926) fail(20, 'CRC stream self-test failed');
        if (typeof zlib.crc32 === 'function') {
            const test = Buffer.alloc(65539);
            for (let i = 0; i < test.length; i++) test[i] = (i * 73 + 17) & 255;
            if (crcFallback(test) !== zlib.crc32(test)) fail(20, 'CRC block self-test failed');
        }
    } else fail(64, '지원하지 않는 ZIP 작업입니다.');
})().catch(error => {
    console.error(clean(error.message));
    process.exitCode = error.code === 'ENOSPC' ? 29 : (error.exitCode || 20);
}).finally(() => clearInterval(timer));
NODE
