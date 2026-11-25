#!/data/data/com.termux/files/usr/bin/bash
# manager owns the operation lock, stopped-server check and global recovery gate.
set -Eeuo pipefail
exec node --input-type=commonjs - "$@" <<'NODE'
const fs = require('fs'), path = require('path'), crypto = require('crypto'), cp = require('child_process');
const [action, rootArg, launcherArg, selection = '', expected = ''] = process.argv.slice(2);
const DISABLED = '.st-launcher-disabled-manifest.json', JOURNAL = 'extension-update-journal.json';
const LIMIT_FILES = 50000, LIMIT_BYTES = 512 * 1024 * 1024, PAGE = 40;
const codes = { EXTENSION_PATH_UNSAFE: 72, EXTENSION_CHANGED: 73, EXTENSION_UNSUPPORTED: 74,
    EXTENSION_UPDATE_FAILED: 75, EXTENSION_RECOVERY_PENDING: 76, EXTENSION_NO_SPACE: 77,
    EXTENSION_OPERATION_FAILED: 78, EXTENSION_LIST_FAILED: 79, OPERATION_CANCELLED: 130 };
let root, launcher, cancelled = false, committed = false, ownedWork, ownedWorkNode;
let lastProgress = 0, lastLog = 0, lastPhase = '', phaseStarted = 0;
const directories = [];
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { cancelled = true; });
const b64 = x => Buffer.from(String(x)).toString('base64');
const sha = x => crypto.createHash('sha256').update(x).digest('hex');
const stat = p => fs.lstatSync(p, { bigint: true });
const ident = s => [s.dev, s.ino, s.mode, s.size, s.mtimeNs, s.ctimeNs].join(':');
const nodeId = s => [s.dev, s.ino, s.mode].join(':');
function fail(code, message) { throw Object.assign(new Error(message), { extensionCode: code }); }
function exists(p) { try { stat(p); return true; } catch (e) { if (e.code === 'ENOENT') return false; throw e; } }
function checkpoint() {
    if (!committed && (cancelled || [process.env.ST_CANCEL_REQUEST_FILE, process.env.ST_CANCEL_REQUEST_ID_FILE].some(p => p && exists(p))))
        fail('OPERATION_CANCELLED', '확장 작업을 중단했습니다. 원본은 유지했습니다.');
}
function progress(phase, detail, completed = null, total = null, current = '') {
    const now = Date.now(), changed = phase !== lastPhase;
    if (!changed && now - lastProgress < 500) return;
    lastProgress = now; if (changed) { lastPhase = phase; phaseStarted = Math.floor(now / 1000); }
    const operation = process.env.ST_PROGRESS_OPERATION || 'extension-update';
    try {
        if (process.env.ST_PROGRESS_FILE) {
            const record = { percent: total ? Math.min(100, Math.floor(completed * 100 / total)) : 0, phase, detail,
                status: 'running', operation, error_code: '', progress_mode: total === null ? 'indeterminate' : 'bytes',
                completed_bytes: completed || 0, total_bytes: total || 0, completed_files: 0, total_files: 0,
                current_item_b64: b64(current), heartbeat_at: Math.floor(now / 1000), activity_at: Math.floor(now / 1000),
                phase_started_at: phaseStarted, operation_started_at: Number(process.env.ST_OPERATION_STARTED_EPOCH) || 0 };
            const temporary = `${process.env.ST_PROGRESS_FILE}.extensions.${process.pid}.tmp`;
            fs.writeFileSync(temporary, Object.entries(record).map(([key, value]) => `${key}=${value}\n`).join(''), { mode: 0o600 });
            fs.renameSync(temporary, process.env.ST_PROGRESS_FILE);
        }
        if (changed || now - lastLog >= 2000) {
            lastLog = now;
            const line = `[${new Date().toLocaleString('sv-SE', { hour12: false })}] [${operation}] ${phase} · ${detail}${total === null ? '' : ` · ${completed}/${total} 바이트`}\n`;
            for (const file of new Set([process.env.ST_PROGRESS_LOG, process.env.ST_PROGRESS_HISTORY].filter(Boolean))) {
                // Keep optional history bounded; the operation log remains the detailed source.
                if (file === process.env.ST_PROGRESS_HISTORY && exists(file) && stat(file).size > 1000000n) continue;
                fs.appendFileSync(file, line, { mode: 0o600 });
            }
        }
    } catch (_) { /* Observation must not change transaction outcome. */ }
}
function safeName(s) { return typeof s === 'string' && s.length > 0 && s !== '.' && s !== '..' && !/[\\/\x00-\x1f\x7f]/.test(s); }
function decode(s) {
    if (!s || s.length > 8192 || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(s)) fail('EXTENSION_PATH_UNSAFE', '확장 경로가 올바르지 않습니다.');
    const value = new TextDecoder('utf-8', { fatal: true }).decode(Buffer.from(s, 'base64'));
    if (b64(value) !== s) fail('EXTENSION_PATH_UNSAFE', '확장 경로가 올바르지 않습니다.');
    return value;
}
function directory(p) {
    const s = stat(p);
    if (!s.isDirectory() || s.isSymbolicLink()) fail('EXTENSION_PATH_UNSAFE', '심볼릭 링크 또는 알 수 없는 폴더는 변경하지 않습니다.');
    directories.push([p, nodeId(s)]); return p;
}
function rootDirectory(p) {
    if (!p || !path.isAbsolute(p) || path.resolve(p) === path.parse(path.resolve(p)).root) fail('EXTENSION_PATH_UNSAFE', '설치 경로가 안전하지 않습니다.');
    let current = path.parse(path.resolve(p)).root;
    for (const part of path.resolve(p).slice(current.length).split(path.sep).filter(Boolean)) current = directory(path.join(current, part));
    return current;
}
function verifyParents() {
    for (const [p, id] of directories) if (nodeId(stat(p)) !== id) fail('EXTENSION_CHANGED', '폴더 위치가 변경되어 중단했습니다.');
}
function extensionPath(relative) {
    const parts = relative.split('/');
    if (!parts.every(safeName) || !(parts.length === 5 && parts.slice(0, 4).join('/') === 'public/scripts/extensions/third-party' ||
        parts.length === 4 && parts[0] === 'data' && parts[2] === 'extensions')) fail('EXTENSION_PATH_UNSAFE', '지원되는 클라이언트 확장 경로가 아닙니다.');
    let current = root;
    for (const part of parts.slice(0, -1)) current = directory(path.join(current, part));
    return path.join(current, parts.at(-1));
}
function read(p, limit) {
    const before = stat(p);
    if (!before.isFile() || before.isSymbolicLink() || before.size > BigInt(limit)) fail('EXTENSION_UNSUPPORTED', '파일 형식이나 크기를 안전하게 확인할 수 없습니다.');
    const fd = fs.openSync(p, fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW || 0));
    try {
        if (ident(fs.fstatSync(fd, { bigint: true })) !== ident(before)) fail('EXTENSION_CHANGED', '파일이 변경되었습니다.');
        const buffer = Buffer.alloc(limit + 1); let used = 0, n;
        while (used < buffer.length && (n = fs.readSync(fd, buffer, used, buffer.length - used, null))) used += n;
        if (used > limit || ident(stat(p)) !== ident(before)) fail('EXTENSION_CHANGED', '파일이 변경되었습니다.');
        return buffer.subarray(0, used);
    } finally { fs.closeSync(fd); }
}
function json(p, limit = 65536) { return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(read(p, limit))); }
function manifest(target) {
    const enabled = exists(path.join(target, 'manifest.json')), disabled = exists(path.join(target, DISABLED));
    if (enabled === disabled) fail('EXTENSION_UNSUPPORTED', 'manifest.json을 하나로 확인할 수 없습니다.');
    const file = path.join(target, enabled ? 'manifest.json' : DISABLED), bytes = read(file, 65536), data = JSON.parse(bytes.toString('utf8'));
    if (!data || typeof data !== 'object' || Array.isArray(data)) fail('EXTENSION_UNSUPPORTED', '확장 manifest 형식이 올바르지 않습니다.');
    return { disabled, file, bytes, data };
}
function cleanText(value, max) { return typeof value === 'string' ? value.replace(/[\x00-\x1f\x7f\u202a-\u202e\u2066-\u2069]/g, '').slice(0, max) : ''; }
const gitEnv = Object.fromEntries(Object.entries(process.env).filter(([key]) => !/^GIT_/i.test(key)));
Object.assign(gitEnv, { GIT_CONFIG_NOSYSTEM: '1', GIT_CONFIG_GLOBAL: process.platform === 'win32' ? 'NUL' : '/dev/null', GIT_TERMINAL_PROMPT: '0', GIT_ALLOW_PROTOCOL: 'https', GIT_OPTIONAL_LOCKS: '0' });
function git(target, args, options = {}) {
    verifyParents();
    const result = cp.spawnSync('git', ['-c', `safe.directory=${target}`, '-c', 'core.hooksPath=/dev/null', '-c', 'core.fsmonitor=false',
        '-c', 'core.untrackedCache=false', '-c', 'protocol.file.allow=never', '-c', 'protocol.ext.allow=never', '-C', target, ...args],
        { env: gitEnv, encoding: 'utf8', timeout: options.timeout || (action === 'list' ? 2000 : 15000), maxBuffer: options.maxBuffer || 1024 * 1024, windowsHide: true });
    if (result.error || result.status !== 0) {
        if (options.allowFailure) return null;
        fail('EXTENSION_UPDATE_FAILED', 'Git 확인 또는 업데이트에 실패했습니다. 원본과 보호 사본을 유지합니다.');
    }
    return result.stdout;
}
async function fetchCandidate(target, info) {
    checkpoint(); verifyParents();
    await new Promise((resolve, reject) => {
        const args = ['-c', `safe.directory=${target}`, '-c', 'core.hooksPath=/dev/null', '-c', 'core.fsmonitor=false',
            '-c', 'protocol.file.allow=never', '-c', 'protocol.ext.allow=never', '-C', target,
            'fetch', '--no-tags', '--no-recurse-submodules', info.origin, `refs/heads/${info.branch}`];
        const child = cp.spawn('git', args, { env: gitEnv, stdio: 'ignore', detached: process.platform !== 'win32', windowsHide: true });
        let reason = null, ended = false, killTimer;
        function kill(signal) { try { if (process.platform !== 'win32') process.kill(-child.pid, signal); else child.kill(signal); } catch (_) {} }
        const started = Date.now();
        const timer = setInterval(() => {
            if (reason || ended) return;
            try { checkpoint(); if (Date.now() - started > 120000) fail('EXTENSION_UPDATE_FAILED', '원격 확인 시간이 초과되었습니다. 원본은 변경하지 않았습니다.'); }
            catch (error) { reason = error; kill('SIGTERM'); killTimer = setTimeout(() => kill('SIGKILL'), 3000); }
        }, 250);
        child.once('error', error => { reason = error; });
        child.once('close', code => {
            ended = true; clearInterval(timer); clearTimeout(killTimer);
            if (reason) reject(reason);
            else if (code !== 0) reject(Object.assign(new Error('확장 원격 확인에 실패했습니다. 네트워크와 GitHub 원본을 확인하세요.'), { extensionCode: 'EXTENSION_UPDATE_FAILED' }));
            else resolve();
        });
    });
    checkpoint();
}
function gitInfo(target, listing = false) {
    const gd = path.join(target, '.git');
    if (!exists(gd) || !stat(gd).isDirectory() || stat(gd).isSymbolicLink()) fail('EXTENSION_UNSUPPORTED', '독립된 Git 폴더가 있는 확장만 업데이트할 수 있습니다.');
    for (const p of ['commondir', 'gitdir', 'objects/info/alternates', 'objects/info/http-alternates', 'info/grafts', 'refs/replace', 'config.worktree', 'index.lock', 'HEAD.lock'])
        if (exists(path.join(gd, p))) fail('EXTENSION_UNSUPPORTED', '특수 Git 구성은 자동 업데이트하지 않습니다.');
    const shallow = exists(path.join(gd, 'shallow'));
    if (shallow && (!listing || stat(path.join(gd, 'shallow')).isSymbolicLink() || !stat(path.join(gd, 'shallow')).isFile()))
        fail('EXTENSION_UNSUPPORTED', '얕은 복제는 원격 버전만 확인할 수 있습니다.');
    // Never ask Git to interpret a local include, credential helper, URL rewrite or executable filter.
    read(path.join(gd, 'config'), 65536);
    const cfg = git(target, ['config', '--file', path.join(gd, 'config'), '--no-includes', '--null', '--list']);
    const values = new Map();
    for (const entry of cfg.split('\0').filter(Boolean)) {
        const split = entry.indexOf('\n'), key = entry.slice(0, split).toLowerCase(), value = entry.slice(split + 1);
        if (split < 0 || values.has(key) || !/^(?:core\.(?:repositoryformatversion|filemode|bare|logallrefupdates|ignorecase|precomposeunicode|symlinks)|remote\.origin\.(?:url|fetch)|branch\.[^\x00-\x20]+\.(?:remote|merge)|user\.(?:name|email))$/.test(key))
            fail('EXTENSION_UNSUPPORTED', '사용자 지정 Git 실행 설정이 있는 확장은 자동 변경하지 않습니다.');
        values.set(key, value);
    }
    if (values.get('core.bare') === 'true' || !/^https:\/\/github\.com\/[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+(?:\.git)?$/.test(values.get('remote.origin.url') || ''))
        fail('EXTENSION_UNSUPPORTED', 'HTTPS GitHub 원본을 확인할 수 있는 확장만 업데이트합니다.');
    const canonical = p => { const resolved = fs.realpathSync.native(p); return process.platform === 'win32' ? resolved.toLowerCase() : resolved; };
    if (canonical(git(target, ['rev-parse', '--show-toplevel']).trim()) !== canonical(target)) fail('EXTENSION_UNSUPPORTED', '다른 Git 저장소에 포함된 확장은 업데이트하지 않습니다.');
    const local = git(target, ['symbolic-ref', '--quiet', '--short', 'HEAD']).trim();
    // Use the configured origin upstream for BOTH comparison and fetch. Never silently compare
    // the similarly named branch at origin when the checkout actually follows another ref.
    const tracking = git(target, ['for-each-ref', '--format=%(upstream:remotename)%09%(upstream:remoteref)', `refs/heads/${local}`]).trim();
    const mapped = tracking && /^origin\trefs\/heads\/(.+)$/.exec(tracking);
    if (tracking && !mapped) fail('EXTENSION_UNSUPPORTED', 'origin의 추적 브랜치를 확인할 수 없습니다.');
    const branch = mapped ? mapped[1] : local;
    if (!branch || branch.startsWith('-') || git(target, ['check-ref-format', '--branch', branch], { allowFailure: true }) === null) fail('EXTENSION_UNSUPPORTED', '현재 브랜치를 확인할 수 없습니다.');
    const commit = git(target, ['rev-parse', 'HEAD']).trim();
    if (!/^[a-f0-9]{40,64}$/.test(commit)) fail('EXTENSION_UNSUPPORTED', 'Git 커밋을 확인할 수 없습니다.');
    const state = git(target, ['status', '--porcelain=v1', '--untracked-files=all', '--ignored=matching']);
    return { branch, commit, origin: values.get('remote.origin.url'), clean: !state.trim(), shallow };
}
function revision(target, m) {
    const gd = path.join(target, '.git'), fields = [root, target, ident(stat(target)), ident(stat(m.file)), sha(m.bytes)];
    if (exists(gd)) { fields.push(ident(stat(gd))); if (stat(gd).isDirectory() && !stat(gd).isSymbolicLink())
        for (const name of ['HEAD', 'config', 'index', 'packed-refs']) if (exists(path.join(gd, name))) fields.push(name, ident(stat(path.join(gd, name)))); }
    return sha(fields.join('\n'));
}
function selected() {
    const relative = decode(selection), target = extensionPath(relative), targetStat = stat(target);
    if (!targetStat.isDirectory() || targetStat.isSymbolicLink()) fail('EXTENSION_PATH_UNSAFE', '확장 폴더가 안전하지 않습니다.');
    const m = manifest(target), rev = revision(target, m);
    if (!/^[a-f0-9]{64}$/.test(expected) || rev !== expected) fail('EXTENSION_CHANGED', '확장이 변경되었습니다. 목록을 새로고침한 뒤 다시 선택하세요.');
    return { relative, target, m, rev };
}
function syncDirectory(p) { if (process.platform !== 'win32') { const fd = fs.openSync(p, fs.constants.O_RDONLY | fs.constants.O_DIRECTORY); try { fs.fsyncSync(fd); } finally { fs.closeSync(fd); } } }
function writeExclusive(p, value) { const fd = fs.openSync(p, 'wx', 0o600); try { fs.writeFileSync(fd, JSON.stringify(value)); fs.fsyncSync(fd); } finally { fs.closeSync(fd); } syncDirectory(path.dirname(p)); }
function renameNoReplace(from, to) {
    verifyParents();
    if (exists(to)) fail('EXTENSION_CHANGED', '대상 이름이 이미 있어 원본을 보존했습니다.');
    const before = nodeId(stat(from));
    if (stat(path.dirname(from)).dev !== stat(path.dirname(to)).dev) fail('EXTENSION_PATH_UNSAFE', '서로 다른 저장 장치 사이의 자동 교체는 지원하지 않습니다.');
    if (process.platform === 'linux') {
        // Inherit directory descriptors into mv itself. /proc/self belongs to that child, avoiding
        // Android's restrictions on opening the parent's /proc/<pid>/fd directory.
        const flags = fs.constants.O_RDONLY | fs.constants.O_DIRECTORY | fs.constants.O_NOFOLLOW;
        const source = fs.openSync(path.dirname(from), flags), destination = fs.openSync(path.dirname(to), flags);
        try {
            verifyParents();
            const moved = cp.spawnSync('mv', ['-T', '-n', '--', `/proc/self/fd/3/${path.basename(from)}`, `/proc/self/fd/4/${path.basename(to)}`],
                { stdio: ['ignore', 'pipe', 'pipe', source, destination], timeout: 10000 });
            if (moved.error || moved.status !== 0 || exists(from) || !exists(to) || nodeId(stat(to)) !== before)
                fail('EXTENSION_CHANGED', '대상 경로가 변경되어 덮어쓰지 않았습니다. 복구 기록을 확인하세요.');
        } finally { fs.closeSync(source); fs.closeSync(destination); }
    } else fs.renameSync(from, to); // Non-Android fixture platforms; production uses atomic no-clobber mv.
    syncDirectory(path.dirname(from)); if (path.dirname(from) !== path.dirname(to)) syncDirectory(path.dirname(to));
}
function mkdir(p) { fs.mkdirSync(p, { mode: 0o700 }); syncDirectory(path.dirname(p)); return p; }
function pinnedDirectory(p, body, expectedNode = null) {
    const before = stat(p);
    if (!before.isDirectory() || before.isSymbolicLink()) fail('EXTENSION_PATH_UNSAFE', '폴더 경로가 변경되었거나 링크입니다.');
    if (expectedNode !== null && nodeId(before) !== expectedNode) fail('EXTENSION_CHANGED', '소유한 임시 폴더가 변경되어 보존했습니다.');
    const fd = fs.openSync(p, fs.constants.O_RDONLY | (fs.constants.O_DIRECTORY || 0) | (fs.constants.O_NOFOLLOW || 0));
    try {
        if (nodeId(fs.fstatSync(fd, { bigint: true })) !== nodeId(before)) fail('EXTENSION_CHANGED', '폴더가 변경되었습니다.');
        return body(process.platform === 'linux' ? `/proc/self/fd/${fd}` : p, before, fd);
    } finally { fs.closeSync(fd); }
}
function trashContainer() {
    const trash = path.join(launcher, 'file-trash'); if (!exists(trash)) mkdir(trash); directory(trash);
    const id = `${Date.now()}-${crypto.randomUUID()}`, container = mkdir(path.join(trash, id));
    return { id, container, item: path.join(container, 'item') };
}
function removeOwnedTree(p, expectedNode = null) {
    // No recursive library delete: do not follow links even in our own interrupted staging tree.
    const s = stat(p);
    if (expectedNode !== null && nodeId(s) !== expectedNode) fail('EXTENSION_CHANGED', '소유한 임시 폴더가 변경되어 보존했습니다.');
    if (s.isDirectory() && !s.isSymbolicLink()) {
        pinnedDirectory(p, pinned => {
            for (const name of fs.readdirSync(pinned)) { if (!safeName(name)) fail('EXTENSION_PATH_UNSAFE', '임시 폴더를 정리할 수 없습니다.'); removeOwnedTree(path.join(pinned, name)); }
        }, nodeId(s));
        if (nodeId(stat(p)) !== nodeId(s)) fail('EXTENSION_CHANGED', '임시 폴더가 변경되어 보존했습니다.');
        fs.rmdirSync(p);
    }
    else fs.unlinkSync(p);
}
function scanTree(target) {
    const entries = []; let bytes = 0;
    function walk(p, rel) {
        checkpoint(); const s = stat(p);
        if (s.isSymbolicLink() || !(s.isDirectory() || s.isFile()) || ++entries.length > LIMIT_FILES) fail('EXTENSION_UNSUPPORTED', '링크·특수 파일이 있거나 너무 큰 확장은 자동 변경하지 않습니다.');
        entries[entries.length - 1] = { rel, identity: ident(s), directory: s.isDirectory(), size: Number(s.size), mode: Number(s.mode) & 0o777 };
        if (s.isDirectory()) pinnedDirectory(p, (pinned, before, fd) => {
            for (const name of fs.readdirSync(pinned).sort()) { if (!safeName(name)) fail('EXTENSION_PATH_UNSAFE', '지원하지 않는 파일 이름입니다.'); walk(path.join(pinned, name), rel ? `${rel}/${name}` : name); }
            if (ident(fs.fstatSync(fd, { bigint: true })) !== ident(before) || nodeId(stat(p)) !== nodeId(before)) fail('EXTENSION_CHANGED', '검사 중 폴더 내용이 변경되었습니다.');
        });
        else if ((bytes += Number(s.size)) > LIMIT_BYTES) fail('EXTENSION_UNSUPPORTED', '512 MiB보다 큰 확장은 자동 변경하지 않습니다.');
    }
    walk(target, ''); return { entries, bytes, signature: sha(JSON.stringify(entries)) };
}
function copyTree(source, destination, snapshot) {
    let copied = 0, visited = 0; const expectedEntries = new Map(snapshot.entries.map(entry => [entry.rel, entry]));
    function copy(from, to, relative) {
        checkpoint(); const entry = expectedEntries.get(relative);
        if (!entry) fail('EXTENSION_CHANGED', '원본에 새 항목이 생겨 적용하지 않았습니다.');
        visited++;
        if (ident(stat(from)) !== entry.identity) fail('EXTENSION_CHANGED', '복사 중 원본 확장이 변경되었습니다.');
        if (entry.directory) {
            if (relative) fs.mkdirSync(to, { mode: 0o700 });
            pinnedDirectory(to, (destinationPin, destinationBefore, destinationFd) => {
                pinnedDirectory(from, (pinned, before, fd) => {
                    for (const name of fs.readdirSync(pinned).sort()) {
                        if (!safeName(name)) fail('EXTENSION_PATH_UNSAFE', '지원하지 않는 파일 이름입니다.');
                        copy(path.join(pinned, name), path.join(destinationPin, name), relative ? `${relative}/${name}` : name);
                    }
                    if (ident(fs.fstatSync(fd, { bigint: true })) !== ident(before) || nodeId(stat(from)) !== nodeId(before)) fail('EXTENSION_CHANGED', '복사 중 폴더가 변경되었습니다.');
                });
                if (nodeId(stat(to)) !== nodeId(destinationBefore)) fail('EXTENSION_CHANGED', '복사 대상 폴더가 변경되었습니다.');
                if (process.platform === 'linux') fs.fsyncSync(destinationFd);
            });
            return;
        }
        const input = fs.openSync(from, fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW || 0)), output = fs.openSync(to, 'wx', entry.mode);
        try {
            if (ident(fs.fstatSync(input, { bigint: true })) !== entry.identity) fail('EXTENSION_CHANGED', '원본 파일이 변경되었습니다.');
            const buffer = Buffer.alloc(256 * 1024); let n, count = 0;
            while ((n = fs.readSync(input, buffer, 0, buffer.length, null))) {
                checkpoint(); let offset = 0;
                while (offset < n) offset += fs.writeSync(output, buffer, offset, n - offset);
                count += n; copied += n;
                progress('확장 업데이트 준비', '원본을 변경하지 않고 별도 준비 폴더로 복사하고 있습니다.', copied, snapshot.bytes, entry.rel);
            }
            if (count !== entry.size || ident(stat(from)) !== entry.identity) fail('EXTENSION_CHANGED', '원본 파일이 변경되었습니다.');
            fs.fsyncSync(output);
        } finally { fs.closeSync(input); fs.closeSync(output); }
    }
    copy(source, destination, '');
    if (visited !== snapshot.entries.length) fail('EXTENSION_CHANGED', '복사 중 원본 항목 수가 변경되었습니다.');
}
function journalPath() { return path.join(launcher, JOURNAL); }
function replaceReceipt(next, allowedPhases = ['preparing']) {
    const previous = read(journalPath(), 8192), before = stat(journalPath()), current = JSON.parse(previous.toString('utf8'));
    if (!allowedPhases.includes(current.phase) || current.work !== next.work || current.workNode !== next.workNode || current.root !== next.root)
        fail('EXTENSION_RECOVERY_PENDING', '확장 준비 기록이 변경되어 원본을 보존했습니다.');
    const temporary = path.join(launcher, `.extension-receipt-${crypto.randomUUID()}.tmp`);
    writeExclusive(temporary, next);
    try {
        verifyParents();
        if (ident(stat(journalPath())) !== ident(before) || !read(journalPath(), 8192).equals(previous)) fail('EXTENSION_RECOVERY_PENDING', '확장 준비 기록이 변경되었습니다.');
        fs.renameSync(temporary, journalPath()); syncDirectory(launcher);
    } finally { if (exists(temporary)) fs.unlinkSync(temporary); }
}
function recoveryState() {
    if (!exists(journalPath())) return { status: 'none', message: '' };
    try {
        const j = json(journalPath(), 8192);
        if (j.version !== 1 || j.root !== root || !/^extension-work-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(j.work) ||
            !/^[0-9]+:[0-9]+:[0-9]+$/.test(j.originalNode) || !/^[0-9]+:[0-9]+:[0-9]+$/.test(j.candidateNode) || !/^[0-9]+:[0-9]+:[0-9]+$/.test(j.workNode)) throw Error('journal');
        const target = extensionPath(j.relative), work = path.join(launcher, j.work), candidate = path.join(work, 'candidate');
        directory(work);
        if (nodeId(stat(work)) !== j.workNode || fs.readdirSync(work).some(name => name !== 'candidate')) throw Error('work owner');
        const identityAt = p => exists(p) ? nodeId(stat(p)) : null;
        if (j.phase === 'cleanup') {
            if (identityAt(target) !== j.originalNode || (identityAt(candidate) !== null && identityAt(candidate) !== j.candidateNode)) throw Error('cleanup identities');
            let container;
            if (j.trashId) {
                if (!/^[0-9]{1,16}-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(j.trashId)) throw Error('cleanup trash');
                container = path.join(launcher, 'file-trash', j.trashId); directory(path.dirname(container));
                if (exists(container)) {
                    directory(container);
                    if (nodeId(stat(container)) !== j.trashNode || fs.readdirSync(container).some(name => !['metadata.json', 'EXTENSION-PENDING'].includes(name))) throw Error('cleanup container');
                }
            }
            return { status: 'pending', message: '원본 확장은 유지되어 있습니다. 중단된 준비 폴더 정리를 마치세요.', j, state: 'cleanup', target, work, candidate, container };
        }
        if (j.phase === 'preparing') {
            if (identityAt(target) !== j.originalNode || identityAt(candidate) !== j.candidateNode) throw Error('preparing identities');
            return { status: 'pending', message: '중단된 확장 준비 폴더가 있습니다. 점검·복구하면 원본은 유지하고 준비 폴더만 정리합니다.', j, state: 'preparing', target, work, candidate };
        }
        if (j.phase !== 'swap' || !/^[0-9]{1,16}-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(j.trashId)) throw Error('swap receipt');
        const container = path.join(launcher, 'file-trash', j.trashId), item = path.join(container, 'item');
        directory(path.dirname(container)); directory(container);
        if (nodeId(stat(container)) !== j.trashNode) throw Error('trash owner');
        const metadata = json(path.join(container, 'metadata.json'), 8192);
        if (metadata.root !== root || metadata.relative !== j.relative || metadata.reason !== 'extension-update' || !Number.isSafeInteger(metadata.deleted_at)) throw Error('metadata');
        const original = identityAt(target), backup = identityAt(item), staged = identityAt(candidate);
        const state = original === j.originalNode && !backup && staged === j.candidateNode ? 'prepared' :
            !original && backup === j.originalNode && staged === j.candidateNode ? 'original-moved' :
            original === j.candidateNode && backup === j.originalNode && !staged ? 'promoted' : null;
        if (!state) throw Error('unknown identities');
        const pending = path.join(container, 'EXTENSION-PENDING');
        if (exists(pending) ? json(pending, 1024).version !== 1 : state !== 'promoted') throw Error('pending marker');
        return { status: 'pending', message: '중단된 확장 교체 기록이 있습니다. 점검·복구 후 다시 시도하세요.', j, state, target, work, candidate, container, item };
    } catch (_) { return { status: 'blocked', message: '확장 교체 기록과 현재 파일을 안전하게 대조할 수 없습니다. 원본을 보존했으며 자동 변경을 차단합니다.' }; }
}
function emitRecovery(r) { console.log(`extension_recovery=${r.status}\nextension_recovery_message_b64=${b64(r.message)}`); }
function finishJournal(r) {
    const pending = path.join(r.container, 'EXTENSION-PENDING'); if (exists(pending)) fs.unlinkSync(pending);
    syncDirectory(r.container); fs.unlinkSync(journalPath()); syncDirectory(launcher);
    if (exists(r.work) && fs.readdirSync(r.work).length === 0) fs.rmdirSync(r.work);
}
function recover(emit = true) {
    const r = recoveryState();
    if (r.status === 'none') { if (emit) emitRecovery(r); return; }
    if (r.status !== 'pending') fail('EXTENSION_RECOVERY_PENDING', r.message);
    verifyParents();
    if (r.state === 'promoted') finishJournal(r);
    else {
        if (r.state === 'original-moved') renameNoReplace(r.item, r.target);
        if (r.state !== 'cleanup') replaceReceipt({ ...r.j, phase: 'cleanup' }, ['preparing', 'swap']);
        // The cleanup receipt tolerates an already-removed candidate or empty container, so another
        // process death during recursive cleanup remains recoverable without leaving large orphan copies.
        if (exists(r.candidate)) removeOwnedTree(r.candidate, r.j.candidateNode);
        if (r.container && exists(r.container)) {
            for (const name of ['metadata.json', 'EXTENSION-PENDING']) if (exists(path.join(r.container, name))) fs.unlinkSync(path.join(r.container, name));
            fs.rmdirSync(r.container);
            syncDirectory(path.dirname(r.container));
        }
        syncDirectory(r.work);
        fs.unlinkSync(journalPath()); syncDirectory(launcher);
        fs.rmdirSync(r.work); syncDirectory(launcher);
    }
    if (emit) console.log('extension_recovery=none\nextension_recovery_message_b64=');
}
// This preflight only enumerates names in the user-content folders. Never read
// chats, manifests, or secrets. Do not follow links or scan the application tree.
function resetSummary() {
    const sums = { characters: 0, chats: 0, extensions: 0 };
    let visited = 0;
    const deadline = Date.now() + 10000;
    function entries(parent, visit) {
        if (++visited > 150000 || Date.now() > deadline) throw Error('scan limit');
        if (!exists(parent)) return;
        const s = stat(parent);
        if (!s.isDirectory() || s.isSymbolicLink() || fs.realpathSync(parent) !== parent) throw Error('unsupported directory');
        const handle = fs.opendirSync(parent);
        try { for (let entry; (entry = handle.readSync()) !== null;) {
            if (++visited > 150000 || Date.now() > deadline) throw Error('scan limit');
            if (safeName(entry.name) && !entry.isSymbolicLink()) visit(entry, path.join(parent, entry.name));
        } } finally { handle.closeSync(); }
    }
    function cards(user) { entries(path.join(user, 'characters'), e => { if (e.isFile() && /\.png$/i.test(e.name)) sums.characters++; }); }
    function chats(dir, nested) { entries(dir, (e, child) => {
        if (e.isFile() && /\.jsonl$/i.test(e.name)) sums.chats++;
        else if (nested && e.isDirectory()) chats(child, false);
    }); }
    function extensions(dir) { entries(dir, (e, child) => {
        if (e.isDirectory() && ['manifest.json', DISABLED].some(name => {
            const file = path.join(child, name); return exists(file) && stat(file).isFile();
        })) sums.extensions++;
    }); }
    function content(user) { cards(user); chats(path.join(user,'chats'),true); chats(path.join(user,'group chats'),true); }
    const users = path.join(root, 'data');
    entries(users, (e, user) => { if (e.isDirectory()) { content(user); extensions(path.join(user, 'extensions')); } });
    content(path.join(root, 'public'));
    extensions(path.join(root, 'public/scripts/extensions/third-party'));
    for (const [name, count] of Object.entries(sums)) console.log(name + '=' + count);
    // Count named backup files, not verified archive contents. Resolve Termux's
    // Download shortcut once; never open a ZIP, follow child links or recurse.
    if (selection) try {
        const downloads = fs.realpathSync(selection);
        let count = 0;
        const handle = fs.opendirSync(downloads);
        try { for (let e; (e = handle.readSync()) !== null;) {
            if (++visited > 150000 || Date.now() > deadline) throw Error('scan limit');
            if (e.isFile() && !e.isSymbolicLink() && /^SillyTavern-Launcher-[0-9]{8}-[0-9]{6}(-[0-9]+)?( \([0-9]+\))*\.zip$/.test(e.name)) count++;
        } } finally { handle.closeSync(); }
        console.log('backup_files=' + count);
    } catch (_) { /* Missing permission/path or truncated scan: leave only this count unknown. */ }
}
function list() {
    const r = recoveryState(), candidates = []; let skipped = 0;
    function children(parent, prefix) {
        if (!exists(parent)) return;
        try {
            directory(parent);
            const dir = fs.opendirSync(parent); try { let entry; while ((entry = dir.readSync())) {
                if (candidates.length >= 2000) { skipped++; break; }
                if (safeName(entry.name) && entry.isDirectory() && !entry.isSymbolicLink()) candidates.push(`${prefix}/${entry.name}`); else skipped++;
            } } finally { dir.closeSync(); }
        } catch (_) { skipped++; }
    }
    try { const parent = extensionPath('public/scripts/extensions/third-party/_probe'); children(path.dirname(parent), 'public/scripts/extensions/third-party'); } catch (_) { /* optional */ }
    const data = path.join(root, 'data');
    if (exists(data)) try { directory(data); const users = fs.opendirSync(data); let count = 0;
        try { let user; while ((user = users.readSync())) {
            if (++count > 2000) { skipped++; break; }
            if (safeName(user.name) && user.isDirectory() && !user.isSymbolicLink()) {
                directory(path.join(data, user.name)); children(path.join(data, user.name, 'extensions'), `data/${user.name}/extensions`);
            }
        } } finally { users.closeSync(); }
    } catch (_) { skipped++; }
    candidates.sort(); const cursor = Number(selection || 0);
    if (!Number.isInteger(cursor) || cursor < 0 || cursor > 2000) fail('EXTENSION_LIST_FAILED', '목록 위치가 올바르지 않습니다.');
    const rows = []; let processed = cursor, rowBytes = 0; const deadline = Date.now() + 10000;
    for (const relative of candidates.slice(cursor, cursor + PAGE)) try {
        if (processed > cursor && Date.now() >= deadline) break;
        const target = extensionPath(relative); directory(target); const m = manifest(target); let info, restriction = '';
        try { info = gitInfo(target, true); if (info.shallow) restriction = '얕은 복제여서 원격 버전만 확인할 수 있습니다.';
            else if (!info.clean) restriction = '수정·추가·무시된 파일이 있어 자동 업데이트하지 않습니다.'; }
        catch (e) { restriction = e.message; }
        if (m.disabled) restriction = '런처에서 비활성화한 확장은 다시 사용한 뒤 업데이트하세요.';
        const row = ['extension', b64(relative), revision(target, m), b64(cleanText(m.data.display_name, 256) || path.basename(target)),
            b64(cleanText(m.data.version, 80)), b64(relative.startsWith('public/') ? '공용 · 모든 계정' : `사용자 ${relative.split('/')[1]}`),
            m.disabled ? 'disabled' : 'enabled', info ? info.clean ? 'clean' : 'dirty' : 'unsupported', info?.commit || '',
            info?.clean && !info.shallow && !m.disabled && r.status === 'none' ? '1' : '0', b64(restriction),
            b64(info ? info.origin.slice('https://github.com/'.length).replace(/\.git$/i, '') : ''), b64(info?.branch || '')].join('\t');
        if (rowBytes + Buffer.byteLength(row) > 40 * 1024) break;
        rows.push(row); rowBytes += Buffer.byteLength(row) + 1; processed++;
    } catch (_) { skipped++; processed++; }
    console.log('extension_inventory=1'); emitRecovery(r);
    console.log(`next_cursor=${processed < candidates.length ? processed : -1}\nskipped_entries=${skipped}`);
    console.log(rows.join('\n'));
}
async function update(item) {
    if (item.m.disabled) fail('EXTENSION_UNSUPPORTED', '확장을 다시 사용한 뒤 업데이트하세요.');
    const info = gitInfo(item.target); if (!info.clean) fail('EXTENSION_UNSUPPORTED', '수정·추가·무시된 파일을 보존하기 위해 업데이트를 중단했습니다.');
    progress('확장 파일 검사', '수정 파일·링크·크기를 확인하고 있습니다.');
    const snapshot = scanTree(item.target);
    if (fs.statfsSync) { const space = fs.statfsSync(launcher); if (Number(space.bavail) * Number(space.bsize) < snapshot.bytes * 2 + 64 * 1024 * 1024) fail('EXTENSION_NO_SPACE', '확장 업데이트 준비 공간이 부족합니다.'); }
    ownedWork = mkdir(path.join(launcher, `extension-work-${crypto.randomUUID()}`)); const candidate = path.join(ownedWork, 'candidate');
    ownedWorkNode = nodeId(stat(ownedWork));
    mkdir(candidate); directory(ownedWork); directory(candidate);
    const receipt = { version: 1, phase: 'preparing', root, relative: item.relative, work: path.basename(ownedWork),
        workNode: nodeId(stat(ownedWork)), originalNode: nodeId(stat(item.target)), candidateNode: nodeId(stat(candidate)) };
    writeExclusive(journalPath(), receipt);
    copyTree(item.target, candidate, snapshot); checkpoint();
    const stagedInfo = gitInfo(candidate);
    if (!stagedInfo.clean || stagedInfo.origin !== info.origin || stagedInfo.branch !== info.branch || stagedInfo.commit !== info.commit)
        fail('EXTENSION_CHANGED', '복사한 확장의 Git 정보가 원본과 일치하지 않습니다.');
    progress('확장 원격 확인', 'GitHub 원본에서 현재 브랜치 정보를 받고 있습니다.');
    await fetchCandidate(candidate, info);
    const next = git(candidate, ['rev-parse', 'FETCH_HEAD']).trim();
    if (next === info.commit) { recover(false); ownedWork = null; console.log(`extension_result=already-current\nextension_commit=${next}`); return; }
    if (git(candidate, ['merge-base', '--is-ancestor', 'HEAD', 'FETCH_HEAD'], { allowFailure: true }) === null) fail('EXTENSION_UNSUPPORTED', '브랜치가 갈라져 있어 자동 병합하지 않습니다.');
    const tree = git(candidate, ['ls-tree', '-r', '-z', 'FETCH_HEAD'], { maxBuffer: 4 * 1024 * 1024 });
    for (const entry of tree.split('\0').filter(Boolean)) if (!/^100(?:644|755) blob [a-f0-9]+\t/.test(entry) || entry.slice(entry.indexOf('\t') + 1).split('/').some(p => !safeName(p) || p.toLowerCase() === '.git'))
        fail('EXTENSION_UNSUPPORTED', '새 버전에 링크·하위 저장소·안전하지 않은 경로가 있어 적용하지 않았습니다.');
    progress('확장 새 버전 검사', '별도 폴더에 새 버전을 적용하고 안전한 교체 여부를 확인합니다.');
    git(candidate, ['merge', '--ff-only', '--no-edit', '--no-stat', 'FETCH_HEAD']);
    manifest(candidate); if (!gitInfo(candidate).clean) fail('EXTENSION_UPDATE_FAILED', '새 버전 검사에 실패했습니다. 원본은 변경하지 않았습니다.');
    const prepared = scanTree(candidate);
    for (const entry of prepared.entries.filter(e => !e.directory)) {
        const fd = fs.openSync(path.join(candidate, entry.rel), fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW || 0));
        try { if (process.platform !== 'win32') fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
    }
    for (const entry of prepared.entries.filter(e => e.directory).reverse()) syncDirectory(path.join(candidate, entry.rel));
    syncDirectory(ownedWork);
    if (scanTree(item.target).signature !== snapshot.signature || revision(item.target, manifest(item.target)) !== item.rev) fail('EXTENSION_CHANGED', '준비 중 원본 확장이 변경되어 적용하지 않았습니다.');
    checkpoint(); verifyParents();
    const trash = trashContainer();
    writeExclusive(path.join(trash.container, 'metadata.json'), { root, relative: item.relative, deleted_at: Date.now(), reason: 'extension-update' });
    writeExclusive(path.join(trash.container, 'EXTENSION-PENDING'), { version: 1 });
    replaceReceipt({ ...receipt, phase: 'swap', trashId: trash.id, trashNode: nodeId(stat(trash.container)) });
    // From here cancellation is deferred. The immutable durable receipt covers both rename boundaries.
    committed = true;
    progress('확장 교체', '원본을 휴지통에 보호하고 검사가 끝난 새 폴더로 교체합니다.');
    renameNoReplace(item.target, trash.item); renameNoReplace(candidate, item.target);
    finishJournal({ container: trash.container, work: ownedWork }); ownedWork = null;
    console.log(`extension_result=updated\nextension_commit=${next}\nprotected_trash_id=${trash.id}`);
}
(async () => { try {
    root = rootDirectory(rootArg); launcher = rootDirectory(launcherArg);
    if (root === launcher || launcher.startsWith(root + path.sep) || root.startsWith(launcher + path.sep)) fail('EXTENSION_PATH_UNSAFE', '설치 폴더와 런처 관리 폴더가 겹칩니다.');
    if (action === 'inspect-recovery') emitRecovery(recoveryState());
    else if (action === 'recover') recover();
    else if (action === 'list') list();
    else if (action === 'reset-summary') resetSummary();
    else {
        if (recoveryState().status !== 'none') fail('EXTENSION_RECOVERY_PENDING', '중단된 확장 교체부터 점검·복구하세요.');
        checkpoint(); const item = selected();
        if (action === 'update') await update(item);
        else if (action === 'disable' || action === 'enable') {
            if ((action === 'disable') === item.m.disabled) fail('EXTENSION_CHANGED', '확장 사용 상태가 이미 변경되었습니다.');
            if (revision(item.target, manifest(item.target)) !== item.rev) fail('EXTENSION_CHANGED', '확장 정보가 변경되어 적용하지 않았습니다.');
            renameNoReplace(item.m.file, path.join(item.target, action === 'disable' ? DISABLED : 'manifest.json'));
            console.log(`extension_result=${action === 'disable' ? 'disabled' : 'enabled'}`);
        } else if (action === 'trash') {
            scanTree(item.target); checkpoint(); const trash = trashContainer();
            if (revision(item.target, manifest(item.target)) !== item.rev) fail('EXTENSION_CHANGED', '확장 정보가 변경되어 적용하지 않았습니다.');
            writeExclusive(path.join(trash.container, 'metadata.json'), { root, relative: item.relative, deleted_at: Date.now(), reason: 'extension-trash' });
            renameNoReplace(item.target, trash.item); console.log(`extension_result=trashed\nprotected_trash_id=${trash.id}`);
        } else fail('EXTENSION_UNSUPPORTED', '지원하지 않는 확장 작업입니다.');
    }
} catch (e) {
    if (ownedWork) try {
        if (!exists(journalPath())) removeOwnedTree(ownedWork, ownedWorkNode);
        else if (!committed) {
            const recovery = recoveryState();
            if (recovery.status === 'pending' && ['preparing', 'prepared'].includes(recovery.state)) recover(false);
        }
    } catch (_) { /* Preserve unexpected paths and receipts for explicit recovery. */ }
    const code = e.extensionCode || (e.code === 'ENOSPC' ? 'EXTENSION_NO_SPACE' : 'EXTENSION_OPERATION_FAILED');
    console.error(`error_code=${code}\n${e.extensionCode ? e.message : '확장 작업을 완료하지 못했습니다. 원본과 복구 기록을 보존합니다.'}`);
    process.exitCode = codes[code] || 78;
} })();
NODE
