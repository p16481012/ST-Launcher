#!/data/data/com.termux/files/usr/bin/bash
# The JSON comes from the single-use loopback transfer, never command arguments.
set -Eeuo pipefail
exec 3<&0
exec node --input-type=commonjs - "$@" <<'NODE'
const fs = require('fs'), path = require('path'), crypto = require('crypto'), net = require('net');
const { createRequire } = require('module');
const [installation, launcher] = process.argv.slice(2);
const handles = [], privateBuffers = []; let secretBytes, request, temporary, temporaryId, backup, committed = false;
// Keep stderr and manager operation-history classification identical.
const exitCodes = Object.freeze({
    LAN_SETTINGS_INPUT_INVALID: 90, LAN_SETTINGS_PATH_UNSAFE: 91,
    LAN_SETTINGS_CHANGED: 92, LAN_SETTINGS_TRANSFER_FAILED: 93,
    LAN_SETTINGS_CONFIG_INVALID: 94, LAN_SETTINGS_PER_USER_AUTH: 95,
    LAN_SETTINGS_CREDENTIALS_REQUIRED: 96, LAN_SETTINGS_SAVE_FAILED: 97,
    OPERATION_CANCELLED: 130,
});
function fail(code) { throw Object.assign(new Error('LAN settings request failed'), { safeCode: code }); }
function stat(file) { return fs.lstatSync(file, { bigint: true }); }
function identity(value) { return `${value.dev}:${value.ino}`; }
function sameRevision(a, b) { return identity(a) === identity(b) && a.size === b.size && a.mtimeNs === b.mtimeNs && a.ctimeNs === b.ctimeNs; }
function exists(file) { try { stat(file); return true; } catch (error) { if (error.code === 'ENOENT') return false; throw error; } }
function cancelled() {
    if ([process.env.ST_CANCEL_REQUEST_FILE, process.env.ST_CANCEL_REQUEST_ID_FILE].some(file => file && exists(file))) fail('OPERATION_CANCELLED');
}
function openDirectory(target, create = false) {
    if (create) { try { fs.mkdirSync(target, { mode: 0o700 }); } catch (error) { if (error.code !== 'EEXIST') throw error; } }
    const before = stat(target);
    if (!before.isDirectory() || before.isSymbolicLink()) fail('LAN_SETTINGS_PATH_UNSAFE');
    const fd = fs.openSync(target, fs.constants.O_RDONLY | (fs.constants.O_DIRECTORY || 0) | (fs.constants.O_NOFOLLOW || 0));
    const opened = fs.fstatSync(fd, { bigint: true });
    if (!opened.isDirectory() || identity(opened) !== identity(before)) { fs.closeSync(fd); fail('LAN_SETTINGS_CHANGED'); }
    const directory = { target, fd, id: identity(opened), anchor: process.platform === 'linux' ? `/proc/self/fd/${fd}` : target };
    handles.push(directory); return directory;
}
function checkDirectories() { for (const directory of handles) if (identity(stat(directory.target)) !== directory.id) fail('LAN_SETTINGS_CHANGED'); }
function readConfig(file) {
    const before = stat(file);
    if (!before.isFile() || before.isSymbolicLink() || before.nlink !== 1n || before.size > 1048576n) fail('LAN_SETTINGS_PATH_UNSAFE');
    const fd = fs.openSync(file, fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW || 0));
    try {
        if (!sameRevision(before, fs.fstatSync(fd, { bigint: true }))) fail('LAN_SETTINGS_CHANGED');
        const bytes = Buffer.alloc(Number(before.size)); privateBuffers.push(bytes); let offset = 0;
        while (offset < bytes.length) { const count = fs.readSync(fd, bytes, offset, bytes.length - offset, offset); if (!count) fail('LAN_SETTINGS_CHANGED'); offset += count; }
        if (!sameRevision(before, fs.fstatSync(fd, { bigint: true }))) fail('LAN_SETTINGS_CHANGED');
        return { before, bytes, digest: crypto.createHash('sha256').update(bytes).digest('hex') };
    } finally { fs.closeSync(fd); }
}
function literal(value) {
    if (typeof value !== 'string' || !value || value.length > 45 || /\s/.test(value)) return null;
    if (!value.includes(':')) {
        const words = value.split('.');
        if (words.length !== 4 || words.some(word => !/^(0|[1-9][0-9]{0,2})$/.test(word) || Number(word) > 255)) return null;
        return words.map(Number);
    }
    if (!/^[a-fA-F0-9:]+$/.test(value) || net.isIP(value) !== 6) return null;
    const halves = value.split('::'), left = halves[0] ? halves[0].split(':') : [], right = halves.length > 1 && halves[1] ? halves[1].split(':') : [];
    const words = halves.length > 1 ? [...left, ...Array(8 - left.length - right.length).fill('0'), ...right] : left;
    return words.flatMap(word => { const number = parseInt(word, 16); return [number >> 8, number & 255]; });
}
function loopback(address) { return address.length === 4 ? address[0] === 127 : address.slice(0, 15).every(number => !number) && address[15] === 1; }
function privatePrefix(address) {
    if (address.length === 4) {
        if (address[0] === 127 || address[0] === 10) return 8;
        if (address[0] === 172 && address[1] >= 16 && address[1] <= 31) return 12;
        if (address[0] === 192 && address[1] === 168) return 16;
    } else { if (loopback(address)) return 128; if ((address[0] & 254) === 252) return 7; }
    return null;
}
function network(value) {
    if (typeof value !== 'string' || value.length > 80 || value.trim() !== value) fail('LAN_SETTINGS_INPUT_INVALID');
    const parts = value.split('/'); if (parts.length > 2) fail('LAN_SETTINGS_INPUT_INVALID');
    const address = literal(parts[0]); if (!address) fail('LAN_SETTINGS_INPUT_INVALID');
    const bits = address.length * 8;
    if (parts.length === 2 && !/^(0|[1-9][0-9]{0,2})$/.test(parts[1])) fail('LAN_SETTINGS_INPUT_INVALID');
    const prefix = parts.length === 2 ? Number(parts[1]) : bits, minimum = privatePrefix(address);
    if (minimum === null || prefix < minimum || prefix > bits) fail('LAN_SETTINGS_INPUT_INVALID');
    const normalized = address.map((number, index) => number & (255 << (8 - Math.min(8, Math.max(0, prefix - index * 8)))) & 255);
    const host = normalized.length === 4 ? normalized.join('.') : loopback(normalized) ? '::1' : Array.from({ length: 8 }, (_, index) => ((normalized[index * 2] << 8) | normalized[index * 2 + 1]).toString(16)).join(':');
    return parts.length === 2 ? `${host}/${prefix}` : host;
}
function exactKeys(value, keys) {
    return value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).sort().join(',') === keys.slice().sort().join(',');
}
function readRequest() {
    const chunks = []; let length = 0;
    try {
        for (;;) {
            const chunk = Buffer.alloc(2048), count = fs.readSync(3, chunk, 0, chunk.length, null);
            if (!count) { chunk.fill(0); break; }
            length += count; if (length > 16384) { chunk.fill(0); fail('LAN_SETTINGS_INPUT_INVALID'); }
            chunks.push(chunk.subarray(0, count));
        }
        if (!length) fail('LAN_SETTINGS_TRANSFER_FAILED');
        secretBytes = Buffer.concat(chunks);
    } catch (error) {
        if (error.safeCode) throw error;
        fail('LAN_SETTINGS_TRANSFER_FAILED');
    } finally { chunks.forEach(chunk => chunk.fill(0)); }
    try { request = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(secretBytes)); }
    catch (_) { fail('LAN_SETTINGS_INPUT_INVALID'); }
    if (exactKeys(request, ['protocol', 'action']) && request.protocol === 2 && request.action === 'disable-listen') return;
    if (!exactKeys(request, ['protocol', 'listen', 'allowedNetworks', 'basicAuthEnabled', 'credentials']) || request.protocol !== 1 || typeof request.listen !== 'boolean' || typeof request.basicAuthEnabled !== 'boolean') fail('LAN_SETTINGS_INPUT_INVALID');
    if (request.allowedNetworks === null) { if (request.listen) fail('LAN_SETTINGS_INPUT_INVALID'); }
    else {
        if (!Array.isArray(request.allowedNetworks) || request.allowedNetworks.length > 66) fail('LAN_SETTINGS_INPUT_INVALID');
        request.allowedNetworks = [...new Set(['127.0.0.1', '::1', ...request.allowedNetworks.map(network)])];
    }
    if (request.credentials !== null) {
        const credentials = request.credentials;
        if (!exactKeys(credentials, ['username', 'password']) || typeof credentials.username !== 'string' || typeof credentials.password !== 'string' ||
            !credentials.username.trim() || credentials.username.length > 128 || /[:\x00-\x1f\x7f-\x9f]/.test(credentials.username) ||
            credentials.password.length < 8 || credentials.password.length > 1024 || !credentials.password.trim() ||
            /[\x00-\x1f\x7f-\x9f]/.test(credentials.password) || !request.basicAuthEnabled) fail('LAN_SETTINGS_INPUT_INVALID');
    }
}
function writeNew(file, bytes) {
    const fd = fs.openSync(file, fs.constants.O_WRONLY | fs.constants.O_CREAT | fs.constants.O_EXCL | (fs.constants.O_NOFOLLOW || 0), 0o600);
    const id = identity(fs.fstatSync(fd, { bigint: true }));
    let complete = false;
    try { fs.writeFileSync(fd, bytes); fs.fsyncSync(fd); complete = true; return id; }
    finally {
        fs.closeSync(fd);
        if (!complete) { try { if (identity(stat(file)) === id) fs.unlinkSync(file); } catch (_) {} }
    }
}
try {
    readRequest(); cancelled();
    if (!path.isAbsolute(installation || '') || !path.isAbsolute(launcher || '')) fail('LAN_SETTINGS_PATH_UNSAFE');
    const root = openDirectory(installation), owner = openDirectory(launcher);
    const config = path.join(root.anchor, 'config.yaml'), hadConfig = exists(config);
    const source = hadConfig ? config : path.join(openDirectory(path.join(root.anchor, 'default')).anchor, 'config.yaml');
    const original = readConfig(source);
    let YAML, document;
    try {
        YAML = createRequire(path.join(installation, 'package.json'))('yaml');
        document = YAML.parseDocument(new TextDecoder('utf-8', { fatal: true }).decode(original.bytes), { uniqueKeys: true });
        if (document.errors.length || !YAML.isMap(document.contents)) fail('LAN_SETTINGS_CONFIG_INVALID');
        document.toJS({ maxAliasCount: 50 }); // Bound malicious alias expansion before making changes.
    } catch (_) { fail('LAN_SETTINGS_CONFIG_INVALID'); }
    if (request.protocol === 2) {
        // The user only disabled exposure. Never replay cached auth/allow-list state,
        // and never require unrelated credentials to be usable before closing the listener.
        const old = document.get('listen', true);
        if (old && old.anchor) YAML.visit(document, { Alias(_, alias) {
            if (alias.source === old.anchor && alias.resolve(document) === old) {
                throw Object.assign(new Error(), { safeCode: 'LAN_SETTINGS_CONFIG_INVALID', sharedListenAnchor: true });
            }
        } });
        document.set('listen', false);
    } else {
        const perUser = document.get('perUserBasicAuth') === true, wasBasicAuth = document.get('basicAuthMode') === true;
        if (perUser && (request.credentials !== null || request.basicAuthEnabled !== wasBasicAuth)) fail('LAN_SETTINGS_PER_USER_AUTH');
        if (request.basicAuthEnabled && !perUser && request.credentials === null) {
            const existing = document.get('basicAuthUser');
            if (!wasBasicAuth || !YAML.isMap(existing) || typeof existing.get('username') !== 'string' || !existing.get('username') || typeof existing.get('password') !== 'string' || !existing.get('password')) fail('LAN_SETTINGS_CREDENTIALS_REQUIRED');
        }
        document.set('listen', request.listen);
        if (request.allowedNetworks !== null) { document.set('whitelistMode', true); document.set('whitelist', request.allowedNetworks); }
        document.set('basicAuthMode', request.basicAuthEnabled);
        if (request.credentials !== null) {
            document.set('basicAuthUser', { username: request.credentials.username, password: request.credentials.password });
        }
    }
    const replacement = Buffer.from(String(document), 'utf8'); privateBuffers.push(replacement);
    if (replacement.length > 1048576) fail('LAN_SETTINGS_CONFIG_INVALID');
    const backupRoot = openDirectory(path.join(owner.anchor, 'backups'), true);
    const saved = openDirectory(path.join(backupRoot.anchor, 'network-settings'), true);
    const now = new Date(), pad = number => String(number).padStart(2, '0');
    const stamp = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
    backup = path.join(saved.anchor, `config-${stamp}.yaml`);
    cancelled(); checkDirectories();
    writeNew(backup, original.bytes); // O_EXCL: never overwrite a prior settings backup.
    if (process.platform === 'linux') fs.fsyncSync(saved.fd);
    temporary = path.join(root.anchor, `.config-lan-${crypto.randomUUID()}.tmp`);
    temporaryId = writeNew(temporary, replacement);
    cancelled(); checkDirectories();
    if (hadConfig !== exists(config)) fail('LAN_SETTINGS_CHANGED');
    const current = readConfig(source);
    if (!sameRevision(original.before, current.before) || original.digest !== current.digest) fail('LAN_SETTINGS_CHANGED');
    if (identity(stat(temporary)) !== temporaryId) fail('LAN_SETTINGS_CHANGED');
    fs.renameSync(temporary, config); committed = true; temporary = null;
    let durable = true;
    if (process.platform === 'linux') { try { fs.fsyncSync(root.fd); } catch (_) { durable = false; } }
    process.stdout.write(`saved=1\ndurability_warning=${durable ? 0 : 1}\n`);
} catch (error) {
    // Never print stack/message/path/YAML: all can include credentials from parser errors.
    if (committed) process.stdout.write('saved=1\ndurability_warning=1\n');
    else {
        const code = Object.hasOwn(exitCodes, error.safeCode) ? error.safeCode : 'LAN_SETTINGS_SAVE_FAILED';
        const hint = error.sharedListenAnchor === true ? 'config.yaml에서 listen 값을 참조하는 YAML 별칭을 먼저 분리해 주세요.' : '연결 설정을 저장하지 못했습니다. 기존 설정은 유지됩니다.';
        process.stderr.write(`error_code=${code}\n${hint}\n`);
        process.exitCode = exitCodes[code];
    }
} finally {
    if (secretBytes) secretBytes.fill(0);
    privateBuffers.forEach(bytes => bytes.fill(0));
    if (request) { if (request.credentials) { request.credentials.username = ''; request.credentials.password = ''; } request = null; }
    if (temporary && temporaryId) { try { if (identity(stat(temporary)) === temporaryId) fs.unlinkSync(temporary); } catch (_) {} }
    for (const directory of handles.reverse()) { try { fs.closeSync(directory.fd); } catch (_) {} }
}
NODE
