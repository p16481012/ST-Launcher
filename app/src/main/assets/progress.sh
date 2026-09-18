#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

# Copying stays in one process so progress measures completed writes, rather than
# directory sizes sampled by another process or a percentage assigned to a step.
exec node - "$@" <<'NODE'
'use strict';

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const [command, sourceArgument, destinationArgument, excluded = '', linkPolicy = 'reject-links'] = process.argv.slice(2);
const CHUNK_SIZE = 128 * 1024;
// Only the regression harness enables this; production copies never sleep.
const testDelay = process.env.ST_PROGRESS_TEST_MODE === '1'
    ? Math.min(50, Math.max(0, Number(process.env.ST_PROGRESS_TEST_CHUNK_DELAY_MS) || 0)) : 0;
const testWait = testDelay ? new Int32Array(new SharedArrayBuffer(4)) : null;
const minimumFreeArgument = process.env.ST_PROGRESS_MIN_FREE_BYTES || '';
const blockProtectedNames = process.env.ST_PROGRESS_BLOCK_PROTECTED_NAMES === '1';
let minimumFreeBytes = null;
let spaceCheckPath = '';
let lastSpaceCheck = 0;
const unsafeText = /[\u0000-\u001f\u007f]/;
const cleanLine = value => String(value || '').replace(/[\u0000-\u001f\u007f]/g, ' ');
const phase = cleanLine(process.env.ST_PROGRESS_PHASE || '파일 복사');
const detail = cleanLine(process.env.ST_PROGRESS_DETAIL || '파일을 복사하고 있습니다.');
const operation = cleanLine(process.env.ST_PROGRESS_OPERATION || 'copy');
const progressFile = process.env.ST_PROGRESS_FILE;
const activityLog = process.env.ST_PROGRESS_LOG;
const historyLog = process.env.ST_PROGRESS_HISTORY;
const phaseStartedAt = Math.floor(Date.now() / 1000);
const state = {
    planning: true,
    completedBytes: 0,
    totalBytes: 0,
    completedFiles: 0,
    totalFiles: 0,
    currentItem: '',
    activityAt: phaseStartedAt,
    finished: false,
};
let lastPublished = 0;
let lastLogged = 0;
let lastLoggedSnapshot = '';
let progressReady = false;
let displayItem = '';
const temporaryFiles = new Set();

function fail(code) {
    const error = new Error(code);
    error.code = code;
    throw error;
}

function activity(item) {
    if (item !== undefined) state.currentItem = displayItem || item;
    state.activityAt = Math.floor(Date.now() / 1000);
}

function readableBytes(value) {
    const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];
    let amount = value;
    let unit = 0;
    while (amount >= 1024 && unit < units.length - 1) { amount /= 1024; unit++; }
    return `${unit === 0 ? amount : amount.toFixed(amount < 10 ? 2 : 1)} ${units[unit]}`;
}

function trimHistory(filename) {
    const size = fs.statSync(filename).size;
    if (size <= 1000000) return;
    const tail = Buffer.alloc(750000);
    const input = fs.openSync(filename, fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW || 0));
    let read;
    try { read = fs.readSync(input, tail, 0, tail.length, size - tail.length); }
    finally { fs.closeSync(input); }
    // Begin at the next complete line so a truncated UTF-8 name is never shown.
    const contents = tail.subarray(0, read);
    const boundary = contents.indexOf(10);
    const temporary = `${filename}.tmp.${process.pid}.${crypto.randomBytes(6).toString('hex')}`;
    temporaryFiles.add(temporary);
    fs.writeFileSync(temporary, boundary < 0 ? Buffer.alloc(0) : contents.subarray(boundary + 1), {
        flag: 'wx', mode: 0o600,
    });
    fs.renameSync(temporary, filename);
    temporaryFiles.delete(temporary);
}

