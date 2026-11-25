#!/data/data/com.termux/files/usr/bin/bash
# The manager must hold its operation lock and recovery gate for mutations.
# Never enumerate recovery/work directories as disposable safety copies.
set -Eeuo pipefail
exec node --input-type=commonjs - "$@" <<'NODE'
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const [action, launcherArgument, installationArgument, downloadArgument, selected = '', revision = ''] = process.argv.slice(2);
const MAX_PAGE = 40 * 1024, MAX_NAMES = 10000, MAX_ROW = 12000;
const ARCHIVE = /^before-(?:forced-update|branch)-[0-9]{8}-[0-9]{6}\.tar\.gz$/;
const SNAPSHOT = /^safety-snapshot-[0-9]+\.[A-Za-z0-9]{8}$/;
const CONFIG = /^config-[0-9]{8}-[0-9]{6}\.yaml$/;
const COMPLETION = '.st-safety-complete.json';
const TRASH = /^[0-9]{1,16}-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const RECOVERY = /^(?:(?:restore|reset|update|branch|import|install-import)-work-|(?:reset|update)-rollback-)/;
const codes = { SAFETY_ITEM_CHANGED: 65, SAFETY_PATH_UNSAFE: 66, SAFETY_RECOVERY_PENDING: 67,
    SAFETY_EXPORT_FAILED: 68, SAFETY_ITEM_UNSUPPORTED: 69, SAFETY_INVENTORY_FAILED: 70, OPERATION_CANCELLED: 130 };
