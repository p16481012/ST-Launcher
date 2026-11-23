#!/usr/bin/env node
// Opt-in isolated benchmark. Default payload + archive < 140 MiB; hard cap < 256 MiB.
// Measures extra on-disk bytes, including the eventual retained archive. Not a
// device-speed guarantee, nor permission to optimize journal copies into moves.
const fs = require('fs');
const path = require('path');
const os = require('os');
const crypto = require('crypto');
const { spawn } = require('child_process');
const MiB = 1024 * 1024;
const smallFiles = Math.min(8000, Math.max(1, Number(process.argv[2]) || 4000));
const largeMiB = Math.min(96, Math.max(1, Number(process.argv[3]) || 64));
const base = fs.mkdtempSync(path.join(os.tmpdir(), 'st-storage-benchmark-'));
const sourceOf = name => fs.readFileSync(path.join(__dirname, '../app/src/main/assets', name), 'utf8').split("<<'NODE'\n")[1].split('\nNODE')[0];
const scanCode = sourceOf('storage-usage.sh'), backupCode = sourceOf('safe-backup.sh');
function bytesUnder(directory) {
    if (!fs.existsSync(directory)) return 0;
    let bytes = 0;
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
        const target = path.join(directory, entry.name);
        if (entry.isDirectory()) bytes += bytesUnder(target);
        else if (entry.isFile()) bytes += fs.statSync(target).size;
    }
    return bytes;
}
async function execute(code, args, storageToMeasure) {
    const env = { ...process.env, ST_PROGRESS_FILE: '', ST_PROGRESS_LOG: '', ST_PROGRESS_HISTORY: '',
        ST_SAFETY_WORK_PARENT: '', ST_SAFETY_RESERVE_BYTES: '33554432', ST_CANCEL_REQUEST_FILE: '', ST_CANCEL_REQUEST_ID_FILE: '' };
    if (process.platform === 'win32' && fs.existsSync('C:/Program Files/Git/usr/bin/tar.exe')) env.PATH = `C:/Program Files/Git/usr/bin;${env.PATH || ''}`;
    const started = performance.now(); let stdout = '', stderr = '', peak = 0;
    const usageHook = "process.on('exit', () => console.error('benchmark_resource_usage=' + JSON.stringify(process.resourceUsage())));\n";
    const worker = spawn(process.execPath, ['-e', usageHook + code, '--', '-', ...args], { env, stdio: ['ignore', 'pipe', 'pipe'] });
    worker.stdout.on('data', chunk => { stdout = (stdout + chunk.toString()).slice(-20000); });
    worker.stderr.on('data', chunk => { stderr = (stderr + chunk.toString()).slice(-20000); });
    const sample = () => { try { peak = Math.max(peak, bytesUnder(storageToMeasure)); } catch (_) {} };
    const timer = setInterval(sample, 50), timeout = setTimeout(() => worker.kill('SIGKILL'), 120000);
    try {
        await new Promise((resolve, reject) => {
            worker.once('error', reject);
            worker.once('close', (code, signal) => code === 0 ? resolve() : reject(new Error(`benchmark worker failed (${code || signal}): ${stderr}`)));
        });
    } finally { clearInterval(timer); clearTimeout(timeout); sample(); }
    const usage = stderr.match(/benchmark_resource_usage=(\{[^\n]+\})/);
    return { elapsedMillis: Math.round(performance.now() - started), peakAdditionalBytes: peak,
        maxRssReported: usage ? JSON.parse(usage[1]).maxRSS : null, stdout };
}
async function scenario(name, files, largeBytes) {
    const home = path.join(base, name), install = path.join(home, 'SillyTavern'), launcher = path.join(home, '.st-launcher'), downloads = path.join(home, 'Downloads');
    const output = path.join(launcher, 'backups');
    for (const directory of [path.join(install, 'data'), output, downloads]) fs.mkdirSync(directory, { recursive: true });
    for (let index = 0; index < files; index++) fs.writeFileSync(path.join(install, 'data', `${String(index).padStart(5, '0')}.json`), crypto.randomBytes(512));
    if (largeBytes) {
        const fd = fs.openSync(path.join(install, 'data', 'large.bin'), 'wx');
        try { for (let written = 0; written < largeBytes; written += 256 * 1024) fs.writeSync(fd, crypto.randomBytes(Math.min(256 * 1024, largeBytes - written))); }
        finally { fs.closeSync(fd); }
    }
    const sourceBytes = bytesUnder(install);
    const scan = await execute(scanCode, [install, launcher, downloads, home, '30000', '500000'], output);
    const archive = path.join(output, 'before-forced-update-20261124-000000.tar.gz');
    const backup = await execute(backupCode, [install, archive], output);
    const result = { scenario: name, sourceFiles: files + (largeBytes ? 1 : 0), sourceBytes,
        storageScanElapsedMillis: scan.elapsedMillis, storageScanComplete: /^complete=1$/m.test(scan.stdout),
        safetyBackupElapsedMillis: backup.elapsedMillis, peakObservedAdditionalBytes: backup.peakAdditionalBytes,
        retainedArchiveBytes: fs.statSync(archive).size, maxRssReported: backup.maxRssReported };
    if (bytesUnder(base) > 256 * MiB) throw new Error('benchmark fixture exceeded 256 MiB cap');
    console.log(JSON.stringify(result));
}
(async () => {
    try {
        await scenario('many-small-files', smallFiles, 0);
        await scenario('large-payload', 16, largeMiB * MiB);
    } finally {
        if (!path.basename(base).startsWith('st-storage-benchmark-') || path.dirname(base) !== os.tmpdir()) throw new Error('unexpected benchmark fixture path');
        fs.rmSync(base, { recursive: true, force: true });
    }
    console.log(JSON.stringify({ completed: true, temporaryFixtureRemoved: true, fixtureLimitBytes: 256 * MiB }));
})().catch(error => { console.error(error.message); process.exitCode = 1; });