function logActivity(force = false) {
    if (!activityLog && !historyLog) return;
    const now = Date.now();
    if (!force && now - lastLogged < 2000) return;
    const snapshot = [state.planning, state.completedBytes, state.totalBytes, state.completedFiles,
        state.totalFiles, state.currentItem, state.finished].join('|');
    // A heartbeat alone is not evidence of processed data. Never add a repeated
    // line just because another second passed without a file or byte changing.
    if (snapshot === lastLoggedSnapshot) return;
    const clock = new Date(now);
    const timestamp = [clock.getHours(), clock.getMinutes(), clock.getSeconds()].map(value => String(value).padStart(2, '0')).join(':');
    const item = Array.from(state.currentItem.replace(/[\u0000-\u001f\u007f]/g, ' ')).slice(0, 160).join('');
    const counts = state.planning
        ? `목록 확인 · 확인한 파일 ${state.totalFiles}개 · 확인한 용량 ${readableBytes(state.totalBytes)}`
        : `${state.finished ? '복사 완료' : '복사 중'} · 처리 ${readableBytes(state.completedBytes)} / ${readableBytes(state.totalBytes)} · 파일 ${state.completedFiles} / ${state.totalFiles}개`;
    const line = `[${timestamp}] [${phase}] ${counts}${item ? ` · ${item}` : ''}\n`;
    const targets = new Set([activityLog, historyLog].filter(Boolean).map(filename => path.resolve(filename)));
    for (const filename of targets) fs.appendFileSync(filename, line, { mode: 0o600 });
    // operation.log can have a native command writer; only trim the dedicated
    // history when it is a distinct target and the worker is its sole writer.
    if (historyLog && (!activityLog || path.resolve(historyLog) !== path.resolve(activityLog))) trimHistory(historyLog);
    lastLogged = now;
    lastLoggedSnapshot = snapshot;
}

function publish(force = false) {
    if (!progressReady) return;
    if (!progressFile) { logActivity(force); return; }
    const now = Date.now();
    if (!force && now - lastPublished < 1000) return;
    const mode = state.planning ? 'indeterminate' : state.totalBytes > 0 ? 'bytes' : 'files';
    const numerator = mode === 'bytes' ? state.completedBytes : state.completedFiles;
    const denominator = mode === 'bytes' ? state.totalBytes : state.totalFiles;
    const percent = state.planning ? 0 : denominator > 0
        ? Math.floor(numerator * 100 / denominator) : state.finished ? 100 : 0;
    const lines = {
        percent,
        phase,
        detail: state.planning ? '전송할 파일 목록과 용량을 확인하고 있습니다.' : detail,
        status: 'running',
        operation,
        error_code: '',
        progress_mode: mode,
        completed_bytes: state.completedBytes,
        total_bytes: state.totalBytes,
        completed_files: state.completedFiles,
        total_files: state.totalFiles,
        current_item_b64: Buffer.from(state.currentItem, 'utf8').toString('base64'),
        heartbeat_at: Math.floor(now / 1000),
        activity_at: state.activityAt,
        phase_started_at: phaseStartedAt,
    };
    const temporary = `${progressFile}.tmp.${process.pid}.${crypto.randomBytes(6).toString('hex')}`;
    temporaryFiles.add(temporary);
    fs.writeFileSync(temporary, Object.entries(lines).map(([key, value]) => `${key}=${value}\n`).join(''), {
        flag: 'wx', mode: 0o600,
    });
    fs.renameSync(temporary, progressFile);
    temporaryFiles.delete(temporary);
    lastPublished = now;
    logActivity(force);
}

function within(parent, candidate) {
    return candidate === parent || candidate.startsWith(parent + path.sep);
}

function existsStat(filename) {
    try {
        return fs.lstatSync(filename);
    } catch (error) {
        if (error.code === 'ENOENT') return null;
        throw error;
    }
}

function canonicalFuture(filename) {
    const missing = [];
    let ancestor = filename;
    while (!existsStat(ancestor)) {
        missing.unshift(path.basename(ancestor));
        const parent = path.dirname(ancestor);
        if (parent === ancestor) fail('COPY_INVALID_DESTINATION');
        ancestor = parent;
    }
    return path.join(fs.realpathSync(ancestor), ...missing);
}

function validArgument(value) {
    return typeof value === 'string' && value.length > 0 && path.isAbsolute(value) && !unsafeText.test(value);
}

function equivalent(planned, current) {
    return planned.dev === current.dev && planned.ino === current.ino &&
        planned.mode === current.mode && planned.size === current.size &&
        planned.mtimeMs === current.mtimeMs && planned.ctimeMs === current.ctimeMs;
}