const b64 = value => Buffer.from(String(value), 'utf8').toString('base64');
const digest = value => crypto.createHash('sha256').update(value).digest('hex');
const handles = [];
let root, rootPath, installPath, protectedEntries = 0, scanTruncated = false, sizeBudget = 10000, nameBudget = 20000;
let cancelled = false;
if (action === 'export') for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => { cancelled = true; });
function cancellationCheckpoint() {
    if (cancelled || [process.env.ST_CANCEL_REQUEST_FILE, process.env.ST_CANCEL_REQUEST_ID_FILE].some(file => file && fs.existsSync(file))) fail('OPERATION_CANCELLED', '내보내기를 중단했습니다. 원본 안전 사본은 유지했습니다.');
}
function fail(code, message) { throw Object.assign(new Error(message), { safetyCode: code }); }
function identity(stat) { return [stat.dev, stat.ino, stat.mode, stat.size, stat.mtimeNs, stat.ctimeNs].join(':'); }
function sameNode(a, b) { return a.dev === b.dev && a.ino === b.ino && a.mode === b.mode; }
function stat(target) { return fs.lstatSync(target, { bigint: true }); }
function exists(target) { try { stat(target); return true; } catch (error) { if (error.code === 'ENOENT') return false; throw error; } }
function safeName(name) { return name && name !== '.' && name !== '..' && !/[\\/\x00-\x1f\x7f]/.test(name); }
function decode(value) {
    if (!value || value.length > 8192 || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value)) fail('SAFETY_PATH_UNSAFE', '올바르지 않은 보관 항목입니다.');
    const bytes = Buffer.from(value, 'base64'), result = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    if (b64(result) !== value) fail('SAFETY_PATH_UNSAFE', '올바르지 않은 보관 항목입니다.');
    return result;
}
function openDirectory(target) {
    const before = stat(target);
    if (!before.isDirectory() || before.isSymbolicLink()) fail('SAFETY_PATH_UNSAFE', '보관 폴더 경로가 변경되었거나 안전하지 않습니다.');
    const fd = fs.openSync(target, fs.constants.O_RDONLY | (fs.constants.O_DIRECTORY || 0) | (fs.constants.O_NOFOLLOW || 0));
    const opened = fs.fstatSync(fd, { bigint: true });
    if (!sameNode(before, opened)) { fs.closeSync(fd); fail('SAFETY_ITEM_CHANGED', '보관 폴더가 변경되었습니다. 목록을 새로 열어 주세요.'); }
    // On Android/Linux all descendant operations are anchored to an open
    // directory, not a path whose parent could be swapped for a symlink.
    const directory = { fd, original: target, before, pinned: process.platform === 'linux' ? `/proc/self/fd/${fd}` : target };
    handles.push(directory);
    return directory;
}
function verifyDirectories() {
    for (const directory of handles) {
        if (!sameNode(directory.before, stat(directory.original))) fail('SAFETY_ITEM_CHANGED', '보관 폴더가 변경되어 작업을 중단했습니다.');
    }
}
function child(directory, name) {
    if (!safeName(name)) fail('SAFETY_PATH_UNSAFE', '안전하지 않은 보관 항목 이름입니다.');
    return path.join(directory.pinned, name);
}
function names(directory) {
    const entries = [], iterator = fs.opendirSync(directory.pinned);
    try {
        let entry;
        while ((entry = iterator.readSync())) {
            if (entries.length >= MAX_NAMES || --nameBudget < 0) { scanTruncated = true; break; }
            entries.push(entry.name);
        }
    } finally { iterator.closeSync(); }
    return entries.sort();
}
function optionalDirectory(directory, name) {
    const target = child(directory, name);
    if (!exists(target)) return null;
    return openDirectory(target);
}
function boundedRead(target, limit) {
    const before = stat(target);
    if (!before.isFile() || before.isSymbolicLink() || before.size > BigInt(limit)) fail('SAFETY_PATH_UNSAFE', '보관 항목의 정보를 안전하게 읽을 수 없습니다.');
    const fd = fs.openSync(target, fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW || 0));
    try {
        if (identity(before) !== identity(fs.fstatSync(fd, { bigint: true }))) fail('SAFETY_ITEM_CHANGED', '보관 항목이 변경되었습니다.');
        const buffer = Buffer.alloc(limit + 1); let length = 0, count;
        while (length < buffer.length && (count = fs.readSync(fd, buffer, length, buffer.length - length, null))) length += count;
        if (length > limit || identity(before) !== identity(fs.fstatSync(fd, { bigint: true }))) fail('SAFETY_ITEM_CHANGED', '보관 항목 정보가 변경되었거나 너무 큽니다.');
        return { bytes: buffer.subarray(0, length), before };
    } finally { fs.closeSync(fd); }
}
function classify(relative) {
    const parts = relative.split('/');
    if (parts.some(part => !safeName(part))) fail('SAFETY_PATH_UNSAFE', '올바르지 않은 보관 경로입니다.');
    if (parts.length === 2 && parts[0] === 'backups' && ARCHIVE.test(parts[1])) return 'archive';
    if (parts.length === 3 && parts[0] === 'backups' && SNAPSHOT.test(parts[1]) && ARCHIVE.test(parts[2])) return 'archive';
    if (parts.length === 3 && parts[0] === 'backups' && parts[1] === 'network-settings' && CONFIG.test(parts[2])) return 'config';
    if (parts.length === 2 && parts[0] === 'file-trash' && TRASH.test(parts[1])) return 'trash';
    fail('SAFETY_ITEM_UNSUPPORTED', '직접 관리할 수 없는 복구용 항목입니다. 원본을 유지했습니다.');
}
function parentFor(relative) {
    const parts = relative.split('/'); let directory = root;
    for (const part of parts.slice(0, -1)) directory = openDirectory(child(directory, part));
    return directory;
}
function folderSize(directory, depth = 0) {
    if (depth > 64 || sizeBudget <= 0 || scanTruncated) return null;
    let size = 0n;
    const contents = names(directory);
    if (scanTruncated) return null;
    for (const name of contents) {
        if (--sizeBudget < 0) return null;
        const target = child(directory, name), current = stat(target);
        if (current.isSymbolicLink()) return null;
        if (current.isFile()) size += current.size;
        else if (current.isDirectory()) {
            const nested = openDirectory(target);
            try { const result = folderSize(nested, depth + 1); if (result === null) return null; size += result; }
            finally { closeDirectory(nested); }
        } else return null;
    }
    return size <= BigInt(Number.MAX_SAFE_INTEGER) ? size : null;
}
function closeDirectory(directory) {
    const index = handles.indexOf(directory);
    if (index >= 0) handles.splice(index, 1);
    fs.closeSync(directory.fd);
}
function entry(relative, includeSize = false) {
    const kind = classify(relative), parent = parentFor(relative), name = path.basename(relative), target = child(parent, name);
    const current = stat(target);
    if (current.isSymbolicLink()) fail('SAFETY_PATH_UNSAFE', '심볼릭 링크 항목은 관리하지 않습니다.');
    const result = { kind, relative, parent, target, current, label: name, bytes: current.size, modified: Number(current.mtimeNs / 1000000n), trashId: '', original: '', restorable: false, completionRecorded: false };
    let fingerprint = [rootPath, identity(current)];
    if (kind === 'trash') {
        if (!current.isDirectory()) fail('SAFETY_PATH_UNSAFE', '휴지통 항목이 올바르지 않습니다.');
        const container = openDirectory(target);
        if (names(container).join('\0') !== 'item\0metadata.json') fail('SAFETY_PATH_UNSAFE', '완료되지 않았거나 알 수 없는 휴지통 항목은 유지합니다.');
        const metadata = boundedRead(child(container, 'metadata.json'), 8192);
        const info = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(metadata.bytes));
        if (typeof info.root !== 'string' || !path.isAbsolute(info.root) || typeof info.relative !== 'string' ||
            Buffer.byteLength(info.relative, 'utf8') > 4096 || info.relative.split('/').some(part => !safeName(part)) || !Number.isSafeInteger(info.deleted_at) || info.deleted_at < 0) fail('SAFETY_PATH_UNSAFE', '휴지통 복원 정보를 확인할 수 없습니다.');
        const item = child(container, 'item'), itemStat = stat(item);
        if (itemStat.isSymbolicLink() || (!itemStat.isFile() && !itemStat.isDirectory())) fail('SAFETY_PATH_UNSAFE', '안전하지 않은 휴지통 항목입니다.');
        result.container = container; result.item = item; result.itemStat = itemStat;
        result.label = info.relative; result.original = info.relative; result.trashId = name;
        result.restorable = info.root === installPath;
        result.completionRecorded = true;
        result.modified = info.deleted_at;
        result.bytes = itemStat.isFile() ? itemStat.size : null;
        if (includeSize && itemStat.isDirectory()) {
            const itemDirectory = openDirectory(item);
            try { result.bytes = folderSize(itemDirectory); } finally { closeDirectory(itemDirectory); }
        }
        fingerprint.push(identity(metadata.before), digest(metadata.bytes), identity(itemStat));
    } else {
        if (!current.isFile() || current.size > BigInt(Number.MAX_SAFE_INTEGER)) fail('SAFETY_PATH_UNSAFE', '일반 보관 파일만 관리할 수 있습니다.');
        if (kind === 'archive' && relative.split('/').length === 3) {
            const contents = names(parent);
            if (contents.some(part => part !== name && part !== COMPLETION)) fail('SAFETY_PATH_UNSAFE', '완료되지 않은 안전 백업 폴더는 유지합니다.');
            if (contents.includes(COMPLETION)) {
                const marker = boundedRead(child(parent, COMPLETION), 2048);
                const info = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(marker.bytes));
                if (info.format !== 'st-launcher-safety-complete-v1' || info.file !== name ||
                    ['dev', 'ino', 'size', 'mtimeNs'].some(key => info[key] !== current[key].toString())) fail('SAFETY_ITEM_CHANGED', '안전 백업 완료 기록과 현재 파일이 일치하지 않습니다. 원본을 유지했습니다.');
                result.completionRecorded = true; result.completionMarker = marker;
                fingerprint.push(identity(marker.before), digest(marker.bytes));
            }
        }
    }
    result.revision = digest(fingerprint.join('\n'));
    return result;
}
function discover() {
    const candidates = []; let blocked = false;
    const backups = optionalDirectory(root, 'backups');
    if (backups) for (const name of names(backups)) {
        if (RECOVERY.test(name)) { blocked = true; protectedEntries++; continue; }
        const target = child(backups, name), current = stat(target);
        if (current.isSymbolicLink()) { protectedEntries++; continue; }
        if (ARCHIVE.test(name) && current.isFile()) candidates.push(`backups/${name}`);
        else if ((SNAPSHOT.test(name) || name === 'network-settings') && current.isDirectory()) {
            const nested = openDirectory(target);
            try {
                const contents = names(nested);
                if (SNAPSHOT.test(name)) {
                    const files = contents.filter(file => ARCHIVE.test(file));
                    if (files.length === 1 && contents.every(file => file === files[0] || file === COMPLETION)) candidates.push(`backups/${name}/${files[0]}`);
                    else protectedEntries++;
                } else for (const file of contents) if (CONFIG.test(file)) candidates.push(`backups/${name}/${file}`); else protectedEntries++;
            } finally { closeDirectory(nested); }
        } else protectedEntries++;
    }
    const trash = optionalDirectory(root, 'file-trash');
    if (trash) for (const name of names(trash)) {
        if (TRASH.test(name)) candidates.push(`file-trash/${name}`); else protectedEntries++;
    }
    return { candidates: candidates.sort(), blocked: blocked || scanTruncated };
}
function row(item) {
    return ['safety', item.kind, b64(item.relative), item.revision, b64(item.label), item.bytes === null ? -1 : item.bytes,
        item.modified, item.trashId, b64(item.original), item.restorable ? 1 : 0, item.completionRecorded ? 1 : 0,
        item.kind === 'trash' && item.itemStat.isDirectory() ? 1 : 0].join('\t') + '\n';
}
function releaseAfter(count) { while (handles.length > count) closeDirectory(handles[handles.length - 1]); }
function checkRevision(item) {
    if (!/^[a-f0-9]{64}$/.test(revision) || item.revision !== revision) fail('SAFETY_ITEM_CHANGED', '선택한 보관 항목이 변경되었습니다. 목록을 새로 열어 주세요.');
    verifyDirectories();
}
let lastProgress = 0, lastHistory = 0;
function exportProgress(item, copied, finished = false) {
    const now = Date.now();
    if (!finished && now - lastProgress < 500) return;
    lastProgress = now;
    const epoch = Math.floor(now / 1000), total = Number(item.current.size), completed = Number(copied);
    if (process.env.ST_PROGRESS_FILE) {
        const progress = { percent: total ? Math.min(100, Math.floor(completed * 100 / total)) : 0,
            phase: '안전 사본 내보내기', detail: '원본은 유지하면서 다운로드 폴더에 실제 기록한 용량을 표시합니다.',
            status: 'running', operation: process.env.ST_PROGRESS_OPERATION || 'safety-export', error_code: '',
            progress_mode: 'bytes', completed_bytes: completed, total_bytes: total, completed_files: finished ? 1 : 0,
            total_files: 1, current_item_b64: b64(item.label), heartbeat_at: epoch, activity_at: epoch,
            phase_started_at: Number(process.env.ST_OPERATION_STARTED_EPOCH) || epoch,
            operation_started_at: Number(process.env.ST_OPERATION_STARTED_EPOCH) || 0 };
        const temporary = `${process.env.ST_PROGRESS_FILE}.safety-inventory.${process.pid}.tmp`;
        fs.writeFileSync(temporary, Object.entries(progress).map(([key, value]) => `${key}=${value}\n`).join(''), { mode: 0o600 });
        fs.renameSync(temporary, process.env.ST_PROGRESS_FILE);
    }
    if (finished || now - lastHistory >= 2000) {
        lastHistory = now;
        const line = `[${new Date().toLocaleString('sv-SE', { hour12: false })}] [safety-export] 안전 사본 내보내기 · ${completed} / ${total} 바이트${finished ? ' · 기록 완료' : ''}\n`;
        for (const file of new Set([process.env.ST_PROGRESS_LOG, process.env.ST_PROGRESS_HISTORY].filter(Boolean))) {
            fs.appendFileSync(file, line, { mode: 0o600 });
            if (file === process.env.ST_PROGRESS_HISTORY && fs.statSync(file).size > 1000000) {
                const fd = fs.openSync(file, 'r'), tail = Buffer.alloc(750000);
                try { fs.readSync(fd, tail, 0, tail.length, fs.fstatSync(fd).size - tail.length); }
                finally { fs.closeSync(fd); }
                const temporary = `${file}.safety-inventory.${process.pid}.tmp`, newline = tail.indexOf(10);
                fs.writeFileSync(temporary, tail.subarray(newline < 0 ? 0 : newline + 1), { mode: 0o600 });
                fs.renameSync(temporary, file);
            }
        }
    }
}
async function exportItem(item) {
    if (item.kind === 'trash') fail('SAFETY_ITEM_UNSUPPORTED', '휴지통 항목은 먼저 원래 위치로 복원해 주세요.');
    checkRevision(item);
    cancellationCheckpoint();
    const downloads = openDirectory(fs.realpathSync(downloadArgument));
    // The newly and exclusively created directory owns both files. No rename,
    // hardlink, second large copy or child access to /proc/PID/fd is needed.
    const exportName = `ST-Launcher-internal-${crypto.randomUUID()}`;
    const exportPath = child(downloads, exportName);
    fs.mkdirSync(exportPath, { mode: 0o700 });
    const exportIdentity = stat(exportPath), exportDirectory = openDirectory(exportPath);
    const destination = child(exportDirectory, item.label), marker = child(exportDirectory, 'INCOMPLETE.txt');
    let input, output, outputIdentity, markerIdentity, success = false;
    try {
        fs.writeFileSync(marker, '내보내기가 완료되지 않았습니다. 이 폴더의 파일을 복원에 사용하지 마세요. 런처에서 내보내기를 완료하면 이 안내 파일은 제거됩니다.\n', { flag: 'wx', mode: 0o600 });
        markerIdentity = stat(marker);
        input = fs.openSync(item.target, fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW || 0));
        if (identity(item.current) !== identity(fs.fstatSync(input, { bigint: true }))) fail('SAFETY_ITEM_CHANGED', '내보낼 원본이 변경되었습니다.');
        output = fs.openSync(destination, fs.constants.O_WRONLY | fs.constants.O_CREAT | fs.constants.O_EXCL | (fs.constants.O_NOFOLLOW || 0), 0o600);
        outputIdentity = fs.fstatSync(output, { bigint: true });
        const buffer = Buffer.alloc(256 * 1024); let copied = 0n, count;
        exportProgress(item, copied);
        while ((count = fs.readSync(input, buffer, 0, buffer.length, null)) > 0) {
            cancellationCheckpoint();
            copied += BigInt(count);
            if (copied > item.current.size) fail('SAFETY_ITEM_CHANGED', '내보내기 중 원본 파일이 변경되었습니다.');
            let offset = 0;
            while (offset < count) offset += fs.writeSync(output, buffer, offset, count - offset);
            exportProgress(item, copied);
            await new Promise(resolve => setImmediate(resolve));
        }
        if (copied !== item.current.size || identity(item.current) !== identity(fs.fstatSync(input, { bigint: true }))) fail('SAFETY_ITEM_CHANGED', '내보내기 중 원본 파일이 변경되었습니다.');
        fs.fsyncSync(output); verifyDirectories();
        if (!sameNode(outputIdentity, stat(destination))) fail('SAFETY_ITEM_CHANGED', '내보내기 위치가 변경되었습니다.');
        fs.closeSync(output); output = undefined;
        cancellationCheckpoint();
        if (!sameNode(markerIdentity, stat(marker))) fail('SAFETY_ITEM_CHANGED', '내보내기 완료 표시가 변경되었습니다.');
        fs.unlinkSync(marker); markerIdentity = null;
        success = true;
        // A progress/log write failure after a durable export must not turn it
        // into a failed operation or cause deletion of the completed file.
        try { exportProgress(item, copied, true); } catch (_) {}
        console.log(`exported_name_b64=${b64(`${exportName}/${item.label}`)}`);
    } finally {
        if (input !== undefined) fs.closeSync(input);
        if (output !== undefined) fs.closeSync(output);
        if (!success) {
            // Clean only inodes this export created. A changed folder or any
            // new foreign entry is retained instead of recursively removed.
            try {
                if (outputIdentity && exists(destination) && sameNode(outputIdentity, stat(destination))) fs.unlinkSync(destination);
                if (markerIdentity && exists(marker) && sameNode(markerIdentity, stat(marker))) fs.unlinkSync(marker);
                closeDirectory(exportDirectory);
                if (exists(exportPath) && sameNode(exportIdentity, stat(exportPath))) fs.rmdirSync(exportPath);
            } catch (_) { console.error('미완성 내보내기 폴더가 변경되어 남은 항목은 자동으로 삭제하지 않았습니다.'); }
        }
    }
}
function removeTree(directory, depth = 0) {
    if (depth > 128) fail('SAFETY_PATH_UNSAFE', '휴지통 폴더가 너무 깊습니다. 남은 항목은 유지했습니다.');
    const iterator = fs.opendirSync(directory.pinned);
    try {
    let directoryEntry;
    while ((directoryEntry = iterator.readSync())) {
        const name = directoryEntry.name;
        const target = child(directory, name), before = stat(target);
        // Unlink symlinks themselves, never traverse their targets. Such links
        // can legitimately be inside a trashed folder imported from Termux.
        if (before.isDirectory() && !before.isSymbolicLink()) {
            const nested = openDirectory(target);
            try { removeTree(nested, depth + 1); verifyDirectories(); }
            finally { closeDirectory(nested); }
            if (!sameNode(before, stat(target))) fail('SAFETY_ITEM_CHANGED', '삭제 중 폴더가 변경되어 중단했습니다.');
            fs.rmdirSync(target);
        } else {
            if (identity(before) !== identity(stat(target))) fail('SAFETY_ITEM_CHANGED', '삭제 중 항목이 변경되어 중단했습니다.');
            fs.unlinkSync(target);
        }
    }
    } finally { iterator.closeSync(); }
}
function deleteItem(item) {
    checkRevision(item);
    if (item.kind === 'trash') {
        const before = item.itemStat;
        if (before.isDirectory()) {
            const directory = openDirectory(item.item);
            try { removeTree(directory); verifyDirectories(); } finally { closeDirectory(directory); }
            if (!sameNode(before, stat(item.item))) fail('SAFETY_ITEM_CHANGED', '휴지통 폴더가 변경되어 삭제를 중단했습니다.');
            fs.rmdirSync(item.item);
        } else {
            if (identity(before) !== identity(stat(item.item))) fail('SAFETY_ITEM_CHANGED', '휴지통 파일이 변경되었습니다.');
            fs.unlinkSync(item.item);
        }
        fs.unlinkSync(child(item.container, 'metadata.json'));
        closeDirectory(item.container);
        fs.rmdirSync(item.target);
    } else {
        if (identity(item.current) !== identity(stat(item.target))) fail('SAFETY_ITEM_CHANGED', '보관 파일이 변경되었습니다.');
        fs.unlinkSync(item.target);
        if (item.completionMarker) {
            const marker = child(item.parent, COMPLETION);
            if (exists(marker) && identity(stat(marker)) === identity(item.completionMarker.before)) fs.unlinkSync(marker);
        }
        // Keep empty snapshot containers. Never recursively delete an archive's
        // parent: it could now contain new recovery or user-created material.
    }
    console.log(`deleted_id_b64=${b64(item.relative)}`);
}
async function main() { try {
    if (!['list', 'count', 'export', 'delete', 'validate-trash'].includes(action) || !launcherArgument || !installationArgument) fail('SAFETY_ITEM_UNSUPPORTED', '지원하지 않는 보관 파일 작업입니다.');
    if (stat(launcherArgument).isSymbolicLink()) fail('SAFETY_PATH_UNSAFE', '런처 보관 폴더가 심볼릭 링크입니다.');
    rootPath = fs.realpathSync(launcherArgument); root = openDirectory(rootPath);
    installPath = exists(installationArgument) ? fs.realpathSync(installationArgument) : path.resolve(installationArgument);
    const discovery = discover(), baseline = handles.length;
    const counts = { archive: 0, config: 0, trash: 0 };
    function countRemainder(from) {
        for (let i = from; i < discovery.candidates.length && !scanTruncated; i++) {
            try {
                const item = entry(discovery.candidates[i]);
                if (Buffer.byteLength(row(item)) <= MAX_ROW) counts[item.kind]++;
            } catch (_) { /* also excluded by the inventory list */ }
            finally { releaseAfter(baseline); }
        }
    }
    if (action === 'count') {
        countRemainder(0);
        if (!scanTruncated) console.log(`safety_copies=${counts.archive + counts.config}\ntrash_items=${counts.trash}`);
    } else if (action === 'list') {
        if (!/^(0|[1-9][0-9]{0,7})$/.test(selected || '0')) fail('SAFETY_ITEM_UNSUPPORTED', '목록 페이지가 올바르지 않습니다.');
        const firstPage = !Number(selected || '0');
        let cursor = Number(selected || '0'), output = '', skipped = 0;
        if (cursor > discovery.candidates.length) fail('SAFETY_ITEM_CHANGED', '보관 목록이 변경되었습니다. 처음부터 다시 열어 주세요.');
        while (cursor < discovery.candidates.length) {
            let line, item;
            try { item = entry(discovery.candidates[cursor], true); line = row(item); }
            catch (_) { skipped++; cursor++; continue; }
            finally { releaseAfter(baseline); }
            if (Buffer.byteLength(line) > MAX_ROW) { skipped++; cursor++; continue; }
            if (Buffer.byteLength(output) + Buffer.byteLength(line) > MAX_PAGE - 512) break;
            counts[item.kind]++; output += line; cursor++;
        }
        // Only the first page counts remaining validated metadata; never walk
        // item contents or repeatedly scan all pages for the category headings.
        if (firstPage) countRemainder(cursor);
        console.log('safety_inventory=1');
        if (firstPage && !scanTruncated) console.log(`inventory_counts=${counts.archive},${counts.config},${counts.trash}`);
        console.log(`next_cursor=${cursor < discovery.candidates.length ? cursor : -1}`);
        console.log(`inventory_truncated=${scanTruncated ? 1 : 0}`);
        console.log(`mutation_blocked=${discovery.blocked || scanTruncated ? 1 : 0}`);
        console.log(`protected_entries=${protectedEntries + skipped}`);
        process.stdout.write(output);
    } else {
        if (discovery.blocked) fail('SAFETY_RECOVERY_PENDING', '진행 중이거나 복구가 필요한 작업의 보관 자료는 먼저 점검·복구해 주세요.');
        const relative = decode(selected);
        // An exact allowlist match is not enough: incomplete snapshots must
        // also have appeared in discovery. Client-provided paths are untrusted.
        classify(relative);
        if (!discovery.candidates.includes(relative)) fail('SAFETY_PATH_UNSAFE', '완료되지 않았거나 보호된 보관 항목입니다.');
        const item = entry(relative);
        if (action === 'export') await exportItem(item);
        else if (action === 'validate-trash') {
            checkRevision(item);
            if (item.kind !== 'trash' || !item.restorable) fail('SAFETY_ITEM_UNSUPPORTED', '현재 설치로 복원할 수 없는 휴지통 항목입니다.');
            console.log(`trash_id=${item.trashId}`);
        } else deleteItem(item);
    }
} catch (error) {
    const code = error.safetyCode || (action === 'export' ? 'SAFETY_EXPORT_FAILED' : 'SAFETY_INVENTORY_FAILED');
    console.error(`error_code=${code}`);
    console.error(error.safetyCode ? error.message : `보관 파일 작업을 완료하지 못했습니다 (${error.code || 'INVALID_DATA'}). 원본 경로를 확인해 주세요.`);
    process.exitCode = codes[code] || 70;
} finally { releaseAfter(0); } }
main();
NODE
