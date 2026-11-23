#!/data/data/com.termux/files/usr/bin/bash
# Read-only planning over the manager's validated ZIP index. No live writes.
set -Eeuo pipefail
exec node --input-type=commonjs - "$@" <<'NODE'
const fs = require('fs'), path = require('path'), crypto = require('crypto');
const [action, indexPath, archivePath, installationArgument, argument = '0', outputPath = '', expectedArchive = '', expectedInstallation = ''] = process.argv.slice(2);
const b64 = value => Buffer.from(String(value), 'utf8').toString('base64');
const hash = value => crypto.createHash('sha256').update(value).digest('hex');
const fail = (code, message) => { throw Object.assign(new Error(message), { exitCode: code }); };
const stat = target => fs.lstatSync(target, { bigint: true });
const revision = current => hash([current.dev, current.ino, current.mode, current.size, current.mtimeNs, current.ctimeNs].join(':'));
const partSafe = part => !!part && part !== '.' && !part.includes('..') && !/[\\/\x00-\x1f\x7f]/.test(part);
let install, installationRevision;
function readJson(target, limit) {
    const before = stat(target);
    if (!before.isFile() || before.isSymbolicLink() || before.size > BigInt(limit)) fail(75, '개별 복원 정보를 안전하게 읽을 수 없습니다. 전체 복원 또는 더 작은 백업을 선택해 주세요.');
    const fd = fs.openSync(target, fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW || 0));
    try {
        if (revision(before) !== revision(fs.fstatSync(fd, { bigint: true }))) fail(72, '백업 정보가 변경되었습니다. 다시 선택해 주세요.');
        const bytes = Buffer.alloc(Number(before.size)); let offset = 0;
        while (offset < bytes.length) { const count = fs.readSync(fd, bytes, offset, bytes.length - offset, null); if (!count) fail(72, '백업 정보가 변경되었습니다.'); offset += count; }
        if (revision(before) !== revision(fs.fstatSync(fd, { bigint: true }))) fail(72, '백업 정보가 변경되었습니다.');
        return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
    } finally { fs.closeSync(fd); }
}
function classify(relative) {
    if (typeof relative !== 'string' || Buffer.byteLength(relative) > 4096) return null;
    const parts = relative.split('/');
    if (parts.some(part => !partSafe(part)) || parts[0] !== 'data') return null;
    if (parts.length === 4 && parts[2] === 'characters' && /\.png$/i.test(parts[3])) return { kind: 'character', profile: parts[1], character: parts[3].slice(0, -4), label: parts[3] };
    if (parts.length === 5 && parts[2] === 'chats' && /\.jsonl$/i.test(parts[4])) return { kind: 'chat', profile: parts[1], character: parts[3], label: `${parts[3]}/${parts[4]}` };
    return null;
}
function targetInfo(relative) {
    if (!relative.split('/').every(partSafe)) fail(75, '안전하지 않은 복원 경로입니다.');
    let current = install;
    const parts = relative.split('/');
    for (let index = 0; index < parts.length; index++) {
        current = path.join(current, parts[index]);
        let item;
        try { item = stat(current); } catch (error) { if (error.code === 'ENOENT') return { exists: false, revision: 'none', bytes: 0 }; throw error; }
        if (item.isSymbolicLink() || (index < parts.length - 1 && !item.isDirectory())) fail(75, '복원 대상 경로에 심볼릭 링크 또는 다른 종류의 항목이 있습니다.');
        if (index === parts.length - 1) return { exists: true, revision: revision(item), bytes: Number(item.size), directory: item.isDirectory(), file: item.isFile() };
    }
}
function archiveIdentity() {
    const current = stat(archivePath);
    if (!current.isFile() || current.isSymbolicLink()) fail(72, '선택한 백업 파일을 확인할 수 없습니다.');
    return revision(current);
}
function profileExists(profile) { return targetInfo(`data/${profile}`).directory === true; }
function characterExists(profile, character) { return targetInfo(`data/${profile}/characters/${character}.png`).file === true; }
function outputJson(file, value) {
    const fd = fs.openSync(file, 'wx', 0o600);
    try { fs.writeFileSync(fd, JSON.stringify(value)); fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
}
const nodeIdentity = current => `${current.dev}:${current.ino}`;
function prepareGuard(plan) {
    const work = path.dirname(indexPath), items = [];
    for (const item of plan.items) {
        if (!classify(item.path) || targetInfo(item.path).revision !== item.targetRevision) fail(73, '보호 기록 준비 중 대상이 변경되었습니다.');
        const incoming = stat(path.join(outputPath, item.path));
        if (!incoming.isFile() || incoming.isSymbolicLink() || Number(incoming.size) !== item.bytes) fail(75, '추출한 파일을 확인하지 못했습니다.');
        let protectedIdentity = 'none';
        if (item.targetRevision !== 'none') {
            const saved = stat(path.join(work, 'rollback', item.path));
            if (!saved.isFile() || saved.isSymbolicLink()) fail(75, '현재 파일 보호 사본을 확인하지 못했습니다.');
            protectedIdentity = nodeIdentity(saved);
        }
        items.push({ path: item.path, originalRevision: item.targetRevision, replacementIdentity: nodeIdentity(incoming), protectedIdentity });
    }
    outputJson(path.join(work, 'selection-guard.json'), { format: 'st-launcher-selection-guard-v1', installationRevision, items });
}
function verifyGuard(guard) {
    if (guard.format !== 'st-launcher-selection-guard-v1' || guard.installationRevision !== installationRevision || !Array.isArray(guard.items) || guard.items.length < 1 || guard.items.length > 100 || new Set(guard.items.map(item => item.path)).size !== guard.items.length) fail(73, '개별 복원 보호 기록을 확인할 수 없습니다.');
    if (argument && argument !== '0' && b64(guard.items.map(item => item.path).join('\n') + '\n') !== argument) fail(73, '개별 복원 보호 기록과 저널 경로가 다릅니다.');
    for (const item of guard.items) {
        if (!classify(item.path) || !/^(none|[a-f0-9]{64})$/.test(item.originalRevision) || !/^[0-9]+:[0-9]+$/.test(item.replacementIdentity) || !/^(none|[0-9]+:[0-9]+)$/.test(item.protectedIdentity)) fail(73, '개별 복원 보호 기록을 확인할 수 없습니다.');
        const live = targetInfo(item.path);
        if (!live.exists) continue;
        if (!live.file) fail(73, '복원 경로에 알 수 없는 항목이 있어 원본을 보존했습니다.');
        const identity = nodeIdentity(stat(path.join(install, item.path)));
        if (live.revision !== item.originalRevision && identity !== item.replacementIdentity && identity !== item.protectedIdentity) fail(73, '선택한 경로에 다른 파일이 생겨 자동 원복을 차단했습니다. 현재 파일과 보호 사본을 모두 유지합니다.');
    }
    console.log('selection_guard_verified=1');
}
function applyItem(plan) {
    const item = plan.items.find(item => item.path === argument);
    if (!item || !classify(item.path)) fail(75, '선택하지 않은 파일은 적용하지 않습니다.');
    const handles = [], originalRoot = stat(install), extracted = fs.realpathSync(outputPath);
    if (stat(outputPath).isSymbolicLink()) fail(75, '임시 복원 경로가 변경되었습니다.');
    const sameNode = (a, b) => a.dev === b.dev && a.ino === b.ino && a.mode === b.mode;
    function pin(p) {
        const before = stat(p);
        if (!before.isDirectory() || before.isSymbolicLink()) fail(73, '복원 대상의 상위 폴더가 변경되었습니다.');
        const fd = fs.openSync(p, fs.constants.O_RDONLY | (fs.constants.O_DIRECTORY || 0) | (fs.constants.O_NOFOLLOW || 0));
        if (!sameNode(before, fs.fstatSync(fd, { bigint: true }))) { fs.closeSync(fd); fail(73, '복원 대상의 상위 폴더가 변경되었습니다.'); }
        handles.push(fd);
        return process.platform === 'linux' ? `/proc/self/fd/${fd}` : p;
    }
    function parent(base, create) {
        let current = pin(base);
        for (const part of item.path.split('/').slice(0, -1)) {
            const child = path.join(current, part);
            if (create) { try { fs.mkdirSync(child, { mode: 0o700 }); } catch (e) { if (e.code !== 'EEXIST') throw e; } }
            current = pin(child);
        }
        return current;
    }
    try {
        // Check the live path before pinning and check the pinned final inode
        // immediately before rename. Intermediate symlinks are never followed.
        if (targetInfo(item.path).revision !== item.targetRevision) fail(73, '적용 직전 복원 대상 파일이 변경되었습니다.');
        const sourceParent = parent(extracted, false), sourceParentFd = handles.at(-1);
        const destinationParent = parent(install, true), destinationParentFd = handles.at(-1), name = path.basename(item.path);
        const source = path.join(sourceParent, name), destination = path.join(destinationParent, name);
        const incoming = stat(source);
        if (!incoming.isFile() || incoming.isSymbolicLink() || Number(incoming.size) !== item.bytes || incoming.dev !== fs.fstatSync(destinationParentFd, { bigint: true }).dev) fail(75, '임시 복원 파일을 안전하게 적용할 수 없습니다.');
        let current = 'none';
        try { const target = stat(destination); if (!target.isFile() || target.isSymbolicLink()) fail(73, '복원 대상 파일 종류가 변경되었습니다.'); current = revision(target); }
        catch (e) { if (e.code !== 'ENOENT') throw e; }
        if (current !== item.targetRevision || !sameNode(originalRoot, stat(install))) fail(73, '적용 직전 복원 대상 파일이 변경되었습니다.');
        if (current === 'none' && process.platform === 'linux') {
            // GNU mv inherits our pinned directories into its own fd table.
            // It never opens another PID's /proc and must not overwrite a file
            // created after the absent-target check.
            const result = require('child_process').spawnSync('mv', ['-T', '-n', '--', `/proc/self/fd/3/${name}`, `/proc/self/fd/4/${name}`], {
                stdio: ['ignore', 'pipe', 'pipe', sourceParentFd, destinationParentFd], timeout: 10000,
            });
            if (result.error || result.status !== 0) fail(73, '새 복원 파일을 안전하게 등록하지 못했습니다.');
            try { stat(source); fail(73, '적용 직전에 같은 이름의 파일이 생겨 덮어쓰지 않았습니다.'); }
            catch (e) { if (e.code !== 'ENOENT') throw e; }
        } else fs.renameSync(source, destination);
        if (!sameNode(incoming, stat(destination))) fail(73, '복원 결과를 확인할 수 없습니다.');
        if (process.platform === 'linux') { fs.fsyncSync(handles.at(-1)); }
        console.log(`selection_applied_b64=${b64(item.path)}`);
    } finally { for (const fd of handles.reverse()) fs.closeSync(fd); }
}
try {
    const rootStat = stat(installationArgument);
    if (!rootStat.isDirectory() || rootStat.isSymbolicLink()) fail(76, '기존 SillyTavern 설치가 있어야 개별 복원할 수 있습니다.');
    install = fs.realpathSync(installationArgument);
    installationRevision = hash(`${install}:${rootStat.dev}:${rootStat.ino}:${rootStat.mode}`);
    if (action === 'verify-guard') {
        verifyGuard(readJson(indexPath, 256 * 1024));
    } else if (action === 'verify-targets' || action === 'apply-item' || action === 'prepare-guard') {
        const plan = readJson(indexPath, 256 * 1024);
        if (plan.format !== 'st-launcher-selected-restore-v1' || plan.installationRevision !== installationRevision || !Array.isArray(plan.items) || plan.items.length > 100) fail(73, '설치 위치 또는 개별 복원 계획이 변경되었습니다.');
        if (action === 'apply-item') applyItem(plan);
        else if (action === 'prepare-guard') prepareGuard(plan);
        else {
            for (const item of plan.items) {
                if (!classify(item.path) || targetInfo(item.path).revision !== item.targetRevision) fail(73, '복원 대상 파일이 변경되었습니다. 다시 선택해 주세요.');
            }
            console.log('selection_targets_verified=1');
        }
    } else {
        const index = readJson(indexPath, 64 * 1024 * 1024);
        if (!index || !index.archive || !Array.isArray(index.entries) || index.entries.length > 100000) fail(75, '개별 복원 목록이 너무 큽니다. 전체 복원 또는 더 작은 백업을 선택해 주세요.');
        const source = fs.lstatSync(archivePath);
        if (!source.isFile() || source.isSymbolicLink() || ['size', 'mtimeMs', 'dev', 'ino'].some(key => source[key] !== index.archive[key])) fail(72, '백업이 변경되었습니다. 목록을 새로 열어 주세요.');
        const archiveRevision = archiveIdentity();
        const items = index.entries.filter(entry => !entry.isDirectory && classify(entry.path));
        if (new Set(items.map(item => item.path)).size !== items.length) fail(75, '중복된 복원 경로입니다.');
        for (const entry of items) if (!Number.isSafeInteger(entry.bytes) || entry.bytes < 0) fail(75, '복원 파일 크기가 올바르지 않습니다.');
        items.sort((a, b) => a.path < b.path ? -1 : a.path > b.path ? 1 : 0);
        if (action === 'list') {
            if (!/^(0|[1-9][0-9]{0,6})$/.test(argument)) fail(75, '목록 페이지가 올바르지 않습니다.');
            let cursor = Number(argument), output = '';
            if (cursor > items.length) fail(72, '목록이 변경되었습니다. 처음부터 다시 열어 주세요.');
            while (cursor < items.length) {
                const entry = items[cursor], info = classify(entry.path), target = targetInfo(entry.path);
                const missing = info.kind === 'chat' && !characterExists(info.profile, info.character);
                const line = ['restore_item', info.kind, b64(entry.path), entry.bytes, target.exists ? 1 : 0, target.revision,
                    missing ? 1 : 0, profileExists(info.profile) ? 1 : 0, b64(info.profile), b64(info.label)].join('\t') + '\n';
                if (Buffer.byteLength(line) > 12000) fail(75, '복원 경로가 너무 깁니다.');
                if (Buffer.byteLength(output) + Buffer.byteLength(line) > 40 * 1024 - 512) break;
                output += line; cursor++;
            }
            if (archiveIdentity() !== archiveRevision) fail(72, '목록을 읽는 동안 백업이 변경되었습니다.');
            console.log(`selection_format=1\narchive_revision=${archiveRevision}\ninstallation_revision=${installationRevision}\nnext_cursor=${cursor < items.length ? cursor : -1}\ncandidate_count=${items.length}`);
            process.stdout.write(output);
        } else if (action === 'plan') {
            if (archiveRevision !== expectedArchive || installationRevision !== expectedInstallation) fail(72, '백업 또는 설치가 변경되었습니다. 목록을 새로 열어 주세요.');
            if (argument.length > 65536 || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(argument)) fail(75, '선택 항목 요청이 올바르지 않거나 너무 큽니다.');
            const requested = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.from(argument, 'base64')));
            if (!Array.isArray(requested) || !requested.length || requested.length > 100 || new Set(requested.map(item => item.path)).size !== requested.length) fail(75, '한 번에 1개에서 100개까지 선택해 주세요.');
            const byPath = new Map(items.map(item => [item.path, item]));
            const selected = []; let skipped = 0, oldBytes = 0, bytes = 0;
            for (const request of requested) {
                if (!request || !byPath.has(request.path) || !['skip', 'replace'].includes(request.policy) || !/^(none|[a-f0-9]{64})$/.test(request.targetRevision)) fail(75, '지원하지 않는 개별 복원 요청입니다.');
                const info = classify(request.path), target = targetInfo(request.path);
                if (!profileExists(info.profile)) fail(76, '현재 설치에 같은 사용자 프로필이 없습니다. 전체 사용자 데이터 복원을 사용해 주세요.');
                if (target.revision !== request.targetRevision) fail(73, '선택 후 복원 대상 파일이 변경되었습니다. 다시 선택해 주세요.');
                if (target.exists && !target.file) fail(75, '같은 이름의 폴더가 있어 파일을 복원할 수 없습니다.');
                if (target.exists && request.policy === 'skip') { skipped++; continue; }
                const entry = byPath.get(request.path); bytes += entry.bytes; oldBytes += target.bytes;
                if (!Number.isSafeInteger(bytes) || !Number.isSafeInteger(oldBytes)) fail(75, '선택 용량이 너무 큽니다.');
                selected.push({ path: request.path, targetRevision: target.revision, bytes: entry.bytes, entry, info });
            }
            const characters = new Set(selected.filter(item => item.info.kind === 'character').map(item => `${item.info.profile}/${item.info.character}`));
            for (const item of selected) if (item.info.kind === 'chat' && !characters.has(`${item.info.profile}/${item.info.character}`) && !characterExists(item.info.profile, item.info.character)) fail(74, '채팅에 연결할 캐릭터가 없습니다. 같은 캐릭터 카드도 함께 선택해 주세요.');
            const plan = { format: 'st-launcher-selected-restore-v1', archiveRevision, installationRevision, bytes, oldBytes, skipped,
                items: selected.map(({ path, targetRevision, bytes }) => ({ path, targetRevision, bytes })) };
            outputJson(outputPath, plan);
            outputJson(`${outputPath}.index`, { archive: index.archive, entries: selected.map(item => item.entry) });
            console.log(`selection_count=${selected.length}\nselection_skipped=${skipped}\nselection_bytes=${bytes}\nselection_protected_bytes=${oldBytes}`);
        } else fail(75, '지원하지 않는 개별 복원 작업입니다.');
    }
} catch (error) {
    const code = error.exitCode || 75;
    const names = { 72: 'SELECTIVE_RESTORE_SOURCE_CHANGED', 73: 'SELECTIVE_RESTORE_TARGET_CHANGED', 74: 'SELECTIVE_RESTORE_CHARACTER_MISSING', 75: 'SELECTIVE_RESTORE_INVALID', 76: 'SELECTIVE_RESTORE_PROFILE_MISSING' };
    console.error(`error_code=${names[code] || names[75]}`);
    console.error(error.exitCode ? error.message : '개별 복원 정보를 안전하게 처리하지 못했습니다. 현재 파일은 변경하지 않았습니다.');
    process.exitCode = code;
}
NODE