function sourceAccess(action) {
    try { return action(); }
    catch (error) {
        if (error.code === 'EACCES' || error.code === 'EPERM') fail('COPY_SOURCE_ACCESS_DENIED');
        throw error;
    }
}

function verifySource(entry) {
    const current = sourceAccess(() => fs.lstatSync(entry.source));
    if (!equivalent(entry.stat, current)) fail('COPY_SOURCE_CHANGED');
}

function addSafeSize(value) {
    if (!Number.isSafeInteger(value) || value < 0 || !Number.isSafeInteger(state.totalBytes + value)) {
        fail('COPY_SIZE_LIMIT');
    }
    state.totalBytes += value;
}

function planEntry(filename, relative, entries) {
    if (unsafeText.test(relative)) fail('COPY_UNSAFE_ENTRY');
    if (blockProtectedNames && relative.split(path.sep).some(part => part === '.git' || part === 'node_modules')) fail('COPY_PROTECTED_ENTRY');
    const stat = sourceAccess(() => fs.lstatSync(filename));
    const entry = { source: filename, relative, stat };
    if (stat.isDirectory()) entry.kind = 'directory';
    else if (stat.isFile()) {
        entry.kind = 'file';
        state.totalFiles++;
        addSafeSize(stat.size);
    } else if (stat.isSymbolicLink() && linkPolicy === 'preserve-links') {
        entry.kind = 'link';
        entry.link = fs.readlinkSync(filename);
        state.totalFiles++;
    } else fail('COPY_UNSAFE_ENTRY');
    entries.push(entry);
    activity(relative || path.basename(filename));
    publish();
    if (entry.kind === 'directory') {
        for (const name of sourceAccess(() => fs.readdirSync(filename))) {
            if (name === excluded) continue;
            if (unsafeText.test(name)) fail('COPY_UNSAFE_ENTRY');
            planEntry(path.join(filename, name), relative ? path.join(relative, name) : name, entries);
        }
        // A directory changed while its children were enumerated: do not copy a
        // partial snapshot and subsequently let the caller delete the source.
        verifySource(entry);
    }
}

function ensureDirectory(filename, destinationRoot) {
    if (!within(destinationRoot, filename)) fail('COPY_DESTINATION_ESCAPE');
    const parent = path.dirname(filename);
    if (filename !== destinationRoot) ensureDirectory(parent, destinationRoot);
    const stat = existsStat(filename);
    if (stat) {
        if (!stat.isDirectory() || stat.isSymbolicLink()) fail('COPY_UNSAFE_DESTINATION');
    } else fs.mkdirSync(filename, { mode: 0o700 });
}

function verifyDestination(filename, destinationRoot, kind) {
    const parent = path.dirname(filename);
    ensureDirectory(parent, destinationRoot);
    const stat = existsStat(filename);
    if (stat && (stat.isSymbolicLink() || (kind === 'directory' ? !stat.isDirectory() : !stat.isFile()))) {
        fail('COPY_UNSAFE_DESTINATION');
    }
}

function temporaryFor(destination) {
    const filename = path.join(path.dirname(destination), `.st-copy-${process.pid}-${crypto.randomBytes(12).toString('hex')}`);
    temporaryFiles.add(filename);
    return filename;
}

function checkFreeSpace(requiredBytes = 0n, force = false) {
    if (minimumFreeBytes === null || !spaceCheckPath) return;
    const now = Date.now();
    if (!force && now - lastSpaceCheck < 1000) return;
    const stat = fs.statfsSync(spaceCheckPath, { bigint: true });
    if (stat.bavail * stat.bsize < requiredBytes + minimumFreeBytes) fail('COPY_NO_SPACE');
    lastSpaceCheck = now;
}

function applyMetadata(filename, stat) {
    fs.chmodSync(filename, stat.mode & 0o777);
    fs.utimesSync(filename, stat.atimeMs / 1000, stat.mtimeMs / 1000);
}

