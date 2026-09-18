#!/data/data/com.termux/files/usr/bin/bash
# No package downloads or source writes: validate Node requirements or hash a
# stable installation snapshot before permitting its original to be removed.
set -Eeuo pipefail
exec node --input-type=commonjs - "$@" <<'NODE'
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const [action, rootArgument, npmExecutable = '', prefix = ''] = process.argv.slice(2);
const clean = value => String(value ?? '').replace(/[\x00-\x1f\x7f]/g, ' ').slice(0, 1024);
function fail(code, message) { throw Object.assign(new Error(message), { exitCode: code }); }
function npmSemver() {
    // Resolve the executable first for Termux's usr/bin/npm ->
    // usr/lib/node_modules/npm/bin/npm-cli.js. Windows' npm shell wrapper lives
    // beside node.exe instead. Never resolve modules from the imported tree.
    const roots = [];
    try {
        const executable = fs.realpathSync(npmExecutable);
        roots.push(path.resolve(path.dirname(executable), '..'));
        roots.push(path.join(path.dirname(executable), 'node_modules', 'npm'));
    } catch (_) { /* Explicit system-location candidates below remain valid. */ }
    if (prefix) roots.push(path.join(prefix, 'lib', 'node_modules', 'npm'));
    roots.push(path.join(path.dirname(process.execPath), 'node_modules', 'npm'));
    for (const root of new Set(roots)) {
        try {
            if (JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8')).name !== 'npm') continue;
            const semver = require(path.join(root, 'node_modules', 'semver'));
            if (typeof semver.validRange === 'function' && typeof semver.satisfies === 'function') return semver;
        } catch (_) { /* Do not approximate ranges when the real parser is missing. */ }
    }
    fail(57, 'Node.js 요구 조건을 해석할 npm의 semver 모듈을 찾지 못했습니다. Termux의 Node.js/npm 설치를 복구해 주세요.');
}
function runtime() {
    let manifest;
    try {
        const file = path.join(rootArgument, 'package.json'), stat = fs.lstatSync(file);
        if (!stat.isFile() || stat.isSymbolicLink() || stat.size > 1048576) fail(57, '설치의 package.json 파일이 안전 검사 조건을 충족하지 않습니다.');
        manifest = JSON.parse(fs.readFileSync(file, 'utf8'));
    }
    catch (_) { fail(57, '설치의 package.json을 읽거나 해석하지 못해 Node.js 요구 조건을 확인할 수 없습니다.'); }
    if (!manifest || typeof manifest !== 'object' || Array.isArray(manifest)) fail(57, '설치의 package.json 형식이 올바르지 않습니다.');
    if (manifest.engines !== undefined && (!manifest.engines || typeof manifest.engines !== 'object' || Array.isArray(manifest.engines))) {
        fail(57, '설치의 engines 형식이 올바르지 않습니다.');
    }
    const requirement = manifest.engines?.node;
    if (requirement === undefined) {
        console.log(`Node.js ${process.versions.node} 확인 · 설치에 별도 Node.js 요구 조건이 없습니다.`);
        return;
    }
    if (typeof requirement !== 'string' || requirement.length > 4096) fail(57, '설치의 Node.js 요구 조건 형식이 올바르지 않습니다.');
    const semver = npmSemver();
    let range;
    try { range = semver.validRange(requirement); } catch (_) { range = null; }
    if (range === null) fail(57, '설치의 Node.js 요구 조건을 정확하게 해석할 수 없습니다. 기존 설치와 원본은 변경하지 않았습니다.');
    // npm uses normal semver range semantics (including prerelease exclusion),
    // not just a numeric major-version comparison or an engine-warning exit code.
    if (!semver.satisfies(process.versions.node, range)) {
        fail(56, `이 설치는 Node.js ${clean(requirement)} 버전이 필요합니다. 현재 버전은 ${process.versions.node}입니다. 먼저 Termux의 Node.js를 업데이트해 주세요.`);
    }
    console.log(`Node.js 요구 조건 확인 완료 · 현재 ${process.versions.node} · 요구 ${clean(requirement)}`);
}
function metadata(stat) {
    return [stat.dev, stat.ino, stat.mode, stat.nlink, stat.size, stat.mtimeNs, stat.ctimeNs].map(String);
}
function equal(a, b) { return JSON.stringify(metadata(a)) === JSON.stringify(metadata(b)); }
function snapshot() {
    const root = path.resolve(rootArgument), initial = fs.lstatSync(root, { bigint: true });
    if (!initial.isDirectory() || initial.isSymbolicLink() || root === path.parse(root).root) fail(53, '원본 설치 경로가 안전하지 않습니다.');
    const digest = crypto.createHash('sha256'), entries = [], buffer = Buffer.allocUnsafe(262144);
    const started = Math.floor(Date.now() / 1000), phase = clean(process.env.ST_PROGRESS_PHASE || '원본 내용 검증');
    let checkedFiles = 0, checkedBytes = 0n, lastPublished = 0, lastLogged = 0, lastItem = '', lastFingerprint = '';
    function publish(item, force = false, verifying = false) {
        const now = Date.now(); lastItem = item;
        if (!force && now - lastPublished < 1000) return;
        lastPublished = now;
        const description = `${verifying ? '읽은 파일의 변경 여부 재확인' : '원본 SHA256 검사'} · 파일 ${checkedFiles}개 · 읽은 용량 ${checkedBytes}바이트`;
        const current = clean(item), fingerprint = `${checkedFiles}:${checkedBytes}:${current}:${verifying}`;
        if (fingerprint !== lastFingerprint && (force || now - lastLogged >= 2000)) {
            const line = `[${new Date(now).toLocaleString('sv-SE', { hour12: false })}] [${clean(process.env.ST_PROGRESS_OPERATION)}] ${phase} · ${description}${current ? ` · ${current}` : ''}\n`;
            for (const log of new Set([process.env.ST_PROGRESS_LOG, process.env.ST_PROGRESS_HISTORY].filter(Boolean))) {
                fs.appendFileSync(log, line, { mode: 0o600 });
                if (log === process.env.ST_PROGRESS_HISTORY && fs.statSync(log).size > 1000000) {
                    const fd = fs.openSync(log, 'r'), tail = Buffer.alloc(750000);
                    try { fs.readSync(fd, tail, 0, tail.length, fs.fstatSync(fd).size - tail.length); } finally { fs.closeSync(fd); }
                    const end = tail.indexOf(10), temporary = `${log}.validation.${process.pid}.tmp`;
                    fs.writeFileSync(temporary, tail.subarray(end < 0 ? 0 : end + 1), { mode: 0o600 });
                    fs.renameSync(temporary, log);
                }
            }
            lastFingerprint = fingerprint; lastLogged = now;
        }
        if (!process.env.ST_PROGRESS_FILE) return;
        const values = { percent: 0, phase, detail: `${description}. 전체 처리량을 사전에 추정하지 않습니다.`,
            status: 'running', operation: clean(process.env.ST_PROGRESS_OPERATION), error_code: '', progress_mode: 'indeterminate',
            completed_bytes: String(checkedBytes), total_bytes: 0, completed_files: checkedFiles, total_files: 0,
            current_item_b64: Buffer.from(current).toString('base64'), heartbeat_at: Math.floor(now / 1000),
            activity_at: Math.floor(now / 1000), phase_started_at: started,
            operation_started_at: Number(process.env.ST_OPERATION_STARTED_EPOCH) || 0 };
        const temporary = `${process.env.ST_PROGRESS_FILE}.validation.${process.pid}.tmp`;
        fs.writeFileSync(temporary, Object.entries(values).map(([key, value]) => `${key}=${value}\n`).join(''), { mode: 0o600 });
        fs.renameSync(temporary, process.env.ST_PROGRESS_FILE);
    }
    const frame = value => { const data = Buffer.from(JSON.stringify(value)); digest.update(String(data.length) + ':'); digest.update(data); };
    function check(entry) {
        let stat;
        try { stat = fs.lstatSync(entry.absolute, { bigint: true }); }
        catch (_) { fail(53, '원본 검사 중 파일이 사라졌습니다. 원본을 유지합니다.'); }
        if (!equal(entry.stat, stat)) fail(53, '원본 검사 중 파일 내용이나 메타데이터가 변경되었습니다. 원본을 유지합니다.');
        if (entry.names && JSON.stringify(fs.readdirSync(entry.absolute).sort()) !== JSON.stringify(entry.names)) {
            fail(53, '원본 검사 중 폴더 내용이 변경되었습니다. 원본을 유지합니다.');
        }
    }
    function visit(relative = '') {
        const absolute = relative ? path.join(root, relative) : root;
        const stat = fs.lstatSync(absolute, { bigint: true }), entry = { relative, absolute, stat, names: null };
        if (entries.length >= 500000) fail(53, '원본 설치의 파일 수가 안전 검사 한도를 초과합니다.');
        entries.push(entry);
        frame([relative, metadata(stat)]);
        if (stat.isDirectory()) {
            entry.names = fs.readdirSync(absolute).sort(); frame(entry.names);
            publish(relative);
            for (const name of entry.names) visit(relative ? `${relative}/${name}` : name);
            check(entry);
        } else if (stat.isFile()) {
            const content = crypto.createHash('sha256');
            const fd = fs.openSync(absolute, fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW || 0) | (fs.constants.O_NONBLOCK || 0));
            try {
                if (!equal(stat, fs.fstatSync(fd, { bigint: true }))) fail(53, '원본 파일이 검사 직전에 변경되었습니다.');
                let remaining = stat.size;
                while (remaining > 0n) {
                    const count = fs.readSync(fd, buffer, 0, Number(remaining > BigInt(buffer.length) ? BigInt(buffer.length) : remaining), null);
                    if (!count) fail(53, '원본 파일이 검사 도중 변경되었습니다.');
                    content.update(buffer.subarray(0, count)); remaining -= BigInt(count); checkedBytes += BigInt(count); publish(relative);
                }
                if (fs.readSync(fd, buffer, 0, 1, null) || !equal(stat, fs.fstatSync(fd, { bigint: true }))) fail(53, '원본 파일이 검사 도중 변경되었습니다.');
            } finally { fs.closeSync(fd); }
            frame(content.digest('hex')); checkedFiles++; check(entry); publish(relative);
        } else if (stat.isSymbolicLink()) {
            // Validation excludes dependencies from the copied installation,
            // but the cleanup guard still covers their links without following.
            frame(fs.readlinkSync(absolute)); checkedFiles++; check(entry); publish(relative);
        } else { checkedFiles++; publish(relative); }
    }
    visit();
    // Catch an earlier file being rewritten while later files were hashed.
    for (const entry of entries) { check(entry); publish(entry.relative, false, true); }
    publish(lastItem, true, true);
    console.log(digest.digest('hex'));
}
try {
    if (action === 'runtime') runtime();
    else if (action === 'snapshot') snapshot();
    else fail(64, '지원하지 않는 설치 검사 명령입니다.');
} catch (error) {
    console.error(clean(error.message));
    process.exitCode = error.exitCode || (action === 'runtime' ? 57 : 53);
}
NODE
