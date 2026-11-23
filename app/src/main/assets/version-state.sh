#!/data/data/com.termux/files/usr/bin/bash
# Bounded local version/snapshot receipts. No network, checkout, or deletion.
set -Eeuo pipefail
exec node --input-type=commonjs - "$@" <<'NODE'
const fs = require('fs'), path = require('path'), crypto = require('crypto'), cp = require('child_process');
const [action, rootArg, launcherArg, downloadsArg, arg = '', expected = '', installExpected = ''] = process.argv.slice(2);
const sha = v => crypto.createHash('sha256').update(v).digest('hex'), b64 = v => Buffer.from(String(v)).toString('base64');
const stat = p => fs.lstatSync(p, { bigint: true });
const rev = s => sha([s.dev, s.ino, s.mode, s.size, s.mtimeNs, s.ctimeNs].join(':'));
const validName = name => typeof name === 'string' && name.length <= 255 && /^SillyTavern-Launcher-[0-9]{8}-[0-9]{6}(-[0-9]+)?( \([0-9]+\))*\.zip$/.test(name);
const commitValid = s => /^[a-f0-9]{40}$/.test(s), revisionValid = s => /^[a-f0-9]{64}$/.test(s);
const fail = (code, message) => { throw Object.assign(new Error(message), { versionCode: code }); };
const handles = [];
function directory(p, create = false) {
    if (create) try { fs.mkdirSync(p, { mode: 0o700 }); } catch (e) { if (e.code !== 'EEXIST') throw e; }
    const s = stat(p); if (!s.isDirectory() || s.isSymbolicLink()) fail(83, '버전 백업 경로를 안전하게 확인할 수 없습니다.');
    if (process.platform !== 'linux') return p;
    const fd = fs.openSync(p, fs.constants.O_RDONLY | fs.constants.O_DIRECTORY | fs.constants.O_NOFOLLOW);
    const open = fs.fstatSync(fd, { bigint: true });
    if (s.dev !== open.dev || s.ino !== open.ino) { fs.closeSync(fd); fail(81, '폴더가 변경되었습니다.'); }
    handles.push(fd); return `/proc/self/fd/${fd}`;
}
function readJson(file) {
    const before = stat(file);
    if (!before.isFile() || before.isSymbolicLink() || before.size > 8192n) fail(83, '완료된 전체 백업 기록을 확인할 수 없습니다.');
    const fd = fs.openSync(file, fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW || 0));
    try {
        if (rev(before) !== rev(fs.fstatSync(fd, { bigint: true }))) fail(83, '백업 기록이 변경되었습니다.');
        const bytes = Buffer.alloc(Number(before.size)); let offset = 0;
        while (offset < bytes.length) { const n = fs.readSync(fd, bytes, offset, bytes.length - offset, null); if (!n) fail(83, '백업 기록을 끝까지 읽지 못했습니다.'); offset += n; }
        if (rev(before) !== rev(fs.fstatSync(fd, { bigint: true }))) fail(83, '백업 기록이 변경되었습니다.');
        return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
    } finally { fs.closeSync(fd); }
}
try {
    const rootStat = stat(rootArg); if (!rootStat.isDirectory() || rootStat.isSymbolicLink()) fail(81, '현재 설치를 찾을 수 없습니다.');
    const root = fs.realpathSync(rootArg), installationRevision = sha(`${root}:${rootStat.dev}:${rootStat.ino}:${rootStat.mode}`);
    const git = (...args) => {
        const result = cp.spawnSync('git', ['-c', 'core.fsmonitor=false', '-c', 'core.hooksPath=/dev/null', '-c', 'log.showSignature=false', '-c', 'color.ui=false', '-C', root, ...args], { encoding: 'utf8', timeout: 10000, maxBuffer: 128 * 1024, env: { ...process.env, GIT_TERMINAL_PROMPT: '0', GIT_PAGER: 'cat' } });
        if (result.status !== 0) fail(82, '현재 설치의 로컬 Git 기록을 확인할 수 없습니다.');
        return result.stdout.trim();
    };
    const head = git('rev-parse', 'HEAD'), branch = git('branch', '--show-current');
    if (!commitValid(head) || !['release', 'staging'].includes(branch)) fail(82, 'release 또는 staging 설치에서만 이전 버전을 선택할 수 있습니다.');
    if (action === 'versions') {
        const history = git('log', '--no-show-signature', '--no-decorate', '--no-color', '--no-notes', '--max-count=31', '--format=%H%x09%ct%x09%<(160,trunc)%s', 'HEAD', '--').split('\n').slice(1);
        console.log(`version_inventory=1\ncurrent_commit=${head}\ninstallation_revision=${installationRevision}\nbranch=${branch}`);
        for (const line of history) {
            const [commit, seconds, ...subject] = line.split('\t');
            if (!commitValid(commit) || !/^[0-9]{1,12}$/.test(seconds)) fail(82, 'Git 기록 형식을 확인할 수 없습니다.');
            const label = subject.join(' ').replace(/[\x00-\x1f\x7f]/g, ' ').slice(0, 240).trim();
            console.log(`version\t${commit}\t${seconds}\t${b64(label)}`);
        }
    } else if (action === 'validate-target') {
        if (head !== expected || installationRevision !== installExpected) fail(81, '목록 확인 후 현재 설치가 변경되었습니다. 다시 선택해 주세요.');
        if (!commitValid(arg) || arg === head) fail(82, '현재 버전과 다른 이전 커밋을 선택해 주세요.');
        git('merge-base', '--is-ancestor', arg, head);
        console.log('version_target_valid=1');
    } else {
        const launcher = directory(launcherArg);
        let snapshots;
        try { const history = directory(path.join(launcher, 'update-history'), action === 'record'); snapshots = directory(path.join(history, 'version-snapshots'), action === 'record'); }
        catch (e) { if (action === 'snapshots' && e.code === 'ENOENT') { console.log('version_snapshots=1\nsnapshots_truncated=0'); process.exit(0); } throw e; }
        // Android's Downloads link is an intentional entry point; pin its resolved directory.
        const downloads = directory(fs.realpathSync(downloadsArg));
        const receiptPath = name => path.join(snapshots, sha(name) + '.json');
        const archiveRevision = name => { if (!validName(name)) fail(83, '지원하지 않는 전체 백업 이름입니다.'); const s = stat(path.join(downloads, name)); if (!s.isFile() || s.isSymbolicLink()) fail(83, '전체 백업 파일이 없습니다.'); return { revision: rev(s), bytes: Number(s.size) }; };
        const receipt = name => {
            const data = readJson(receiptPath(name)), archive = archiveRevision(name);
            if (data.format !== 'st-launcher-version-snapshot-v1' || data.root !== root || data.filename !== name || !commitValid(data.commit) || data.archiveRevision !== archive.revision || data.bytes !== archive.bytes || !Number.isSafeInteger(data.createdAt) || data.createdAt < 0 || typeof data.version !== 'string' || data.version.length > 128) fail(83, '전체 백업과 커밋 기록이 일치하지 않습니다. 기존 전체 복원 메뉴를 이용해 주세요.');
            return data;
        };
        if (action === 'record') {
            if (head !== expected) fail(81, '백업 생성 중 현재 커밋이 변경되었습니다.');
            const archive = archiveRevision(arg), record = { format: 'st-launcher-version-snapshot-v1', root, filename: arg, commit: head, archiveRevision: archive.revision, bytes: archive.bytes, createdAt: Date.now(), version: '' };
            try { const pkg = readJson(path.join(root, 'package.json')); if (typeof pkg.version === 'string') record.version = pkg.version.slice(0, 128); } catch (_) { /* optional display label only */ }
            const fd = fs.openSync(receiptPath(arg), 'wx', 0o600);
            try { fs.writeFileSync(fd, JSON.stringify(record)); fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
            if (process.platform === 'linux') fs.fsyncSync(handles.at(-2));
            console.log(`snapshot_recorded=1\nsnapshot_revision=${archive.revision}`);
        } else if (action === 'snapshot-info') {
            const data = receipt(arg);
            console.log(`snapshot_revision=${data.archiveRevision}\nsnapshot_commit=${data.commit}`);
        } else if (action === 'verify-snapshot') {
            const data = receipt(arg);
            if (data.archiveRevision !== expected || data.commit !== installExpected) fail(83, '선택한 스냅샷의 커밋 또는 파일이 변경되었습니다.');
            console.log(`snapshot_verified=1\nsnapshot_commit=${data.commit}`);
        } else if (action === 'snapshots') {
            const dir = fs.opendirSync(snapshots); const records = []; let scanned = 0, truncated = false;
            try { for (;;) { const entry = dir.readSync(); if (!entry) break; if (++scanned > 1000) { truncated = true; break; } if (!/^[a-f0-9]{64}\.json$/.test(entry.name) || !entry.isFile()) continue;
                try { const info = readJson(path.join(snapshots, entry.name)); if (validName(info.filename) && entry.name === sha(info.filename) + '.json') records.push(receipt(info.filename)); } catch (_) { /* unknown/changed records are never offered */ }
            } } finally { dir.closeSync(); }
            records.sort((a, b) => b.createdAt - a.createdAt); if (records.length > 50) truncated = true;
            console.log(`version_snapshots=1\nsnapshots_truncated=${truncated ? 1 : 0}`);
            for (const item of records.slice(0, 50)) console.log(['snapshot', b64(item.filename), item.archiveRevision, item.commit, item.createdAt, item.bytes, b64(item.version)].join('\t'));
        } else fail(82, '지원하지 않는 버전 작업입니다.');
    }
} catch (e) {
    const code = e.versionCode || 83;
    console.error(`error_code=${({81:'VERSION_INSTALLATION_CHANGED',82:'VERSION_TARGET_INVALID',83:'VERSION_SNAPSHOT_INVALID'})[code] || 'VERSION_SNAPSHOT_INVALID'}`);
    console.error(e.versionCode ? e.message : '버전 백업 기록을 안전하게 확인하지 못했습니다. 기존 설치와 백업은 유지했습니다.');
    process.exitCode = code;
} finally { for (const fd of handles.reverse()) { try { fs.closeSync(fd); } catch (_) {} } }
NODE