function copyFile(entry, destination, destinationRoot, buffer, hardLinks) {
    verifySource(entry);
    verifyDestination(destination, destinationRoot, 'file');
    const temporary = temporaryFor(destination);
    const hardLinkKey = `${entry.stat.dev}:${entry.stat.ino}`;
    const existingLink = entry.stat.nlink > 1 ? hardLinks.get(hardLinkKey) : undefined;
    if (existingLink) {
        try {
            fs.linkSync(existingLink, temporary);
            verifySource(entry);
            fs.renameSync(temporary, destination);
            temporaryFiles.delete(temporary);
            state.completedBytes += entry.stat.size;
            state.completedFiles++;
            activity();
            publish();
            return;
        } catch (error) {
            if (error.code !== 'EXDEV') throw error;
        }
    }
    let input;
    let output;
    try {
        input = sourceAccess(() => fs.openSync(entry.source, fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW || 0)));
        if (!equivalent(entry.stat, fs.fstatSync(input))) fail('COPY_SOURCE_CHANGED');
        output = fs.openSync(temporary, fs.constants.O_WRONLY | fs.constants.O_CREAT | fs.constants.O_EXCL, 0o600);
        let remaining = entry.stat.size;
        while (remaining > 0) {
            const read = fs.readSync(input, buffer, 0, Math.min(buffer.length, remaining), null);
            if (read === 0) fail('COPY_SOURCE_CHANGED');
            let written = 0;
            while (written < read) {
                const count = fs.writeSync(output, buffer, written, read - written);
                if (count === 0) fail('COPY_WRITE_FAILED');
                written += count;
                state.completedBytes += count;
                checkFreeSpace();
                activity();
                publish();
            }
            remaining -= read;
            if (testWait) Atomics.wait(testWait, 0, 0, testDelay);
        }
        if (fs.readSync(input, buffer, 0, 1, null) !== 0 || !equivalent(entry.stat, fs.fstatSync(input))) {
            fail('COPY_SOURCE_CHANGED');
        }
        fs.fchmodSync(output, entry.stat.mode & 0o777);
        fs.futimesSync(output, entry.stat.atimeMs / 1000, entry.stat.mtimeMs / 1000);
    } finally {
        if (input !== undefined) fs.closeSync(input);
        if (output !== undefined) fs.closeSync(output);
    }
    verifySource(entry);
    verifyDestination(destination, destinationRoot, 'file');
    fs.renameSync(temporary, destination);
    temporaryFiles.delete(temporary);
    if (entry.stat.nlink > 1) hardLinks.set(hardLinkKey, destination);
    state.completedFiles++;
    activity();
    publish();
}

try {
    if (command !== 'copy' || process.argv.length > 7 || !validArgument(sourceArgument) ||
        !validArgument(destinationArgument) || (excluded && (unsafeText.test(excluded) ||
        excluded.includes('/') || excluded.includes('\\') || excluded === '.' || excluded === '..')) ||
        !['reject-links', 'preserve-links'].includes(linkPolicy)) {
        fail('COPY_INVALID_ARGUMENT');
    }
    if (minimumFreeArgument) {
        if (!/^[0-9]{1,16}$/.test(minimumFreeArgument)) fail('COPY_INVALID_ARGUMENT');
        minimumFreeBytes = BigInt(minimumFreeArgument);
    }
    const source = path.resolve(sourceArgument);
    const destination = path.resolve(destinationArgument);
    if (source === path.parse(source).root || destination === path.parse(destination).root) fail('COPY_UNSAFE_ROOT');
    const sourceStat = sourceAccess(() => fs.lstatSync(source));
    // Android's selected file is staged under a random transport name. A label
    // may identify its real destination in the UI, but never affects file I/O.
    // Directory copies always show their actual individual relative entries.
    if (!sourceStat.isDirectory()) {
        displayItem = Array.from(cleanLine(process.env.ST_PROGRESS_ITEM).replace(/\p{Cf}/gu, '')).slice(0, 1024).join('');
        state.currentItem = displayItem;
    }
    const sourceCanonical = sourceStat.isSymbolicLink()
        ? path.join(sourceAccess(() => fs.realpathSync(path.dirname(source))), path.basename(source)) : sourceAccess(() => fs.realpathSync(source));
    const destinationCanonical = canonicalFuture(destination);
    if (within(sourceCanonical, destinationCanonical) || within(destinationCanonical, sourceCanonical)) {
        fail('COPY_OVERLAP');
    }
    const destinationStat = existsStat(destination);
    if (destinationStat && destinationStat.isSymbolicLink()) fail('COPY_UNSAFE_DESTINATION');
    if (progressFile && (!validArgument(progressFile) || !fs.statSync(path.dirname(progressFile)).isDirectory())) {
        fail('COPY_INVALID_PROGRESS_FILE');
    }
    for (const filename of [activityLog, historyLog].filter(Boolean)) {
        const stat = existsStat(filename);
        if (!validArgument(filename) || !fs.statSync(path.dirname(filename)).isDirectory() ||
            (stat && (!stat.isFile() || stat.isSymbolicLink()))) fail('COPY_INVALID_PROGRESS_LOG');
    }
    // The caller creates the destination parent inside its transaction directory.
    // Never recursively create or traverse an unvalidated arbitrary ancestor.
    const destinationParent = path.dirname(destination);
    if (!fs.statSync(destinationParent).isDirectory()) fail('COPY_INVALID_DESTINATION');
    spaceCheckPath = destinationParent;
    progressReady = true;
    publish(true);
    const entries = [];
    planEntry(source, '', entries);
    if (minimumFreeBytes !== null) {
        const stat = fs.statfsSync(destinationParent, { bigint: true });
        // Include a filesystem block per entry for allocation/metadata overhead;
        // logical sizes intentionally overestimate hard-link/sparse copies.
        checkFreeSpace(BigInt(state.totalBytes) + BigInt(entries.length) * stat.bsize, true);
    }
    const directorySource = sourceStat.isDirectory();
    if (!directorySource && excluded) fail('COPY_INVALID_ARGUMENT');
    const destinationRoot = directorySource ? destination : destinationParent;
    // Validate every existing destination component before writing the first byte.
    for (const entry of entries) {
        checkFreeSpace();
        const target = directorySource ? path.join(destination, entry.relative) : destination;
        let ancestor = target;
        while (within(destinationRoot, ancestor)) {
            const stat = existsStat(ancestor);
            if (stat && (stat.isSymbolicLink() || (ancestor !== target && !stat.isDirectory()))) {
                fail('COPY_UNSAFE_DESTINATION');
            }
            if (ancestor === destinationRoot) break;
            ancestor = path.dirname(ancestor);
        }
        const targetStat = existsStat(target);
        if (targetStat && (entry.kind === 'directory' ? !targetStat.isDirectory() : !targetStat.isFile())) {
            fail('COPY_UNSAFE_DESTINATION');
        }
    }
    state.planning = false;
    state.currentItem = '';
    publish(true);
    const buffer = Buffer.allocUnsafe(CHUNK_SIZE);
    const hardLinks = new Map();
    for (const entry of entries) {
        checkFreeSpace();
        const target = directorySource ? path.join(destination, entry.relative) : destination;
        activity(entry.relative || path.basename(source));
        verifySource(entry);
        if (entry.kind === 'directory') ensureDirectory(target, destinationRoot);
        else if (entry.kind === 'file') copyFile(entry, target, destinationRoot, buffer, hardLinks);
        else {
            verifyDestination(target, destinationRoot, 'file');
            const temporary = temporaryFor(target);
            fs.symlinkSync(entry.link, temporary);
            if (typeof fs.lutimesSync === 'function') fs.lutimesSync(temporary, entry.stat.atimeMs / 1000, entry.stat.mtimeMs / 1000);
            verifySource(entry);
            fs.renameSync(temporary, target);
            temporaryFiles.delete(temporary);
            state.completedFiles++;
            activity();
        }
        publish();
    }
    for (let i = entries.length - 1; i >= 0; i--) {
        const entry = entries[i];
        verifySource(entry);
        if (entry.kind === 'directory') {
            applyMetadata(path.join(destination, entry.relative), entry.stat);
            activity(entry.relative || path.basename(source));
            publish();
        }
    }
    state.finished = true;
    state.currentItem = '';
    activity();
    // 100% refers to this transfer phase only; the manager owns operation success.
    publish(true);
} catch (error) {
    try { publish(true); } catch (_) { /* Preserve the original failure. */ }
    process.stderr.write(`progress-copy: ${cleanLine(error.code || 'COPY_FAILED')}\n`);
    process.exitCode = error.code === 'COPY_INVALID_ARGUMENT' ? 64 : 1;
} finally {
    for (const temporary of temporaryFiles) {
        try { fs.unlinkSync(temporary); } catch (_) { /* Caller cleans its staging area. */ }
    }
}
NODE
